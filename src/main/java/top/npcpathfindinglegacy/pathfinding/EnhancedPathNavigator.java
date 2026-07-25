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
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.pathfinding.Path;
import net.minecraft.pathfinding.PathNavigateGround;
import net.minecraft.pathfinding.PathPoint;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import noppes.npcs.entity.EntityNPCInterface;
import top.npcpathfindinglegacy.NpcPathfindingConfig;

/**
 * Ground navigator ported from the 1.20.1 addon without its unused embedded
 * Baritone payload. Vanilla navigation remains the cheap first choice; the
 * bounded custom A* is used for platforms, gaps, drops and partial recovery.
 */
public final class EnhancedPathNavigator extends PathNavigateGround {
    private static final EnumFacing[] HORIZONTAL = new EnumFacing[]{
        EnumFacing.NORTH,
        EnumFacing.SOUTH,
        EnumFacing.WEST,
        EnumFacing.EAST
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

    private Vec3d lastProgressPosition;
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

    public EnhancedPathNavigator(EntityNPCInterface npc, World world) {
        super(npc, world);
        this.npc = npc;
        this.lastProgressPosition = npc.getPositionVector();
        this.refreshCapabilities();
    }

    public void refreshCapabilities() {
        this.setCanSwim(npc.ais != null && npc.ais.canSwim);
        this.setEnterDoors(npc.ais != null && npc.ais.doorInteract == 1);
        this.setBreakDoors(npc.ais != null && npc.ais.doorInteract == 0);
    }

    @Override
    public Path getPathToPos(BlockPos target) {
        return this.createBestPath(this.resolveTarget(target), 1, true);
    }

    @Override
    public Path getPathToEntityLiving(Entity entity) {
        return this.createBestPath(this.resolveTarget(this.predictTarget(entity)), 1, true);
    }

    @Override
    public boolean tryMoveToXYZ(double x, double y, double z, double speedIn) {
        this.chaseEntity = null;
        return this.requestMove(this.resolveTarget(new BlockPos(x, y, z)), speedIn, false);
    }

    @Override
    public boolean tryMoveToEntityLiving(Entity entity, double speedIn) {
        this.chaseEntity = entity;
        return this.requestMove(this.resolveTarget(this.predictTarget(entity)), speedIn, true);
    }

    private boolean requestMove(BlockPos target, double speedIn, boolean chasing) {
        if (target == null) {
            return false;
        }

        long now = this.world.getTotalWorldTime();
        this.lastMoveRequestTick = now;
        double reuseDistance = chasing ? 2.25D : 1.0D;
        boolean sameGoal = this.lastComputedTarget != null
            && this.lastComputedTarget.distanceSq(target) <= reuseDistance;
        if (sameGoal
            && this.currentPath != null
            && !this.currentPath.isFinished()
            && now - this.lastPathComputeTick < NpcPathfindingConfig.pathReuseTicks) {
            this.requestedTarget = target;
            this.speed = speedIn;
            return true;
        }

        boolean changedGoal = this.requestedTarget == null
            || this.requestedTarget.distanceSq(target) > (chasing ? 4.0D : 1.0D);
        this.requestedTarget = target;
        if (changedGoal) {
            this.failedRepaths = 0;
            this.stuckCounter = 0;
            this.recoveryTicks = 0;
        }

        Path path = this.createBestPath(target, 1, true);
        this.lastPathComputeTick = now;
        this.lastComputedTarget = target;
        if (path == null || path.getCurrentPathLength() < 2) {
            this.repathCooldown = 3;
            // Keep the AI task alive while the global A* budget defers this NPC.
            // The active request is retried from onUpdateNavigation.
            return true;
        }

        this.repathCooldown = 0;
        this.failedRepaths = 0;
        this.lastProgressPosition = this.npc.getPositionVector();
        return this.installComputedPath(path, speedIn);
    }

    @Override
    public void onUpdateNavigation() {
        if (this.repathCooldown > 0) {
            this.repathCooldown--;
        }
        if (this.jumpCooldown > 0) {
            this.jumpCooldown--;
        }
        if (this.flatJumpCooldown > 0) {
            this.flatJumpCooldown--;
        }

        long now = this.world.getTotalWorldTime();
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
            && (this.currentPath == null || this.currentPath.isFinished())
            && this.repathCooldown <= 0
            && this.failedRepaths < 10) {
            this.recomputeActivePath();
        }

        if (this.recoveryTicks > 0) {
            this.applyRecoveryMotion();
            this.trackProgress();
            return;
        }

        if (this.currentPath != null && !this.currentPath.isFinished()) {
            this.updatingVanillaNavigation = true;
            try {
                super.onUpdateNavigation();
            } finally {
                this.updatingVanillaNavigation = false;
            }
            this.applyEnhancedMovement();
            this.trackProgress();
        }
    }

    @Override
    public boolean noPath() {
        // Vanilla calls noPath() again after pathFollow(). If that call sees the
        // logical pending request instead of the Path array state, it reads one
        // node past the end when pathFollow() just consumed the final point.
        if (this.updatingVanillaNavigation) {
            return this.currentPath == null || this.currentPath.isFinished();
        }
        if (this.currentPath != null && !this.currentPath.isFinished()) {
            return false;
        }
        long age = this.world.getTotalWorldTime() - this.lastMoveRequestTick;
        return this.requestedTarget == null
            || this.hasArrived()
            || age >= 40L
            || (this.failedRepaths >= 10 && this.repathCooldown <= 0);
    }

    @Override
    public void clearPath() {
        super.clearPath();
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
            this.npc.motionX = this.recoveryMotionX;
            this.npc.motionZ = this.recoveryMotionZ;
            this.npc.velocityChanged = true;
            return;
        }
        boolean jumping = this.jumpTarget != null
            && this.movementLockTicks > 0
            && !this.npc.onGround;
        boolean settling = this.landingSettleTarget != null
            && this.landingSettleTicks > 0
            && this.npc.onGround;
        if (!jumping && !settling) {
            return;
        }
        this.haltMoveHelper();
        this.npc.motionX = this.lockedMotionX;
        this.npc.motionZ = this.lockedMotionZ;
        this.npc.velocityChanged = true;
        if (jumping) {
            this.npc.setSprinting(true);
            this.ownsSprint = true;
        }
    }

    private void recomputeActivePath() {
        BlockPos target = this.requestedTarget;
        if (this.chaseEntity != null && !this.chaseEntity.isDead) {
            target = this.resolveTarget(this.predictTarget(this.chaseEntity));
            this.requestedTarget = target;
        }
        Path path = this.createBestPath(target, 1, true);
        this.lastComputedTarget = target;
        this.lastPathComputeTick = this.world.getTotalWorldTime();
        if (path != null && path.getCurrentPathLength() >= 2) {
            this.installComputedPath(path, this.speed <= 0.0D ? 1.0D : this.speed);
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
        if (this.currentPath != null && this.currentPath.isFinished()) {
            super.clearPath();
        }
        return super.setPath(path, speedIn);
    }

    private void finishNavigation() {
        super.clearPath();
        this.requestedTarget = null;
        this.lastComputedTarget = null;
        this.chaseEntity = null;
        this.clearJumpState();
        this.clearLandingSettle();
        this.recoveryTicks = 0;
        this.releaseSprint();
    }

    private boolean hasArrived() {
        if (this.requestedTarget == null || !this.npc.onGround) {
            return false;
        }
        double x = this.requestedTarget.getX() + 0.5D;
        double z = this.requestedTarget.getZ() + 0.5D;
        double dx = this.npc.posX - x;
        double dz = this.npc.posZ - z;
        int feetY = MathHelper.floor(this.npc.getEntityBoundingBox().minY + 0.05D);
        return dx * dx + dz * dz <= 0.64D
            && this.requestedTarget.getY() <= feetY
            && Math.abs(this.npc.getEntityBoundingBox().minY - this.requestedTarget.getY()) < 1.0D;
    }

    private BlockPos predictTarget(Entity entity) {
        double lead = entity.motionX * 4.0D;
        double leadZ = entity.motionZ * 4.0D;
        if (lead * lead + leadZ * leadZ > 16.0D) {
            double scale = 4.0D / Math.sqrt(lead * lead + leadZ * leadZ);
            lead *= scale;
            leadZ *= scale;
        }
        return new BlockPos(entity.posX + lead, entity.getEntityBoundingBox().minY, entity.posZ + leadZ);
    }

    private void applyEnhancedMovement() {
        if (this.currentPath == null || this.currentPath.isFinished()) {
            return;
        }

        int index = this.currentPath.getCurrentPathIndex();
        if (index >= this.currentPath.getCurrentPathLength()) {
            return;
        }
        PathPoint point = this.currentPath.getPathPointFromIndex(index);
        Vec3d target = this.currentPath.getVectorFromIndex(this.npc, index);
        int feetY = MathHelper.floor(this.npc.getEntityBoundingBox().minY + 0.05D);
        double dx = target.x - this.npc.posX;
        double dz = target.z - this.npc.posZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        int dy = point.y - feetY;

        if (this.handleLadder(point, target, dy)) {
            return;
        }

        if (this.jumpCooldown <= 0
            && this.npc.onGround
            && this.isParkourTransition(point, horizontal, dy)) {
            if (this.centerOnNarrowTakeoff(point)) {
                return;
            }
            this.startParkourJump(point, horizontal, dy);
            return;
        }

        if (this.jumpCooldown <= 0
            && this.npc.onGround
            && dy >= 1
            && dy <= 2
            && horizontal <= 1.8D) {
            this.startClimbJump(point, horizontal, dy);
            return;
        }

        double moveSpeed = this.speed <= 0.0D ? 1.0D : this.speed;
        if (this.chaseEntity != null && horizontal > 0.2D) {
            moveSpeed *= this.walkSpeedMultiplier();
            this.npc.setSprinting(true);
            this.ownsSprint = true;
            this.npc.getMoveHelper().setMoveTo(target.x, target.y, target.z, moveSpeed);
        } else if (this.ownsSprint && this.npc.onGround) {
            this.releaseSprint();
        }

        this.faceToward(target.x, target.z);
        this.handleFlatChaseJump(target, feetY);
    }

    private boolean handleLadder(PathPoint point, Vec3d target, int dy) {
        BlockPos feet = new BlockPos(
            MathHelper.floor(this.npc.posX),
            MathHelper.floor(this.npc.getEntityBoundingBox().minY + 0.05D),
            MathHelper.floor(this.npc.posZ)
        );
        BlockPos node = new BlockPos(point.x, point.y, point.z);
        if (!this.npc.isOnLadder() && !this.isClimbable(feet) && !this.isClimbable(node)) {
            return false;
        }

        double moveY = dy > 0 ? 0.22D : (dy < 0 ? -0.12D : 0.05D);
        double dx = target.x - this.npc.posX;
        double dz = target.z - this.npc.posZ;
        double length = Math.sqrt(dx * dx + dz * dz);
        double scale = length > 1.0E-4D ? Math.min(0.12D, length) / length : 0.0D;
        this.haltMoveHelper();
        this.npc.motionX = dx * scale;
        this.npc.motionY = moveY;
        this.npc.motionZ = dz * scale;
        this.npc.velocityChanged = true;
        this.faceToward(target.x, target.z);
        return true;
    }

    private boolean isParkourTransition(PathPoint point, double horizontal, int dy) {
        if (this.parkourMaxGap() <= 0 || horizontal < 1.35D || dy < -4 || dy > 2) {
            return false;
        }
        BlockPos landing = new BlockPos(point.x, point.y, point.z);
        if (!this.canStand(landing)) {
            return false;
        }

        int samples = Math.max(2, MathHelper.ceil(horizontal));
        int feetY = MathHelper.floor(this.npc.getEntityBoundingBox().minY + 0.05D);
        boolean foundGap = false;
        for (int i = 1; i < samples; i++) {
            double part = i / (double) samples;
            int x = MathHelper.floor(this.npc.posX + (landing.getX() + 0.5D - this.npc.posX) * part);
            int z = MathHelper.floor(this.npc.posZ + (landing.getZ() + 0.5D - this.npc.posZ) * part);
            if (!this.canStand(new BlockPos(x, feetY, z))) {
                foundGap = true;
                break;
            }
        }
        return foundGap;
    }

    private void startParkourJump(PathPoint point, double horizontal, int dy) {
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

    private void startClimbJump(PathPoint point, double horizontal, int dy) {
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

    private void handleFlatChaseJump(Vec3d target, int feetY) {
        int interval = this.chaseJumpInterval();
        if (this.chaseEntity == null
            || interval <= 0
            || this.flatJumpCooldown > 0
            || this.jumpCooldown > 0
            || !this.npc.onGround
            || this.npc.collidedHorizontally
            || this.currentPath == null
            || this.currentPath.isFinished()) {
            return;
        }
        if (this.npc.getDistanceSq(this.chaseEntity) < 16.0D || !this.nextNodesAreFlat(feetY)) {
            return;
        }

        double dx = target.x - this.npc.posX;
        double dz = target.z - this.npc.posZ;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-4D) {
            return;
        }
        double takeoff = Math.max(0.30D, Math.sqrt(this.npc.motionX * this.npc.motionX + this.npc.motionZ * this.npc.motionZ));
        this.npc.motionX = dx / length * takeoff;
        this.npc.motionY = this.jumpVelocity() * 0.85D;
        this.npc.motionZ = dz / length * takeoff;
        this.npc.velocityChanged = true;
        this.flatJumpCooldown = Math.max(12, interval);
        this.jumpCooldown = 4;
        this.npc.setSprinting(true);
        this.ownsSprint = true;
    }

    private boolean nextNodesAreFlat(int feetY) {
        int end = Math.min(
            this.currentPath.getCurrentPathLength(),
            this.currentPath.getCurrentPathIndex() + 6
        );
        for (int i = this.currentPath.getCurrentPathIndex(); i < end; i++) {
            if (this.currentPath.getPathPointFromIndex(i).y != feetY) {
                return false;
            }
        }
        return true;
    }

    private boolean centerOnNarrowTakeoff(PathPoint landing) {
        BlockPos feet = this.currentFeetPos();
        int adjacentSupports = 0;
        for (EnumFacing facing : HORIZONTAL) {
            if (this.canStand(feet.offset(facing))) {
                adjacentSupports++;
            }
        }
        if (adjacentSupports > 1) {
            return false;
        }

        double dx = feet.getX() + 0.5D - this.npc.posX;
        double dz = feet.getZ() + 0.5D - this.npc.posZ;
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
        double dx = landing.getX() + 0.5D - this.npc.posX;
        double dz = landing.getZ() + 0.5D - this.npc.posZ;
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < 1.0E-4D) {
            return;
        }

        int flightTicks = JumpTrajectoryPlanner.estimateFlightTicks(
            this.npc.getEntityBoundingBox().minY,
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
        this.npc.motionX = this.lockedMotionX;
        this.npc.motionY = verticalSpeed;
        this.npc.motionZ = this.lockedMotionZ;
        this.npc.velocityChanged = true;
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

        if (this.npc.onGround) {
            if (this.jumpAirTicks <= 0) {
                this.jumpLaunchGroundTicks++;
                if (this.jumpLaunchGroundTicks > 3 || this.movementLockTicks <= 0) {
                    this.failControlledJump();
                    return;
                }
                this.npc.motionX = this.lockedMotionX;
                this.npc.motionZ = this.lockedMotionZ;
                this.npc.velocityChanged = true;
                return;
            }
            this.finishControlledLanding(landing);
            return;
        }

        this.jumpAirTicks++;
        double currentY = this.npc.getEntityBoundingBox().minY;
        double targetY = landing.getY();
        double dx = landing.getX() + 0.5D - this.npc.posX;
        double dz = landing.getZ() + 0.5D - this.npc.posZ;
        double distance = Math.sqrt(dx * dx + dz * dz);
        boolean unrecoverableWallHit = this.npc.collidedHorizontally
            && this.jumpAirTicks >= 5
            && distance > 0.85D
            && currentY < targetY + 0.2D;
        boolean fellBelowLanding = this.npc.motionY < -0.05D && currentY < targetY - 0.55D;
        if (this.movementLockTicks <= 0 || unrecoverableWallHit || fellBelowLanding) {
            this.failControlledJump();
            return;
        }

        int remainingTicks = JumpTrajectoryPlanner.estimateFlightTicks(
            currentY,
            this.npc.motionY,
            targetY
        );
        double desiredSpeed = JumpTrajectoryPlanner.horizontalSpeed(
            distance,
            remainingTicks,
            this.jumpMaxHorizontalSpeed
        );
        boolean descendingIntoPad = this.npc.motionY <= 0.04D
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
        this.npc.motionX = this.lockedMotionX;
        this.npc.motionZ = this.lockedMotionZ;
        this.npc.velocityChanged = true;
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
        double dx = this.npc.posX - (landing.getX() + 0.5D);
        double dz = this.npc.posZ - (landing.getZ() + 0.5D);
        return this.npc.onGround
            && Math.abs(dx) <= 0.72D
            && Math.abs(dz) <= 0.72D
            && Math.abs(this.npc.getEntityBoundingBox().minY - landing.getY()) <= 0.7D
            && this.canStand(landing);
    }

    private boolean settleLanding() {
        BlockPos landing = this.landingSettleTarget;
        if (landing == null) {
            return false;
        }
        if (!this.npc.onGround
            || Math.abs(this.npc.getEntityBoundingBox().minY - landing.getY()) > 0.8D) {
            this.clearLandingSettle();
            return false;
        }

        double dx = landing.getX() + 0.5D - this.npc.posX;
        double dz = landing.getZ() + 0.5D - this.npc.posZ;
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance <= 0.10D || this.landingSettleTicks-- <= 0) {
            this.haltMoveHelper();
            this.lockedMotionX = 0.0D;
            this.lockedMotionZ = 0.0D;
            this.npc.motionX = 0.0D;
            this.npc.motionZ = 0.0D;
            this.npc.velocityChanged = true;
            this.clearLandingSettle();
            return false;
        }

        double speed = Math.min(0.12D, distance * 0.42D);
        this.lockedMotionX = dx / distance * speed;
        this.lockedMotionZ = dz / distance * speed;
        this.haltMoveHelper();
        this.npc.motionX = this.lockedMotionX;
        this.npc.motionZ = this.lockedMotionZ;
        this.npc.velocityChanged = true;
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
        if (this.currentPath == null) {
            return;
        }
        int index = this.currentPath.getCurrentPathIndex();
        int end = Math.min(this.currentPath.getCurrentPathLength(), index + 5);
        for (int i = Math.max(0, index - 1); i < end; i++) {
            PathPoint point = this.currentPath.getPathPointFromIndex(i);
            if (point.x == landing.getX()
                && point.z == landing.getZ()
                && Math.abs(point.y - landing.getY()) <= 1) {
                if (i >= index) {
                    this.currentPath.setCurrentPathIndex(i + 1);
                }
                return;
            }
        }
    }

    private void trackProgress() {
        if (this.currentPath == null
            || this.currentPath.isFinished()
            || !this.npc.onGround
            || this.movementLockTicks > 0
            || this.recoveryTicks > 0) {
            return;
        }
        Vec3d current = this.npc.getPositionVector();
        if (current.squareDistanceTo(this.lastProgressPosition) >= 0.0036D) {
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
        Vec3d escape = this.chooseRecoveryDirection();
        this.recoveryMotionX = escape.x * 0.24D;
        this.recoveryMotionZ = escape.z * 0.24D;
        this.recoveryTicks = 8;
        this.stuckCounter = 0;
        this.clearJumpState();
        this.clearLandingSettle();
        this.banBlockingColumn(60);
        super.clearPath();
        this.repathCooldown = 2;
    }

    private Vec3d chooseRecoveryDirection() {
        double goalX = this.requestedTarget.getX() + 0.5D - this.npc.posX;
        double goalZ = this.requestedTarget.getZ() + 0.5D - this.npc.posZ;
        double length = Math.sqrt(goalX * goalX + goalZ * goalZ);
        if (length < 1.0E-4D) {
            goalX = 1.0D;
            goalZ = 0.0D;
            length = 1.0D;
        }
        double forwardX = goalX / length;
        double forwardZ = goalZ / length;
        Vec3d[] choices = new Vec3d[]{
            new Vec3d(-forwardZ, 0.0D, forwardX),
            new Vec3d(forwardZ, 0.0D, -forwardX),
            new Vec3d(-forwardX, 0.0D, -forwardZ)
        };

        int feetY = MathHelper.floor(this.npc.getEntityBoundingBox().minY + 0.05D);
        Vec3d best = choices[2];
        double bestScore = Double.MAX_VALUE;
        for (Vec3d choice : choices) {
            BlockPos candidate = new BlockPos(
                this.npc.posX + choice.x * 1.25D,
                feetY,
                this.npc.posZ + choice.z * 1.25D
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
        this.npc.motionX = this.recoveryMotionX;
        this.npc.motionZ = this.recoveryMotionZ;
        this.npc.velocityChanged = true;
        this.faceToward(this.npc.posX + this.recoveryMotionX * 4.0D, this.npc.posZ + this.recoveryMotionZ * 4.0D);
        if (this.recoveryTicks <= 0) {
            this.repathCooldown = 0;
            this.lastProgressPosition = this.npc.getPositionVector();
        }
    }

    private void banBlockingColumn(int ticks) {
        Vec3d direction;
        if (this.currentPath != null && !this.currentPath.isFinished()) {
            Vec3d target = this.currentPath.getPosition(this.npc);
            direction = new Vec3d(target.x - this.npc.posX, 0.0D, target.z - this.npc.posZ);
        } else {
            direction = new Vec3d(
                this.requestedTarget.getX() + 0.5D - this.npc.posX,
                0.0D,
                this.requestedTarget.getZ() + 0.5D - this.npc.posZ
            );
        }
        double length = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        if (length < 1.0E-4D) {
            return;
        }
        int x = MathHelper.floor(this.npc.posX + direction.x / length * 0.9D);
        int z = MathHelper.floor(this.npc.posZ + direction.z / length * 0.9D);
        this.bannedColumns.put(columnKey(x, z), this.world.getTotalWorldTime() + ticks);
        if (this.bannedColumns.size() > 16) {
            this.removeExpiredBans();
        }
    }

    private boolean isColumnBanned(int x, int z) {
        Long until = this.bannedColumns.get(columnKey(x, z));
        return until != null && until > this.world.getTotalWorldTime();
    }

    private void removeExpiredBans() {
        long now = this.world.getTotalWorldTime();
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
        this.npc.getMoveHelper().setMoveTo(this.npc.posX, this.npc.posY, this.npc.posZ, 0.0D);
    }

    private void faceToward(double x, double z) {
        double dx = x - this.npc.posX;
        double dz = z - this.npc.posZ;
        if (dx * dx + dz * dz < 1.0E-4D) {
            return;
        }
        float desired = (float) (MathHelper.atan2(dz, dx) * 57.29577951308232D) - 90.0F;
        float delta = MathHelper.wrapDegrees(desired - this.npc.rotationYaw);
        float yaw = this.npc.rotationYaw + MathHelper.clamp(delta, -40.0F, 40.0F);
        this.npc.rotationYaw = yaw;
        this.npc.rotationYawHead = yaw;
        this.npc.renderYawOffset = yaw;
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
                vanilla = super.getPathToPos(target);
            } catch (Throwable ignored) {
                // A modded block can still break the vanilla node processor. The bounded
                // path below uses guarded block queries and remains available.
            }
        }
        if (this.isCompleteUsefulPath(vanilla, target)) {
            return vanilla;
        }

        if (useBudget && !PathComputationBudget.tryAcquire(this.world)) {
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
        if (path == null || path.getCurrentPathLength() < 2 || this.pathUsesBlacklistedBlock(path)) {
            return false;
        }
        BlockPos end = this.finalPosition(path);
        return end.distanceSq(goal) <= 4.0D
            || (end.getX() == goal.getX()
                && end.getZ() == goal.getZ()
                && end.getY() >= goal.getY() - 1);
    }

    private boolean isUsefulPartial(Path path, BlockPos goal) {
        if (path == null || path.getCurrentPathLength() < 2 || this.pathUsesBlacklistedBlock(path)) {
            return false;
        }
        BlockPos start = this.currentFeetPos();
        BlockPos end = this.finalPosition(path);
        return this.partialScore(end, goal) < this.partialScore(start, goal) - 0.75D
            && manhattan(start, end) >= 2;
    }

    private boolean pathUsesBlacklistedBlock(Path path) {
        for (int i = 0; i < path.getCurrentPathLength(); i++) {
            PathPoint point = path.getPathPointFromIndex(i);
            BlockPos feet = new BlockPos(point.x, point.y, point.z);
            if (this.isConfiguredBlacklisted(feet) || this.isConfiguredBlacklisted(feet.down())) {
                return true;
            }
        }
        return false;
    }

    private BlockPos finalPosition(Path path) {
        PathPoint point = path.getFinalPathPoint();
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
                if (current.pos.distanceSq(goal) <= goalRadiusSq) {
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
            List<PathPoint> points = new ArrayList<PathPoint>();
            for (SearchNode node = best; node != null; node = node.parent) {
                points.add(new PathPoint(node.pos.getX(), node.pos.getY(), node.pos.getZ()));
            }
            Collections.reverse(points);
            if (points.size() < 2) {
                return null;
            }
            return new Path(points.toArray(new PathPoint[points.size()]));
        } finally {
            this.standCache = null;
            this.bodyCache = null;
        }
    }

    private boolean isUsefulSearchEnd(BlockPos end, BlockPos start, BlockPos goal, int goalRadiusSq) {
        if (end.distanceSq(goal) <= goalRadiusSq) {
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

        for (EnumFacing facing : HORIZONTAL) {
            BlockPos side = from.offset(facing);
            if (this.canStand(side)) {
                this.addMove(moves, side, this.walkCost(goalHigher ? 1.15D : 1.0D));
            }

            for (int up = 1; up <= 2; up++) {
                BlockPos raised = side.up(up);
                if (this.isColumnBanned(raised.getX(), raised.getZ())) {
                    break;
                }
                if (this.canStand(raised) && this.hasClimbClearance(from, raised)) {
                    this.addMove(moves, raised, this.jumpCost(1.25D + up * 0.35D));
                    break;
                }
            }

            BlockPos downOne = side.down();
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

        if (this.isClimbable(from) || this.isClimbable(from.down())) {
            BlockPos up = from.up();
            if (this.canOccupyClimb(up)) {
                this.addMove(moves, up, this.jumpCost(0.75D));
            }
            BlockPos down = from.down();
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
                    BlockPos target = from.add(dx, dy, dz);
                    if (!this.canStand(target) || !this.canMoveDiagonally(from, target, dx, dz)) {
                        continue;
                    }
                    this.addMove(moves, target, dy == 0 ? 1.45D : this.jumpCost(2.0D));
                }
                BlockPos down = from.add(dx, -1, dz);
                if (this.canStand(down) && this.canMoveDiagonally(from, down, dx, dz)) {
                    this.addMove(moves, down, 1.65D);
                }
            }
        }
    }

    private boolean canMoveDiagonally(BlockPos from, BlockPos to, int dx, int dz) {
        int checkY = Math.max(from.getY(), to.getY());
        BlockPos sideX = new BlockPos(from.getX() + dx, checkY, from.getZ());
        BlockPos sideZ = new BlockPos(from.getX(), checkY, from.getZ() + dz);
        return this.isBodyColumnPassable(sideX) && this.isBodyColumnPassable(sideZ);
    }

    private void addDropMove(List<Move> moves, BlockPos from, int x, int z, BlockPos goal) {
        int minY = Math.max(
            from.getY() - NpcPathfindingConfig.maxDropHeight,
            Math.min(goal.getY() - 1, from.getY() - 1)
        );
        for (int y = from.getY() - 1; y >= minY; y--) {
            BlockPos landing = new BlockPos(x, y, z);
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

    private void addParkourMoves(List<Move> moves, BlockPos from, EnumFacing direction, BlockPos goal) {
        int maxForward = Math.min(6, this.parkourMaxGap() + 1);
        int minYOffset = goal.getY() < from.getY() ? -4 : -1;
        for (int forward = 2; forward <= maxForward; forward++) {
            for (int yOffset = minYOffset; yOffset <= 2; yOffset++) {
                BlockPos requested = from.offset(direction, forward).up(yOffset);
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

    private boolean canParkourJump(BlockPos from, BlockPos to, EnumFacing direction, int forward) {
        if (!this.canStand(to) || forward < 2) {
            return false;
        }
        boolean gap = false;
        int yOffset = to.getY() - from.getY();
        for (int step = 1; step < forward; step++) {
            double fraction = step / (double) forward;
            int x = from.getX() + direction.getFrontOffsetX() * step;
            int z = from.getZ() + direction.getFrontOffsetZ() * step;
            int baseY = from.getY() + MathHelper.floor(yOffset * fraction);
            if (!this.canStand(new BlockPos(x, from.getY(), z))) {
                gap = true;
            }

            double arc = 1.35D * 4.0D * fraction * (1.0D - fraction);
            int arcY = baseY + MathHelper.floor(arc);
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
            BlockPos check = new BlockPos(to.getX(), y, to.getZ());
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
        return new BlockPos(
            this.npc.posX,
            this.npc.getEntityBoundingBox().minY + 0.05D,
            this.npc.posZ
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
        for (EnumFacing facing : HORIZONTAL) {
            BlockPos side = position.offset(facing);
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
            BlockPos up = position.up(distance);
            if (this.canStand(up)) {
                return up;
            }
            BlockPos down = position.down(distance);
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
                        BlockPos candidate = requested.add(dx, dy, dz);
                        if (!this.canStand(candidate)) {
                            continue;
                        }
                        double score = candidate.distanceSq(requested)
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
        if (feet == null || feet.getY() <= 0 || feet.getY() >= 255) {
            return false;
        }
        long key = feet.toLong();
        if (this.standCache != null) {
            Byte cached = this.standCache.get(key);
            if (cached != null) {
                return cached.byteValue() == 1;
            }
        }

        boolean result = this.isBodyColumnPassable(feet)
            && (this.isClimbable(feet)
                || this.isClimbable(feet.down())
                || this.isSolidSupport(feet.down()));
        if (this.standCache != null) {
            this.standCache.put(key, result ? (byte) 1 : (byte) 0);
        }
        return result;
    }

    private boolean isBodyColumnPassable(BlockPos feet) {
        int height = MathHelper.clamp(MathHelper.ceil(this.npc.height), 2, 4);
        for (int y = 0; y < height; y++) {
            if (!this.isBodyPassable(feet.up(y))) {
                return false;
            }
        }
        return true;
    }

    private boolean isBodyPassable(BlockPos position) {
        if (position == null || !this.isLoaded(position)) {
            return false;
        }
        long key = position.toLong();
        if (this.bodyCache != null) {
            Byte cached = this.bodyCache.get(key);
            if (cached != null) {
                return cached.byteValue() == 1;
            }
        }

        IBlockState state = this.world.getBlockState(position);
        Block block = state.getBlock();
        boolean passable;
        if (NpcPathfindingConfig.isBlacklisted(block)) {
            passable = false;
        } else if (NpcPathfindingConfig.isWhitelisted(block)
            || block == Blocks.AIR
            || this.isClimbable(position)) {
            passable = true;
        } else {
            Material material = state.getMaterial();
            if (material == Material.LAVA || material == Material.FIRE) {
                passable = false;
            } else if (material == Material.WATER) {
                passable = this.npc.ais != null && this.npc.ais.canSwim;
            } else {
                AxisAlignedBB collision = state.getCollisionBoundingBox(this.world, position);
                passable = collision == Block.NULL_AABB || block.isPassable(this.world, position);
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
        IBlockState state = this.world.getBlockState(position);
        Block block = state.getBlock();
        if (NpcPathfindingConfig.isBlacklisted(block)
            || state.getMaterial() == Material.WATER
            || state.getMaterial() == Material.LAVA) {
            return false;
        }
        AxisAlignedBB collision = state.getCollisionBoundingBox(this.world, position);
        return collision != Block.NULL_AABB
            && collision.maxY >= 0.4D
            && collision.maxY <= 1.01D;
    }

    private boolean canOccupyClimb(BlockPos position) {
        return this.isBodyColumnPassable(position)
            && (this.isClimbable(position)
                || this.isClimbable(position.down())
                || this.isSolidSupport(position.down()));
    }

    private boolean isClimbable(BlockPos position) {
        if (position == null || !this.isLoaded(position)) {
            return false;
        }
        IBlockState state = this.world.getBlockState(position);
        Block block = state.getBlock();
        return block == Blocks.LADDER
            || block == Blocks.VINE
            || block.isLadder(state, this.world, position, this.npc);
    }

    private boolean isConfiguredBlacklisted(BlockPos position) {
        return position != null
            && this.isLoaded(position)
            && NpcPathfindingConfig.isBlacklisted(this.world.getBlockState(position).getBlock());
    }

    private boolean canUseVanillaPathfinder() {
        if (!NpcPathfindingConfig.preventChunkLoads) {
            return true;
        }
        int radius = (int) (this.getPathSearchRange() + 8.0F);
        return this.world.isAreaLoaded(this.currentFeetPos(), radius, false);
    }

    private boolean isLoaded(BlockPos position) {
        return position != null
            && position.getY() >= 0
            && position.getY() < 256
            && (!NpcPathfindingConfig.preventChunkLoads
                || this.world.isBlockLoaded(position, false));
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
