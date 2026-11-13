package top.npcpathfindinglegacy.pathfinding;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.MathHelper;
import net.minecraft.pathfinding.PathPoint;
import net.minecraft.util.math.Vec3d;
import noppes.npcs.entity.EntityNPCInterface;

/** Original controlled parkour and landing logic, independent of route computation. */
final class LegacyMovementController {
    private static final EnumFacing[] HORIZONTAL = {EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.EAST};
    private final EnhancedPathNavigator navigation;
    private final EntityNPCInterface npc;

    LegacyMovementController(EnhancedPathNavigator navigation, EntityNPCInterface npc) {
        this.navigation = navigation;
        this.npc = npc;
    }

    boolean busy() { return jumpTarget != null || landingSettleTarget != null; }

    boolean tick() {
        if (jumpCooldown > 0) jumpCooldown--;
        if (flatJumpCooldown > 0) flatJumpCooldown--;
        if (jumpTarget != null) { updateControlledJump(); return true; }
        return landingSettleTarget != null && settleLanding();
    }

    void reset() {
        clearJumpState();
        clearLandingSettle();
        jumpCooldown = 0;
        flatJumpCooldown = 0;
        releaseSprint();
    }

    private BlockPos jumpTakeoff;
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

    void applyEnhancedMovement() {
        if (this.navigation.getPath() == null || this.navigation.getPath().isFinished()) {
            return;
        }

        int index = this.navigation.getPath().getCurrentPathIndex();
        if (index >= this.navigation.getPath().getCurrentPathLength()) {
            return;
        }
        PathPoint point = this.navigation.getPath().getPathPointFromIndex(index);
        Vec3d target = this.navigation.nextPosition();
        int feetY = this.navigation.currentFeetPos().getY();
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
            && dy <= 1
            && horizontal <= 1.8D) {
            this.startClimbJump(point, horizontal, dy);
            return;
        }

        double moveSpeed = this.navigation.moveSpeed() <= 0.0D ? 1.0D : this.navigation.moveSpeed();
        if (this.navigation.chaseEntity() != null && horizontal > 0.2D) {
            moveSpeed *= this.walkSpeedMultiplier();
            this.npc.setSprinting(this.navigation.maySprint() && !this.npc.collidedHorizontally);
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
        if (!this.npc.isOnLadder() && !this.navigation.isClimbable(feet) && !this.navigation.isClimbable(node)) {
            return false;
        }

        double moveY = dy > 0 ? 0.22D : (dy < 0 ? -0.12D : 0.05D);
        double dx = target.x - this.npc.posX;
        double dz = target.z - this.npc.posZ;
        double length = Math.sqrt(dx * dx + dz * dz);
        double scale = length > 1.0E-4D ? Math.min(0.12D, length) / length : 0.0D;
        this.haltMoveHelper();
        this.setMotionX(dx * scale);
        this.setMotionY(moveY);
        this.setMotionZ(dz * scale);
        this.npc.velocityChanged = true;
        this.faceToward(target.x, target.z);
        return true;
    }

    private boolean isParkourTransition(PathPoint point, double horizontal, int dy) {
        if (this.navigation.nextAction() != PlannedRoute.Action.PARKOUR) return false;
        if (this.parkourMaxGap() <= 0 || horizontal < 0.1D || dy < -4 || dy > 1) {
            return false;
        }
        BlockPos landing = new BlockPos(point.x, point.y, point.z);
        if (!this.navigation.canStand(landing)) {
            return false;
        }

        return true;
    }

    private void startParkourJump(PathPoint point, double horizontal, int dy) {
        if (horizontal < 1.0E-4D) {
            return;
        }
        double verticalSpeed = JumpTrajectoryPlanner.parkourVelocity(this.jumpTendency(), dy);
        this.beginControlledJump(
            new BlockPos(point.x, point.y, point.z),
            verticalSpeed,
            JumpTrajectoryPlanner.MAX_PARKOUR_SPEED,
            true
        );
    }

    private void startClimbJump(PathPoint point, double horizontal, int dy) {
        if (horizontal < 0.35D) {
            this.npc.getJumpHelper().setJumping();
            return;
        }
        double verticalSpeed = this.jumpVelocity() + 0.045D;
        verticalSpeed = JumpTrajectoryPlanner.ensureReachableVerticalSpeed(verticalSpeed, dy);
        this.beginControlledJump(
            new BlockPos(point.x, point.y, point.z),
            verticalSpeed,
            0.34D,
            false
        );
    }

    private void handleFlatChaseJump(Vec3d target, int feetY) {
        if (this.navigation.combatMode()) return;
        int interval = this.chaseJumpInterval();
        if (this.navigation.chaseEntity() == null
            || interval <= 0
            || this.flatJumpCooldown > 0
            || this.jumpCooldown > 0
            || !this.npc.onGround
            || this.npc.collidedHorizontally
            || this.navigation.getPath() == null
            || this.navigation.getPath().isFinished()) {
            return;
        }
        if (this.npc.getDistanceSq(this.navigation.chaseEntity()) < 16.0D || !this.nextNodesAreFlat(feetY)) {
            return;
        }

        double dx = target.x - this.npc.posX;
        double dz = target.z - this.npc.posZ;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-4D) {
            return;
        }
        double takeoff = Math.max(0.30D, Math.sqrt(this.motionX() * this.motionX() + this.motionZ() * this.motionZ()));
        this.setMotionX(dx / length * takeoff);
        this.setMotionY(this.jumpVelocity() * 0.85D);
        this.setMotionZ(dz / length * takeoff);
        this.npc.velocityChanged = true;
        this.flatJumpCooldown = Math.max(12, interval);
        this.jumpCooldown = 4;
        this.npc.setSprinting(true);
        this.ownsSprint = true;
    }

    private boolean nextNodesAreFlat(int feetY) {
        int end = Math.min(
            this.navigation.getPath().getCurrentPathLength(),
            this.navigation.getPath().getCurrentPathIndex() + 6
        );
        for (int i = this.navigation.getPath().getCurrentPathIndex(); i < end; i++) {
            if (this.navigation.getPath().getPathPointFromIndex(i).y != feetY) {
                return false;
            }
        }
        return true;
    }

    private boolean centerOnNarrowTakeoff(PathPoint landing) {
        BlockPos feet = this.navigation.currentFeetPos();
        int adjacentSupports = 0;
        for (EnumFacing facing : HORIZONTAL) {
            if (this.navigation.canStand(feet.offset(facing))) {
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
        this.landingSettleTicks = 12;
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
            this.navigation.standY(landing)
        );
        double horizontalSpeed = JumpTrajectoryPlanner.horizontalSpeed(
            distance,
            flightTicks,
            maxHorizontalSpeed
        );

        this.clearLandingSettle();
        this.jumpTarget = landing;
        this.jumpTakeoff = this.navigation.currentFeetPos();
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
        this.setMotionY(Math.min(JumpTrajectoryPlanner.MAX_JUMP_VELOCITY, verticalSpeed));
        this.setMotionZ(this.lockedMotionZ);
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
                this.setMotionX(this.lockedMotionX);
                this.setMotionZ(this.lockedMotionZ);
                this.npc.velocityChanged = true;
                return;
            }
            this.finishControlledLanding(landing);
            return;
        }

        this.jumpAirTicks++;
        double currentY = this.npc.getEntityBoundingBox().minY;
        double targetY = this.navigation.standY(landing);
        double dx = landing.getX() + 0.5D - this.npc.posX;
        double dz = landing.getZ() + 0.5D - this.npc.posZ;
        double distance = Math.sqrt(dx * dx + dz * dz);
        boolean unrecoverableWallHit = this.npc.collidedHorizontally
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
        if (distance < 0.02D) {
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
        this.landingSettleTicks = wasParkour ? 12 : 8;
        this.jumpCooldown = 1;
        this.settleLanding();
    }

    private boolean isStandingNearLanding(BlockPos landing) {
        double dx = this.npc.posX - (landing.getX() + 0.5D);
        double dz = this.npc.posZ - (landing.getZ() + 0.5D);
        return this.npc.onGround
            && this.navigation.currentFeetPos().equals(landing)
            && Math.abs(dx) <= 0.72D
            && Math.abs(dz) <= 0.72D
            && Math.abs(this.npc.getEntityBoundingBox().minY - this.navigation.standY(landing)) <= 0.35D
            && this.navigation.canStand(landing);
    }

    private boolean settleLanding() {
        BlockPos landing = this.landingSettleTarget;
        if (landing == null) {
            return false;
        }
        if (!this.npc.onGround
            || Math.abs(this.npc.getEntityBoundingBox().minY - this.navigation.standY(landing)) > 0.35D) {
            this.clearLandingSettle();
            return false;
        }

        double dx = landing.getX() + 0.5D - this.npc.posX;
        double dz = landing.getZ() + 0.5D - this.npc.posZ;
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (this.landingSettleTicks-- <= 0 && distance > 0.2D) {
            this.clearLandingSettle();
            this.releaseSprint();
            this.navigation.recoverJump(null, null);
            return true;
        }
        if (distance <= 0.10D || this.landingSettleTicks <= 0) {
            this.haltMoveHelper();
            this.lockedMotionX = 0.0D;
            this.lockedMotionZ = 0.0D;
            this.setMotionX(0.0D);
            this.setMotionZ(0.0D);
            this.npc.velocityChanged = true;
            this.clearLandingSettle();
            return false;
        }

        double speed = Math.min(0.16D, distance * 0.6D);
        this.lockedMotionX = dx / distance * speed;
        this.lockedMotionZ = dz / distance * speed;
        this.haltMoveHelper();
        this.setMotionX(this.lockedMotionX);
        this.setMotionZ(this.lockedMotionZ);
        this.npc.velocityChanged = true;
        return true;
    }

    private void failControlledJump() {
        BlockPos takeoff = this.jumpTakeoff;
        BlockPos landing = this.jumpTarget;
        this.clearJumpState();
        this.clearLandingSettle();
        this.releaseSprint();
        this.navigation.recoverJump(takeoff, landing);
    }

    private void clearJumpState() {
        this.jumpTakeoff = null;
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
        if (this.navigation.getPath() == null) {
            return;
        }
        int index = this.navigation.getPath().getCurrentPathIndex();
        int end = Math.min(this.navigation.getPath().getCurrentPathLength(), index + 5);
        for (int i = Math.max(0, index - 1); i < end; i++) {
            PathPoint point = this.navigation.getPath().getPathPointFromIndex(i);
            if (point.x == landing.getX()
                && point.z == landing.getZ()
                && Math.abs(point.y - landing.getY()) <= 1) {
                if (i >= index) {
                    this.navigation.getPath().setCurrentPathIndex(i + 1);
                }
                return;
            }
        }
    }

    private void haltMoveHelper() {
        this.navigation.haltMovement();
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
        this.npc.setRotationYawHead(yaw);
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

    private double motionX() {
        return top.npcpathfindinglegacy.compat.LegacyMath.motion(npc).x;
    }

    private double motionY() {
        return top.npcpathfindinglegacy.compat.LegacyMath.motion(npc).y;
    }

    private double motionZ() {
        return top.npcpathfindinglegacy.compat.LegacyMath.motion(npc).z;
    }

    private void setMotionX(double x) {
        Vec3d m = top.npcpathfindinglegacy.compat.LegacyMath.motion(npc);
        top.npcpathfindinglegacy.compat.LegacyMath.motion(npc,x, m.y, m.z);
    }

    private void setMotionY(double y) {
        Vec3d m = top.npcpathfindinglegacy.compat.LegacyMath.motion(npc);
        top.npcpathfindinglegacy.compat.LegacyMath.motion(npc,m.x, y, m.z);
    }

    private void setMotionZ(double z) {
        Vec3d m = top.npcpathfindinglegacy.compat.LegacyMath.motion(npc);
        top.npcpathfindinglegacy.compat.LegacyMath.motion(npc,m.x, m.y, z);
    }

    public void applyPostAiMovementLock() {
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
        this.setMotionX(this.lockedMotionX);
        this.setMotionZ(this.lockedMotionZ);
        this.npc.velocityChanged = true;
        if (jumping) {
            this.npc.setSprinting(true);
            this.ownsSprint = true;
        }
    }

}
