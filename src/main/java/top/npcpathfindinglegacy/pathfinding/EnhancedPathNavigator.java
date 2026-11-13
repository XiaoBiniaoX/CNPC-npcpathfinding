package top.npcpathfindinglegacy.pathfinding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import noppes.npcs.entity.EntityNPCInterface;
import top.npcpathfindinglegacy.NpcPathfindingConfig;
import top.npcpathfindinglegacy.NpcPathfindingLegacy;
import top.npcpathfindinglegacy.pathfinding.PlannedRoute.Action;
import top.npcpathfindinglegacy.pathfinding.PlannedRoute.Edge;
import top.npcpathfindinglegacy.tactics.TacticsRuntime;

/** Server-thread coordinator: requests, snapshots, result validation and route following. */
public final class EnhancedPathNavigator extends GroundPathNavigation {
    private final EntityNPCInterface npc;
    private final TerrainSampler terrain;
    private final LegacyMovementController legacyMovement;
    private final WallMovementController wallMovement;
    private final GroundMovementController groundMovement;
    private final ParkourRecovery parkourRecovery = new ParkourRecovery();
    private final Map<Edge, Long> failedEdges = new HashMap<>();
    private boolean wallMode;
    private int mode = 1;
    private boolean waitingForLanding;
    private BlockPos requestedTarget;
    private Entity chaseEntity;
    private PlannedRoute activeRoute;
    private BlockPos pendingTarget;
    private BlockPos pendingFinalTarget, readyFinalTarget, activeFinalTarget;
    private boolean pendingPrefetch, readyPrefetch;
    private GridPos pendingStart;
    private int pendingAccuracy;
    private int pendingTieSeed;
    private RoutePlanner.Settings pendingSettings;
    private CombatTactics pendingTactics, readyTactics, activeTactics;
    private Set<Edge> pendingFailures;
    private TerrainSampler.Builder snapshot;
    private Future<PlannedRoute> calculation;
    private PlannedRoute readyRoute;
    private long readyTick, workStartedTick;
    private int retryTicks, failedSearches, stuckTicks, recoveryTicks;
    private Vec3 lastProgressPosition;
    private Vec3 recoveryMotion = Vec3.ZERO;

    public EnhancedPathNavigator(EntityNPCInterface npc, Level level) {
        super(npc, level);
        this.npc = npc;
        terrain = new TerrainSampler(npc, level);
        legacyMovement = new LegacyMovementController(this, npc);
        wallMovement = new WallMovementController(this, npc, terrain);
        groundMovement = new GroundMovementController(this, npc);
        lastProgressPosition = npc.position();
        refreshCapabilities();
    }

    public void refreshCapabilities() {
        int newMode = npc.ais instanceof IDataAIPathfinding data ? data.npcpathfindinglegacy$getMode() : 1;
        if (mode != newMode) { stop(); mode = newMode; }
        wallMode = mode == 2;
        setCanFloat(npc.ais != null && npc.ais.canSwim);
        setCanOpenDoors(npc.ais != null && (npc.ais.doorInteract == 0 || npc.ais.doorInteract == 1));
    }

    public boolean wallMode() { return wallMode; }
    public boolean legacyMode() { return mode == 1; }
    public boolean combatMode() { return mode == 4; }
    public boolean controlledMode() { return legacyMode() || combatMode(); }
    public boolean controlsJump() { return waitingForLanding || wallMode || (controlledMode() && legacyMovement.busy()); }
    public boolean maySprint() {
        boolean allowed = !wallMode && ((mode != 3 && mode < 5) || ((IDataAIPathfinding) npc.ais).npcpathfindinglegacy$getAllowSprint());
        return allowed && requestedTarget != null && path != null && !path.isDone() && !waitingForLanding;
    }
    double moveSpeed() { return speedModifier; }
    Entity chaseEntity() { return chaseEntity; }
    BlockPos currentFeetPos() { return terrain.feetPosition(); }
    double standY(BlockPos pos) { return terrain.standY(pos); }
    boolean canStand(BlockPos pos) { return terrain.standable(pos, !wallMode); }
    boolean isClimbable(BlockPos pos) { return terrain.ladder(pos); }

    private boolean serverThread() {
        return !level.isClientSide && level.getServer() != null && level.getServer().isSameThread();
    }

    @Override
    public Path createPath(BlockPos target, int accuracy) {
        if (!serverThread() || target == null || !terrain.loaded(target)) return null;
        BlockPos goal = terrain.resolveTarget(target, wallMode);
        if (TacticsRuntime.redirects(npc)) return new DeferredPath(goal, currentFeetPos());
        if (mode == 7 && activeRoute != null && path != null && !path.isDone() && sameGoal(activeFinalTarget, goal))
            return new DeferredPath(goal, currentFeetPos());
        BlockPos planningGoal = parkourRecovery.returning() ? planningTarget() : goal;
        if (!parkourRecovery.returning() && readyRoute != null
            && readyRoute.target().equals(grid(goal)) && readyIsUsable()) return toPath(readyRoute);
        prepare(planningGoal, accuracy);
        // CNPC goals expect a non-null query result. This token is never installed or followed.
        return new DeferredPath(goal, currentFeetPos());
    }

    @Override
    public Path createPath(Entity entity, int accuracy) {
        if (!serverThread() || entity == null || entity.isRemoved()) return null;
        Path result = createPath(predictTarget(entity), accuracy);
        return result == null ? null : new EntityQueryPath(result, entity);
    }

    @Override
    public Path createPath(BlockPos pos, int accuracy, int range) { return createPath(pos, accuracy); }

    @Override
    public Path createPath(Set<BlockPos> targets, int accuracy) { return createPath(targets.stream(), accuracy); }

    @Override
    public Path createPath(Stream<BlockPos> targets, int accuracy) {
        if (!serverThread()) return null;
        BlockPos feet = currentFeetPos();
        return targets.filter(terrain::loaded).min(java.util.Comparator.comparingDouble(feet::distSqr))
            .map(pos -> createPath(pos, accuracy)).orElse(null);
    }

    @Override
    protected Path createPath(Set<BlockPos> targets, int range, boolean offsetUp, int accuracy) {
        return createPath(targets, accuracy);
    }

    @Override
    protected Path createPath(Set<BlockPos> targets, int range, boolean offsetUp, int accuracy, float distance) {
        return createPath(targets, accuracy);
    }

    @Override
    public boolean moveTo(double x, double y, double z, double speed) {
        if (!serverThread() || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return false;
        if (TacticsRuntime.redirects(npc)) return true;
        chaseEntity = null;
        return requestMove(BlockPos.containing(x, y, z), speed);
    }

    @Override
    public boolean moveTo(Entity entity, double speed) {
        if (!serverThread() || entity == null || entity.isRemoved()) return false;
        if (TacticsRuntime.redirects(npc)) return true;
        chaseEntity = entity;
        return requestMove(predictTarget(entity), speed);
    }

    @Override
    public boolean moveTo(Path supplied, double speed) {
        if (!serverThread()) return false;
        if (TacticsRuntime.redirects(npc)) return true;
        if (supplied == null) { stop(); return false; }
        // Replan externally supplied paths too, so all custom routes use the same thread boundary.
        chaseEntity = supplied instanceof EntityQueryPath query ? query.entity : null;
        return requestMove(supplied.getTarget(), speed);
    }

    @Override
    public void setSpeedModifier(double speed) { speedModifier = normalizeSpeed(speed); }

    private double normalizeSpeed(double speed) {
        double normalized = Double.isFinite(speed) ? Mth.clamp(speed, 0.05, 4.0) : 1.0;
        boolean walkingOnly = wallMode || ((mode == 3 || mode >= 5) && !((IDataAIPathfinding) npc.ais).npcpathfindinglegacy$getAllowSprint());
        return walkingOnly ? Math.min(1.0, normalized) : normalized;
    }

    private boolean requestMove(BlockPos target, double speed) {
        if (!serverThread() || target == null || !terrain.loaded(target)) return false;
        BlockPos goal = terrain.resolveTarget(target, wallMode);
        boolean changed = requestedTarget == null || requestedTarget.distSqr(goal) > 4;
        requestedTarget = goal;
        speedModifier = normalizeSpeed(speed);
        if (combatMode() && holdFiringPosition()) return true;
        if (changed) { failedSearches = 0; retryTicks = 0; }
        if (hasArrived()) { finishNavigation(); return true; }
        if (controlledMode() && legacyMovement.busy()) return true;
        goal = planningTarget();
        if (activeRoute != null && path != null && !path.isDone()
            && (mode == 7 ? sameGoal(activeFinalTarget, goal) : activeRoute.target().distanceSquared(grid(goal)) <= 4)
            && tacticsCompatible(activeTactics, combatTactics())) { prefetchSegment(); return true; }
        if (readyRoute != null && (mode == 7 ? sameGoal(readyFinalTarget, goal) : readyRoute.target().equals(grid(goal))) && readyIsUsable()) {
            install(readyRoute); readyRoute = null; return true;
        }
        prepare(goal, 0);
        return true;
    }

    private RoutePlanner.Settings settings() {
        IDataAIPathfinding data = (IDataAIPathfinding) npc.ais;
        return new RoutePlanner.Settings(mode, data.npcpathfindinglegacy$getWalkTendency(),
            data.npcpathfindinglegacy$getJumpTendency(), data.npcpathfindinglegacy$getParkourMaxDist(),
            NpcPathfindingConfig.maxDropHeight, NpcPathfindingConfig.maxSearchNodes);
    }

    private void prepare(BlockPos target, int accuracy) {
        prepareFrom(currentFeetPos(), target, accuracy, false);
    }

    private static boolean sameGoal(BlockPos previous, BlockPos current) {
        return previous != null && previous.distSqr(current) <= 4;
    }

    private void prefetchSegment() {
        if (mode != 7 || activeRoute == null || requestedTarget == null || readyRoute != null
            || !sameGoal(activeFinalTarget, requestedTarget)) return;
        GridPos end = activeRoute.steps().get(activeRoute.steps().size() - 1).position();
        if (!end.equals(grid(requestedTarget))) prepareFrom(block(end), requestedTarget, 0, true);
    }

    private void prepareFrom(BlockPos start, BlockPos target, int accuracy, boolean prefetch) {
        if (!serverThread() || retryTicks > 0 || waitingForLanding) return;
        RoutePlanner.Settings current = settings();
        CombatTactics currentTactics = combatTactics();
        // Tactical decisions update frequently. Finish one frozen generation before sampling the next.
        if (pendingTarget != null && !pendingPrefetch && !prefetch && TacticsRuntime.active(npc)
            && current.equals(pendingSettings) && tacticsCompatible(pendingTactics,currentTactics)) return;
        if (pendingTarget != null && sameGoal(pendingFinalTarget, target) && current.equals(pendingSettings)
            && (pendingPrefetch == prefetch || pendingStart.equals(grid(start)))
            && (!prefetch || pendingStart.equals(grid(start)))
            && tacticsCompatible(pendingTactics, currentTactics)) return;
        cancelWork();
        readyRoute = null;
        if (!PathComputationBudget.tryAcquire(level) || !AsyncPathService.beginSnapshot()) { retryTicks = 2; return; }
        try {
            pendingTarget = mode == 7 ? terrain.resolveTarget(block(SegmentedPlanning.nextGoal(
                grid(start), grid(target))), false) : target.immutable();
            pendingFinalTarget = target.immutable(); pendingPrefetch = prefetch;
            pendingStart = grid(start);
            pendingAccuracy = Math.max(0, Math.min(4, accuracy));
            // Vary only equal-cost choices during combat/chase. Coordinate patrols keep their stable route semantics.
            pendingTieSeed = chaseEntity != null || TacticsRuntime.active(npc) ? npc.getRandom().nextInt() : 0;
            pendingSettings = current;
            pendingTactics = currentTactics;
            failedEdges.entrySet().removeIf(entry -> entry.getValue() <= level.getGameTime());
            pendingFailures = Set.copyOf(failedEdges.keySet());
            snapshot = terrain.begin(start, pendingTarget);
            workStartedTick = level.getGameTime();
        } catch (RuntimeException error) {
            AsyncPathService.endSnapshot(); pendingTarget = null; retryTicks = 20;
            NpcPathfindingLegacy.LOG.warn("Cannot capture NPC terrain", error);
        }
    }

    private void pollWork() {
        if (pendingTarget == null) return;
        if (!settings().equals(pendingSettings) || !tacticsCompatible(pendingTactics, combatTactics())
            || (mode == 7 && requestedTarget != null && !sameGoal(pendingFinalTarget, requestedTarget)
                && (!TacticsRuntime.active(npc) || pendingPrefetch))
            || level.getGameTime() - workStartedTick > (snapshot != null ? 800 : 200)) {
            cancelWork(); retryTicks = 2; return;
        }
        if (snapshot != null) {
            int count = PathComputationBudget.acquireSnapshotBlocks(level);
            if (count == 0) return;
            try {
                if (!snapshot.capture(count)) return;
                TerrainSnapshot input = snapshot.freeze();
                snapshot = null;
                AsyncPathService.endSnapshot();
                calculation = AsyncPathService.submit(input, pendingSettings, pendingStart,
                    grid(pendingTarget), pendingAccuracy, pendingFailures, pendingTactics,pendingTieSeed);
                if (calculation == null) { cancelWork(); retryTicks = 4; }
            } catch (RuntimeException error) {
                cancelWork(); retryTicks = 20;
                NpcPathfindingLegacy.LOG.warn("Cannot sample NPC terrain", error);
            }
            return;
        }
        if (calculation == null || !calculation.isDone()) return;
        try {
            PlannedRoute result = calculation.get();
            if (result != null && result.steps().size() >= 2) {
                readyRoute = result; readyTactics = pendingTactics; readyTick = level.getGameTime();
                readyFinalTarget = pendingFinalTarget; readyPrefetch = pendingPrefetch;
            } else { failedSearches++; retryTicks = Math.min(60, 6 + failedSearches * 4); }
        } catch (CancellationException ignored) {
            retryTicks = 2;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); retryTicks = 20;
        } catch (ExecutionException error) {
            failedSearches++; retryTicks = 20;
            NpcPathfindingLegacy.LOG.warn("NPC route calculation failed", error.getCause());
        } finally { calculation = null; pendingTarget = null; pendingFinalTarget = null; pendingFailures = null; }
    }

    private boolean readyIsUsable() {
        return readyRoute != null && level.getGameTime() - readyTick <= (readyPrefetch ? 200 : 40)
            && readyRoute.steps().get(0).position().equals(grid(currentFeetPos()))
            && tacticsCompatible(readyTactics, combatTactics())
            && routeIsValid(readyRoute);
    }

    private boolean routeIsValid(PlannedRoute route) {
        for (PlannedRoute.Step step : route.steps()) {
            BlockPos pos = block(step.position());
            boolean valid = step.action() == Action.WALL
                ? terrain.bodyClear(pos) && (terrain.wallDirection(pos) != null || terrain.standable(pos, false))
                : terrain.standable(pos, !wallMode);
            if (!valid) return false;
        }
        return true;
    }

    private void install(PlannedRoute route) {
        path = toPath(route); activeRoute = route; failedSearches = 0; stuckTicks = 0;
        activeTactics = readyTactics;
        activeFinalTarget = readyFinalTarget;
        lastProgressPosition = npc.position();
    }

    private BlockPos planningTarget() {
        return block(parkourRecovery.target(grid(requestedTarget)));
    }

    private void finishReturn() {
        parkourRecovery.returned();
        path = null; activeRoute = null; readyRoute = null;
        cancelWork(); legacyMovement.reset();
        retryTicks = 0; failedSearches = 0; stuckTicks = 0;
        haltMovement();
    }

    @Override
    public void tick() {
        if (!serverThread()) return;
        if (!npc.isAlive() || npc.isRemoved()) { stop(); return; }
        tick++;
        if (retryTicks > 0) retryTicks--;
        pollWork();
        if (requestedTarget == null) return;
        if (chaseEntity != null) {
            if (chaseEntity.isRemoved() || !chaseEntity.isAlive()) { stop(); return; }
            requestedTarget = terrain.resolveTarget(predictTarget(chaseEntity), wallMode);
        }
        if (combatMode() && holdFiringPosition()) return;
        if (hasArrived()) { finishNavigation(); return; }
        if (controlledMode() && legacyMovement.tick()) return;
        if (waitingForLanding) {
            if (!npc.onGround()) { haltMovement(); return; }
            waitingForLanding = false;
        }
        if (parkourRecovery.returning() && arrivedAt(planningTarget(), 0.1)) finishReturn();
        if (recoveryTicks > 0) { applyRecovery(); return; }
        if (readyRoute != null) {
            boolean targetMatches = mode == 7 ? sameGoal(readyFinalTarget, planningTarget())
                : readyRoute.target().distanceSquared(grid(planningTarget())) <= 4;
            if ((targetMatches || TacticsRuntime.active(npc) && !readyPrefetch) && readyIsUsable() && (!readyPrefetch || path == null || path.isDone())) {
                install(readyRoute); readyRoute = null;
            } else if (!targetMatches || level.getGameTime() - readyTick > (readyPrefetch ? 200 : 40)
                || !readyPrefetch) readyRoute = null;
        }
        if (path == null || path.isDone()) {
            if (failedSearches >= 8 && retryTicks == 0) { finishNavigation(); return; }
            activeRoute = null; prepare(planningTarget(), 0); haltMovement(); return;
        }
        if (!nextStepValid()) { recoverJump(null, null); return; }
        prefetchSegment();
        followRoute();
        if (path == null || path.isDone()) return;
        if (wallMode) wallMovement.follow();
        else if (mode == 3 || mode >= 5) groundMovement.follow();
        else if (combatMode() && nextAction() != Action.PARKOUR && nextAction() != Action.STEP) groundMovement.follow();
        else {
            if (combatMode()) groundMovement.reset();
            Vec3 next = nextPosition();
            npc.getMoveControl().setWantedPosition(next.x, next.y, next.z, speedModifier);
            legacyMovement.applyEnhancedMovement();
        }
        trackProgress();
    }

    private boolean nextStepValid() {
        BlockPos pos = path.getNextNodePos();
        if (mode == 5 && nextAction() == Action.WALK && pos.distSqr(currentFeetPos()) > 2
            && !terrain.groundLineClear(npc.position(), nextPosition())) return false;
        return nextAction() == Action.WALL ? terrain.bodyClear(pos)
            && (terrain.wallDirection(pos) != null || terrain.standable(pos, false)) : terrain.standable(pos, !wallMode);
    }

    private void followRoute() {
        while (path != null && !path.isDone()) {
            Vec3 next = nextPosition();
            double dx = npc.getX() - next.x, dz = npc.getZ() - next.z;
            boolean returningToTakeoff = parkourRecovery.returning() && path.getNextNodePos().equals(planningTarget());
            double radiusSquared = returningToTakeoff ? 0.01 : 0.09;
            if (dx * dx + dz * dz > radiusSquared || Math.abs(npc.getBoundingBox().minY - next.y) > 0.35) return;
            if (!wallMode && nextAction() == Action.PARKOUR && !npc.onGround()) return;
            path.advance();
        }
        wallMovement.reset();
        if (activeRoute != null && activeRoute.reached()
            && activeRoute.target().equals(grid(planningTarget()))) {
            if (parkourRecovery.returning()) finishReturn();
            else finishNavigation();
        }
    }

    Vec3 nextPosition() {
        BlockPos pos = path.getNextNodePos();
        return new Vec3(pos.getX() + 0.5, nextAction() == Action.WALL ? pos.getY() : standY(pos), pos.getZ() + 0.5);
    }

    Action nextAction() {
        int index = path == null ? -1 : path.getNextNodeIndex();
        return activeRoute != null && index >= 0 && index < activeRoute.steps().size()
            ? activeRoute.steps().get(index).action() : Action.WALK;
    }

    @Override
    public BlockPos getTargetPos() { return requestedTarget != null ? planningTarget() : pendingTarget; }

    @Override
    public void recomputePath() {
        if (!serverThread() || requestedTarget == null) return;
        cancelWork(); readyRoute = null;
        if (!legacyMovement.busy()) { path = null; activeRoute = null; }
        retryTicks = 0; prepare(planningTarget(), 0);
    }

    @Override
    public boolean isDone() { return requestedTarget == null && (path == null || path.isDone()); }

    private boolean hasArrived() {
        if (requestedTarget == null || (controlledMode() && legacyMovement.busy())
            || (!npc.onGround() && !npc.isInWater())) return false;
        // Landing/centering must finish before an arrival can release the movement lock.
        return arrivedAt(requestedTarget, controlledMode() ? 0.1 : 0.8);
    }

    private boolean arrivedAt(BlockPos target, double radius) {
        if (!npc.onGround() && !npc.isInWater()) return false;
        double dx = npc.getX() - target.getX() - 0.5, dz = npc.getZ() - target.getZ() - 0.5;
        return dx * dx + dz * dz <= radius * radius && Math.abs(npc.getBoundingBox().minY - standY(target)) < 0.35;
    }

    private void finishNavigation() { stop(); }

    @Override
    public void stop() {
        super.stop(); requestedTarget = null; chaseEntity = null; activeRoute = null; readyRoute = null;
        pendingFinalTarget = null; readyFinalTarget = null; activeFinalTarget = null;
        activeTactics = null; readyTactics = null;
        cancelWork(); retryTicks = 0; failedSearches = 0; stuckTicks = 0; recoveryTicks = 0; failedEdges.clear();
        waitingForLanding = false;
        parkourRecovery.reset();
        if (legacyMovement != null) legacyMovement.reset();
        if (wallMovement != null) wallMovement.reset();
        if (groundMovement != null) groundMovement.reset();
        if (npc != null && !level.isClientSide) haltMovement();
    }

    private void cancelWork() {
        if (snapshot != null) { snapshot = null; AsyncPathService.endSnapshot(); }
        AsyncPathService.cancel(calculation); calculation = null; pendingTarget = null; pendingFinalTarget = null; pendingFailures = null;
    }

    void recoverJump(BlockPos takeoff, BlockPos landing) {
        if (takeoff != null && landing != null) {
            parkourRecovery.failed(grid(takeoff));
            if (failedEdges.size() >= 32) failedEdges.clear();
            failedEdges.put(new Edge(grid(takeoff), grid(landing)), level.getGameTime() + 80);
        }
        cancelWork(); readyRoute = null; path = null; activeRoute = null;
        legacyMovement.reset(); wallMovement.reset(); groundMovement.reset(); stuckTicks = 0; retryTicks = 2;
        haltMovement();
        npc.setDeltaMovement(0, npc.getDeltaMovement().y, 0);
        // Recovery never pushes an airborne NPC into a column; retries use its actual landing position.
        waitingForLanding = !npc.onGround();
        if (waitingForLanding || parkourRecovery.returning()) return;
        for (int[] d : TerrainSnapshot.HORIZONTAL) {
            BlockPos side = currentFeetPos().offset(d[0], 0, d[1]);
            if (!terrain.standable(side, false)) continue;
            recoveryMotion = new Vec3(d[0] * 0.12, 0, d[1] * 0.12); recoveryTicks = 5; break;
        }
    }

    private void applyRecovery() {
        recoveryTicks--; haltMovement();
        npc.setDeltaMovement(recoveryMotion.x, npc.getDeltaMovement().y, recoveryMotion.z); npc.hasImpulse = true;
        if (recoveryTicks == 0) { retryTicks = 0; lastProgressPosition = npc.position(); }
    }

    private void trackProgress() {
        if (controlledMode() && legacyMovement.busy()) return;
        if (path != null && !path.isDone() && terrain.interactableDoor(path.getNextNodePos())) return;
        if (npc.position().distanceToSqr(lastProgressPosition) >= 0.0036) {
            lastProgressPosition = npc.position(); stuckTicks = 0; return;
        }
        if (++stuckTicks >= NpcPathfindingConfig.stuckTicks) recoverJump(null, null);
    }

    void haltMovement() {
        npc.getMoveControl().setWantedPosition(npc.getX(), npc.getY(), npc.getZ(), 0);
        npc.setZza(0); npc.setXxa(0);
        npc.setJumping(false);
        npc.setSprinting(false);
    }

    public void applyPostAiMovementLock() {
        if (!serverThread()) return;
        if (wallMode) wallMovement.applyPostAiMovement();
        else if (mode == 3 || mode >= 5 || (combatMode() && !legacyMovement.busy())) groundMovement.applyPostAiMovement();
        else if (recoveryTicks > 0) {
            haltMovement(); npc.setDeltaMovement(recoveryMotion.x, npc.getDeltaMovement().y, recoveryMotion.z);
            npc.hasImpulse = true;
        } else legacyMovement.applyPostAiMovementLock();
        if (!maySprint()) npc.setSprinting(false);
    }

    private BlockPos predictTarget(Entity entity) {
        BlockPos feet = terrain.feetPosition(entity);
        Vec3 lead = entity.getDeltaMovement().scale(4);
        double horizontal = Math.sqrt(lead.x * lead.x + lead.z * lead.z);
        double scale = horizontal > 4 ? 4 / horizontal : 1;
        BlockPos predicted = BlockPos.containing(entity.getX() + lead.x * scale, feet.getY(),
            entity.getZ() + lead.z * scale);
        // A grounded target stays on its actual platform when prediction runs beyond the edge.
        return entity.onGround() && (!terrain.standable(predicted, !wallMode)
            || Math.abs(standY(predicted) - entity.getBoundingBox().minY) > 0.35) ? feet : predicted;
    }

    private CombatTactics combatTactics() {
        if (!combatMode() || parkourRecovery.returning() || TacticsRuntime.active(npc)) return null;
        Entity threat = npc.getTarget();
        if (threat == null || !threat.isAlive() || threat.isRemoved()) return null;
        boolean retreat = npc.ais.onAttack == 1 || npc.ais.onAttack == 2
            ;
        if (retreat) return new CombatTactics(grid(terrain.feetPosition(threat)), true, 0, 0);
        if (npc.ais.onAttack != 0 || chaseEntity != threat || npc.inventory.getProjectile() == null) return null;
        double maxRange = Math.min(32, npc.stats.ranged.getRange()) * 0.9;
        double minRange = Math.min(maxRange * 0.6, Math.max(2, npc.stats.ranged.getMeleeRange() + 1));
        return maxRange <= 1 ? null : new CombatTactics(grid(terrain.feetPosition(threat)), false, minRange, maxRange);
    }

    private static boolean tacticsCompatible(CombatTactics previous, CombatTactics current) {
        if (previous == null || current == null) return previous == current;
        return previous.retreat() == current.retreat() && previous.minimumRange() == current.minimumRange()
            && previous.maximumRange() == current.maximumRange()
            && previous.threat().distanceSquared(current.threat()) <= 4;
    }

    private boolean holdFiringPosition() {
        if (!npc.onGround() || legacyMovement.busy() || parkourRecovery.returning()) return false;
        CombatTactics tactics = combatTactics();
        if (tactics == null || !tactics.ranged()) return false;
        double distance = Math.sqrt(npc.distanceToSqr(chaseEntity));
        if (distance < tactics.minimumRange() || distance > tactics.maximumRange()
            || !npc.getSensing().hasLineOfSight(chaseEntity)) return false;
        cancelWork(); readyRoute = null; path = null; activeRoute = null;
        haltMovement(); npc.setDeltaMovement(0, npc.getDeltaMovement().y, 0);
        return true;
    }

    private static GridPos grid(BlockPos pos) { return new GridPos(pos.getX(), pos.getY(), pos.getZ()); }
    private static BlockPos block(GridPos pos) { return new BlockPos(pos.x(), pos.y(), pos.z()); }

    private static Path toPath(PlannedRoute route) {
        ArrayList<Node> nodes = new ArrayList<>();
        for (PlannedRoute.Step step : route.steps()) {
            GridPos pos = step.position(); nodes.add(new Node(pos.x(), pos.y(), pos.z()));
        }
        return new Path(nodes, block(route.target()), route.reached());
    }

    private static final class DeferredPath extends Path {
        DeferredPath(BlockPos target, BlockPos start) {
            super(java.util.List.of(new Node(start.getX(), start.getY(), start.getZ())), target, false);
        }
    }

    private static final class EntityQueryPath extends Path {
        final Entity entity;
        EntityQueryPath(Path query, Entity entity) {
            super(copyNodes(query), query.getTarget(), query.canReach());
            this.entity = entity;
        }

        private static ArrayList<Node> copyNodes(Path query) {
            ArrayList<Node> nodes = new ArrayList<>(query.getNodeCount());
            for (int i = 0; i < query.getNodeCount(); i++) nodes.add(query.getNode(i));
            return nodes;
        }
    }
}
