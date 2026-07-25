package top.npcpathfinding.pathfinding;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import noppes.npcs.entity.EntityNPCInterface;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.PathCalculationResult;
import baritone.pathing.calc.AStarPathFinder;
import baritone.pathing.movement.CalculationContext;
import baritone.utils.pathing.Favoring;
import top.npcpathfinding.config.NpcPathfindingConfig;
import top.npcpathfinding.baritone.NpcBaritoneAdapter;
import top.npcpathfinding.debug.NpcPathDebugLog;
import top.npcpathfinding.pathfinding.IDataAIPathfinding;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

public class NpcBaritonePathNavigator extends GroundPathNavigation {
    private final Mob npc;
    private boolean sprinting = false;
    private final NpcBaritoneAdapter baritone;
    private Path baritonePath;
    private BlockPos baritoneTarget;
    private double baritoneSpeed = 1.0;
    private int stuckTicks;
    private int jumpCooldown;
    private int repathCooldown;
    private int failRepaths;
    private long lastMoveToGameTime = -1L;
    private boolean inParkourJump = false;
    private long noJumpUntil = -1L;
    private long lastPathComputeGameTime = -1L;
    private int parkourSprintTicks;
    private int flatChaseJumpCooldown;
    private Vec3 lastPos = Vec3.ZERO;
    /** Horizontal lock only (never re-apply jump Y — that caused 10+ block rocket jumps). */
    private double parkourLockX;
    private double parkourLockZ;
    private int parkourLockTicks;
    private int wallHitBackoff;
    private double backoffX;
    private double backoffZ;
    private double lookAtX;
    private double lookAtY;
    private double lookAtZ;
    private int lookAtTicks;
    private long lastPostAiGameTime = -1L;
    /** Fixed landing pad for the current hop (null = not mid-hop). */
    private BlockPos hopTarget;
    private BlockPos hopTakeoff;
    private int hopAirTicks;
    /** Climb onto adjacent pad (dy 1–2 only). Separate from pad-to-pad parkour. */
    private BlockPos climbTarget;
    private int climbAirTicks;
    private long lastFollowLogTime = -1L;
    private String lastFollowBranch = "";
    /** After bouncing on a pillar face, ban that XZ column so path won't re-target it (A2 loop). */
    private final Map<Long, Long> bannedPillarUntil = new HashMap<>();
    private long forceGroundPathUntil = -1L;
    /** Last chase entity (for intercept / tighter repath on flat). */
    private Entity chaseEntity;
    private long lastChaseEntityId = -1L;

    public NpcBaritonePathNavigator(Mob mob, Level level) {
        super(mob, level);
        this.npc = mob;
        this.baritone = new NpcBaritoneAdapter(mob);
        NpcPathDebugLog.sessionStart();
        NpcPathDebugLog.event(mob, "INIT", "NpcBaritonePathNavigator created");
    }

    @Override
    protected PathFinder createPathFinder(int maxVisitedNodes) {
        this.nodeEvaluator = new BaritoneNodeEvaluator();
        this.nodeEvaluator.setCanPassDoors(true);
        return new PathFinder(this.nodeEvaluator, maxVisitedNodes);
    }

    @Override
    public Path createPath(BlockPos target, int accuracy) {
        BlockPos resolved = this.resolveTarget(target);
        Path path = this.createBaritonePath(resolved, accuracy);
        return path != null ? path : this.createStationaryPath(resolved);
    }

    @Override
    public Path createPath(Entity entity, int accuracy) {
        BlockPos resolved = this.resolveTarget(entity.blockPosition());
        Path path = this.createBaritonePath(resolved, accuracy);
        return path != null ? path : this.createStationaryPath(resolved);
    }

    @Override
    public boolean moveTo(double x, double y, double z, double speed) {
        this.chaseEntity = null;
        return this.startBaritonePath(this.resolveTarget(BlockPos.containing(x, y, z)), speed, 1, false);
    }

    @Override
    public boolean moveTo(Entity entity, double speed) {
        this.chaseEntity = entity;
        this.lastChaseEntityId = entity.getId();
        // Lead slightly in target velocity for intercept (flat chase strength).
        BlockPos feet = entity.blockPosition();
        Vec3 v = entity.getDeltaMovement();
        if (v.horizontalDistanceSqr() > 0.001) {
            int lx = Mth.floor(entity.getX() + v.x * 4.0);
            int lz = Mth.floor(entity.getZ() + v.z * 4.0);
            feet = new BlockPos(lx, feet.getY(), lz);
        }
        return this.startBaritonePath(this.resolveTarget(feet), speed, 1, true);
    }

    @Override
    public boolean moveTo(Path path, double speed) {
        if (path == null || path.getNodeCount() == 0) {
            this.stop();
            return false;
        }
        this.chaseEntity = null;
        return this.startBaritonePath(this.resolveTarget(path.getTarget()), speed, 1, false);
    }

    private boolean startBaritonePath(BlockPos target, double speed, int accuracy, boolean chasing) {
        // Flat chase: repath sooner when target moves (log: 20t freeze → roundabout lag).
        // Static goals (home): keep longer throttle.
        double sameDist = chasing ? 2.25 : 9.0; // chase: ~1.5 blocks; home: 3
        int sameAge = chasing ? 8 : 20;
        boolean sameGoal = this.baritoneTarget != null && this.baritoneTarget.distSqr(target) <= sameDist;
        if (sameGoal
                && this.lastPathComputeGameTime >= 0L
                && this.level.getGameTime() - this.lastPathComputeGameTime < sameAge
                && this.baritonePath != null
                && !this.baritonePath.isDone()) {
            this.baritoneTarget = target;
            this.baritoneSpeed = speed;
            this.lastMoveToGameTime = this.level.getGameTime();
            return true;
        }
        // New goal (e.g. return home after aggro lost) always replaces combat path.
        this.baritoneTarget = target;
        this.baritoneSpeed = speed;
        this.failRepaths = 0;
        this.repathCooldown = 0;
        this.stuckTicks = 0;
        this.parkourSprintTicks = 0;
        this.inParkourJump = false;
        this.parkourLockTicks = 0;
        this.wallHitBackoff = 0;
        this.backoffX = 0;
        this.backoffZ = 0;
        this.lookAtTicks = 0;
        this.hopTarget = null;
        this.hopTakeoff = null;
        this.hopAirTicks = 0;
        this.climbTarget = null;
        this.climbAirTicks = 0;
        // Keep bannedPillarUntil across repath — only cleared on stop().
        this.noJumpUntil = -1L;
        this.lastPos = this.mob.position();
        this.lastMoveToGameTime = this.level.getGameTime();
        this.baritonePath = this.createBaritonePath(target, accuracy);
        this.path = this.baritonePath;
        this.lastPathComputeGameTime = this.level.getGameTime();
        // Log freeze: MOVE_TO path=null spam while bans still block A1/A2 step pads.
        if (this.baritonePath == null && target.getY() > Mth.floor(this.mob.getY())) {
            this.bannedPillarUntil.clear();
            this.forceGroundPathUntil = -1L;
            this.baritonePath = this.createBaritonePath(target, accuracy);
            this.path = this.baritonePath;
        }
        NpcPathDebugLog.event(this.mob, "MOVE_TO", "target=" + NpcPathDebugLog.pos(target)
                + " speed=" + String.format("%.2f", speed)
                + " elev=" + (target.getY() > Mth.floor(this.mob.getY()))
                + " chase=" + chasing
                + " parkourMax=" + this.parkourMaxDist());
        NpcPathDebugLog.pathSnapshot("PATH_NEW", this.baritonePath, target);
        return true;
    }

    /**
     * After CNPC goals + moveControl: keep horizontal hop / detour velocity + face move dir.
     * Y is never re-locked (gravity must decay normally).
     */
    /**
     * Only re-apply locked XZ after moveControl. NO head snap (log: nodding / Thomas spin).
     */
    public void applyPostAiParkourLock() {
        long gt = this.level.getGameTime();
        boolean first = this.lastPostAiGameTime != gt;
        if (first) {
            this.lastPostAiGameTime = gt;
        }
        if (this.wallHitBackoff > 0) {
            if (first) this.wallHitBackoff--;
            this.haltMoveControl();
            this.mob.setDeltaMovement(this.backoffX, this.mob.getDeltaMovement().y, this.backoffZ);
            this.mob.hasImpulse = true;
            return;
        }
        if (this.parkourLockTicks <= 0) return;
        if (first) this.parkourLockTicks--;
        this.haltMoveControl();
        this.mob.setDeltaMovement(this.parkourLockX, this.mob.getDeltaMovement().y, this.parkourLockZ);
        this.mob.hasImpulse = true;
        this.mob.setSprinting(true);
        this.sprinting = true;
    }

    private void lockParkourHorizontal(double x, double z, int ticks) {
        this.parkourLockX = x;
        this.parkourLockZ = z;
        this.parkourLockTicks = Math.max(this.parkourLockTicks, ticks);
    }

    /** Body yaw only, max 40°/tick, pitch forced 0. No LookControl (causes nod). */
    private void faceToward(double x, double y, double z) {
        double dx = x - this.mob.getX();
        double dz = z - this.mob.getZ();
        if (dx * dx + dz * dz < 1.0E-4) return;
        float desired = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        float cur = this.mob.getYRot();
        float delta = Mth.wrapDegrees(desired - cur);
        float step = Mth.clamp(delta, -40.0F, 40.0F);
        float yaw = cur + step;
        this.mob.setYRot(yaw);
        this.mob.yBodyRot = yaw;
        this.mob.setXRot(0.0F);
    }

    /**
     * Goal block Y strictly above feet block Y.
     * Log bug: goalY+0.01 > mobY made elev=true on flat (both -60) → climb spam.
     */
    private boolean needsElevation() {
        if (this.baritoneTarget == null) return false;
        if (this.climbTarget != null || this.hopTarget != null) return true;
        return this.baritoneTarget.getY() > Mth.floor(this.mob.getY());
    }

    /**
     * Standing ON TOP of a pad/pillar (not ground hugging pillar face).
     * Log: ground -60 next to A1 was pad=true → gap_hold thrash.
     */
    private boolean isStandingOnPad() {
        if (!this.mob.onGround()) return false;
        int feetY = Mth.floor(this.mob.getY());
        BlockPos feet = BlockPos.containing(this.mob.getX(), feetY, this.mob.getZ());
        if (!this.canStand(feet) && !this.canStandOnBlock(feet.below())) return false;
        BlockPos belowPos = feet.below();
        BlockState below = this.level.getBlockState(belowPos);
        if (below.isAir() || below.getCollisionShape(this.level, belowPos).isEmpty()) return false;
        // Wall-hugging at base of higher standable column → ground, not pad top.
        if (this.mob.horizontalCollision) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos base = feet.relative(d);
                for (int up = 1; up <= 4; up++) {
                    if (this.canStand(new BlockPos(base.getX(), feetY + up, base.getZ()))) {
                        return false;
                    }
                }
            }
        }
        // Elevated: ≥2 sides not standable at same Y (1-wide pad has 3–4 void sides).
        int voidSides = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (!this.canStand(feet.relative(d))) voidSides++;
        }
        return voidSides >= 2;
    }

    @Override
    public void tick() {
        if (this.jumpCooldown > 0) {
            this.jumpCooldown--;
        }
        if (this.repathCooldown > 0) {
            this.repathCooldown--;
        }
        if (this.flatChaseJumpCooldown > 0) {
            this.flatChaseJumpCooldown--;
        }

        if (this.baritoneTarget == null) {
            return;
        }

        // Arrived: XZ close AND height close (log: stopped under elevated goal with dy=1).
        if (this.hasArrivedAtGoal()) {
            NpcPathDebugLog.event(this.mob, "STOP", "arrived goal=" + NpcPathDebugLog.pos(this.baritoneTarget));
            this.stop();
            return;
        }

        // CNPC must keep calling moveTo. After home return, AI stops calling → release.
        boolean aiStillDriving = this.lastMoveToGameTime >= 0L
                && this.level.getGameTime() - this.lastMoveToGameTime < 20L;
        if (!aiStillDriving) {
            NpcPathDebugLog.event(this.mob, "STOP", "no moveTo for 20t last=" + this.lastMoveToGameTime
                    + " now=" + this.level.getGameTime());
            this.stop();
            return;
        }

        Path active = this.baritonePath != null ? this.baritonePath : this.path;
        if (active == null || active.isDone()) {
            boolean elevate = this.needsElevation();
            boolean notThere = !this.hasArrivedAtGoal();
            if (this.repathCooldown > 0) {
                // Log freeze: repathCooldown + path=null → idle stare. Keep walking toward goal.
                if (active == null && notThere && this.baritoneTarget != null) {
                    double gx = this.baritoneTarget.getX() + 0.5;
                    double gz = this.baritoneTarget.getZ() + 0.5;
                    this.mob.getMoveControl().setWantedPosition(gx, this.mob.getY(), gz,
                            Math.max(1.0, this.baritoneSpeed));
                }
                return;
            }
            boolean aiDriving = this.lastMoveToGameTime >= 0L
                    && this.level.getGameTime() - this.lastMoveToGameTime < 40L;
            // Cap repath rate. Log: stand still after path 7→8 never reaches 9,-59.
            if (notThere && aiDriving && this.failRepaths < 12) {
                this.baritonePath = this.createBaritonePath(this.baritoneTarget, 1);
                this.path = this.baritonePath;
                this.failRepaths++;
                this.lastPathComputeGameTime = this.level.getGameTime();
                if (this.baritonePath == null) {
                    // Log freeze: path=null for 120t ban window. Clear bans and walk.
                    this.recoverFromNullPath();
                    this.repathCooldown = 8;
                } else if (!this.baritonePath.canReach() && elevate) {
                    this.repathCooldown = 8;
                    NpcPathDebugLog.pathSnapshot("PATH_REPATH_PARTIAL", this.baritonePath, this.baritoneTarget);
                } else {
                    this.repathCooldown = 10;
                    NpcPathDebugLog.pathSnapshot("PATH_REPATH", this.baritonePath, this.baritoneTarget);
                }
                return;
            }
            // Only stop if AI stopped driving or we truly arrived.
            if (!aiDriving || this.hasArrivedAtGoal()) {
                this.stop();
            } else {
                // Still driven but repath budget used: clear bans and keep trying (never freeze).
                this.recoverFromNullPath();
                this.failRepaths = 0;
                this.repathCooldown = 10;
            }
            return;
        }

        this.followThePath();
    }

    private static long pillarKey(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    private boolean isPillarBanned(int x, int z) {
        Long until = this.bannedPillarUntil.get(pillarKey(x, z));
        return until != null && this.level.getGameTime() < until;
    }

    private void banPillarColumn(int x, int z, int ticks) {
        // Log freeze 14:11: ban goal-column 17,* AND step-pad 9/*12 → path=null → stand still.
        if (this.isProtectedPillar(x, z)) {
            return;
        }
        long key = pillarKey(x, z);
        long until = this.level.getGameTime() + ticks;
        Long old = this.bannedPillarUntil.get(key);
        if (old == null || until > old) {
            this.bannedPillarUntil.put(key, until);
            NpcPathDebugLog.event(this.mob, "BAN", "pillar " + x + ",*," + z + " for " + ticks + "t");
        }
        if (this.bannedPillarUntil.size() > 12) {
            long now = this.level.getGameTime();
            this.bannedPillarUntil.entrySet().removeIf(e -> e.getValue() <= now);
        }
    }

    /**
     * Never ban: combat goal column (any Z offset on same X), path elevated next, or pads
     * that are the only climb stepping stones toward an elevated goal.
     */
    private boolean isProtectedPillar(int x, int z) {
        if (this.baritoneTarget != null) {
            int gx = this.baritoneTarget.getX();
            int gz = this.baritoneTarget.getZ();
            // Same column as goal, or same X and close Z (A3 pad top often z differs by 1–2).
            if (x == gx && Math.abs(z - gz) <= 2) return true;
            if (z == gz && Math.abs(x - gx) <= 2) return true;
        }
        Path path = this.baritonePath != null ? this.baritonePath : this.path;
        if (path != null && !path.isDone()) {
            int idx = path.getNextNodeIndex();
            for (int i = idx; i < path.getNodeCount() && i < idx + 4; i++) {
                Node n = path.getNode(i);
                if (n.y > Mth.floor(this.mob.getY()) && n.x == x && n.z == z) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Ban only the solid column we touch — never protected pads. Short ban (log 120t froze 6s). */
    private void banCollidingPillar(int ticks) {
        int fx = Mth.floor(this.mob.getX());
        int fy = Mth.floor(this.mob.getY());
        int fz = Mth.floor(this.mob.getZ());
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos p = new BlockPos(fx + d.getStepX(), fy, fz + d.getStepZ());
            BlockState st = this.level.getBlockState(p);
            if (st.isAir() || st.getCollisionShape(this.level, p).isEmpty()) continue;
            BlockPos up = p.above();
            if (!this.level.getBlockState(up).getCollisionShape(this.level, up).isEmpty()) {
                this.banPillarColumn(p.getX(), p.getZ(), ticks);
            }
        }
    }

    /**
     * Must stand on ground near goal center. Never mid-air "arrived" (log: STOP at y=-58.8 goal=-59).
     */
    private boolean hasArrivedAtGoal() {
        if (this.baritoneTarget == null) return false;
        if (!this.mob.onGround()) return false;
        double gx = this.baritoneTarget.getX() + 0.5;
        double gz = this.baritoneTarget.getZ() + 0.5;
        double dx = this.mob.getX() - gx;
        double dz = this.mob.getZ() - gz;
        if (dx * dx + dz * dz > 0.56) return false;
        int feetY = Mth.floor(this.mob.getY());
        int goalY = this.baritoneTarget.getY();
        if (goalY > feetY) return false;
        return Math.abs(this.mob.getY() - goalY) < 0.85;
    }

    @Override
    public boolean isDone() {
        if (this.baritoneTarget == null) return true;
        if (this.hasArrivedAtGoal()) return true;
        // No recent moveTo → done (home AI stopped).
        if (this.lastMoveToGameTime < 0L
                || this.level.getGameTime() - this.lastMoveToGameTime >= 20L) {
            return true;
        }
        Path active = this.baritonePath != null ? this.baritonePath : this.path;
        if (active != null && !active.isDone()) return false;
        // Mid recovery / repath wait: still navigating (do not hand control back mid-climb thrash).
        if (this.needsElevation() && this.mob.blockPosition().distSqr(this.baritoneTarget) > 9.0
                && (this.repathCooldown > 0 || (this.failRepaths > 0 && this.failRepaths < 6))) {
            return false;
        }
        return true;
    }

    @Override
    public Path getPath() {
        return this.baritonePath;
    }

    @Override
    public void stop() {
        super.stop();
        this.baritonePath = null;
        this.path = null;
        this.baritoneTarget = null;
        this.failRepaths = 0;
        this.repathCooldown = 0;
        this.stuckTicks = 0;
        this.parkourSprintTicks = 0;
        this.flatChaseJumpCooldown = 0;
        this.parkourLockTicks = 0;
        this.wallHitBackoff = 0;
        this.backoffX = 0;
        this.backoffZ = 0;
        this.lookAtTicks = 0;
        this.hopTarget = null;
        this.hopTakeoff = null;
        this.hopAirTicks = 0;
        this.climbTarget = null;
        this.climbAirTicks = 0;
        this.bannedPillarUntil.clear();
        this.forceGroundPathUntil = -1L;
        this.lastMoveToGameTime = -1L;
        this.noJumpUntil = -1L;
        this.inParkourJump = false;
        this.chaseEntity = null;
        this.lastChaseEntityId = -1L;
        this.mob.setSprinting(false);
        this.sprinting = false;
        this.haltMoveControl();
        if (this.mob.onGround()) {
            this.mob.setDeltaMovement(0.0, this.mob.getDeltaMovement().y, 0.0);
        }
    }

    @Override
    protected void followThePath() {
        Path path = this.baritonePath != null ? this.baritonePath : this.path;
        if (path == null) return;

        // Log root cause of "原地抽搐": path stuck at pIdx=0 next=start forever.
        // getTargetPosition raised Y by +0.5 when next node higher → elevatedNode true
        // → isAtTargetY never true → never advance → walk toward self.
        this.advancePathNodes(path);
        if (path.isDone()) return;

        Vec3 vec3 = this.getTempMobPos();
        Vec3 target = this.getTargetPosition();
        this.handleLadder(target);

        if (path.isDone()) return;

        if (this.isInLiquid()) {
            this.mob.getMoveControl().setWantedPosition(target.x, target.y, target.z, 0.5F);
            this.applyBaritoneInput(target, 0.7);
            this.sprinting = false;
            return;
        }

        this.doStuckRecovery(path, vec3);
        path = this.baritonePath != null ? this.baritonePath : this.path;
        if (path == null) return;

        double speed = Math.max(0.85, Math.min(1.2, this.baritoneSpeed));
        boolean shouldSprint = this.shouldSprint(path, vec3, target);
        boolean flatChase = this.isFlatGroundChase(path);

        if (shouldSprint || flatChase) {
            speed *= this.walkSpeedMultiplier();
            this.sprinting = true;
            this.mob.setSprinting(true);
        } else if (!this.inParkourJump) {
            this.sprinting = false;
            this.mob.setSprinting(false);
        }

        boolean elev = this.needsElevation();
        boolean onPad = this.isStandingOnPad();

        // Mid-climb / mid-hop ALWAYS first (before elev clear wipes state).
        if (this.climbTarget != null && this.handleClimbAir()) {
            this.logFollow("climb_air", true, onPad, false, path);
            return;
        }
        if (this.hopTarget != null && this.handleParkour(path, vec3, target)) {
            this.logFollow("parkour_air", true, onPad, false, path);
            return;
        }

        // Same-Y / home: clear leftovers only when fully grounded and not climbing.
        if (!elev && this.mob.onGround() && this.climbTarget == null && this.hopTarget == null) {
            this.inParkourJump = false;
            this.parkourLockTicks = 0;
            this.parkourSprintTicks = 0;
        }

        // 1) Climb adjacent pad dy 1–2 only.
        if (elev && this.handleClimbOntoPad(path, vec3, target)) {
            this.logFollow("climb", elev, onPad, false, path);
            return;
        }
        path = this.baritonePath != null ? this.baritonePath : this.path;
        if (path == null) return;

        // 2) Pad hop only when ON a pad top (not ground, not mid-climb).
        if (elev && this.climbTarget == null && (onPad || this.hopTarget != null)
                && this.handleParkour(path, vec3, target)) {
            this.logFollow("parkour", elev, onPad, false, path);
            return;
        }
        path = this.baritonePath != null ? this.baritonePath : this.path;
        if (path == null) return;

        // 3) Stuck on pillar FACE: try climb first (log: wall_out loop at A2 while climb was rejected).
        if (elev && this.climbTarget == null && this.hopTarget == null
                && this.mob.horizontalCollision
                && this.isStuckAgainstElevatedPad(path)) {
            // Prefer climb onto path pad over bounce thrash.
            if (this.mob.onGround() && this.jumpsAllowed() && this.handleClimbOntoPad(path, vec3, target)) {
                this.logFollow("climb_from_wall", elev, onPad, true, path);
                return;
            }
            this.banCollidingPillar(30);
            this.forceGroundPathUntil = this.level.getGameTime() + 15L;
            this.logFollow("wall_out", elev, onPad, true, path);
            this.walkOutFromUnderPlatform(target, speed);
            // Advance past elevated next if banned so we don't re-walk into same face.
            if (path != null && !path.isDone()) {
                Node nn = path.getNode(path.getNextNodeIndex());
                if (nn.y > Mth.floor(this.mob.getY()) && this.isPillarBanned(nn.x, nn.z)) {
                    path.advance();
                }
            }
            this.repathCooldown = 0;
            this.failRepaths = Math.max(0, this.failRepaths - 1);
            this.baritonePath = this.createBaritonePath(this.baritoneTarget, 1);
            this.path = this.baritonePath;
            // Log freeze: PATH_AFTER_BAN path=null then MOVE_TO spam stand still for seconds.
            if (this.baritonePath == null) {
                this.recoverFromNullPath();
            } else {
                this.repathCooldown = 6;
                NpcPathDebugLog.pathSnapshot("PATH_AFTER_BAN", this.baritonePath, this.baritoneTarget);
            }
            return;
        }

        // Path next is banned elevated pad — skip node, keep moving (don't null-path freeze).
        if (elev && path != null && !path.isDone()) {
            Node nn = path.getNode(path.getNextNodeIndex());
            if (nn.y > Mth.floor(this.mob.getY()) && this.isPillarBanned(nn.x, nn.z)) {
                path.advance();
                this.logFollow("skip_banned", elev, onPad, false, path);
                if (path.isDone()) {
                    this.baritonePath = this.createBaritonePath(this.baritoneTarget, 1);
                    this.path = this.baritonePath;
                }
                return;
            }
        }

        boolean trap = this.isTrulyTrappedUnderSolid(path, vec3, target);
        if (trap) {
            this.banCollidingPillar(30);
            this.forceGroundPathUntil = this.level.getGameTime() + 15L;
            this.logFollow("trap_walkout", elev, onPad, true, path);
            this.walkOutFromUnderPlatform(target, speed);
            this.repathCooldown = 0;
            this.baritonePath = this.createBaritonePath(this.baritoneTarget, 1);
            this.path = this.baritonePath;
            if (this.baritonePath == null) {
                this.recoverFromNullPath();
            } else {
                this.repathCooldown = 6;
            }
            return;
        }

        // 4) On pad with gap: hop; if fail, walk (no freeze).
        if (elev && onPad && this.climbTarget == null && this.isGapHopTarget(path, vec3)) {
            if (this.handleParkour(path, vec3, target)) {
                this.logFollow("gap_hop", elev, onPad, false, path);
                return;
            }
            this.logFollow("gap_walk", elev, onPad, false, path);
        }

        if (!this.inParkourJump || this.mob.onGround()) {
            this.mob.getMoveControl().setWantedPosition(target.x, target.y, target.z, speed);
        }
        this.applyBaritoneInput(target, speed);
        this.faceToward(target.x, this.mob.getY() + this.mob.getEyeHeight(), target.z);

        if (elev) {
            this.handleStepOrJump(path, target);
        } else {
            // Flat chase run-jump (was removed — user wants it back for 平地索敌).
            this.handleFlatChaseRunJump(path, vec3, target);
        }
        this.logFollow("walk", elev, onPad, false, path);
    }

    /**
     * Path next is elevated pad and we are pressed against a solid column (fall under A2).
     */
    private boolean isStuckAgainstElevatedPad(Path path) {
        if (path == null || path.isDone()) return false;
        int feetY = Mth.floor(this.mob.getY());
        int idx = path.getNextNodeIndex();
        if (idx >= path.getNodeCount()) return false;
        Node n = path.getNode(idx);
        if (n.y <= feetY + 0) {
            // Look a bit ahead for high pad.
            boolean high = false;
            for (int i = idx; i < path.getNodeCount() && i < idx + 4; i++) {
                if (path.getNode(i).y > feetY + 1) {
                    high = true;
                    break;
                }
            }
            if (!high && this.baritoneTarget != null && this.baritoneTarget.getY() > feetY + 1) {
                high = true;
            }
            if (!high) return false;
        }
        // Solid block in facing/collision direction.
        int fx = Mth.floor(this.mob.getX());
        int fy = feetY;
        int fz = Mth.floor(this.mob.getZ());
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos p = new BlockPos(fx + d.getStepX(), fy, fz + d.getStepZ());
            BlockState st = this.level.getBlockState(p);
            if (st.isAir() || st.getCollisionShape(this.level, p).isEmpty()) continue;
            // Column continues up (pillar).
            BlockPos up = p.above();
            if (!this.level.getBlockState(up).getCollisionShape(this.level, up).isEmpty()) {
                return true;
            }
        }
        return this.mob.horizontalCollision;
    }

    /**
     * Airborne climb: log showed y reaches pad height but z stuck on face (collH) then fall.
     * Once high enough, hard-pull XZ onto pad center (wall no longer blocks feet).
     */
    private boolean handleClimbAir() {
        if (this.climbTarget == null) return false;
        this.haltMoveControl();
        double tx = this.climbTarget.getX() + 0.5;
        double tz = this.climbTarget.getZ() + 0.5;
        double ty = this.climbTarget.getY();
        double hx = tx - this.mob.getX();
        double hz = tz - this.mob.getZ();
        this.faceToward(tx, this.mob.getY() + this.mob.getEyeHeight(), tz);

        if (!this.mob.onGround()) {
            this.climbAirTicks++;
            double horiz = Math.sqrt(hx * hx + hz * hz);
            double my = this.mob.getY();
            // Log thrash: oscilated 9.38↔9.62 past pad center. Once over pad, SNAP and stop fighting.
            boolean overPadColumn = Math.abs(hx) < 0.55 && Math.abs(hz) < 0.55;
            boolean aboveLedge = my >= ty - 0.2;
            if (overPadColumn && aboveLedge) {
                this.mob.setPos(tx, Math.max(my, ty), tz);
                this.mob.setDeltaMovement(0.0, Math.min(0.0, this.mob.getDeltaMovement().y), 0.0);
                this.mob.hasImpulse = true;
                NpcPathDebugLog.event(this.mob, "CLIMB", "snap-land " + NpcPathDebugLog.pos(this.climbTarget));
                this.clearClimb();
                this.jumpCooldown = 2;
                this.advancePastCurrentPad(this.baritonePath != null ? this.baritonePath : this.path);
                return true;
            }
            // Log: stuck collH at y≈-58 x=11.70 never enters overPad (hx>0.55). Hard teleport onto pad.
            if (aboveLedge && this.mob.horizontalCollision && horiz < 1.4) {
                this.mob.setPos(tx, Math.max(my, ty + 0.01), tz);
                this.mob.setDeltaMovement(0.0, 0.0, 0.0);
                this.mob.hasImpulse = true;
                NpcPathDebugLog.event(this.mob, "CLIMB", "wall-snap " + NpcPathDebugLog.pos(this.climbTarget));
                this.clearClimb();
                this.jumpCooldown = 2;
                this.advancePastCurrentPad(this.baritonePath != null ? this.baritonePath : this.path);
                return true;
            }
            if (aboveLedge && horiz > 0.08) {
                Vec3 dir = new Vec3(hx, 0, hz).normalize();
                double pull = Math.min(0.42, 0.28 + horiz * 0.15);
                double vy = Math.max(this.mob.getDeltaMovement().y, 0.0);
                this.mob.setDeltaMovement(dir.x * pull, vy, dir.z * pull);
                this.mob.hasImpulse = true;
                this.lockParkourHorizontal(dir.x * pull, dir.z * pull, 2);
            } else if (horiz > 0.08) {
                Vec3 dir = new Vec3(hx, 0, hz).normalize();
                double push = this.mob.horizontalCollision ? 0.16 : 0.24;
                this.mob.setDeltaMovement(dir.x * push, this.mob.getDeltaMovement().y, dir.z * push);
                this.mob.hasImpulse = true;
                this.lockParkourHorizontal(dir.x * push, dir.z * push, 2);
            }
            if (this.climbAirTicks > 16) {
                NpcPathDebugLog.event(this.mob, "CLIMB", "air timeout tgt=" + NpcPathDebugLog.pos(this.climbTarget));
                this.clearClimb();
                this.stepBackFromWall();
            }
            return true;
        }

        if (this.climbAirTicks < 1) {
            return true;
        }

        boolean onPad = hx * hx + hz * hz < 0.85 * 0.85
                && Math.abs(this.mob.getY() - ty) < 0.6;
        if (onPad) {
            this.mob.setPos(tx, Math.max(this.mob.getY(), ty), tz);
            this.mob.setDeltaMovement(0.0, 0.0, 0.0);
        }
        NpcPathDebugLog.event(this.mob, "CLIMB", (onPad ? "landed " : "missed ")
                + NpcPathDebugLog.pos(this.climbTarget) + " air=" + this.climbAirTicks
                + " pos=" + String.format("%.2f,%.2f,%.2f", this.mob.getX(), this.mob.getY(), this.mob.getZ()));
        this.clearClimb();
        this.jumpCooldown = Math.max(this.jumpCooldown, onPad ? 1 : 6);
        if (!onPad) {
            this.stepBackFromWall();
        } else {
            this.advancePastCurrentPad(this.baritonePath != null ? this.baritonePath : this.path);
        }
        return true;
    }

    private void clearClimb() {
        this.climbTarget = null;
        this.climbAirTicks = 0;
        this.inParkourJump = false;
        this.parkourLockTicks = 0;
    }

    /** After failed face-climb, back off so next approach is not stuck collH. */
    private void stepBackFromWall() {
        Vec3 look = this.mob.getLookAngle().multiply(1, 0, 1);
        if (look.lengthSqr() < 1.0E-4) look = new Vec3(1, 0, 0);
        look = look.normalize();
        this.mob.setDeltaMovement(-look.x * 0.22, 0.0, -look.z * 0.22);
        this.mob.hasImpulse = true;
        this.haltMoveControl();
    }

    private void logFollow(String branch, boolean elev, boolean onPad, boolean trap, Path path) {
        if (!NpcPathDebugLog.ENABLED) return;
        long t = this.level.getGameTime();
        // Branch change always; same branch every 20t (was every tick → TPS).
        if (branch.equals(this.lastFollowBranch) && t - this.lastFollowLogTime < 20L) {
            return;
        }
        this.lastFollowLogTime = t;
        this.lastFollowBranch = branch;
        NpcPathDebugLog.followTick(this.mob, t, this.baritoneTarget, path, branch, elev, onPad, trap,
                this.jumpCooldown, this.inParkourJump, this.hopTarget, this.hopAirTicks,
                this.mob.getDeltaMovement());
    }

    /**
     * True only when stuck under solid with NO adjacent climb available.
     * Near high goal alone is NOT trapped (that caused endless walkOut under A2).
     */
    private boolean isTrulyTrappedUnderSolid(Path path, Vec3 current, Vec3 moveTarget) {
        if (this.baritoneTarget == null || !this.needsElevation()) return false;
        // Can climb → never "trapped".
        if (this.findAdjacentPadTop(current, moveTarget) != null) return false;
        if (path != null) {
            int feetY = Mth.floor(this.mob.getY());
            int idx = path.getNextNodeIndex();
            for (int i = idx; i < path.getNodeCount() && i < idx + 4; i++) {
                Node n = path.getNode(i);
                int cheb = Math.max(Math.abs(n.x - Mth.floor(this.mob.getX())),
                        Math.abs(n.z - Mth.floor(this.mob.getZ())));
                if (n.y > feetY && n.y <= feetY + 4 && cheb == 1) return false;
            }
        }
        BlockPos head = BlockPos.containing(this.mob.getX(), this.mob.getY() + 1.8, this.mob.getZ());
        boolean ceiling = !this.level.getBlockState(head).getCollisionShape(this.level, head).isEmpty();
        // Must be actually jammed: ceiling or wall collision while close under high goal.
        if (!ceiling && !this.mob.horizontalCollision) return false;
        double gx = this.baritoneTarget.getX() + 0.5 - this.mob.getX();
        double gz = this.baritoneTarget.getZ() + 0.5 - this.mob.getZ();
        return gx * gx + gz * gz < 2.5 * 2.5;
    }

    private void walkOutFromUnderPlatform(Vec3 pathTarget, double speed) {
        this.haltMoveControl();
        this.inParkourJump = false;
        this.parkourLockTicks = 0;
        this.hopTarget = null;
        this.hopTakeoff = null;
        this.hopAirTicks = 0;
        this.climbTarget = null;
        this.climbAirTicks = 0;
        // Primary: leave the wall face (log bounce kept pushing -0.08 into wall).
        Vec3 away = this.wallEscapeDirection();
        // Secondary: prefer leaving elevated path node column.
        Path p = this.baritonePath != null ? this.baritonePath : this.path;
        if (p != null && !p.isDone()) {
            Node n = p.getNode(p.getNextNodeIndex());
            if (n.y > Mth.floor(this.mob.getY())) {
                double ax = this.mob.getX() - (n.x + 0.5);
                double az = this.mob.getZ() - (n.z + 0.5);
                if (ax * ax + az * az > 1.0E-4) {
                    away = new Vec3(ax, 0, az).normalize().scale(0.7).add(away.scale(0.3)).normalize();
                }
            }
        }
        double sp = 0.32 * Math.max(0.9, speed);
        // Kill upward bounce against pillar — stay on ground leave.
        this.mob.setDeltaMovement(away.x * sp, Math.min(0.0, this.mob.getDeltaMovement().y), away.z * sp);
        this.mob.hasImpulse = true;
        this.faceToward(this.mob.getX() + away.x * 2, this.mob.getY() + this.mob.getEyeHeight(), this.mob.getZ() + away.z * 2);
        this.sprinting = true;
        this.mob.setSprinting(true);
        // Short only — long noJump locked climb forever in wall thrash loop.
        this.noJumpUntil = Math.max(this.noJumpUntil, this.level.getGameTime() + 4L);
        this.jumpCooldown = Math.max(this.jumpCooldown, 3);
    }

    /** Kill MoveControl so it cannot walk us off a pad after navigation sets deltaMovement. */
    private void haltMoveControl() {
        this.mob.getMoveControl().setWantedPosition(this.mob.getX(), this.mob.getY(), this.mob.getZ(), 0.0);
    }

    /**
     * Climb adjacent pad only if it moves us toward baritoneTarget.
     * Log: home goal=9,-59 but JUMP stand=12,-58 (A2) — climbing wrong pillar.
     */
    private boolean handleClimbOntoPad(Path path, Vec3 current, Vec3 moveTarget) {
        if (!this.needsElevation() || !this.jumpsAllowed() || !this.mob.onGround()) return false;
        if (this.hopTarget != null || this.climbTarget != null) return false;

        BlockPos stand = null;
        int feetY = Mth.floor(this.mob.getY());
        int feetX = Mth.floor(this.mob.getX());
        int feetZ = Mth.floor(this.mob.getZ());

        // 1) Path next node: adjacent dy1–2 AND toward goal.
        int idx = path.getNextNodeIndex();
        if (idx < path.getNodeCount()) {
            Node n = path.getNode(idx);
            BlockPos cand = new BlockPos(n.x, n.y, n.z);
            int dy = n.y - feetY;
            int cheb = Math.max(Math.abs(n.x - feetX), Math.abs(n.z - feetZ));
            double hx = n.x + 0.5 - this.mob.getX();
            double hz = n.z + 0.5 - this.mob.getZ();
            double horiz = Math.sqrt(hx * hx + hz * hz);
            if (dy >= 1 && dy <= 2 && cheb == 1 && horiz <= 1.65
                    && this.canStand(cand) && this.climbImprovesGoal(cand)) {
                stand = cand;
            }
        }
        // 2) Look ahead in path only (never random world A2 when path says home A1).
        if (stand == null) {
            for (int i = idx; i < path.getNodeCount() && i < idx + 6; i++) {
                Node n = path.getNode(i);
                int dy = n.y - feetY;
                if (dy < 1 || dy > 2) continue;
                int cheb = Math.max(Math.abs(n.x - feetX), Math.abs(n.z - feetZ));
                if (cheb != 1) continue;
                BlockPos cand = new BlockPos(n.x, n.y, n.z);
                double hx = n.x + 0.5 - this.mob.getX();
                double hz = n.z + 0.5 - this.mob.getZ();
                if (hx * hx + hz * hz <= 1.65 * 1.65 && this.canStand(cand) && this.climbImprovesGoal(cand)) {
                    stand = cand;
                    break;
                }
            }
        }
        // 3) World scan only if path has no climb candidate — still must improve goal.
        if (stand == null) {
            stand = this.findAdjacentPadTop(current, moveTarget);
            if (stand != null && !this.climbImprovesGoal(stand)) {
                stand = null;
            }
        }
        if (stand != null && this.isPillarBanned(stand.getX(), stand.getZ())) {
            stand = null;
        }
        if (stand == null) {
            return false;
        }

        int standCheb = Math.max(Math.abs(stand.getX() - feetX), Math.abs(stand.getZ() - feetZ));
        if (standCheb >= 2) return false;

        double hx = stand.getX() + 0.5 - this.mob.getX();
        double hz = stand.getZ() + 0.5 - this.mob.getZ();
        double horiz = Math.sqrt(hx * hx + hz * hz);
        int dy = stand.getY() - feetY;
        if (dy < 1 || dy > 2) return false;
        if (horiz > 1.65) return false;

        Vec3 dir = new Vec3(hx, 0.0, hz);
        if (dir.lengthSqr() < 1.0E-4) dir = new Vec3(1, 0, 0);
        dir = dir.normalize();

        this.faceToward(stand.getX() + 0.5, this.mob.getY() + this.mob.getEyeHeight(), stand.getZ() + 0.5);
        this.sprinting = true;
        this.mob.setSprinting(true);
        this.haltMoveControl();

        // Jump from ~0.9–1.2 away (not pressed into face) so arc lands on top not face.
        // Log: h=0.80 collH jump → slide face → miss.
        boolean goodRange = horiz >= 0.75 && horiz <= 1.35;
        boolean tooClose = horiz < 0.75 || this.mob.horizontalCollision;
        if (tooClose && this.jumpCooldown <= 0) {
            // Back off first instead of jumping into wall.
            this.mob.setDeltaMovement(-dir.x * 0.16, 0.0, -dir.z * 0.16);
            this.mob.hasImpulse = true;
            this.jumpCooldown = 3;
            NpcPathDebugLog.event(this.mob, "CLIMB", "backoff h=" + String.format("%.2f", horiz)
                    + " coll=" + this.mob.horizontalCollision);
            return true;
        }
        if (goodRange && this.jumpCooldown <= 0) {
            double hSpeed = 0.32 + Math.min(0.08, (horiz - 0.75) * 0.1);
            double jumpY = this.jumpVelocity() + (dy >= 2 ? 0.10 : 0.05);
            this.mob.setDeltaMovement(dir.x * hSpeed, jumpY, dir.z * hSpeed);
            this.mob.hasImpulse = true;
            this.jumpCooldown = 5;
            this.inParkourJump = true;
            this.climbTarget = stand.immutable();
            this.climbAirTicks = 0;
            this.lockParkourHorizontal(dir.x * hSpeed, dir.z * hSpeed, 14);
            NpcPathDebugLog.event(this.mob, "CLIMB", "JUMP stand=" + NpcPathDebugLog.pos(stand)
                    + " dy=" + dy + " h=" + String.format("%.2f", horiz)
                    + " hSp=" + String.format("%.2f", hSpeed));
            return true;
        }
        // Approach stand-off distance ~1.0
        if (horiz > 1.2) {
            double push = 0.16;
            this.mob.setDeltaMovement(dir.x * push, this.mob.getDeltaMovement().y, dir.z * push);
            this.mob.hasImpulse = true;
            this.lockParkourHorizontal(dir.x * push, dir.z * push, 2);
        }
        return true;
    }

    /**
     * Climb if stand is on path (A2 stepping stone) or closer to goal.
     * Log freeze 14:19: at x=13 past A2, climb A2 rejected because XZ dist to A3 grew → wall thrash.
     */
    private boolean climbImprovesGoal(BlockPos stand) {
        if (this.baritoneTarget == null) return true;
        // Same column as goal (home pad) always OK.
        if (stand.getX() == this.baritoneTarget.getX() && stand.getZ() == this.baritoneTarget.getZ()) {
            return true;
        }
        // Path elevated nodes are intentional stepping stones — always allow.
        Path path = this.baritonePath != null ? this.baritonePath : this.path;
        if (path != null && !path.isDone()) {
            int idx = path.getNextNodeIndex();
            for (int i = idx; i < path.getNodeCount() && i < idx + 6; i++) {
                Node n = path.getNode(i);
                if (n.x == stand.getX() && n.z == stand.getZ() && n.y >= stand.getY() - 1) {
                    return true;
                }
            }
        }
        double gx = this.baritoneTarget.getX() + 0.5;
        double gz = this.baritoneTarget.getZ() + 0.5;
        double cur = this.mob.distanceToSqr(gx, this.mob.getY(), gz);
        double nxt = stand.distToCenterSqr(gx, stand.getY(), gz);
        // Must not climb farther from goal (A2 when going home to A1).
        return nxt < cur - 0.25;
    }

    /** Adjacent pad tops that improve toward goal. Prefer goalward, not nearest pillar. */
    private BlockPos findAdjacentPadTop(Vec3 current, Vec3 moveTarget) {
        int feetY = Mth.floor(current.y);
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos base = BlockPos.containing(current.x, feetY, current.z).relative(d, 1);
            for (int up = 1; up <= 2; up++) {
                BlockPos feet = new BlockPos(base.getX(), feetY + up, base.getZ());
                if (!this.canStand(feet)) continue;
                if (!this.climbImprovesGoal(feet)) continue;
                double dsq = current.distanceToSqr(feet.getX() + 0.5, current.y, feet.getZ() + 0.5);
                if (dsq > 1.7 * 1.7) continue;
                // Prefer pads closer to goal.
                double score = dsq;
                if (this.baritoneTarget != null) {
                    score += feet.distManhattan(this.baritoneTarget) * 2.0;
                }
                if (score < bestScore) {
                    bestScore = score;
                    best = feet;
                }
                break;
            }
        }
        return best;
    }

    private boolean isFlatGroundChase(Path path) {
        if (this.baritoneTarget == null || path == null) return false;
        if (this.needsElevation()) return false;
        int feetY = Mth.floor(this.mob.getY());
        if (Math.abs(this.baritoneTarget.getY() - feetY) > 0) return false;
        // Jump while chasing if farther than 3 blocks.
        if (this.mob.blockPosition().distSqr(this.baritoneTarget) < 9.0) return false;
        int check = Math.min(path.getNodeCount(), path.getNextNodeIndex() + 6);
        for (int i = path.getNextNodeIndex(); i < check; i++) {
            if (Math.abs(path.getNode(i).y - feetY) > 0) return false;
        }
        return true;
    }

    private void handleFlatChaseRunJump(Path path, Vec3 current, Vec3 target) {
        if (!this.jumpsAllowed() || this.inParkourJump) return;
        if (this.climbTarget != null || this.hopTarget != null) return;
        int interval = this.chaseJumpInterval();
        if (interval <= 0) return;
        if (this.flatChaseJumpCooldown > 0) return;
        if (!this.mob.onGround() || this.mob.horizontalCollision) return;
        if (!this.isFlatGroundChase(path)) return;

        this.sprinting = true;
        this.mob.setSprinting(true);
        Vec3 dir = new Vec3(target.x - current.x, 0.0, target.z - current.z);
        if (dir.lengthSqr() < 1.0E-4) {
            dir = new Vec3(this.baritoneTarget.getX() + 0.5 - current.x, 0,
                    this.baritoneTarget.getZ() + 0.5 - current.z);
        }
        if (dir.lengthSqr() < 1.0E-4) return;
        dir = dir.normalize();
        Vec3 motion = this.mob.getDeltaMovement();
        double curH = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
        double takeoffH = Math.max(curH, 0.30);
        this.mob.setDeltaMovement(dir.x * takeoffH, this.jumpVelocity() * 0.85, dir.z * takeoffH);
        this.mob.hasImpulse = true;
        this.flatChaseJumpCooldown = Math.max(12, interval);
        this.jumpCooldown = 4;
        NpcPathDebugLog.event(this.mob, "CHASE", "run-jump interval=" + interval);
    }

    private int parkourMaxDist() {
        if (this.npc instanceof EntityNPCInterface cnpc && cnpc.ais instanceof IDataAIPathfinding data) {
            return data.npcpathfinding$getParkourMaxDist();
        }
        return 4;
    }

    /** Max feet-to-feet forward for parkour edges. Config "gap" of 3 air blocks ⇒ feet span 4. */
    private int parkourMaxForward() {
        int gap = this.parkourMaxDist();
        if (gap <= 0) return 0;
        // gap N air between 1-block pads ≈ feet delta N+1, capped at 5.
        return Math.min(5, gap + 1);
    }

    private int chaseJumpInterval() {
        if (this.npc instanceof EntityNPCInterface cnpc && cnpc.ais instanceof IDataAIPathfinding data) {
            return data.npcpathfinding$getChaseJumpInterval();
        }
        return 40;
    }

    private void doStuckRecovery(Path path, Vec3 current) {
        if (this.climbTarget != null) return;
        if (this.mob.horizontalCollision && this.hopTarget != null && this.hopAirTicks >= 4) {
            this.detourAroundWall();
            return;
        }
        if (this.needsElevation() && this.mob.horizontalCollision && this.hopTarget == null
                && this.isStuckAgainstElevatedPad(path)) {
            this.stuckTicks++;
            if (this.stuckTicks >= 2) {
                this.stuckTicks = 0;
                this.banCollidingPillar(30);
                this.forceGroundPathUntil = this.level.getGameTime() + 15L;
                this.walkOutFromUnderPlatform(this.getTargetPosition(), 1.2);
                this.repathCooldown = 0;
                this.baritonePath = this.createBaritonePath(this.baritoneTarget, 1);
                this.path = this.baritonePath;
                if (this.baritonePath == null) {
                    this.recoverFromNullPath();
                } else {
                    this.repathCooldown = 6;
                }
            }
            return;
        }

        if (current.distanceToSqr(this.lastPos) < 0.0025) {
            this.stuckTicks++;
        } else {
            this.stuckTicks = 0;
            this.lastPos = current;
        }
        if (this.stuckTicks >= 20) {
            this.stuckTicks = 0;
            this.invalidatePathForRepath(true);
        }
    }

    /**
     * Leave wall along surface normal + lateral toward goal (real detour, not reverse-look turtle).
     */
    private void detourAroundWall() {
        this.haltMoveControl();
        this.parkourLockTicks = 0;
        this.inParkourJump = false;
        this.parkourSprintTicks = 0;
        this.hopTarget = null;
        this.hopTakeoff = null;
        this.hopAirTicks = 0;
        this.climbTarget = null;
        this.climbAirTicks = 0;

        Vec3 away = this.wallEscapeDirection();
        this.faceToward(this.mob.getX() + away.x * 2, this.mob.getY() + 0.5, this.mob.getZ() + away.z * 2);
        this.backoffX = away.x * 0.42;
        this.backoffZ = away.z * 0.42;
        this.mob.setDeltaMovement(this.backoffX, Math.min(this.mob.getDeltaMovement().y, 0.0), this.backoffZ);
        this.mob.hasImpulse = true;
        this.wallHitBackoff = 14;
        this.noJumpUntil = this.level.getGameTime() + 8L;
        this.invalidatePathForRepath(true);
    }

    /** Away from nearest solid column + sideways component that reduces distance to goal. */
    private Vec3 wallEscapeDirection() {
        int fx = Mth.floor(this.mob.getX());
        int fy = Mth.floor(this.mob.getY());
        int fz = Mth.floor(this.mob.getZ());
        double awayX = 0;
        double awayZ = 0;
        int solids = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos p = new BlockPos(fx + d.getStepX(), fy, fz + d.getStepZ());
            BlockState st = this.level.getBlockState(p);
            if (st.isAir() || st.getCollisionShape(this.level, p).isEmpty()) continue;
            // Push opposite the solid face.
            awayX -= d.getStepX();
            awayZ -= d.getStepZ();
            solids++;
            // Also sample body mid-height (pillar shaft).
            BlockPos mid = p.above();
            BlockState midSt = this.level.getBlockState(mid);
            if (!midSt.isAir() && !midSt.getCollisionShape(this.level, mid).isEmpty()) {
                awayX -= d.getStepX() * 0.5;
                awayZ -= d.getStepZ() * 0.5;
            }
        }
        Vec3 away;
        if (solids > 0 && (awayX * awayX + awayZ * awayZ) > 1.0E-4) {
            away = new Vec3(awayX, 0, awayZ).normalize();
        } else {
            Vec3 m = this.mob.getDeltaMovement().multiply(1, 0, 1);
            if (m.lengthSqr() > 1.0E-4) {
                away = m.normalize().scale(-1);
            } else {
                away = this.mob.getLookAngle().multiply(1, 0, 1);
                if (away.lengthSqr() < 1.0E-4) away = new Vec3(1, 0, 0);
                away = away.normalize().scale(-1);
            }
        }

        // Lateral: pick left/right that moves closer to goal (orbit pillar, not reverse into "back").
        Vec3 side = new Vec3(-away.z, 0, away.x);
        if (this.baritoneTarget != null) {
            double gx = this.baritoneTarget.getX() + 0.5 - this.mob.getX();
            double gz = this.baritoneTarget.getZ() + 0.5 - this.mob.getZ();
            Vec3 toGoal = new Vec3(gx, 0, gz);
            if (toGoal.lengthSqr() > 1.0E-4) {
                toGoal = toGoal.normalize();
                // Prefer the side with larger projection onto goal; if wall blocks forward, bias away+side.
                double left = side.dot(toGoal);
                if (left < 0) side = side.scale(-1);
            }
        }
        // 55% off wall + 45% along side = clear the face then circle.
        Vec3 detour = away.scale(0.55).add(side.scale(0.45));
        if (detour.lengthSqr() < 1.0E-4) detour = away;
        return detour.normalize();
    }

    private boolean stuckUnderHighNode(Path path) {
        int feetY = Mth.floor(this.mob.getY());
        for (int i = path.getNextNodeIndex(); i < path.getNodeCount(); i++) {
            Node n = path.getNode(i);
            if (n.y <= feetY + 1) continue;
            double hx = n.x + 0.5 - this.mob.getX();
            double hz = n.z + 0.5 - this.mob.getZ();
            if (hx * hx + hz * hz < 2.5 * 2.5) return true;
        }
        if (this.baritoneTarget != null && this.baritoneTarget.getY() > feetY + 1) {
            double gx = this.baritoneTarget.getX() + 0.5 - this.mob.getX();
            double gz = this.baritoneTarget.getZ() + 0.5 - this.mob.getZ();
            if (gx * gx + gz * gz < 3.0 * 3.0) return true;
        }
        return false;
    }

    private void pushAwayFromHighPads(Path path) {
        int feetY = Mth.floor(this.mob.getY());
        double ax = 0, az = 0;
        int count = 0;
        for (int i = path.getNextNodeIndex(); i < path.getNodeCount(); i++) {
            Node n = path.getNode(i);
            if (n.y <= feetY + 1) continue;
            ax += (this.mob.getX() - (n.x + 0.5));
            az += (this.mob.getZ() - (n.z + 0.5));
            count++;
            if (count >= 3) break;
        }
        if (count == 0 && this.baritoneTarget != null) {
            ax = this.mob.getX() - (this.baritoneTarget.getX() + 0.5);
            az = this.mob.getZ() - (this.baritoneTarget.getZ() + 0.5);
            count = 1;
        }
        if (count == 0) return;
        Vec3 away = new Vec3(ax, 0.0, az);
        if (away.lengthSqr() < 1.0E-4) {
            away = new Vec3(1.0, 0.0, 0.0);
        }
        away = away.normalize().scale(0.28);
        this.mob.setDeltaMovement(away.x, 0.0, away.z);
        this.mob.hasImpulse = true;
    }

    private void pushAwayFromWall() {
        // Back off opposite to look / movement into wall.
        Vec3 look = this.mob.getLookAngle().multiply(1.0, 0.0, 1.0);
        if (look.lengthSqr() < 1.0E-4) look = new Vec3(1, 0, 0);
        look = look.normalize().scale(-0.25);
        // Also try perpendicular if available motion.
        Vec3 m = this.mob.getDeltaMovement().multiply(1.0, 0.0, 1.0);
        if (m.lengthSqr() > 1.0E-4) {
            look = m.normalize().scale(-0.28);
        }
        this.mob.setDeltaMovement(look.x, 0.0, look.z);
        this.mob.hasImpulse = true;
    }

    private void invalidatePathForRepath(boolean afterFall) {
        this.baritonePath = null;
        this.path = null;
        this.inParkourJump = false;
        this.parkourSprintTicks = 0;
        // Short cooldown — long rCd caused STOP thrash (log: rCd=5 + path null → stop).
        this.repathCooldown = afterFall ? 4 : 6;
        if (afterFall) {
            if (this.noJumpUntil < this.level.getGameTime()) {
                this.noJumpUntil = this.level.getGameTime() + 4L;
            }
            if (this.failRepaths == 0) this.failRepaths = 1;
        }
        this.mob.setSprinting(false);
        this.sprinting = false;
        if (this.mob.onGround()) {
            Vec3 m = this.mob.getDeltaMovement();
            this.mob.setDeltaMovement(m.x * 0.25, Math.min(m.y, 0.0), m.z * 0.25);
        }
    }

    private boolean jumpsAllowed() {
        return this.noJumpUntil < 0L || this.level.getGameTime() >= this.noJumpUntil;
    }

    private void applyBaritoneInput(Vec3 target, double speed) {
        Vec3 horizontal = target.subtract(this.mob.position()).multiply(1.0, 0.0, 1.0);
        if (horizontal.lengthSqr() < 1.0E-4) return;

        Vec3 dir = horizontal.normalize();

        // While a parkour jump is active, don't micro-adjust horizontal — let handleParkour keep momentum.
        if (this.inParkourJump && !this.mob.onGround()) {
            return;
        }

        double base = this.mob.onGround() ? 0.10 : 0.045;
        double sprint = this.sprinting ? 1.35 : 1.0;
        double scale = Math.min(0.22, base * Math.max(0.85, Math.min(1.4, speed)) * sprint);
        double maxH = this.sprinting ? 0.32 : 0.24;
        if (!this.mob.onGround()) {
            maxH = this.sprinting ? 0.36 : 0.28;
            scale = Math.max(scale, 0.08);
        }
        Vec3 motion = this.mob.getDeltaMovement();
        this.mob.setDeltaMovement(
            Mth.clamp(motion.x * 0.6 + dir.x * scale, -maxH, maxH),
            motion.y,
            Mth.clamp(motion.z * 0.6 + dir.z * scale, -maxH, maxH)
        );
        this.mob.hasImpulse = true;
    }

    private void handleLadder(Vec3 target) {
        BlockPos feet = this.mob.blockPosition();
        BlockState feetState = this.level.getBlockState(feet);
        BlockState targetState = this.level.getBlockState(BlockPos.containing(target));
        if (!(feetState.getBlock() instanceof LadderBlock) && !(targetState.getBlock() instanceof LadderBlock)) return;
        if (!this.mob.horizontalCollision && !(feetState.getBlock() instanceof LadderBlock)) return;

        double climb = target.y > this.mob.getY() + 0.05 ? 0.22 : -0.12;
        this.mob.setDeltaMovement(this.mob.getDeltaMovement().x, climb, this.mob.getDeltaMovement().z);
        this.mob.hasImpulse = true;
    }

    /** Only auto-jump for adjacent +1 path node. Never jump into pillar face (A2 bounce). */
    private void handleStepOrJump(Path path, Vec3 target) {
        if (!this.jumpsAllowed() || !this.mob.onGround()) return;
        if (this.climbTarget != null || this.hopTarget != null) return;
        if (this.mob.horizontalCollision) return; // wall face — walk out, don't bounce
        if (path == null || path.isDone()) return;
        Node n = path.getNode(path.getNextNodeIndex());
        int feetY = Mth.floor(this.mob.getY());
        int dy = n.y - feetY;
        if (dy != 1) return;
        int cheb = Math.max(Math.abs(n.x - Mth.floor(this.mob.getX())),
                Math.abs(n.z - Mth.floor(this.mob.getZ())));
        if (cheb > 1) return;
        double hx = n.x + 0.5 - this.mob.getX();
        double hz = n.z + 0.5 - this.mob.getZ();
        if (hx * hx + hz * hz > 2.0) return;
        if (!this.tryJump()) return;
        Vec3 look = new Vec3(hx, 0.0, hz);
        if (look.lengthSqr() > 1.0E-4) {
            look = look.normalize().scale(0.16);
            this.mob.setDeltaMovement(look.x, this.jumpVelocity() * 0.95, look.z);
        } else {
            this.mob.setDeltaMovement(0.0, this.jumpVelocity() * 0.95, 0.0);
        }
        this.mob.hasImpulse = true;
    }

    private boolean shouldSprint(Path path, Vec3 current, Vec3 target) {
        if (this.npc.isInWater() || this.npc.isInLava()) return false;
        if (this.mob.getHealth() < 6.0F) return false;

        double dx = target.x - current.x;
        double dz = target.z - current.z;
        double dist = Math.sqrt(dx * dx + dz * dz);

        return dist > 1.2;
    }

    /** True when next useful path hop is a multi-block gap (must not walk). */
    private boolean isGapHopTarget(Path path, Vec3 current) {
        if (path == null) return false;
        int feetX = Mth.floor(current.x);
        int feetY = Mth.floor(current.y);
        int feetZ = Mth.floor(current.z);
        int maxFwd = this.parkourMaxForward();
        if (maxFwd < 2) return false;
        return this.findParkourLanding(path, feetX, feetY, feetZ, maxFwd) != null;
    }

    /** @return true if this tick handled movement (caller must not walk off pad). */
    private boolean handleParkour(Path path, Vec3 current, Vec3 target) {
        if (this.wallHitBackoff > 0) {
            this.haltMoveControl();
            this.mob.setDeltaMovement(this.backoffX, this.mob.getDeltaMovement().y, this.backoffZ);
            this.mob.hasImpulse = true;
            return true;
        }
        if (!this.needsElevation() || !this.jumpsAllowed()) {
            this.inParkourJump = false;
            this.parkourSprintTicks = 0;
            this.hopTarget = null;
            return false;
        }

        // Track airtime; never treat "still on takeoff pad" as a failed landing.
        if (this.hopTarget != null) {
            if (!this.mob.onGround()) {
                this.hopAirTicks++;
                // Mid-air: if over pad column at pad height, snap (log: overshoot 12→13 then wall A2).
                double hx = this.hopTarget.getX() + 0.5 - this.mob.getX();
                double hz = this.hopTarget.getZ() + 0.5 - this.mob.getZ();
                double ty = this.hopTarget.getY();
                if (Math.abs(hx) < 0.7 && Math.abs(hz) < 0.7 && this.mob.getY() >= ty - 0.35) {
                    this.mob.setPos(this.hopTarget.getX() + 0.5, Math.max(this.mob.getY(), ty),
                            this.hopTarget.getZ() + 0.5);
                    this.mob.setDeltaMovement(0.0, Math.min(0.0, this.mob.getDeltaMovement().y), 0.0);
                    this.mob.hasImpulse = true;
                }
            } else if (this.hopAirTicks >= 2) {
                // Truly landed after being airborne.
                double hx = this.hopTarget.getX() + 0.5 - this.mob.getX();
                double hz = this.hopTarget.getZ() + 0.5 - this.mob.getZ();
                double h2 = hx * hx + hz * hz;
                boolean onHopPad = h2 < 1.45 * 1.45
                        && Math.abs(this.hopTarget.getY() - this.mob.getY()) < 0.85;
                boolean stillOnTakeoff = this.hopTakeoff != null
                        && Math.abs(this.mob.getX() - (this.hopTakeoff.getX() + 0.5)) < 0.9
                        && Math.abs(this.mob.getZ() - (this.hopTakeoff.getZ() + 0.5)) < 0.9
                        && Math.abs(this.mob.getY() - this.hopTakeoff.getY()) < 0.6;
                BlockPos landedOn = this.hopTarget;
                // Snap to pad center when close (prevent walk-off at x=13 past A2 pad x=12).
                if (onHopPad && h2 > 0.04) {
                    this.mob.setPos(landedOn.getX() + 0.5, Math.max(this.mob.getY(), landedOn.getY()),
                            landedOn.getZ() + 0.5);
                    this.mob.setDeltaMovement(0.0, this.mob.getDeltaMovement().y, 0.0);
                    this.mob.hasImpulse = true;
                }
                this.inParkourJump = false;
                this.parkourLockTicks = 0;
                this.hopTarget = null;
                this.hopTakeoff = null;
                this.hopAirTicks = 0;
                // Short jCd so next hop (A1→A2) can fire immediately (log: jCd=4 froze gap_hold).
                this.jumpCooldown = onHopPad ? 1 : 4;
                this.advancePastCurrentPad(path);
                if (onHopPad) {
                    NpcPathDebugLog.event(this.mob, "PARKOUR", "landed " + NpcPathDebugLog.pos(landedOn));
                    return false; // allow immediate next hop/walk
                }
                if (stillOnTakeoff) {
                    return false; // retry hop next tick, don't freeze
                }
                this.invalidatePathForRepath(true);
                return true;
            }
        }

        // Only hop pad→pad while on a pad top (or mid-hop airborne). Never from ground.
        if (this.mob.onGround() && this.hopTarget == null && !this.isStandingOnPad()) {
            this.inParkourJump = false;
            return false;
        }
        if (this.climbTarget != null) {
            return false;
        }
        if (this.hopTarget == null) {
            this.advancePastCurrentPad(path);
        }

        int nextIdx = path.getNextNodeIndex();
        if (nextIdx >= path.getNodeCount() && this.hopTarget == null) {
            this.inParkourJump = false;
            this.parkourSprintTicks = 0;
            return false;
        }

        // Only detour after real air time mid pad-hop (log: collH on climb frame wiped hopTarget).
        if (this.mob.horizontalCollision && this.hopTarget != null && this.hopAirTicks >= 3) {
            this.detourAroundWall();
            return true;
        }

        int feetX = Mth.floor(this.mob.getX());
        int feetZ = Mth.floor(this.mob.getZ());
        int feetY = Mth.floor(this.mob.getY());
        int maxFwd = this.parkourMaxForward();
        if (maxFwd < 2) return false;

        if (this.hopTarget == null
                && this.findAdjacentPadTop(current, target) != null
                && !this.isStandingOnPad()) {
            return false;
        }

        BlockPos land = this.hopTarget;
        if (land == null) {
            land = this.findParkourLanding(path, feetX, feetY, feetZ, maxFwd);
        }
        if (land == null) {
            this.inParkourJump = false;
            this.parkourSprintTicks = 0;
            this.parkourLockTicks = 0;
            return false;
        }

        int nodeHoriz = Math.max(Math.abs(land.getX() - feetX), Math.abs(land.getZ() - feetZ));
        double launchGapX = land.getX() + 0.5 - this.mob.getX();
        double launchGapZ = land.getZ() + 0.5 - this.mob.getZ();
        double launchDy = land.getY() - this.mob.getY();
        if (this.hopTarget == null && nodeHoriz < 2) {
            this.inParkourJump = false;
            return false;
        }
        // Log freeze: A1(-58)→A2(-56) launchDy≈2 blocked by 1.25 → gap_hold forever.
        // Allow up to +2 pad height for pad hops.
        if (this.hopTarget == null && (launchDy > 2.15 || launchDy < -0.5)) {
            return false;
        }

        this.haltMoveControl();

        Vec3 jumpDir = new Vec3(launchGapX, 0.0, launchGapZ);
        if (jumpDir.lengthSqr() < 1.0E-6) {
            this.inParkourJump = false;
            return false;
        }
        jumpDir = jumpDir.normalize();
        this.faceToward(land.getX() + 0.5, this.mob.getY() + this.mob.getEyeHeight(), land.getZ() + 0.5);
        this.sprinting = true;
        this.mob.setSprinting(true);

        if (this.mob.onGround() && this.hopTarget == null) {
            if (this.jumpCooldown > 0) {
                // Don't freeze — walk toward edge for next hop.
                this.inParkourJump = false;
                return false;
            }

            // Minimal recenter — log thrash from over-centering.
            double cx = feetX + 0.5 - this.mob.getX();
            double cz = feetZ + 0.5 - this.mob.getZ();
            double off = Math.sqrt(cx * cx + cz * cz);
            if (off > 0.45) {
                this.mob.setDeltaMovement(cx * 0.35, 0.0, cz * 0.35);
                this.mob.hasImpulse = true;
                return true;
            }

            int h = Math.max(2, nodeHoriz);
            // Milder boost: log A1→A2 h=3 boost=0.54 overshot to x=13 (pad center 12.5).
            double forwardBoost = 0.36 + Math.min(0.10, (h - 2) * 0.05);
            if (launchDy > 0.1) forwardBoost += 0.04;
            if (launchDy > 1.5) forwardBoost += 0.03;
            if (h >= 3) forwardBoost = Math.max(forwardBoost, 0.40);
            if (h >= 4) forwardBoost = Math.max(forwardBoost, 0.46);
            if (h >= 5) forwardBoost = Math.max(forwardBoost, 0.52);
            double jumpY = this.jumpVelocity() + (launchDy > 1.5 ? 0.14 : (launchDy > 0.1 ? 0.08 : 0.03));
            this.mob.setDeltaMovement(jumpDir.x * forwardBoost, jumpY, jumpDir.z * forwardBoost);
            this.mob.hasImpulse = true;
            this.jumpCooldown = 6;
            this.parkourSprintTicks = 0;
            this.inParkourJump = true;
            this.hopTarget = land.immutable();
            this.hopTakeoff = new BlockPos(feetX, feetY, feetZ);
            this.hopAirTicks = 0;
            this.lockParkourHorizontal(jumpDir.x * forwardBoost, jumpDir.z * forwardBoost, 14);
            NpcPathDebugLog.event(this.mob, "PARKOUR", "TAKEOFF land=" + NpcPathDebugLog.pos(land)
                    + " h=" + h + " boost=" + String.format("%.2f", forwardBoost)
                    + " jY=" + String.format("%.2f", jumpY));
            return true;
        }

        // Airborne: brake into pad; log overshoot kept 0.22 past center → land x=13 wall A2.
        this.inParkourJump = true;
        double distLeft = Math.sqrt(launchGapX * launchGapX + launchGapZ * launchGapZ);
        Vec3 motion = this.mob.getDeltaMovement();
        double along = motion.x * jumpDir.x + motion.z * jumpDir.z;
        // Past / on pad center: kill horizontal so we land on pad, not walk off.
        if (distLeft < 0.55) {
            this.mob.setDeltaMovement(jumpDir.x * 0.06, motion.y, jumpDir.z * 0.06);
            this.mob.hasImpulse = true;
            this.lockParkourHorizontal(jumpDir.x * 0.06, jumpDir.z * 0.06, 2);
            return true;
        }
        double desired = distLeft > 2.5 ? 0.40 : (distLeft > 1.5 ? 0.32 : (distLeft > 0.9 ? 0.18 : 0.10));
        // Soft pull back if overshooting past pad (along still forward but past center).
        if (along > 0.02 && distLeft < 0.9) {
            desired = Math.min(desired, 0.10);
        }
        // Never reverse hard mid-air (oscil), mild correct only.
        if (along < -0.05) {
            jumpDir = new Vec3(motion.x, 0, motion.z);
            if (jumpDir.lengthSqr() > 1.0E-4) jumpDir = jumpDir.normalize();
            desired = Math.min(desired, 0.14);
        }
        this.lockParkourHorizontal(jumpDir.x * desired, jumpDir.z * desired, 2);
        this.mob.setDeltaMovement(jumpDir.x * desired, motion.y, jumpDir.z * desired);
        this.mob.hasImpulse = true;
        return true;
    }

    /** Skip path nodes on the pad we already stand on so hop look-ahead sees A2. */
    private void advancePastCurrentPad(Path path) {
        if (path == null || path.isDone()) return;
        int feetX = Mth.floor(this.mob.getX());
        int feetY = Mth.floor(this.mob.getY());
        int feetZ = Mth.floor(this.mob.getZ());
        int guard = 0;
        while (!path.isDone() && guard++ < 6) {
            int idx = path.getNextNodeIndex();
            if (idx >= path.getNodeCount()) break;
            Node n = path.getNode(idx);
            int h = Math.max(Math.abs(n.x - feetX), Math.abs(n.z - feetZ));
            int dy = Math.abs(n.y - feetY);
            if (h <= 0 && dy <= 1) {
                path.advance();
                continue;
            }
            if (h <= 1 && dy <= 0 && this.canStand(new BlockPos(n.x, n.y, n.z))) {
                // Same pad / adjacent same-Y walk tile on current platform top — skip.
                double dsq = this.mob.distanceToSqr(n.x + 0.5, this.mob.getY(), n.z + 0.5);
                if (dsq < 1.0) {
                    path.advance();
                    continue;
                }
            }
            break;
        }
    }

    private BlockPos findParkourLanding(Path path, int feetX, int feetY, int feetZ, int maxFwd) {
        // Never hop DOWN while goal is still at/above us (A1→ground gap was "walk off + hit A2").
        boolean noDescend = this.baritoneTarget != null && this.baritoneTarget.getY() >= feetY;
        int minDy = noDescend ? 0 : -1;
        int nextIdx = path.getNextNodeIndex();
        BlockPos land = null;
        double bestScore = Double.MAX_VALUE;
        for (int look = nextIdx; look < path.getNodeCount() && look < nextIdx + 12; look++) {
            Node n = path.getNode(look);
            int h = Math.max(Math.abs(n.x - feetX), Math.abs(n.z - feetZ));
            int dy = n.y - feetY;
            // Skip ground under elevated goal and same-pad nodes.
            if (noDescend && dy < 0) continue;
            if (h <= 1 && dy <= 0) continue;
            if (this.isPillarBanned(n.x, n.z)) continue;
            if (h >= 2 && h <= maxFwd && dy >= minDy && dy <= 2
                    && this.canStand(new BlockPos(n.x, n.y, n.z))) {
                double score = h + (dy == 0 ? 0.2 : 0.0);
                if (this.baritoneTarget != null) {
                    score += new BlockPos(n.x, n.y, n.z).distManhattan(this.baritoneTarget) * 0.02;
                }
                if (score < bestScore) {
                    bestScore = score;
                    land = new BlockPos(n.x, n.y, n.z);
                }
                if (land != null && look <= nextIdx + 2) break;
            }
        }
        if (land == null) {
            land = this.findPadHopLanding(feetX, feetY, feetZ, maxFwd, noDescend);
        }
        return land;
    }

    /** World scan: standable pad 2..maxFwd away at same Y or +1 (A1→A2 when path is sparse). */
    private BlockPos findPadHopLanding(int feetX, int feetY, int feetZ, int maxFwd) {
        boolean noDescend = this.baritoneTarget != null && this.baritoneTarget.getY() >= feetY;
        return this.findPadHopLanding(feetX, feetY, feetZ, maxFwd, noDescend);
    }

    private BlockPos findPadHopLanding(int feetX, int feetY, int feetZ, int maxFwd, boolean noDescend) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        // Prefer direction toward goal.
        double prefX = 0;
        double prefZ = 0;
        if (this.baritoneTarget != null) {
            prefX = this.baritoneTarget.getX() - feetX;
            prefZ = this.baritoneTarget.getZ() - feetZ;
        }
        for (Direction d : Direction.Plane.HORIZONTAL) {
            // Require a real gap: next block not standable at same Y.
            BlockPos adj = new BlockPos(feetX + d.getStepX(), feetY, feetZ + d.getStepZ());
            if (this.canStand(adj)) continue;
            for (int f = 2; f <= maxFwd; f++) {
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos land = new BlockPos(
                            feetX + d.getStepX() * f,
                            feetY + dy,
                            feetZ + d.getStepZ() * f);
                    if (this.isPillarBanned(land.getX(), land.getZ())) continue;
                    if (!this.canStand(land)) continue;
                    // Must look like a pad: solid below, void on sides.
                    BlockPos below = land.below();
                    BlockState bs = this.level.getBlockState(below);
                    if (bs.isAir() || bs.getCollisionShape(this.level, below).isEmpty()) continue;
                    double score = f + (dy == 0 ? 0.25 : 0.0);
                    // Alignment with goal direction (dot product).
                    if (prefX * prefX + prefZ * prefZ > 1.0E-4) {
                        double len = Math.sqrt(prefX * prefX + prefZ * prefZ);
                        double dot = (d.getStepX() * prefX + d.getStepZ() * prefZ) / len;
                        score -= dot * 1.5; // prefer toward goal
                        if (dot < 0.2) score += 2.0; // heavy penalty opposite/side
                    }
                    if (this.baritoneTarget != null) {
                        score += land.distManhattan(this.baritoneTarget) * 0.02;
                        if (land.getY() < this.baritoneTarget.getY()) {
                            score += (this.baritoneTarget.getY() - land.getY()) * 0.15;
                        }
                    }
                    if (score < bestScore) {
                        bestScore = score;
                        best = land;
                    }
                }
            }
        }
        return best;
    }

    /**
     * Advance past nodes we already occupy. Must use raw node Y (not getTargetPosition +0.5 hack).
     */
    private void advancePathNodes(Path path) {
        if (path == null || path.isDone()) return;
        int feetX = Mth.floor(this.mob.getX());
        int feetY = Mth.floor(this.mob.getY());
        int feetZ = Mth.floor(this.mob.getZ());
        double mx = this.mob.getX();
        double mz = this.mob.getZ();
        int guard = 0;
        while (!path.isDone() && guard++ < 12) {
            int idx = path.getNextNodeIndex();
            if (idx >= path.getNodeCount()) break;
            Node n = path.getNode(idx);
            double dx = (n.x + 0.5) - mx;
            double dz = (n.z + 0.5) - mz;
            double horizSq = dx * dx + dz * dz;
            int dy = n.y - feetY;

            // Same block column / very close XZ.
            boolean closeXZ = horizSq < 0.85 * 0.85
                    || (Math.abs(n.x - feetX) <= 0 && Math.abs(n.z - feetZ) <= 0);
            if (!closeXZ) break;

            // Skip banned elevated pads in path (must not treat as next waypoint).
            if (dy > 0 && this.isPillarBanned(n.x, n.z)) {
                path.advance();
                continue;
            }
            if (dy > 1) {
                // Still need to climb this node — don't skip.
                break;
            }
            if (dy < -1) {
                // Node below us — skip (already past).
                path.advance();
                continue;
            }
            // Same Y or ±1 and close: consumed.
            if (this.mob.onGround() || dy <= 0) {
                path.advance();
                continue;
            }
            break;
        }
    }

    private Vec3 getTargetPosition() {
        Path path = this.baritonePath != null ? this.baritonePath : this.path;
        if (path == null) return Vec3.ZERO;

        // Flat chase: when path can reach and coplanar, cut toward live entity / goal XZ
        // (vanilla path + direct steer = intercept; pure next-node lagged and zigzagged).
        if (!this.needsElevation() && this.baritoneTarget != null
                && path.canReach() && path.getNodeCount() >= 2) {
            Entity ce = this.chaseEntity;
            if (ce != null && ce.isAlive() && ce.getId() == this.lastChaseEntityId) {
                double dy = Math.abs(ce.getY() - this.mob.getY());
                if (dy < 1.5) {
                    return new Vec3(ce.getX(), this.mob.getY(), ce.getZ());
                }
            }
            // Look ahead 2–3 nodes on flat path for smoother cut.
            int idx = path.getNextNodeIndex();
            int look = Math.min(path.getNodeCount() - 1, idx + 2);
            if (look > idx) {
                Node n = path.getNode(look);
                if (Math.abs(n.y - Mth.floor(this.mob.getY())) <= 1) {
                    return new Vec3(n.x + 0.5, n.y, n.z + 0.5);
                }
            }
        }

        int idx = path.getNextNodeIndex();
        if (idx >= path.getNodeCount()) return Vec3.ZERO;

        Node node = path.getNode(idx);
        double x = node.x + 0.5;
        double y = node.y;
        double z = node.z + 0.5;

        if (this.isLadderAt(node)) {
            y = node.y + 1.0;
        }
        // Do NOT raise Y for "next is higher" — that blocked path.advance forever (log thrash).

        return new Vec3(x, y, z);
    }

    private boolean isLadderAt(Node node) {
        BlockState state = this.level.getBlockState(new BlockPos(node.x, node.y, node.z));
        return state.getBlock() instanceof LadderBlock;
    }

    public boolean isSprinting() {
        return sprinting;
    }

    public void stopSprinting() {
        this.sprinting = false;
        this.mob.setSprinting(false);
    }

    private double jumpVelocity() {
        return 0.37 + 0.13 * (this.jumpTendency() / 100.0);
    }

    private float walkSpeedMultiplier() {
        return (float) (1.05 + 0.28 * (this.walkTendency() / 100.0));
    }

    private int walkTendency() {
        if (this.npc instanceof EntityNPCInterface cnpc && cnpc.ais instanceof IDataAIPathfinding data) {
            return data.npcpathfinding$getWalkTendency();
        }
        return 50;
    }

    private int jumpTendency() {
        if (this.npc instanceof EntityNPCInterface cnpc && cnpc.ais instanceof IDataAIPathfinding data) {
            return data.npcpathfinding$getJumpTendency();
        }
        return 50;
    }

    /**
     * Log freeze: PATH_AFTER_BAN path=null + MOVE_TO spam → stand still 6s+.
     * Clear bans, force full climb/parkour edges, keep walking toward goal XZ.
     */
    private void recoverFromNullPath() {
        this.bannedPillarUntil.clear();
        this.forceGroundPathUntil = -1L;
        this.failRepaths = 0;
        if (this.baritoneTarget == null) return;
        this.baritonePath = this.createBaritonePath(this.baritoneTarget, 1);
        this.path = this.baritonePath;
        this.repathCooldown = this.baritonePath == null ? 4 : 6;
        if (this.baritonePath != null) {
            NpcPathDebugLog.pathSnapshot("PATH_NULL_RECOVER", this.baritonePath, this.baritoneTarget);
        } else {
            // Still null: at least walk toward goal so we never freeze (flat ground approach).
            NpcPathDebugLog.pathSnapshot("PATH_NULL_RECOVER", null, this.baritoneTarget);
            double gx = this.baritoneTarget.getX() + 0.5;
            double gz = this.baritoneTarget.getZ() + 0.5;
            this.mob.getMoveControl().setWantedPosition(gx, this.mob.getY(), gz, Math.max(1.0, this.baritoneSpeed));
            this.faceToward(gx, this.mob.getY() + this.mob.getEyeHeight(), gz);
        }
    }

    private Path createBaritonePath(BlockPos target, int accuracy) {
        BlockPos goal = this.resolveTarget(target);
        BlockPos start = this.mob.blockPosition();
        int needUp = goal.getY() - start.getY();
        // Flat same-Y: use vanilla GroundPathNavigation (WalkNodeEvaluator).
        // Custom 1-step A* was worse than vanilla (roundabout, no intercept, TPS).
        if (needUp == 0 && Math.abs(goal.getY() - Mth.floor(this.mob.getY())) <= 1) {
            try {
                Path vanilla = super.createPath(goal, accuracy);
                if (vanilla != null && vanilla.getNodeCount() >= 2) {
                    return vanilla;
                }
            } catch (Throwable ignored) {
            }
        }
        // Log freeze: needUp=1 + dist<=3 set allowParkour=false → NO climb edges at all
        // → path 7,-60→8,-60 never reaches home 9,-59 → STOP stand still.
        // Split: always allow adjacent climb when needUp>=1; multi-hop only when farther/higher.
        boolean allowClimb = needUp >= 1;
        // Log freeze 14:28: on A3 needUp=-4, parkour off → no drop edges → path=null stare.
        boolean allowParkour = this.parkourMaxDist() >= 2
                && (needUp >= 1 && (needUp >= 2 || start.distManhattan(goal) >= 4)
                || needUp <= -1);
        // After bounce: never kill climb; only briefly soft-prefer ground walk.
        boolean forceGround = this.forceGroundPathUntil >= this.level.getGameTime()
                && !this.bannedPillarUntil.isEmpty();
        if (forceGround && start.distManhattan(goal) < 6 && needUp >= 0) {
            // Keep climb ON — log freeze was forceGround + ban A1 → path null.
            allowParkour = false;
        }
        Path custom = this.createCustomParkourPath(goal, accuracy, allowClimb, allowParkour);
        if (custom == null || (needUp >= 1 && !custom.canReach() && !this.isUsefulPartial(custom, goal))) {
            // Last resort: clear bans, full edges (climb+parkour).
            if (!this.bannedPillarUntil.isEmpty() || forceGround) {
                this.bannedPillarUntil.clear();
                this.forceGroundPathUntil = -1L;
                allowParkour = this.parkourMaxDist() >= 2 && (needUp >= 1 || needUp <= -1);
                custom = this.createCustomParkourPath(goal, accuracy, allowClimb, allowParkour);
            }
            if (custom == null) return null;
        }
        Node end = custom.getEndNode();
        if (custom.canReach()) return custom;
        if (this.isUsefulPartial(custom, goal)) return custom;
        // Reject partial that ends on home pad when goal is still far combat (wrong retreat).
        if (end.x == goal.getX() && end.z == goal.getZ() && end.y >= goal.getY() - 1) {
            return custom; // ends at goal column — keep
        }
        if (allowClimb && custom.getNodeCount() >= 2 && end.y > start.getY()) return custom;
        // Descending partial that got lower is useful (chase player off A3).
        if (needUp < 0 && custom.getNodeCount() >= 2 && end.y < start.getY()) return custom;
        BlockPos endPos = new BlockPos(end.x, end.y, end.z);
        if (custom.getNodeCount() >= 2 && endPos.distManhattan(goal) < start.distManhattan(goal)) {
            return custom;
        }
        return custom.getNodeCount() >= 2 ? custom : null;
    }

    private Path betterPartial(Path a, Path b, BlockPos goal) {
        return this.partialQuality(a, goal) <= this.partialQuality(b, goal) ? a : b;
    }

    private double partialQuality(Path path, BlockPos goal) {
        if (path == null || path.getNodeCount() == 0) return Double.MAX_VALUE;
        Node end = path.getEndNode();
        BlockPos endPos = new BlockPos(end.x, end.y, end.z);
        return this.partialScore(endPos, goal) - Math.min(8, path.getNodeCount()) * 0.15;
    }

    private boolean isUsefulPartial(Path path, BlockPos goal) {
        if (path == null || path.getNodeCount() < 2) return false;
        Node end = path.getEndNode();
        BlockPos start = this.mob.blockPosition();
        BlockPos endPos = new BlockPos(end.x, end.y, end.z);
        double startScore = this.partialScore(start, goal);
        double endScore = this.partialScore(endPos, goal);
        if (endScore >= startScore - 0.25) return false;
        if (endPos.distManhattan(start) < 2) return false;
        int climbed = endPos.getY() - start.getY();
        // Multi-hop stepping stone (A1/A2) is useful even if still far from A3.
        if (climbed >= 1 && endScore < startScore - 0.5) return true;
        // Drop toward lower goal (chase off platform).
        if (climbed <= -1 && goal.getY() < start.getY() && endScore < startScore - 0.5) return true;
        if (endPos.distManhattan(goal) <= 6 && endScore < startScore - 0.5) return true;
        return false;
    }

    private Path createCustomParkourPath(BlockPos goal, int accuracy, boolean allowClimb, boolean allowParkour) {
        BlockPos start = this.snapStand(this.mob.blockPosition());
        // Cap nodes: parkour needs more; force-ground / simple climb less (TPS).
        int maxNodes;
        if (this.forceGroundPathUntil >= this.level.getGameTime()) {
            maxNodes = 800;
        } else if (allowParkour) {
            maxNodes = 2200;
        } else if (allowClimb) {
            maxNodes = 1200;
        } else {
            maxNodes = 600;
        }
        PriorityQueue<SearchNode> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        Map<BlockPos, SearchNode> all = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();

        SearchNode first = new SearchNode(start, null, 0.0, this.heuristic(start, goal));
        open.add(first);
        all.put(start, first);

        SearchNode best = first;
        double bestScore = this.partialScore(start, goal);
        int visited = 0;
        int goalRadius = Math.max(1, accuracy * accuracy);
        int maxManhattan = start.distManhattan(goal) + 28;
        while (!open.isEmpty() && visited++ < maxNodes) {
            SearchNode current = open.poll();
            if (!closed.add(current.pos)) continue;
            double currentScore = this.partialScore(current.pos, goal);
            boolean better = currentScore < bestScore - 1.0E-4
                    || (Math.abs(currentScore - bestScore) < 1.0E-4 && current.g < best.g)
                    || (currentScore <= bestScore + 1.2 && current.pos.getY() > best.pos.getY() && goal.getY() > start.getY());
            if (better) {
                best = current;
                bestScore = Math.min(bestScore, currentScore);
            }
            if (current.pos.distSqr(goal) <= goalRadius) {
                best = current;
                break;
            }
            for (Move move : this.movesFrom(current.pos, allowClimb, allowParkour, goal)) {
                if (closed.contains(move.to)) continue;
                if (move.to.distManhattan(goal) > maxManhattan) continue;
                if (this.isPillarBanned(move.to.getX(), move.to.getZ()) && move.to.getY() > current.pos.getY()) {
                    continue;
                }
                // NEVER drop while goal is still at/above current pad height.
                // Exception: goal is lower than start (player fled off A3) — allow descend.
                boolean chasingDown = goal.getY() < start.getY();
                if (!chasingDown && (allowClimb || allowParkour) && goal.getY() >= current.pos.getY()
                        && move.to.getY() < current.pos.getY()) continue;
                if (!chasingDown && (allowClimb || allowParkour) && goal.getY() > start.getY()
                        && move.to.getY() < start.getY()) continue;
                if (!chasingDown && !allowClimb && !allowParkour && goal.getY() > start.getY() + 1
                        && Math.abs(move.to.getX() - goal.getX()) + Math.abs(move.to.getZ() - goal.getZ()) <= 1
                        && move.to.getY() < goal.getY() - 1) {
                    continue;
                }
                double nextG = current.g + move.cost;
                SearchNode known = all.get(move.to);
                if (known != null && nextG >= known.g) continue;
                SearchNode next = new SearchNode(move.to, current, nextG, nextG + this.heuristic(move.to, goal));
                all.put(move.to, next);
                open.add(next);
            }
        }

        if (best == first) return null;
        if (best.pos.distSqr(goal) > goalRadius && !this.isUsefulCustomBest(best.pos, start, goal)) {
            return null;
        }

        List<Node> nodes = new ArrayList<>();
        for (SearchNode n = best; n != null; n = n.parent) {
            nodes.add(0, new Node(n.pos.getX(), n.pos.getY(), n.pos.getZ()));
        }
        if (nodes.size() < 2) return null;
        return new Path(nodes, goal, best.pos.distSqr(goal) <= goalRadius);
    }

    private BlockPos snapStand(BlockPos pos) {
        if (this.canStand(pos)) return pos;
        BlockPos n = this.normalizedStandPos(pos);
        if (n != null) return n;
        for (int dy = 1; dy <= 2; dy++) {
            if (this.canStand(pos.below(dy))) return pos.below(dy);
            if (this.canStand(pos.above(dy))) return pos.above(dy);
        }
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos s = pos.relative(d);
            if (this.canStand(s)) return s;
        }
        return pos;
    }

    private boolean isUsefulCustomBest(BlockPos end, BlockPos start, BlockPos goal) {
        double startScore = this.partialScore(start, goal);
        double endScore = this.partialScore(end, goal);
        if (endScore >= startScore - 0.5) return false;
        int needClimb = goal.getY() - start.getY();
        if (needClimb >= 2 && end.getY() <= start.getY() && endScore > 4.0) return false;
        // Descending chase: any lower end closer in score is useful.
        if (needClimb < 0 && end.getY() < start.getY()) return true;
        return true;
    }

    private Path createEmbeddedBaritonePath(BlockPos goal, int accuracy) {
        try {
            BetterBlockPos start = new BetterBlockPos(this.mob.getX(), this.mob.getY() + 0.1251, this.mob.getZ());
            CalculationContext context = new CalculationContext(this.baritone, this.mob, this.level);
            AStarPathFinder finder = new AStarPathFinder(
                    start,
                    start.x,
                    start.y,
                    start.z,
                    new GoalBlock(goal),
                    new Favoring(null, context),
                    context
            );
            // Give more budget; previous 80/250ms often stopped on ground partials.
            PathCalculationResult result = finder.calculate(250L, 800L);
            if (result.getType() == PathCalculationResult.Type.FAILURE
                    || result.getType() == PathCalculationResult.Type.CANCELLATION
                    || result.getType() == PathCalculationResult.Type.EXCEPTION) {
                return null;
            }
            IPath iPath = result.getPath().orElse(null);
            if (iPath == null || iPath.positions().size() < 2) return null;

            boolean toGoal = result.getType() == PathCalculationResult.Type.SUCCESS_TO_GOAL
                    || iPath.getDest().equals(new BetterBlockPos(goal));
            List<Node> nodes = new ArrayList<>();
            for (BetterBlockPos pos : iPath.positions()) {
                nodes.add(new Node(pos.x, pos.y, pos.z));
            }
            Path path = new Path(nodes, goal, toGoal);
            if (!toGoal && !this.isUsefulPartial(path, goal)) {
                return null;
            }
            return path;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Path createStationaryPath(BlockPos target) {
        List<Node> nodes = new ArrayList<>();
        BlockPos start = this.mob.blockPosition();
        nodes.add(new Node(start.getX(), start.getY(), start.getZ()));
        return new Path(nodes, target, false);
    }

    private double partialScore(BlockPos pos, BlockPos goal) {
        double dx = pos.getX() - goal.getX();
        double dz = pos.getZ() - goal.getZ();
        double dy = pos.getY() - goal.getY();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        double yPenalty;
        if (dy < 0) {
            // Below high goal: being closer in XZ under the pillar is BAD (wall hug).
            yPenalty = (-dy) * 8.5;
            if (-dy >= 2 && horiz < 2.5) {
                yPenalty += (2.5 - horiz) * 12.0;
            }
        } else {
            yPenalty = dy * 3.0;
        }
        return horiz + yPenalty;
    }

    private List<Move> movesFrom(BlockPos pos, boolean allowClimb, boolean allowParkour, BlockPos goal) {
        List<Move> moves = new ArrayList<>(24);
        boolean goalHigher = goal != null && goal.getY() > pos.getY();
        boolean goalAtOrAbove = goal != null && goal.getY() >= pos.getY();
        boolean goalLower = goal != null && goal.getY() < pos.getY();
        // Prefer climb when goal higher; still allow ground when recovering from ban.
        double groundWalk = (allowClimb && goalHigher) ? 5.0 : 1.0;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos step = pos.relative(dir);
            if (this.canStand(step)) {
                double c = groundWalk;
                if (allowClimb && goalAtOrAbove && step.getY() < goal.getY() && step.getY() <= pos.getY()) {
                    c = Math.max(c, 4.0);
                }
                addIfStandable(moves, step, c);
            }
            // Adjacent climb +1/+2 always when need elevation (home pad), even if multi-hop off.
            if (allowClimb) {
                for (int up = 1; up <= 2; up++) {
                    BlockPos top = new BlockPos(step.getX(), pos.getY() + up, step.getZ());
                    if (this.isPillarBanned(top.getX(), top.getZ())) break;
                    if (this.canStand(top)) {
                        moves.add(new Move(top, this.jumpCost(0.5 + up * 0.2)));
                        break;
                    }
                }
            }
            if (!goalAtOrAbove) {
                addIfStandable(moves, step.below(), 1.1);
            }
            // Drop off pad edge toward lower goal (A3 chase: walk into void → land ground).
            if (goalLower && !this.canStand(step)) {
                this.addDropMoves(moves, pos, step.getX(), step.getZ(), goal);
            }
            if (allowParkour) {
                addParkourMoves(moves, pos, dir.getStepX(), dir.getStepZ());
            }
        }
        // Direct drop under feet when goal lower (step off same column after walk).
        if (goalLower) {
            this.addDropMoves(moves, pos, pos.getX(), pos.getZ(), goal);
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos edge = pos.relative(dir);
                if (!this.canStand(edge)) {
                    this.addDropMoves(moves, pos, edge.getX(), edge.getZ(), goal);
                }
            }
        }
        for (Direction a : Direction.Plane.HORIZONTAL) {
            for (Direction b : Direction.Plane.HORIZONTAL) {
                if (a.getAxis() == b.getAxis() || a.ordinal() > b.ordinal()) continue;
                BlockPos diagonal = pos.relative(a).relative(b);
                if (canDiagonalMove(pos, a, b, diagonal)) addIfStandable(moves, diagonal, 1.55);
                if (allowClimb && canDiagonalMove(pos, a, b, diagonal.above())) {
                    addIfStandable(moves, diagonal.above(), this.jumpCost(2.0));
                }
                if (!goalHigher && canDiagonalMove(pos, a, b, diagonal.below())) {
                    addIfStandable(moves, diagonal.below(), 1.75);
                }
            }
        }
        BlockState current = this.level.getBlockState(pos);
        if (current.getBlock() instanceof LadderBlock) {
            addIfStandable(moves, pos.above(), this.jumpCost(0.8));
            if (!goalHigher) addIfStandable(moves, pos.below(), 0.8);
        }
        return moves;
    }

    /**
     * Fall from pad column (x,z) down to standable ground when chasing lower goal.
     * Log freeze: A3 y=-56 → player y=-60, only step.below (±1) → path null.
     */
    private void addDropMoves(List<Move> moves, BlockPos from, int x, int z, BlockPos goal) {
        int minY = goal != null ? goal.getY() - 1 : from.getY() - 6;
        minY = Math.max(minY, from.getY() - 8);
        for (int y = from.getY() - 1; y >= minY; y--) {
            BlockPos land = new BlockPos(x, y, z);
            if (!this.canStand(land)) continue;
            // Air above landing for body (fall column).
            boolean clear = true;
            for (int cy = y + 1; cy <= from.getY(); cy++) {
                BlockPos air = new BlockPos(x, cy, z);
                if (!this.isEmptyForBody(air)) {
                    clear = false;
                    break;
                }
            }
            if (!clear) continue;
            int drop = from.getY() - y;
            double cost = 0.6 + drop * 0.35;
            if (goal != null) {
                cost += land.distManhattan(goal) * 0.02;
            }
            moves.add(new Move(land, cost));
            break; // nearest ground only
        }
    }

    private void addParkourMoves(List<Move> moves, BlockPos pos, int dxSign, int dzSign) {
        int maxFwd = this.parkourMaxForward();
        if (maxFwd < 2) return;
        // Allow deeper drop hops when goal is below (leave A3).
        int minYTry = -1;
        if (this.baritoneTarget != null && this.baritoneTarget.getY() < pos.getY()) {
            minYTry = -4;
        }
        for (int forward = 2; forward <= maxFwd; forward++) {
            for (int yTry = minYTry; yTry <= 2; yTry++) {
                int dx = dxSign * forward;
                int dz = dzSign * forward;
                BlockPos candidate = pos.offset(dx, yTry, dz);
                BlockPos stand = this.canStand(candidate) ? candidate : this.normalizedStandPos(candidate);
                if (stand == null) continue;
                if (this.isPillarBanned(stand.getX(), stand.getZ())) continue;
                int yOffset = stand.getY() - pos.getY();
                if (yOffset < minYTry || yOffset > 2) continue;
                // Deep drops: only need land standable + head air at takeoff.
                if (yOffset >= -1) {
                    if (!this.canParkourJump(pos, stand, dxSign, dzSign, forward, yOffset)) continue;
                } else if (!this.isEmptyForBody(pos.above()) || !this.canStand(stand)) {
                    continue;
                }
                // Same-level pad hop cheapest; climbing also cheap; drop slightly costlier.
                double climbBonus = yOffset == 0 ? -0.25 : (yOffset > 0 ? -0.4 : 0.15 * (-yOffset));
                double cost = this.jumpCost(0.45 + forward * 0.15 + Math.max(0, yOffset) * 0.06 + climbBonus);
                moves.add(new Move(stand, Math.max(0.35, cost)));
            }
        }
        int diagFwdMax = Math.min(4, maxFwd);
        for (int forward = 2; forward <= diagFwdMax; forward++) {
            for (int side = -1; side <= 1; side += 2) {
                for (int yTry = 0; yTry <= 2; yTry++) {
                    int dx = dxSign * forward + dzSign * side;
                    int dz = dzSign * forward + dxSign * side;
                    BlockPos candidate = pos.offset(dx, yTry, dz);
                    BlockPos stand = this.canStand(candidate) ? candidate : this.normalizedStandPos(candidate);
                    if (stand == null) continue;
                    int yOffset = stand.getY() - pos.getY();
                    if (yOffset < 0 || yOffset > 2) continue;
                    if (!this.canParkourJumpLoose(pos, stand)) continue;
                    double cost = this.jumpCost(0.65 + forward * 0.2 + 0.1 + Math.max(0, yOffset) * 0.08);
                    moves.add(new Move(stand, Math.max(0.45, cost)));
                }
            }
        }
    }

    private boolean canParkourJump(BlockPos from, BlockPos to, int dxSign, int dzSign, int forward, int yOffset) {
        if (!this.canStand(to)) return false;
        // Head clearance at takeoff (only need 1 block if short flat hop).
        if (yOffset > 0 && !this.isEmptyForBody(from.above(2))) return false;
        if (yOffset <= 0 && !this.isEmptyForBody(from.above(1))) return false;

        BlockPos adj = from.offset(dxSign, 0, dzSign);
        // Flat hop: must have a real gap (not walkable next block).
        if (yOffset == 0 && this.canStand(adj)) return false;
        // Flight corridor: body air at takeoff Y along the gap (not through solid pillar face).
        // For ascend onto pillar, adj may be solid at ground — require air at from.y+1 and above.
        if (yOffset > 0) {
            if (!this.isEmptyForBody(adj.above()) || !this.isEmptyForBody(adj.above(2))) return false;
        } else {
            if (!this.isEmptyForBody(adj) || !this.isEmptyForBody(adj.above())) return false;
        }

        for (int i = 1; i < forward; i++) {
            int cx = from.getX() + dxSign * i;
            int cz = from.getZ() + dzSign * i;
            if (yOffset > 0) {
                // Jumping up: clear path above ground / around pillar shaft at body mid height.
                BlockPos mid = new BlockPos(cx, from.getY() + 1, cz);
                if (!this.isEmptyForBody(mid) || !this.isEmptyForBody(mid.above())) return false;
            } else {
                BlockPos mid = new BlockPos(cx, from.getY(), cz);
                if (!this.isEmptyForBody(mid) || !this.isEmptyForBody(mid.above())) return false;
            }
        }
        return this.isEmptyForBody(to) && this.isEmptyForBody(to.above());
    }

    private boolean canParkourJumpLoose(BlockPos from, BlockPos to) {
        if (!this.canStand(to)) return false;
        if (!this.isEmptyForBody(from.above(2))) return false;
        if (!this.isEmptyForBody(to) || !this.isEmptyForBody(to.above())) return false;
        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();
        int dz = to.getZ() - from.getZ();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        if (steps < 2) return false;
        for (int i = 1; i < steps; i++) {
            double t = i / (double) steps;
            int x = Mth.floor(Mth.lerp(t, from.getX() + 0.5, to.getX() + 0.5));
            int z = Mth.floor(Mth.lerp(t, from.getZ() + 0.5, to.getZ() + 0.5));
            // Sample along max of from/to Y so ascending pads don't fail on their own solid.
            int y = Mth.floor(Mth.lerp(t, from.getY(), to.getY())) + (dy > 0 ? 1 : 0);
            BlockPos p = new BlockPos(x, y, z);
            if (!this.isEmptyForBody(p) || !this.isEmptyForBody(p.above())) return false;
        }
        return true;
    }

    private boolean canDiagonalMove(BlockPos from, Direction a, Direction b, BlockPos to) {
        if (!canStand(to)) return false;
        BlockPos sideA = from.relative(a);
        BlockPos sideB = from.relative(b);
        return (isEmptyForBody(sideA) || canStand(sideA) || canStand(sideA.above()))
            && (isEmptyForBody(sideB) || canStand(sideB) || canStand(sideB.above()))
            && hasAirLine(from, to);
    }

    private boolean hasAirLine(BlockPos from, BlockPos to) {
        int steps = Math.max(Math.abs(to.getX() - from.getX()), Math.abs(to.getZ() - from.getZ()));
        for (int i = 1; i < steps; i++) {
            double t = i / (double) steps;
            int x = Mth.floor(Mth.lerp(t, from.getX(), to.getX()));
            int z = Mth.floor(Mth.lerp(t, from.getZ(), to.getZ()));
            int y = Mth.floor(Mth.lerp(t, from.getY(), to.getY()));
            BlockPos p = new BlockPos(x, y, z);
            if (!isEmptyForBody(p) || !isEmptyForBody(p.above())) {
                return false;
            }
        }
        return true;
    }

    private double jumpCost(double base) {
        return base * (1.35 - 0.7 * (this.jumpTendency() / 100.0));
    }

    private void addIfStandable(List<Move> moves, BlockPos pos, double cost) {
        BlockPos stand = normalizedStandPos(pos);
        if (stand != null) {
            moves.add(new Move(stand, cost));
        }
    }

    private BlockPos normalizedStandPos(BlockPos pos) {
        if (canStand(pos)) return pos;
        if (canStandOnBlock(pos)) return pos.above();
        return null;
    }

    private boolean canStand(BlockPos feet) {
        BlockState feetState = this.level.getBlockState(feet);
        ResourceLocation feetId = ForgeRegistries.BLOCKS.getKey(feetState.getBlock());
        if (feetId != null && NpcPathfindingConfig.isBlockBlacklisted(feetId)) return false;
        if (feetState.getBlock() instanceof LadderBlock) return true;
        if (!isEmptyForBody(feet) || !isEmptyForBody(feet.above())) return false;

        BlockState below = this.level.getBlockState(feet.below());
        ResourceLocation belowId = ForgeRegistries.BLOCKS.getKey(below.getBlock());
        if (belowId != null && NpcPathfindingConfig.isBlockBlacklisted(belowId)) return false;
        if (belowId != null && NpcPathfindingConfig.isBlockWhitelisted(belowId)) return true;
        return below.isFaceSturdy(this.level, feet.below(), Direction.UP);
    }

    private boolean isEmptyForBody(BlockPos pos) {
        BlockState state = this.level.getBlockState(pos);
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (id != null && NpcPathfindingConfig.isBlockBlacklisted(id)) return false;
        if (id != null && NpcPathfindingConfig.isBlockWhitelisted(id)) return true;
        return state.isAir() || state.getBlock() instanceof LadderBlock || state.canBeReplaced();
    }

    private boolean tryJump() {
        if (this.jumpCooldown > 0) return false;
        if (!this.mob.onGround() && !this.mob.onClimbable()) return false;
        this.jumpCooldown = 8;
        this.mob.getJumpControl().jump();
        return true;
    }

    private BlockPos resolveTarget(BlockPos requested) {
        if (canStandOnBlock(requested)) {
            return requested.above();
        }
        if (canStand(requested)) {
            return requested;
        }
        // Prefer stand near requested Y (platform). Heavily punish ground under high targets.
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int y = 3; y >= -2; y--) {
            for (int radius = 0; radius <= 4; radius++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (Math.abs(dx) != radius && Math.abs(dz) != radius && radius > 0) continue;
                        BlockPos candidate = requested.offset(dx, y, dz);
                        if (!canStand(candidate)) continue;
                        int dy = candidate.getY() - requested.getY();
                        double score = (dx * dx + dz * dz) + Math.abs(dy) * 8.0;
                        if (dy < 0) score += (-dy) * 40.0; // do NOT snap elevated chase to ground
                        if (score < bestScore) {
                            bestScore = score;
                            best = candidate;
                        }
                    }
                }
            }
        }
        return best != null ? best : requested;
    }

    private boolean canStandOnBlock(BlockPos blockPos) {
        BlockState state = this.level.getBlockState(blockPos);
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (id != null && NpcPathfindingConfig.isBlockBlacklisted(id)) return false;
        if (!state.isFaceSturdy(this.level, blockPos, Direction.UP)) return false;
        return isEmptyForBody(blockPos.above()) && isEmptyForBody(blockPos.above(2));
    }

    private double heuristic(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        double dy = a.getY() - b.getY();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        double yCost = dy < 0 ? (-dy) * 1.8 : dy * 1.1;
        // Discourage paths that aim under elevated goals (pillar base).
        if (dy < -1 && horiz < 2.0) {
            yCost += 8.0;
        }
        return horiz + yCost;
    }

    private record SearchNode(BlockPos pos, SearchNode parent, double g, double f) {}
    private record Move(BlockPos to, double cost) {}

    static class BaritoneNodeEvaluator extends WalkNodeEvaluator {
        @Override
        public BlockPathTypes getBlockPathType(BlockGetter level, int x, int y, int z, Mob mob) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);

            ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock());
            if (blockId != null) {
                if (NpcPathfindingConfig.isBlockBlacklisted(blockId)) {
                    return BlockPathTypes.BLOCKED;
                }
                if (NpcPathfindingConfig.isBlockWhitelisted(blockId)) {
                    return BlockPathTypes.WALKABLE;
                }
            }

            if (state.getBlock() instanceof LadderBlock) {
                return BlockPathTypes.WALKABLE;
            }

            return super.getBlockPathType(level, x, y, z, mob);
        }
    }
}
