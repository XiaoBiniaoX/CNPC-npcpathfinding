package top.npcpathfindinglegacy.pathfinding;

import java.util.Arrays;
import java.util.Set;
import top.npcpathfindinglegacy.pathfinding.PlannedRoute.Action;
import top.npcpathfindinglegacy.pathfinding.PlannedRoute.Edge;

/** Small deterministic checks that run without starting Minecraft or loading a world. */
public final class PathfindingSelfCheck {
    private static final int SOLID = TerrainSnapshot.SUPPORT | TerrainSnapshot.WALL_NORTH
        | TerrainSnapshot.WALL_SOUTH | TerrainSnapshot.WALL_WEST | TerrainSnapshot.WALL_EAST;
    private static final RoutePlanner.Settings LEGACY = new RoutePlanner.Settings(1, 50, 50, 4, 8, 5000);
    private static final RoutePlanner.Settings WALL = new RoutePlanner.Settings(2, 50, 50, 4, 8, 5000);
    private static final RoutePlanner.Settings OPTIMAL = new RoutePlanner.Settings(3, 50, 50, 4, 8, 5000);
    private static final RoutePlanner.Settings COMBAT = new RoutePlanner.Settings(4, 50, 50, 4, 8, 5000);

    public static void main(String[] args) {
        DefaultPathfindingSelfCheck.run();
        MajorUpdateSelfCheck.run();
        top.npcpathfindinglegacy.traffic.TrafficSelfCheck.run();
        for (int tendency = 0; tendency <= 100; tendency++) {
            for (int rise = 0; rise <= 1; rise++) {
                double velocity = JumpTrajectoryPlanner.parkourVelocity(tendency, rise);
                require(JumpTrajectoryPlanner.apexHeight(velocity) < 2,
                    "Actual legacy jump apex must stay below two blocks at every tendency");
                int flight = JumpTrajectoryPlanner.estimateFlightTicks(0, velocity, rise);
                double y = 0, motion = velocity;
                for (int tick = 1; tick <= flight; tick++) {
                    y += motion;
                    if (tick < flight) require(y > rise || motion > 0,
                        "Flight time must include the actual descending landing tick");
                    motion = (motion - 0.08) * 0.98;
                }
                require(y <= rise && motion < 0, "Flight must end when descending through the platform surface");
            }
        }
        ParkourRecovery recovery = new ParkourRecovery();
        GridPos takeoff = new GridPos(0, 3, 0), player = new GridPos(6, 3, 0);
        recovery.failed(takeoff);
        require(recovery.target(player).equals(takeoff), "Failed parkour must return to takeoff first");
        GridPos movedPlayer = player.offset(2, 0, 0);
        recovery.failed(new GridPos(2, 1, 0));
        require(recovery.target(movedPlayer).equals(takeoff), "Chase updates and return failures must keep the takeoff target");
        recovery.returned();
        require(recovery.target(movedPlayer).equals(movedPlayer), "After returning, resume the latest chase target");
        Fixture ledge = new Fixture(8, 9, 1);
        ledge.floor();
        for (int x = 1; x < 8; x++) { ledge.set(x, 1, 0, SOLID); ledge.set(x, 2, 0, SOLID); }
        GridPos start = new GridPos(0, 1, 0), goal = new GridPos(4, 3, 0);
        PlannedRoute old = plan(ledge, LEGACY, start, goal, top.npcpathfindinglegacy.compat.Immutable.set());
        require(old == null || !old.reached(), "Legacy must not climb a two-block ledge");
        PlannedRoute climb = plan(ledge, WALL, start, goal, top.npcpathfindinglegacy.compat.Immutable.set());
        require(climb != null && climb.reached(), "Wall mode must reach a ledge without ladders");
        require(climb.steps().stream().anyMatch(s -> s.action() == Action.WALL), "Wall climbing must be represented");
        require(climb.steps().stream().noneMatch(s -> s.action() == Action.PARKOUR || s.action() == Action.LADDER),
            "Wall mode must not generate parkour or ladder transitions");
        PlannedRoute onWall = plan(ledge, WALL, new GridPos(0, 2, 0), goal, top.npcpathfindinglegacy.compat.Immutable.set());
        require(onWall != null && onWall.reached() && onWall.steps().get(0).action() == Action.WALL,
            "Replanning from mid-wall must keep the initial attachment");

        Fixture platforms = new Fixture(8, 9, 1);
        for (int x : new int[]{0, 3, 6}) platforms.set(x, 0, 0, SOLID);
        GridPos finish = new GridPos(6, 1, 0);
        PlannedRoute parkour = plan(platforms, LEGACY, start, finish, top.npcpathfindinglegacy.compat.Immutable.set());
        require(parkour != null && parkour.reached(), "Legacy must preserve chained parkour");
        require(parkour.steps().stream().anyMatch(s -> s.action() == Action.PARKOUR), "Gaps must be jumped");
        PlannedRoute retry = plan(platforms, LEGACY, start, finish, top.npcpathfindinglegacy.compat.Immutable.set(new Edge(start, new GridPos(3, 1, 0))));
        require(retry != null && retry.reached(), "A failed jump must remain retryable when it is the only route");
        PlannedRoute noJump = plan(platforms, WALL, start, finish, top.npcpathfindinglegacy.compat.Immutable.set());
        require(noJump == null || !noJump.reached(), "Wall mode must not cross an unsupported gap");
        PlannedRoute noParkour = plan(platforms, OPTIMAL, start, finish, top.npcpathfindinglegacy.compat.Immutable.set());
        require(noParkour == null || !noParkour.reached(), "Optimal ladder mode must not cross a parkour gap");
        PlannedRoute combatParkour = plan(platforms, COMBAT, start, finish, top.npcpathfindinglegacy.compat.Immutable.set());
        require(combatParkour != null && combatParkour.reached()
            && combatParkour.steps().stream().anyMatch(s -> s.action() == Action.PARKOUR),
            "Combat mode must chase over reachable gaps using controlled parkour");
        Fixture ascending = new Fixture(10, 10, 7);
        ascending.floor();
        int[][] pads = {{1, 2, 1}, {4, 3, 2}, {7, 4, 4}};
        for (int[] pad : pads) for (int y = 1; y < pad[1]; y++) ascending.set(pad[0], y, pad[2], SOLID);
        GridPos firstPad = new GridPos(1, 2, 1), lastPad = new GridPos(7, 4, 4);
        PlannedRoute raisedParkour = plan(ascending, LEGACY, firstPad, lastPad, top.npcpathfindinglegacy.compat.Immutable.set());
        require(raisedParkour != null && raisedParkour.reached(),
            "Legacy must chase onto offset, successively higher platforms above a lower floor");
        require(raisedParkour.steps().stream().filter(s -> s.action() == Action.PARKOUR).count() >= 2,
            "Ascending offset platforms must be connected with controlled jumps");
        PlannedRoute raisedOptimal = plan(ascending, OPTIMAL, firstPad, lastPad, top.npcpathfindinglegacy.compat.Immutable.set());
        require(raisedOptimal == null || !raisedOptimal.reached(), "Offset parkour stays exclusive to legacy mode");
        Fixture ladders = new Fixture(5, 9, 1);
        ladders.floor();
        for (int x = 1; x < 5; x++) for (int y = 1; y <= 4; y++) ladders.set(x, y, 0, SOLID);
        for (int y = 1; y <= 4; y++) ladders.set(0, y, 0, TerrainSnapshot.BODY | TerrainSnapshot.LADDER);
        PlannedRoute ladderRoute = plan(ladders, OPTIMAL, start, new GridPos(4, 5, 0), top.npcpathfindinglegacy.compat.Immutable.set());
        require(ladderRoute != null && ladderRoute.reached()
            && ladderRoute.steps().stream().anyMatch(s -> s.action() == Action.LADDER), "Optimal mode must climb ladders");
        require(ladderRoute.steps().stream().noneMatch(s -> s.action() == Action.PARKOUR || s.action() == Action.WALL),
            "Optimal ladder mode must not generate parkour or wall climbing");
        PlannedRoute combatLadder = plan(ladders, COMBAT, start, new GridPos(4, 5, 0), top.npcpathfindinglegacy.compat.Immutable.set());
        require(combatLadder != null && combatLadder.reached()
            && combatLadder.steps().stream().anyMatch(s -> s.action() == Action.LADDER),
            "Combat mode must retain ladder routes");
        Fixture diagonalGap = new Fixture(3, 8, 3);
        diagonalGap.set(0, 0, 0, SOLID); diagonalGap.set(1, 0, 1, SOLID);
        PlannedRoute cornerGap = plan(diagonalGap, WALL, start, new GridPos(1, 1, 1), top.npcpathfindinglegacy.compat.Immutable.set());
        require(cornerGap == null || !cornerGap.reached(), "Wall mode must not cut diagonally across a gap");
        PlannedRoute diagonalJump = plan(diagonalGap, LEGACY, start, new GridPos(1, 1, 1), top.npcpathfindinglegacy.compat.Immutable.set());
        require(diagonalJump != null && diagonalJump.reached()
            && diagonalJump.steps().get(1).action() == Action.PARKOUR,
            "A diagonal gap must launch a controlled jump instead of walking off the edge");
        Fixture sideCollision = new Fixture(4, 6, 3);
        sideCollision.set(2, 1, 1, SOLID);
        TerrainSnapshot body = sideCollision.freeze();
        require(body.jumpBodyClear(1.5, 1, 1.5) && !body.jumpBodyClear(1.8, 1, 1.5),
            "Jump collision must include the body beyond its center cell");

        Fixture detour = new Fixture(9, 7, 5);
        detour.floor();
        for (int y = 1; y <= 2; y++) detour.set(4, y, 2, SOLID);
        PlannedRoute cheap = plan(detour, WALL, new GridPos(1, 1, 2), new GridPos(7, 1, 2), top.npcpathfindinglegacy.compat.Immutable.set());
        require(cheap != null && cheap.reached(), "A ground detour must be reachable");
        require(cheap.steps().stream().noneMatch(s -> s.action() == Action.WALL),
            "A cheaper ground detour must beat climbing the shorter ledge route");

        Fixture combatArena = new Fixture(15, 7, 11);
        combatArena.floor();
        GridPos shooter = new GridPos(1, 1, 5), enemy = new GridPos(11, 1, 5);
        CombatTactics ranged = new CombatTactics(enemy, false, 4, 6);
        PlannedRoute approach = new RoutePlanner(combatArena.freeze(), COMBAT, top.npcpathfindinglegacy.compat.Immutable.set(), ranged).search(shooter, enemy, 0);
        require(approach != null && approach.reached(), "Ranged combat must find a firing position");
        GridPos firingPosition = approach.steps().get(approach.steps().size() - 1).position();
        require(ranged.accepts(combatArena.freeze(), firingPosition) && !firingPosition.equals(enemy),
            "Ranged route must end inside the firing band rather than on top of the enemy");
        PlannedRoute backoff = new RoutePlanner(combatArena.freeze(), COMBAT, top.npcpathfindinglegacy.compat.Immutable.set(), ranged)
            .search(new GridPos(10, 1, 5), enemy, 0);
        require(backoff != null && backoff.reached()
            && ranged.accepts(combatArena.freeze(), backoff.steps().get(backoff.steps().size() - 1).position()),
            "A ranged NPC inside minimum range must move away to regain firing distance");
        for (int y = 1; y <= 3; y++) for (int z = 4; z <= 6; z++) combatArena.set(8, y, z, SOLID);
        PlannedRoute flank = new RoutePlanner(combatArena.freeze(), COMBAT, top.npcpathfindinglegacy.compat.Immutable.set(), ranged).search(shooter, enemy, 0);
        require(flank != null && flank.reached()
            && ranged.accepts(combatArena.freeze(), flank.steps().get(flank.steps().size() - 1).position()),
            "A ranged NPC must flank an opaque obstacle to regain line of sight");
        GridPos escapeStart = new GridPos(6, 1, 5), escapeGoal = new GridPos(13, 1, 5);
        CombatTactics retreat = new CombatTactics(new GridPos(3, 1, 5), true, 0, 0);
        PlannedRoute escape = new RoutePlanner(combatArena.freeze(), COMBAT, top.npcpathfindinglegacy.compat.Immutable.set(), retreat).search(escapeStart, escapeGoal, 0);
        require(escape != null && escape.reached()
            && escape.steps().get(escape.steps().size() - 1).position().equals(escapeGoal),
            "Retreat must follow the supplied escape destination rather than chase its threat");
        require(retreat.proximityCost(new GridPos(4, 1, 5)) > retreat.proximityCost(escapeGoal),
            "Escape routing must penalize movement close to the threat");
        PlannedRoute ordinary = plan(combatArena, COMBAT, shooter, escapeGoal, top.npcpathfindinglegacy.compat.Immutable.set());
        require(ordinary != null && ordinary.reached(), "No combat context must retain ordinary script/patrol navigation");

        Fixture corridor = new Fixture(8, 7, 1);
        corridor.floor();
        corridor.set(4, 1, 0, 0); // unknown, hazardous or unloaded
        PlannedRoute blocked = plan(corridor, WALL, start, finish, top.npcpathfindinglegacy.compat.Immutable.set());
        require(blocked == null || !blocked.reached(), "Unknown blocks must never be traversed");
        byte[] mutable = corridor.blocks.clone();
        TerrainSnapshot frozen = new TerrainSnapshot(new GridPos(0, 0, 0), 8, 7, 1, 2, 0, false, mutable);
        Arrays.fill(mutable, (byte) TerrainSnapshot.BODY);
        require(!frozen.bodyClear(new GridPos(4, 1, 0)), "Snapshot must not share mutable block storage");
        Thread.currentThread().interrupt();
        require(new RoutePlanner(frozen, WALL, top.npcpathfindinglegacy.compat.Immutable.set()).search(start, finish, 0) == null,
            "Canceled work must stop searching");
        Thread.interrupted();
        System.out.println("Pathfinding checks passed: combat parkour/ladders, ranged approach/backoff/flanking, retreat, ordinary navigation, legacy modes, collision, cancellation");
    }

    private static PlannedRoute plan(Fixture fixture, RoutePlanner.Settings settings,
                                     GridPos start, GridPos goal, Set<Edge> failures) {
        return new RoutePlanner(fixture.freeze(), settings, failures).search(start, goal, 0);
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }

    private static final class Fixture {
        final int x, y, z;
        final byte[] blocks;
        Fixture(int x, int y, int z) {
            this.x = x; this.y = y; this.z = z;
            blocks = new byte[x * y * z];
            Arrays.fill(blocks, (byte) TerrainSnapshot.BODY);
        }
        void set(int px, int py, int pz, int flags) { blocks[(py * z + pz) * x + px] = (byte) flags; }
        void floor() { for (int px = 0; px < x; px++) for (int pz = 0; pz < z; pz++) set(px, 0, pz, SOLID); }
        TerrainSnapshot freeze() { return new TerrainSnapshot(new GridPos(0, 0, 0), x, y, z, 2, 0, false, blocks); }
    }
}
