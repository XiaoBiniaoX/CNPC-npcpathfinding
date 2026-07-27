package top.npcpathfindinglegacy.pathfinding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import noppes.npcs.entity.EntityNPCInterface;
import top.npcpathfindinglegacy.NpcPathfindingConfig;

/**
 * Ground navigator ported from the 1.12.2 addon without its unused embedded
 * Baritone payload. Vanilla navigation remains the cheap first choice; the
 * bounded custom A* is used for platforms, gaps, drops and partial recovery.
 */
public final class EnhancedPathNavigator extends GroundPathNavigation {
    private static final Direction[] HORIZONTAL = new Direction[]{
        Direction.NORTH,
        Direction.SOUTH,
        Direction.WEST,
        Direction.EAST
    };

    private final EntityNPCInterface npc;
    private final Map<Long, Long> bannedColumns = new HashMap<Long, Long>();

    private BlockPos requestedTarget;
    private BlockPos lastComputedTarget;
    private Entity chaseEntity;
    private long lastMoveRequestTick = Long.MIN_VALUE;
    private long lastPathComputeTick = Long.MIN_VALUE;
    private boolean lastSearchDeferred;
    private boolean updatingVanillaNavigation;
    private int repathCooldown;
    private int failedRepaths;

    private Vec3 lastProgressPosition;
    private int stuckCounter;
    private int recoveryTicks;
    private double recoveryMotionX;
    private double recoveryMotionZ;

    private BlockPos jumpTarget;
    private BlockPos landingSettleTarget;
    private int movementLockTicks;
    private int jumpAirTicks;
    private int jumpLaunchGroundTicks;
    private int landingSettleTicks;
    private boolean parkourJump;
    private double jumpMaxHorizontalSpeed;
    private double lockedMotionX;
    private double lockedMotionZ;
    private int jumpCooldown;
    private int flatJumpCooldown;
    private boolean ownsSprint;

    private Map<Long, Byte> standCache;
    private Map<Long, Byte> bodyCache;

    public EnhancedPathNavigator(EntityNPCInterface npc, Level level) {
        super(npc, level);
        this.npc = npc;
        this.lastProgressPosition = npc.position();
        this.refreshCapabilities();
    }

    public void refreshCapabilities() {
        this.setCanFloat(npc.ais != null && npc.ais.canSwim);
        this.setCanOpenDoors(npc.ais != null && (npc.ais.doorInteract == 0 || npc.ais.doorInteract == 1));
    }

    @Override
    public Path createPath(BlockPos target, int accuracy) {
        return this.createBestPath(this.resolveTarget(target), Math.max(1, accuracy), true);
    }

    @Override
    public Path createPath(Entity entity, int accuracy) {
        return this.createBestPath(this.resolveTarget(this.predictTarget(entity)), Math.max(1, accuracy), true);
    }

    @Override
    public boolean moveTo(double x, double y, double z, double speedIn) {
        this.chaseEntity = null;
        return this.requestMove(this.resolveTarget(BlockPos.containing(x, y, z)), speedIn, false);
    }

    @Override
    public boolean moveTo(Entity entity, double speedIn) {
        this.chaseEntity = entity;
        return this.requestMove(this.resolveTarget(this.predictTarget(entity)), speedIn, true);
    }

    private boolean requestMove(BlockPos target, double speedIn, boolean chasing) {
        if (target == null) {
            return false;
        }

        long now = this.level.getGameTime();
        this.lastMoveRequestTick = now;
        double reuseDistance = chasing ? 2.25D : 1.0D;
        boolean sameGoal = this.lastComputedTarget != null
            && this.lastComputedTarget.distSqr(target) <= reuseDistance;
        if (sameGoal
            && this.path != null
            && !this.path.isDone()
            && now - this.lastPathComputeTick < NpcPathfindingConfig.pathReuseTicks) {
            this.requestedTarget = target;
            this.speedModifier = speedIn;
            return true;
        }

        boolean changedGoal = this.requestedTarget == null
            || this.requestedTarget.distSqr(target) > (chasing ? 4.0D : 1.0D);
        this.requestedTarget = target;
        if (changedGoal) {
            this.failedRepaths = 0;
            this.stuckCounter = 0;
            this.recoveryTicks = 0;
        }

        Path path = this.createBestPath(target, 1, true);
        this.lastPathComputeTick = now;
        this.lastComputedTarget = target;
        if (path == null || path.getNodeCount() < 2) {
            this.repathCooldown = 3;
            // Keep the AI task alive while the global A* budget defers this NPC.
            // The active request is retried from onUpdateNavigation.
            return true;
        }

        this.repathCooldown = 0;
        this.failedRepaths = 0;
        this.lastProgressPosition = this.npc.position();
        return this.installComputedPath(path, speedIn);
    }

    @Override
    public void tick() {
        if (this.repathCooldown > 0) {
            this.repathCooldown--;
        }
        if (this.jumpCooldown > 0) {
            this.jumpCooldown--;
        }
        if (this.flatJumpCooldown > 0) {
            this.flatJumpCooldown--;
        }

        long now = this.level.getGameTime();
        if (this.requestedTarget != null && this.hasArrived()) {
            this.finishNavigation();
            return;
        }

        boolean activelyDriven = this.requestedTarget != null
            && now - this.lastMoveRequestTick < 40L;
        if (!activelyDriven && this.requestedTarget != null) {
            this.finishNavigation();
            return;
        }

        // Keep vanilla path following out of an active jump. Its proximity
        // shortcut may advance to the following node while the NPC is still
        // airborne, which makes a chain of parkour landings lose alignment.
        if (this.jumpTarget != null) {
            this.updateControlledJump();
            return;
        }
        if (this.landingSettleTarget != null && this.settleLanding()) {
            return;
        }

        if (activelyDriven
            && (this.path == null || this.path.isDone())
            && this.repathCooldown <= 0
            && this.failedRepaths < 10) {
            this.recomputeActivePath();
        }

        if (this.recoveryTicks > 0) {
            this.applyRecoveryMotion();
            this.trackProgress();
            return;
        }

        if (this.path != null && !this.path.isDone()) {
            this.updatingVanillaNavigation = true;
            try {
                super.tick();
            } finally {
                this.updatingVanillaNavigation = false;
            }
            this.applyEnhancedMovement();
            this.trackProgress();
        }
    }

    @Override
    public boolean isDone() {
        // Vanilla calls noPath() again after pathFollow(). If that call sees the
        // logical pending request instead of the Path array state, it reads one
        // node past the end when pathFollow() just consumed the final point.
        if (this.updatingVanillaNavigation) {
            return this.path == null || this.path.isDone();
        }
        if (this.path != null && !this.path.isDone()) {
            return false;
        }
        long age = this.level.getGameTime() - this.lastMoveRequestTick;
        return this.requestedTarget == null
            || this.hasArrived()
            || age >= 40L
            || (this.failedRepaths >= 10 && this.repathCooldown <= 0);
    }

    @Override
    public void stop() {
        super.stop();
        this.requestedTarget = null;
        this.lastComputedTarget = null;
        this.chaseEntity = null;
        this.lastMoveRequestTick = Long.MIN_VALUE;
        this.lastPathComputeTick = Long.MIN_VALUE;
        this.repathCooldown = 0;
        this.failedRepaths = 0;
        this.stuckCounter = 0;
        this.recoveryTicks = 0;
        this.clearJumpState();
        this.clearLandingSettle();
        this.jumpCooldown = 0;
        this.flatJumpCooldown = 0;
        this.bannedColumns.clear();
        this.releaseSprint();
        this.haltMoveHelper();
    }

    public void applyPostAiMovementLock() {
        if (this.recoveryTicks > 0) {
            this.haltMoveHelper();
            this.setMotionX(this.recoveryMotionX);
            this.setMotionZ(this.recoveryMotionZ);
            this.npc.hasImpulse = true;
            return;
        }
        boolean jumping = this.jumpTarget != null
            && this.movementLockTicks > 0
            && !this.npc.onGround();
        boolean settling = this.landingSettleTarget != null
            && this.landingSettleTicks > 0
            && this.npc.onGround();
        if (!jumping && !settling) {
            return;
        }
        this.haltMoveHelper();
        this.setMotionX(this.lockedMotionX);
        this.setMotionZ(this.lockedMotionZ);
        this.npc.hasImpulse = true;
        if (jumping) {
            this.npc.setSprinting(true);
            this.ownsSprint = true;
        }
    }

    private void recomputeActivePath() {
        BlockPos target = this.requestedTarget;
        if (this.chaseEntity != null && !this.chaseEntity.isRemoved()) {
            target = this.resolveTarget(this.predictTarget(this.chaseEntity));
            this.requestedTarget = target;
        }
        Path path = this.createBestPath(target, 1, true);
        this.lastComputedTarget = target;
        this.lastPathComputeTick = this.level.getGameTime();
        if (path != null && path.getNodeCount() >= 2) {
            this.installComputedPath(path, this.speedModifier <= 0.0D ? 1.0D : this.speedModifier);
            this.repathCooldown = 6;
            this.failedRepaths = 0;
            this.stuckCounter = 0;
        } else if (this.lastSearchDeferred) {
            this.repathCooldown = 2;
        } else {
            this.failedRepaths++;
            this.repathCooldown = 6 + Math.min(10, this.failedRepaths);
        }
    }

    private boolean installComputedPath(Path path, double speedIn) {
        // PathNavigate keeps the old Path object when the replacement has the
        // same points. A consumed old path must be detached so its end index is
        // not retained across a repath.
        if (this.path != null && this.path.isDone()) {
            super.stop();
        }
        return super.moveTo(path, speedIn);
    }

    private void finishNavigation() {
        super.stop();
        this.requestedTarget = null;
        this.lastComputedTarget = null;
        this.chaseEntity = null;
        this.clearJumpState();
        this.clearLandingSettle();
        this.recoveryTicks = 0;
        this.releaseSprint();
    }

    private boolean hasArrived() {
        if (this.requestedTarget == null || !this.npc.onGround()) {
            return false;
        }
        double x = this.requestedTarget.getX() + 0.5D;
        double z = this.requestedTarget.getZ() + 0.5D;
        double dx = this.npc.getX() - x;
        double dz = this.npc.getZ() - z;
        int feetY = Mth.floor(this.npc.getBoundingBox().minY + 0.05D);
        return dx * dx + dz * dz <= 0.64D
            && this.requestedTarget.getY() <= feetY
            && Math.abs(this.npc.getBoundingBox().minY - this.requestedTarget.getY()) < 1.0D;
    }

    private BlockPos predictTarget(Entity entity) {
        double lead = entity.getDeltaMovement().x * 4.0D;
        double leadZ = entity.getDeltaMovement().z * 4.0D;
        if (lead * lead + leadZ * leadZ > 16.0D) {
            double scale = 4.0D / Math.sqrt(lead * lead + leadZ * leadZ);
            lead *= scale;
            leadZ *= scale;
        }
        return BlockPos.containing(entity.getX() + lead, entity.getBoundingBox().minY, entity.getZ() + leadZ);
    }

    private void applyEnhancedMovement() {
        if (this.path == null || this.path.isDone()) {
            return;
        }

        int index = this.path.getNextNodeIndex();
        if (index >= this.path.getNodeCount()) {
            return;
        }
        Node point = this.path.getNode(index);
        Vec3 target = this.path.getEntityPosAtNode(this.npc, index);
        int feetY = Mth.floor(this.npc.getBoundingBox().minY + 0.05D);
        double dx = target.x - this.npc.getX();
        double dz = target.z - this.npc.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        int dy = point.y - feetY;

        if (this.handleLadder(point, target, dy)) {
            return;
        }

        if (this.jumpCooldown <= 0
            && this.npc.onGround()
            && this.isParkourTransition(point, horizontal, dy)) {
            if (this.centerOnNarrowTakeoff(point)) {
                return;
            }
            this.startParkourJump(point, horizontal, dy);
            return;
        }

        if (this.jumpCooldown <= 0
            && this.npc.onGround()
            && dy >= 1
            && dy <= 2
            && horizontal <= 1.8D) {
            this.startClimbJump(point, horizontal, dy);
            return;
        }

        double moveSpeed = this.speedModifier <= 0.0D ? 1.0D : this.speedModifier;
        if (this.chaseEntity != null && horizontal > 0.2D) {
            moveSpeed *= this.walkSpeedMultiplier();
            this.npc.setSprinting(true);
            this.ownsSprint = true;
            this.npc.getMoveControl().setWantedPosition(target.x, target.y, target.z, moveSpeed);
        } else if (this.ownsSprint && this.npc.onGround()) {
            this.releaseSprint();
        }

        this.faceToward(target.x, target.z);
        this.handleFlatChaseJump(target, feetY);
    }

    private boolean handleLadder(Node point, Vec3 target, int dy) {
        BlockPos feet = BlockPos.containing(
            Mth.floor(this.npc.getX()),
            Mth.floor(this.npc.getBoundingBox().minY + 0.05D),
            Mth.floor(this.npc.getZ())
        );
        BlockPos node = new BlockPos(point.x, point.y, point.z);
        if (!this.npc.onClimbable() && !this.isClimbable(feet) && !this.isClimbable(node)) {
            return false;
        }

        double moveY = dy > 0 ? 0.22D : (dy < 0 ? -0.12D : 0.05D);
        double dx = target.x - this.npc.getX();
        double dz = target.z - this.npc.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        double scale = length > 1.0E-4D ? Math.min(0.12D, length) / length : 0.0D;
        this.haltMoveHelper();
        this.setMotionX(dx * scale);
        this.setMotionY(moveY);
        this.setMotionZ(dz * scale);
        this.npc.hasImpulse = true;
        this.faceToward(target.x, target.z);
        return true;
    }

    private boolean isParkourTransition(Node point, double horizontal, int dy) {
        if (this.parkourMaxGap() <= 0 || horizontal < 1.35D || dy < -4 || dy > 2) {
            return false;
        }
        BlockPos landing = new BlockPos(point.x, point.y, point.z);
        if (!this.canStand(landing)) {
            return false;
        }

        int samples = Math.max(2, Mth.ceil(horizontal));
        int feetY = Mth.floor(this.npc.getBoundingBox().minY + 0.05D);
        boolean foundGap = false;
        for (int i = 1; i < samples; i++) {
            double part = i / (double) samples;
            int x = Mth.floor(this.npc.getX() + (landing.getX() + 0.5D - this.npc.getX()) * part);
            int z = Mth.floor(this.npc.getZ() + (landing.getZ() + 0.5D - this.npc.getZ()) * part);
            if (!this.canStand(new BlockPos(x, feetY, z))) {
                foundGap = true;
                break;
            }
        }
        return foundGap;
    }

    private void startParkourJump(Node point, double horizontal, int dy) {
        if (horizontal < 1.0E-4D) {
            return;
        }
        double verticalSpeed = this.jumpVelocity() + Math.max(0, dy) * 0.055D;
        if (dy <= -2) {
            verticalSpeed = Math.max(0.38D, verticalSpeed - 0.04D);
        }
        verticalSpeed = JumpTrajectoryPlanner.ensureReachableVerticalSpeed(verticalSpeed, Math.max(0, dy));
        this.beginControlledJump(
            new BlockPos(point.x, point.y, point.z),
            verticalSpeed,
            0.54D,
            true
        );
    }

    private void startClimbJump(Node point, double horizontal, int dy) {
        if (horizontal < 0.35D) {
            return;
        }
        double verticalSpeed = this.jumpVelocity() + (dy >= 2 ? 0.10D : 0.045D);
        verticalSpeed = JumpTrajectoryPlanner.ensureReachableVerticalSpeed(verticalSpeed, dy);
        this.beginControlledJump(
            new BlockPos(point.x, point.y, point.z),
            verticalSpeed,
            dy >= 2 ? 0.38D : 0.34D,
            false
        );
    }

    private void handleFlatChaseJump(Vec3 target, int feetY) {
        int interval = this.chaseJumpInterval();
        if (this.chaseEntity == null
            || interval <= 0
            || this.flatJumpCooldown > 0
            || this.jumpCooldown > 0
            || !this.npc.onGround()
            || this.npc.horizontalCollision
            || this.path == null
            || this.path.isDone()) {
            return;
        }
        if (this.npc.distanceToSqr(this.chaseEntity) < 16.0D || !this.nextNodesAreFlat(feetY)) {
            return;
        }

        double dx = target.x - this.npc.getX();
        double dz = target.z - this.npc.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-4D) {
            return;
        }
        double takeoff = Math.max(0.30D, Math.sqrt(this.motionX() * this.motionX() + this.motionZ() * this.motionZ()));
        this.setMotionX(dx / length * takeoff);
        this.setMotionY(this.jumpVelocity() * 0.85D);
        this.setMotionZ(dz / length * takeoff);
        this.npc.hasImpulse = true;
        this.flatJumpCooldown = Math.max(12, interval);
        this.jumpCooldown = 4;
        this.npc.setSprinting(true);
        this.ownsSprint = true;
    }

    private boolean nextNodesAreFlat(int feetY) {
        int end = Math.min(
            this.path.getNodeCount(),
            this.path.getNextNodeIndex() + 6
        );
        for (int i = this.path.getNextNodeIndex(); i < end; i++) {
            if (this.path.getNode(i).y != feetY) {
                return false;
            }
        }
        return true;
    }

    private boolean centerOnNarrowTakeoff(Node landing) {
        BlockPos feet = this.currentFeetPos();
        int adjacentSupports = 0;
        for (Direction facing : HORIZONTAL) {
            if (this.canStand(feet.relative(facing))) {
                adjacentSupports++;
            }
        }
        if (adjacentSupports > 1) {
            return false;
        }

        double dx = feet.getX() + 0.5D - this.npc.getX();
        double dz = feet.getZ() + 0.5D - this.npc.getZ();
        if (dx * dx + dz * dz <= 0.04D) {
            return false;
        }
        this.landingSettleTarget = feet;
        this.landingSettleTicks = 4;
        this.faceToward(landing.x + 0.5D, landing.z + 0.5D);
        return this.settleLanding();
    }

    private void beginControlledJump(
        BlockPos landing,
        double verticalSpeed,
        double maxHorizontalSpeed,
        boolean parkour
    ) {
        double dx = landing.getX() + 0.5D - this.npc.getX();
        double dz = landing.getZ() + 0.5D - this.npc.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < 1.0E-4D) {
            return;
        }

        int flightTicks = JumpTrajectoryPlanner.estimateFlightTicks(
            this.npc.getBoundingBox().minY,
            verticalSpeed,
            landing.getY()
        );
        double horizontalSpeed = JumpTrajectoryPlanner.horizontalSpeed(
            distance,
            flightTicks,
            maxHorizontalSpeed
        );
        double minimumSpeed = parkour ? 0.12D : 0.16D;
        horizontalSpeed = Math.min(maxHorizontalSpeed, Math.max(minimumSpeed, horizontalSpeed));

        this.clearLandingSettle();
        this.jumpTarget = landing;
        this.jumpAirTicks = 0;
        this.jumpLaunchGroundTicks = 0;
        this.parkourJump = parkour;
        this.jumpMaxHorizontalSpeed = maxHorizontalSpeed;
        this.movementLockTicks = 32;
        this.jumpCooldown = 6;
        this.lockedMotionX = dx / distance * horizontalSpeed;
        this.lockedMotionZ = dz / distance * horizontalSpeed;

        this.haltMoveHelper();
        this.setMotionX(this.lockedMotionX);
        this.setMotionY(verticalSpeed);
        this.setMotionZ(this.lockedMotionZ);
        this.npc.hasImpulse = true;
        this.npc.setSprinting(true);
        this.ownsSprint = true;
        this.faceToward(landing.getX() + 0.5D, landing.getZ() + 0.5D);
    }

    private void updateControlledJump() {
        BlockPos landing = this.jumpTarget;
        if (landing == null) {
            return;
        }
        this.movementLockTicks--;
        this.haltMoveHelper();
        this.faceToward(landing.getX() + 0.5D, landing.getZ() + 0.5D);

        if (this.npc.onGround()) {
            if (this.jumpAirTicks <= 0) {
                this.jumpLaunchGroundTicks++;
                if (this.jumpLaunchGroundTicks > 3 || this.movementLockTicks <= 0) {
                    this.failControlledJump();
                    return;
                }
                this.setMotionX(this.lockedMotionX);
                this.setMotionZ(this.lockedMotionZ);
                this.npc.hasImpulse = true;
                return;
            }
            this.finishControlledLanding(landing);
            return;
        }

        this.jumpAirTicks++;
        double currentY = this.npc.getBoundingBox().minY;
        double targetY = landing.getY();
        double dx = landing.getX() + 0.5D - this.npc.getX();
        double dz = landing.getZ() + 0.5D - this.npc.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        boolean unrecoverableWallHit = this.npc.horizontalCollision
            && this.jumpAirTicks >= 5
            && distance > 0.85D
            && currentY < targetY + 0.2D;
        boolean fellBelowLanding = this.motionY() < -0.05D && currentY < targetY - 0.55D;
        if (this.movementLockTicks <= 0 || unrecoverableWallHit || fellBelowLanding) {
            this.failControlledJump();
            return;
        }

        int remainingTicks = JumpTrajectoryPlanner.estimateFlightTicks(
            currentY,
            this.motionY(),
            targetY
        );
        double desiredSpeed = JumpTrajectoryPlanner.horizontalSpeed(
            distance,
            remainingTicks,
            this.jumpMaxHorizontalSpeed
        );
        boolean descendingIntoPad = this.motionY() <= 0.04D
            && currentY >= targetY - 0.15D
            && Math.abs(dx) <= 0.32D
            && Math.abs(dz) <= 0.32D;
        if (descendingIntoPad || distance < 0.08D) {
            desiredSpeed = 0.0D;
        }

        if (distance > 1.0E-4D) {
            this.lockedMotionX = dx / distance * desiredSpeed;
            this.lockedMotionZ = dz / distance * desiredSpeed;
        } else {
            this.lockedMotionX = 0.0D;
            this.lockedMotionZ = 0.0D;
        }
        this.setMotionX(this.lockedMotionX);
        this.setMotionZ(this.lockedMotionZ);
        this.npc.hasImpulse = true;
        this.npc.setSprinting(true);
        this.ownsSprint = true;
    }

    private void finishControlledLanding(BlockPos landing) {
        boolean landed = this.isStandingNearLanding(landing);
        boolean wasParkour = this.parkourJump;
        if (!landed) {
            this.failControlledJump();
            return;
        }

        this.advancePastLanding(landing);
        this.clearJumpState();
        this.landingSettleTarget = landing;
        this.landingSettleTicks = wasParkour ? 4 : 3;
        this.jumpCooldown = 1;
        this.settleLanding();
    }

    private boolean isStandingNearLanding(BlockPos landing) {
        double dx = this.npc.getX() - (landing.getX() + 0.5D);
        double dz = this.npc.getZ() - (landing.getZ() + 0.5D);
        return this.npc.onGround()
            && Math.abs(dx) <= 0.72D
            && Math.abs(dz) <= 0.72D
            && Math.abs(this.npc.getBoundingBox().minY - landing.getY()) <= 0.7D
            && this.canStand(landing);
    }

    private boolean settleLanding() {
        BlockPos landing = this.landingSettleTarget;
        if (landing == null) {
            return false;
        }
        if (!this.npc.onGround()
            || Math.abs(this.npc.getBoundingBox().minY - landing.getY()) > 0.8D) {
            this.clearLandingSettle();
            return false;
        }

        double dx = landing.getX() + 0.5D - this.npc.getX();
        double dz = landing.getZ() + 0.5D - this.npc.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance <= 0.10D || this.landingSettleTicks-- <= 0) {
            this.haltMoveHelper();
            this.lockedMotionX = 0.0D;
            this.lockedMotionZ = 0.0D;
            this.setMotionX(0.0D);
            this.setMotionZ(0.0D);
            this.npc.hasImpulse = true;
            this.clearLandingSettle();
            return false;
        }

        double speed = Math.min(0.12D, distance * 0.42D);
        this.lockedMotionX = dx / distance * speed;
        this.lockedMotionZ = dz / distance * speed;
        this.haltMoveHelper();
        this.setMotionX(this.lockedMotionX);
        this.setMotionZ(this.lockedMotionZ);
        this.npc.hasImpulse = true;
        return true;
    }

    private void failControlledJump() {
        this.clearJumpState();
        this.clearLandingSettle();
        this.triggerStuckRecovery();
    }

    private void clearJumpState() {
        this.jumpTarget = null;
        this.jumpAirTicks = 0;
        this.jumpLaunchGroundTicks = 0;
        this.parkourJump = false;
        this.jumpMaxHorizontalSpeed = 0.0D;
        this.movementLockTicks = 0;
        this.lockedMotionX = 0.0D;
        this.lockedMotionZ = 0.0D;
    }

    private void clearLandingSettle() {
        this.landingSettleTarget = null;
        this.landingSettleTicks = 0;
    }

    private void advancePastLanding(BlockPos landing) {
        if (this.path == null) {
            return;
        }
        int index = this.path.getNextNodeIndex();
        int end = Math.min(this.path.getNodeCount(), index + 5);
        for (int i = Math.max(0, index - 1); i < end; i++) {
            Node point = this.path.getNode(i);
            if (point.x == landing.getX()
                && point.z == landing.getZ()
                && Math.abs(point.y - landing.getY()) <= 1) {
                if (i >= index) {
                    this.path.setNextNodeIndex(i + 1);
                }
                return;
            }
        }
    }

    private void trackProgress() {
        if (this.path == null
            || this.path.isDone()
            || !this.npc.onGround()
            || this.movementLockTicks > 0
            || this.recoveryTicks > 0) {
            return;
        }
        Vec3 current = this.npc.position();
        if (current.distanceToSqr(this.lastProgressPosition) >= 0.0036D) {
            this.lastProgressPosition = current;
            this.stuckCounter = 0;
            return;
        }
        this.stuckCounter++;
        if (this.stuckCounter >= NpcPathfindingConfig.stuckTicks) {
            this.triggerStuckRecovery();
        }
    }

    private void triggerStuckRecovery() {
        if (this.requestedTarget == null || this.recoveryTicks > 0) {
            return;
        }
        Vec3 escape = this.chooseRecoveryDirection();
        this.recoveryMotionX = escape.x * 0.24D;
        this.recoveryMotionZ = escape.z * 0.24D;
        this.recoveryTicks = 8;
        this.stuckCounter = 0;
        this.clearJumpState();
        this.clearLandingSettle();
        this.banBlockingColumn(60);
        super.stop();
        this.repathCooldown = 2;
    }

    private Vec3 chooseRecoveryDirection() {
        double goalX = this.requestedTarget.getX() + 0.5D - this.npc.getX();
        double goalZ = this.requestedTarget.getZ() + 0.5D - this.npc.getZ();
        double length = Math.sqrt(goalX * goalX + goalZ * goalZ);
        if (length < 1.0E-4D) {
            goalX = 1.0D;
            goalZ = 0.0D;
            length = 1.0D;
        }
        double forwardX = goalX / length;
        double forwardZ = goalZ / length;
        Vec3[] choices = new Vec3[]{
            new Vec3(-forwardZ, 0.0D, forwardX),
            new Vec3(forwardZ, 0.0D, -forwardX),
            new Vec3(-forwardX, 0.0D, -forwardZ)
        };

        int feetY = Mth.floor(this.npc.getBoundingBox().minY + 0.05D);
        Vec3 best = choices[2];
        double bestScore = Double.MAX_VALUE;
        for (Vec3 choice : choices) {
            BlockPos candidate = BlockPos.containing(
                this.npc.getX() + choice.x * 1.25D,
                feetY,
                this.npc.getZ() + choice.z * 1.25D
            );
            if (!this.canStand(candidate) && !this.isBodyPassable(candidate)) {
                continue;
            }
            double dx = candidate.getX() + 0.5D - (this.requestedTarget.getX() + 0.5D);
            double dz = candidate.getZ() + 0.5D - (this.requestedTarget.getZ() + 0.5D);
            double score = dx * dx + dz * dz;
            if (score < bestScore) {
                bestScore = score;
                best = choice;
            }
        }
        return best;
    }

    private void applyRecoveryMotion() {
        this.recoveryTicks--;
        this.haltMoveHelper();
        this.setMotionX(this.recoveryMotionX);
        this.setMotionZ(this.recoveryMotionZ);
        this.npc.hasImpulse = true;
        this.faceToward(this.npc.getX() + this.recoveryMotionX * 4.0D, this.npc.getZ() + this.recoveryMotionZ * 4.0D);
        if (this.recoveryTicks <= 0) {
            this.repathCooldown = 0;
            this.lastProgressPosition = this.npc.position();
        }
    }

    private void banBlockingColumn(int ticks) {
        Vec3 direction;
        if (this.path != null && !this.path.isDone()) {
            Vec3 target = this.path.getNextEntityPos(this.npc);
            direction = new Vec3(target.x - this.npc.getX(), 0.0D, target.z - this.npc.getZ());
        } else {
            direction = new Vec3(
                this.requestedTarget.getX() + 0.5D - this.npc.getX(),
                0.0D,
                this.requestedTarget.getZ() + 0.5D - this.npc.getZ()
            );
        }
        double length = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        if (length < 1.0E-4D) {
            return;
        }
        int x = Mth.floor(this.npc.getX() + direction.x / length * 0.9D);
        int z = Mth.floor(this.npc.getZ() + direction.z / length * 0.9D);
        this.bannedColumns.put(columnKey(x, z), this.level.getGameTime() + ticks);
        if (this.bannedColumns.size() > 16) {
            this.removeExpiredBans();
        }
    }

    private boolean isColumnBanned(int x, int z) {
        Long until = this.bannedColumns.get(columnKey(x, z));
        return until != null && until > this.level.getGameTime();
    }

    private void removeExpiredBans() {
        long now = this.level.getGameTime();
        List<Long> expired = new ArrayList<Long>();
        for (Map.Entry<Long, Long> entry : this.bannedColumns.entrySet()) {
            if (entry.getValue() <= now) {
                expired.add(entry.getKey());
            }
        }
        for (Long key : expired) {
            this.bannedColumns.remove(key);
        }
    }

    private static long columnKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private void haltMoveHelper() {
        this.npc.getMoveControl().setWantedPosition(this.npc.getX(), this.npc.getY(), this.npc.getZ(), 0.0D);
    }

    private void faceToward(double x, double z) {
        double dx = x - this.npc.getX();
        double dz = z - this.npc.getZ();
        if (dx * dx + dz * dz < 1.0E-4D) {
            return;
        }
        float desired = (float) (Mth.atan2(dz, dx) * 57.29577951308232D) - 90.0F;
        float delta = Mth.wrapDegrees(desired - this.npc.getYRot());
        float yaw = this.npc.getYRot() + Mth.clamp(delta, -40.0F, 40.0F);
        this.npc.setYRot(yaw);
        this.npc.setYHeadRot(yaw);
        this.npc.yBodyRot = yaw;
    }

    private void releaseSprint() {
        if (this.ownsSprint) {
            this.npc.setSprinting(false);
            this.ownsSprint = false;
        }
    }

    private double jumpVelocity() {
        return 0.37D + 0.13D * (this.jumpTendency() / 100.0D);
    }

    private double walkSpeedMultiplier() {
        return 1.05D + 0.28D * (this.walkTendency() / 100.0D);
    }

    private int walkTendency() {
        return this.pathfindingData().npcpathfindinglegacy$getWalkTendency();
    }

    private int jumpTendency() {
        return this.pathfindingData().npcpathfindinglegacy$getJumpTendency();
    }

    private int parkourMaxGap() {
        return this.pathfindingData().npcpathfindinglegacy$getParkourMaxDist();
    }

    private int chaseJumpInterval() {
        return this.pathfindingData().npcpathfindinglegacy$getChaseJumpInterval();
    }

    private IDataAIPathfinding pathfindingData() {
        return (IDataAIPathfinding) this.npc.ais;
    }

    private Path createBestPath(BlockPos target, int accuracy, boolean useBudget) {
        this.lastSearchDeferred = false;
        if (target == null || !this.isLoaded(target)) {
            return null;
        }

        Path vanilla = null;
        if (this.canUseVanillaPathfinder()) {
            try {
                vanilla = super.createPath(target, 0);
            } catch (Throwable ignored) {
                // A modded block can still break the vanilla node processor. The bounded
                // path below uses guarded block queries and remains available.
            }
        }
        if (this.isCompleteUsefulPath(vanilla, target)) {
            return vanilla;
        }

        if (useBudget && !PathComputationBudget.tryAcquire(this.level)) {
            this.lastSearchDeferred = true;
            return this.isUsefulPartial(vanilla, target) ? vanilla : null;
        }

        Path custom = this.createCustomPath(target, accuracy);
        if (custom == null) {
            return this.isUsefulPartial(vanilla, target) ? vanilla : null;
        }
        if (this.isCompleteUsefulPath(custom, target)) {
            return custom;
        }
        if (this.isUsefulPartial(vanilla, target)
            && this.partialScore(this.finalPosition(vanilla), target)
                < this.partialScore(this.finalPosition(custom), target)) {
            return vanilla;
        }
        return custom;
    }

    private boolean isCompleteUsefulPath(Path path, BlockPos goal) {
        if (path == null || path.getNodeCount() < 2 || this.pathUsesBlacklistedBlock(path)) {
            return false;
        }
        BlockPos end = this.finalPosition(path);
        return end.distSqr(goal) <= 4.0D
            || (end.getX() == goal.getX()
                && end.getZ() == goal.getZ()
                && end.getY() >= goal.getY() - 1);
    }

    private boolean isUsefulPartial(Path path, BlockPos goal) {
        if (path == null || path.getNodeCount() < 2 || this.pathUsesBlacklistedBlock(path)) {
            return false;
        }
        BlockPos start = this.currentFeetPos();
        BlockPos end = this.finalPosition(path);
        return this.partialScore(end, goal) < this.partialScore(start, goal) - 0.75D
            && manhattan(start, end) >= 2;
    }

    private boolean pathUsesBlacklistedBlock(Path path) {
        for (int i = 0; i < path.getNodeCount(); i++) {
            Node point = path.getNode(i);
            BlockPos feet = new BlockPos(point.x, point.y, point.z);
            if (this.isConfiguredBlacklisted(feet) || this.isConfiguredBlacklisted(feet.below())) {
                return true;
            }
        }
        return false;
    }

    private BlockPos finalPosition(Path path) {
        Node point = path.getEndNode();
        return point == null
            ? this.currentFeetPos()
            : new BlockPos(point.x, point.y, point.z);
    }

    private Path createCustomPath(BlockPos goal, int accuracy) {
        BlockPos start = this.snapToStand(this.currentFeetPos());
        if (start == null || goal == null) {
            return null;
        }

        this.standCache = new HashMap<Long, Byte>();
        this.bodyCache = new HashMap<Long, Byte>();
        try {
            PriorityQueue<SearchNode> open = new PriorityQueue<SearchNode>(
                Comparator.comparingDouble(node -> node.f)
            );
            Map<BlockPos, SearchNode> known = new HashMap<BlockPos, SearchNode>();
            Set<BlockPos> closed = new HashSet<BlockPos>();
            SearchNode first = new SearchNode(start, null, 0.0D, this.heuristic(start, goal));
            open.add(first);
            known.put(start, first);

            SearchNode best = first;
            double bestScore = this.partialScore(start, goal);
            int visited = 0;
            int goalRadiusSq = Math.max(1, accuracy * accuracy);
            int maxManhattan = manhattan(start, goal) + 32;

            while (!open.isEmpty() && visited++ < NpcPathfindingConfig.maxSearchNodes) {
                SearchNode current = open.poll();
                SearchNode newest = known.get(current.pos);
                if (newest != current || !closed.add(current.pos)) {
                    continue;
                }

                double score = this.partialScore(current.pos, goal);
                if (score < bestScore - 1.0E-4D
                    || (Math.abs(score - bestScore) < 1.0E-4D && current.g < best.g)) {
                    best = current;
                    bestScore = score;
                }
                if (current.pos.distSqr(goal) <= goalRadiusSq) {
                    best = current;
                    break;
                }

                for (Move move : this.movesFrom(current.pos, goal)) {
                    if (closed.contains(move.to)
                        || manhattan(move.to, goal) > maxManhattan
                        || Math.abs(move.to.getY() - start.getY()) > 24
                        || (this.isColumnBanned(move.to.getX(), move.to.getZ())
                            && move.to.getY() > current.pos.getY())) {
                        continue;
                    }
                    double nextG = current.g + move.cost;
                    SearchNode old = known.get(move.to);
                    if (old != null && nextG >= old.g - 1.0E-6D) {
                        continue;
                    }
                    SearchNode next = new SearchNode(
                        move.to,
                        current,
                        nextG,
                        nextG + this.heuristic(move.to, goal)
                    );
                    known.put(move.to, next);
                    open.add(next);
                }
            }

            if (best == first || !this.isUsefulSearchEnd(best.pos, start, goal, goalRadiusSq)) {
                return null;
            }
            List<Node> points = new ArrayList<Node>();
            for (SearchNode node = best; node != null; node = node.parent) {
                points.add(new Node(node.pos.getX(), node.pos.getY(), node.pos.getZ()));
            }
            Collections.reverse(points);
            if (points.size() < 2) {
                return null;
            }
            return new Path(points, goal, best.pos.distSqr(goal) <= goalRadiusSq);
        } finally {
            this.standCache = null;
            this.bodyCache = null;
        }
    }

    private boolean isUsefulSearchEnd(BlockPos end, BlockPos start, BlockPos goal, int goalRadiusSq) {
        if (end.distSqr(goal) <= goalRadiusSq) {
            return true;
        }
        if (this.partialScore(end, goal) >= this.partialScore(start, goal) - 0.75D) {
            return false;
        }
        if (goal.getY() > start.getY() + 1 && end.getY() <= start.getY()) {
            return false;
        }
        return manhattan(start, end) >= 2;
    }

    private List<Move> movesFrom(BlockPos from, BlockPos goal) {
        List<Move> moves = new ArrayList<Move>(32);
        boolean goalHigher = goal.getY() > from.getY();
        boolean goalLower = goal.getY() < from.getY();

        for (Direction facing : HORIZONTAL) {
            BlockPos side = from.relative(facing);
            if (this.canStand(side)) {
                this.addMove(moves, side, this.walkCost(goalHigher ? 1.15D : 1.0D));
            }

            for (int up = 1; up <= 2; up++) {
                BlockPos raised = side.above(up);
                if (this.isColumnBanned(raised.getX(), raised.getZ())) {
                    break;
                }
                if (this.canStand(raised) && this.hasClimbClearance(from, raised)) {
                    this.addMove(moves, raised, this.jumpCost(1.25D + up * 0.35D));
                    break;
                }
            }

            BlockPos downOne = side.below();
            if (this.canStand(downOne)) {
                this.addMove(moves, downOne, goalLower ? 0.95D : 1.35D);
            } else if (!this.canStand(side)) {
                this.addDropMove(moves, from, side.getX(), side.getZ(), goal);
            }

            if (this.parkourMaxGap() > 0) {
                this.addParkourMoves(moves, from, facing, goal);
            }
        }

        this.addDiagonalMoves(moves, from);

        if (this.isClimbable(from) || this.isClimbable(from.below())) {
            BlockPos up = from.above();
            if (this.canOccupyClimb(up)) {
                this.addMove(moves, up, this.jumpCost(0.75D));
            }
            BlockPos down = from.below();
            if (this.canOccupyClimb(down)) {
                this.addMove(moves, down, 0.8D);
            }
        }

        if (goalLower) {
            this.addDropMove(moves, from, from.getX(), from.getZ(), goal);
        }
        return moves;
    }

    private void addDiagonalMoves(List<Move> moves, BlockPos from) {
        int[] signs = new int[]{-1, 1};
        for (int dx : signs) {
            for (int dz : signs) {
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos target = from.offset(dx, dy, dz);
                    if (!this.canStand(target) || !this.canMoveDiagonally(from, target, dx, dz)) {
                        continue;
                    }
                    this.addMove(moves, target, dy == 0 ? 1.45D : this.jumpCost(2.0D));
                }
                BlockPos down = from.offset(dx, -1, dz);
                if (this.canStand(down) && this.canMoveDiagonally(from, down, dx, dz)) {
                    this.addMove(moves, down, 1.65D);
                }
            }
        }
    }

    private boolean canMoveDiagonally(BlockPos from, BlockPos to, int dx, int dz) {
        int checkY = Math.max(from.getY(), to.getY());
        BlockPos sideX = BlockPos.containing(from.getX() + dx, checkY, from.getZ());
        BlockPos sideZ = BlockPos.containing(from.getX(), checkY, from.getZ() + dz);
        return this.isBodyColumnPassable(sideX) && this.isBodyColumnPassable(sideZ);
    }

    private void addDropMove(List<Move> moves, BlockPos from, int x, int z, BlockPos goal) {
        int minY = Math.max(
            from.getY() - NpcPathfindingConfig.maxDropHeight,
            Math.min(goal.getY() - 1, from.getY() - 1)
        );
        for (int y = from.getY() - 1; y >= minY; y--) {
            BlockPos landing = BlockPos.containing(x, y, z);
            if (!this.canStand(landing)) {
                continue;
            }
            boolean clear = true;
            for (int checkY = y + 1; checkY <= from.getY(); checkY++) {
                if (!this.isBodyPassable(new BlockPos(x, checkY, z))) {
                    clear = false;
                    break;
                }
            }
            if (clear) {
                int drop = from.getY() - y;
                this.addMove(moves, landing, 0.65D + drop * 0.35D);
            }
            return;
        }
    }

    private void addParkourMoves(List<Move> moves, BlockPos from, Direction direction, BlockPos goal) {
        int maxForward = Math.min(6, this.parkourMaxGap() + 1);
        int minYOffset = goal.getY() < from.getY() ? -4 : -1;
        for (int forward = 2; forward <= maxForward; forward++) {
            for (int yOffset = minYOffset; yOffset <= 2; yOffset++) {
                BlockPos requested = from.relative(direction, forward).above(yOffset);
                BlockPos landing = this.normalizeStand(requested);
                if (landing == null) {
                    continue;
                }
                int actualYOffset = landing.getY() - from.getY();
                if (actualYOffset < minYOffset
                    || actualYOffset > 2
                    || this.isColumnBanned(landing.getX(), landing.getZ())
                    || !this.canParkourJump(from, landing, direction, forward)) {
                    continue;
                }
                double cost = 0.55D + forward * 0.17D + Math.max(0, actualYOffset) * 0.18D;
                this.addMove(moves, landing, this.jumpCost(cost));
            }
        }
    }

    private boolean canParkourJump(BlockPos from, BlockPos to, Direction direction, int forward) {
        if (!this.canStand(to) || forward < 2) {
            return false;
        }
        boolean gap = false;
        int yOffset = to.getY() - from.getY();
        for (int step = 1; step < forward; step++) {
            double fraction = step / (double) forward;
            int x = from.getX() + direction.getStepX() * step;
            int z = from.getZ() + direction.getStepZ() * step;
            int baseY = from.getY() + Mth.floor(yOffset * fraction);
            if (!this.canStand(new BlockPos(x, from.getY(), z))) {
                gap = true;
            }

            double arc = 1.35D * 4.0D * fraction * (1.0D - fraction);
            int arcY = baseY + Mth.floor(arc);
            if (!this.isBodyColumnPassable(new BlockPos(x, arcY, z))) {
                return false;
            }
        }
        return gap;
    }

    private boolean hasClimbClearance(BlockPos from, BlockPos to) {
        int minY = Math.min(from.getY(), to.getY());
        int maxY = Math.max(from.getY(), to.getY()) + 1;
        for (int y = minY; y <= maxY; y++) {
            BlockPos check = BlockPos.containing(to.getX(), y, to.getZ());
            if (y >= to.getY() && !this.isBodyPassable(check)) {
                return false;
            }
        }
        return true;
    }

    private void addMove(List<Move> moves, BlockPos target, double cost) {
        for (Move move : moves) {
            if (move.to.equals(target) && move.cost <= cost) {
                return;
            }
        }
        moves.add(new Move(target, cost));
    }

    private double walkCost(double base) {
        return base * (1.12D - this.walkTendency() * 0.0024D);
    }

    private double jumpCost(double base) {
        return base * (1.35D - this.jumpTendency() * 0.006D);
    }

    private double heuristic(BlockPos position, BlockPos goal) {
        double dx = position.getX() - goal.getX();
        double dz = position.getZ() - goal.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        int dy = goal.getY() - position.getY();
        return horizontal + (dy > 0 ? dy * 2.4D : -dy * 1.25D);
    }

    private double partialScore(BlockPos position, BlockPos goal) {
        double dx = position.getX() - goal.getX();
        double dz = position.getZ() - goal.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        int dy = position.getY() - goal.getY();
        double yPenalty = dy < 0 ? -dy * 8.5D : dy * 3.0D;
        if (dy <= -2 && horizontal < 2.5D) {
            yPenalty += (2.5D - horizontal) * 12.0D;
        }
        return horizontal + yPenalty;
    }

    private static int manhattan(BlockPos first, BlockPos second) {
        return Math.abs(first.getX() - second.getX())
            + Math.abs(first.getY() - second.getY())
            + Math.abs(first.getZ() - second.getZ());
    }

    private BlockPos currentFeetPos() {
        return BlockPos.containing(
            this.npc.getX(),
            this.npc.getBoundingBox().minY + 0.05D,
            this.npc.getZ()
        );
    }

    private BlockPos snapToStand(BlockPos position) {
        if (this.canStand(position)) {
            return position;
        }
        BlockPos normalized = this.normalizeStand(position);
        if (normalized != null) {
            return normalized;
        }
        for (Direction facing : HORIZONTAL) {
            BlockPos side = position.relative(facing);
            if (this.canStand(side)) {
                return side;
            }
        }
        return null;
    }

    private BlockPos normalizeStand(BlockPos position) {
        if (this.canStand(position)) {
            return position;
        }
        for (int distance = 1; distance <= 2; distance++) {
            BlockPos up = position.above(distance);
            if (this.canStand(up)) {
                return up;
            }
            BlockPos down = position.below(distance);
            if (this.canStand(down)) {
                return down;
            }
        }
        return null;
    }

    private BlockPos resolveTarget(BlockPos requested) {
        if (requested == null || !this.isLoaded(requested)) {
            return requested;
        }
        if (this.canStand(requested)) {
            return requested;
        }

        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int radius = 0; radius <= 2; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (radius > 0 && Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    for (int dy = -3; dy <= 3; dy++) {
                        BlockPos candidate = requested.offset(dx, dy, dz);
                        if (!this.canStand(candidate)) {
                            continue;
                        }
                        double score = candidate.distSqr(requested)
                            + Math.abs(dy) * 0.35D
                            + radius * 0.2D;
                        if (score < bestScore) {
                            bestScore = score;
                            best = candidate;
                        }
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        return requested;
    }

    private boolean canStand(BlockPos feet) {
        if (feet == null || feet.getY() < this.level.getMinBuildHeight() || feet.getY() >= this.level.getMaxBuildHeight()) {
            return false;
        }
        long key = feet.asLong();
        if (this.standCache != null) {
            Byte cached = this.standCache.get(key);
            if (cached != null) {
                return cached.byteValue() == 1;
            }
        }

        boolean result = this.isBodyColumnPassable(feet)
            && (this.isClimbable(feet)
                || this.isClimbable(feet.below())
                || this.isSolidSupport(feet.below()));
        if (this.standCache != null) {
            this.standCache.put(key, result ? (byte) 1 : (byte) 0);
        }
        return result;
    }

    private boolean isBodyColumnPassable(BlockPos feet) {
        int height = Mth.clamp(Mth.ceil(this.npc.getBbHeight()), 2, 4);
        for (int y = 0; y < height; y++) {
            if (!this.isBodyPassable(feet.above(y))) {
                return false;
            }
        }
        return true;
    }

    private boolean isBodyPassable(BlockPos position) {
        if (position == null || !this.isLoaded(position)) {
            return false;
        }
        long key = position.asLong();
        if (this.bodyCache != null) {
            Byte cached = this.bodyCache.get(key);
            if (cached != null) {
                return cached.byteValue() == 1;
            }
        }

        BlockState state = this.level.getBlockState(position);
        Block block = state.getBlock();
        boolean passable;
        if (NpcPathfindingConfig.isBlacklisted(block)) {
            passable = false;
        } else if (NpcPathfindingConfig.isWhitelisted(block)
            || state.isAir()
            || this.isClimbable(position)) {
            passable = true;
        } else {
            FluidState fluid = state.getFluidState();
            if (state.is(Blocks.LAVA) || fluid.is(FluidTags.LAVA) || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)) {
                passable = false;
            } else if (fluid.is(FluidTags.WATER)) {
                passable = this.npc.ais != null && this.npc.ais.canSwim;
            } else {
                VoxelShape shape = state.getCollisionShape(this.level, position);
                passable = shape.isEmpty();
            }
        }

        if (this.bodyCache != null) {
            this.bodyCache.put(key, passable ? (byte) 1 : (byte) 0);
        }
        return passable;
    }

    private boolean isSolidSupport(BlockPos position) {
        if (position == null || !this.isLoaded(position)) {
            return false;
        }
        BlockState state = this.level.getBlockState(position);
        Block block = state.getBlock();
        FluidState fluid = state.getFluidState();
        if (NpcPathfindingConfig.isBlacklisted(block)
            || fluid.is(FluidTags.WATER)
            || fluid.is(FluidTags.LAVA)) {
            return false;
        }
        VoxelShape shape = state.getCollisionShape(this.level, position);
        if (shape.isEmpty()) {
            return false;
        }
        AABB collision = shape.bounds();
        return collision.maxY >= 0.4D
            && collision.maxY <= 1.01D;
    }

    private boolean canOccupyClimb(BlockPos position) {
        return this.isBodyColumnPassable(position)
            && (this.isClimbable(position)
                || this.isClimbable(position.below())
                || this.isSolidSupport(position.below()));
    }

    private boolean isClimbable(BlockPos position) {
        if (position == null || !this.isLoaded(position)) {
            return false;
        }
        BlockState state = this.level.getBlockState(position);
        return state.is(BlockTags.CLIMBABLE) || state.isLadder(this.level, position, this.npc);
    }
    private boolean isConfiguredBlacklisted(BlockPos position) {
        return position != null
            && this.isLoaded(position)
            && NpcPathfindingConfig.isBlacklisted(this.level.getBlockState(position).getBlock());
    }

    private boolean canUseVanillaPathfinder() {
        if (!NpcPathfindingConfig.preventChunkLoads) {
            return true;
        }
        int radius = (int) ((float) this.npc.getAttributeValue(Attributes.FOLLOW_RANGE) + 8.0F);
        return this.hasChunksAround(this.currentFeetPos(), radius);
    }

    private boolean isLoaded(BlockPos position) {
        return position != null
            && position.getY() >= this.level.getMinBuildHeight()
            && position.getY() < this.level.getMaxBuildHeight()
            && (!NpcPathfindingConfig.preventChunkLoads
                || this.level.isLoaded(position));
    }


    private double motionX() {
        return this.npc.getDeltaMovement().x;
    }

    private double motionY() {
        return this.npc.getDeltaMovement().y;
    }

    private double motionZ() {
        return this.npc.getDeltaMovement().z;
    }

    private void setMotionX(double x) {
        Vec3 m = this.npc.getDeltaMovement();
        this.npc.setDeltaMovement(x, m.y, m.z);
    }

    private void setMotionY(double y) {
        Vec3 m = this.npc.getDeltaMovement();
        this.npc.setDeltaMovement(m.x, y, m.z);
    }

    private void setMotionZ(double z) {
        Vec3 m = this.npc.getDeltaMovement();
        this.npc.setDeltaMovement(m.x, m.y, z);
    }

    private boolean hasChunksAround(BlockPos center, int radius) {
        return this.level.hasChunksAt(
            center.getX() - radius,
            center.getZ() - radius,
            center.getX() + radius,
            center.getZ() + radius
        );
    }
    private static final class SearchNode {
        private final BlockPos pos;
        private final SearchNode parent;
        private final double g;
        private final double f;

        private SearchNode(BlockPos pos, SearchNode parent, double g, double f) {
            this.pos = pos;
            this.parent = parent;
            this.g = g;
            this.f = f;
        }
    }

    private static final class Move {
        private final BlockPos to;
        private final double cost;

        private Move(BlockPos to, double cost) {
            this.to = to;
            this.cost = cost;
        }
    }
}
