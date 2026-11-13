package top.npcpathfindinglegacy.pathfinding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import top.npcpathfindinglegacy.pathfinding.PlannedRoute.Action;
import top.npcpathfindinglegacy.pathfinding.PlannedRoute.Edge;
import top.npcpathfindinglegacy.pathfinding.PlannedRoute.Step;

/** Bounded A* over immutable input. This class never reads Minecraft state. */
public final class RoutePlanner {
    public static final class Settings {
private final int mode;
private final int walkTendency;
private final int jumpTendency;
private final int parkourGap;
private final int maxDrop;
private final int maxNodes;
public Settings(int mode, int walkTendency, int jumpTendency, int parkourGap, int maxDrop, int maxNodes) {
this.mode=mode;this.walkTendency=walkTendency;this.jumpTendency=jumpTendency;this.parkourGap=parkourGap;this.maxDrop=maxDrop;this.maxNodes=maxNodes;}
public int mode() { return mode; }
public int walkTendency() { return walkTendency; }
public int jumpTendency() { return jumpTendency; }
public int parkourGap() { return parkourGap; }
public int maxDrop() { return maxDrop; }
public int maxNodes() { return maxNodes; }
@Override public int hashCode() { return java.util.Objects.hash(mode,walkTendency,jumpTendency,parkourGap,maxDrop,maxNodes); }
@Override public boolean equals(Object value) { if (this==value) return true; if (!(value instanceof Settings)) return false; Settings other=(Settings)value; return java.util.Objects.equals(mode,other.mode) && java.util.Objects.equals(walkTendency,other.walkTendency) && java.util.Objects.equals(jumpTendency,other.jumpTendency) && java.util.Objects.equals(parkourGap,other.parkourGap) && java.util.Objects.equals(maxDrop,other.maxDrop) && java.util.Objects.equals(maxNodes,other.maxNodes); }

        public boolean wallMode() { return mode == 2; }
        public boolean optimalMode() { return mode == 3; }
        public boolean combatMode() { return mode == 4; }
        public boolean costAware() { return mode >= 2; }
        public boolean parkourEnabled() { return (mode == 1 || mode == 4) && parkourGap > 0; }
    }
    private static final class SearchNode {
private final GridPos pos;
private final SearchNode parent;
private final Action action;
private final double g;
private final double f;
public SearchNode(GridPos pos, SearchNode parent, Action action, double g, double f) {
this.pos=pos;this.parent=parent;this.action=action;this.g=g;this.f=f;}
public GridPos pos() { return pos; }
public SearchNode parent() { return parent; }
public Action action() { return action; }
public double g() { return g; }
public double f() { return f; }
@Override public int hashCode() { return java.util.Objects.hash(pos,parent,action,g,f); }
@Override public boolean equals(Object value) { if (this==value) return true; if (!(value instanceof SearchNode)) return false; SearchNode other=(SearchNode)value; return java.util.Objects.equals(pos,other.pos) && java.util.Objects.equals(parent,other.parent) && java.util.Objects.equals(action,other.action) && java.util.Objects.equals(g,other.g) && java.util.Objects.equals(f,other.f); }
}
    private static final class Move {
private final GridPos to;
private final Action action;
private final double cost;
public Move(GridPos to, Action action, double cost) {
this.to=to;this.action=action;this.cost=cost;}
public GridPos to() { return to; }
public Action action() { return action; }
public double cost() { return cost; }
@Override public int hashCode() { return java.util.Objects.hash(to,action,cost); }
@Override public boolean equals(Object value) { if (this==value) return true; if (!(value instanceof Move)) return false; Move other=(Move)value; return java.util.Objects.equals(to,other.to) && java.util.Objects.equals(action,other.action) && java.util.Objects.equals(cost,other.cost); }
}
    private final TerrainSnapshot terrain;
    private final Settings settings;
    private final Set<Edge> failedEdges;
    private final CombatTactics tactics;
    private final int tieSeed;

    public RoutePlanner(TerrainSnapshot terrain, Settings settings, Set<Edge> failedEdges) {
        this(terrain, settings, failedEdges, null);
    }

    public RoutePlanner(TerrainSnapshot terrain, Settings settings, Set<Edge> failedEdges, CombatTactics tactics) {
        this(terrain,settings,failedEdges,tactics,0);
    }
    public RoutePlanner(TerrainSnapshot terrain, Settings settings, Set<Edge> failedEdges, CombatTactics tactics, int tieSeed) {
        this.terrain = terrain;
        this.settings = settings;
        this.failedEdges = top.npcpathfindinglegacy.compat.Immutable.copySet(failedEdges);
        this.tactics = settings.combatMode() ? tactics : null;
        this.tieSeed = tieSeed;
    }

    public PlannedRoute search(GridPos requestedStart, GridPos goal, int accuracy) {
        if (settings.mode() == 6) return new JumpPointPlanner(terrain, settings.maxNodes(),tieSeed).search(requestedStart, goal);
        GridPos start = snapStart(requestedStart);
        if (start == null) return null;
        PriorityQueue<SearchNode> open = new PriorityQueue<>(Comparator.comparingDouble(SearchNode::f)
            .thenComparingInt(node -> tieOrder(node.pos(),tieSeed)));
        Map<GridPos, SearchNode> known = new HashMap<>();
        Action firstAction = settings.wallMode() && !terrain.standable(start, false) ? Action.WALL : Action.WALK;
        SearchNode first = new SearchNode(start, null, firstAction, 0, heuristic(start, goal, accuracy));
        open.add(first);
        known.put(start, first);
        SearchNode best = first;
        double bestScore = partialScore(start, goal);
        int radius = Math.max(0, Math.min(4, accuracy));
        int maxDistance = start.manhattan(goal) + 32;
        int visited = 0;
        while (!open.isEmpty() && visited < settings.maxNodes()) {
            if (Thread.currentThread().isInterrupted()) return null;
            SearchNode current = open.poll();
            if (known.get(current.pos()) != current) continue;
            visited++;
            double score = partialScore(current.pos(), goal);
            if (score < bestScore || (score == bestScore && current.g() < best.g())) {
                best = current;
                bestScore = score;
            }
            if (reachedGoal(current.pos(), goal, radius)) {
                best = current;
                break;
            }
            for (Move move : movesFrom(current.pos())) {
                if (move.to().manhattan(goal) > maxDistance
                    || Math.abs(move.to().y() - start.y()) > 24) continue;
                // Penalize only the failed transition, so its landing can still be reached another way.
                double penalty = failedEdges.contains(new Edge(current.pos(), move.to())) ? 12.0 : 0.0;
                double g = current.g() + move.cost() + penalty
                    + (tactics == null ? 0 : tactics.proximityCost(move.to()));
                SearchNode previous = known.get(move.to());
                if (previous != null && previous.g() <= g + 1.0E-6) continue;
                SearchNode next = new SearchNode(move.to(), current, move.action(), g,
                    g + heuristic(move.to(), goal, radius));
                known.put(next.pos(), next);
                open.add(next);
            }
        }
        boolean reached = reachedGoal(best.pos(), goal, radius);
        if (best == first || (!reached && (bestScore >= partialScore(start, goal) - 0.75
            || start.manhattan(best.pos()) < 2
            || (!settings.costAware() && goal.y() > start.y() + 1 && best.pos().y() <= start.y())))) return null;
        List<Step> steps = new ArrayList<>();
        for (SearchNode n = best; n != null; n = n.parent()) steps.add(new Step(n.pos(), n.action()));
        Collections.reverse(steps);
        PlannedRoute route = new PlannedRoute(steps, goal, reached, best.g());
        return settings.mode() == 5 ? ThetaPathSmoother.smooth(terrain, route) : route;
    }
    static int tieOrder(GridPos position, int seed) {
        if (seed == 0) return 0;
        int value = position.x() * 0x45D9F3B ^ position.z() * 0x119DE1F3 ^ position.y() * 31 ^ seed;
        value ^= value >>> 16; value *= 0x45D9F3B; return value ^ value >>> 16;
    }

    private GridPos snapStart(GridPos start) {
        if (occupiable(start)) return start;
        for (int distance = 1; distance <= 3; distance++) {
            GridPos down = start.offset(0, -distance, 0);
            if (occupiable(down)) return down;
            if (distance == 1 && occupiable(start.above())) return start.above();
        }
        return null;
    }

    private boolean occupiable(GridPos pos) {
        return terrain.standable(pos, !settings.wallMode())
            || (settings.wallMode() && terrain.wallSupported(pos));
    }

    private List<Move> movesFrom(GridPos from) {
        List<Move> moves = new ArrayList<>(24);
        boolean grounded = terrain.standable(from, !settings.wallMode());
        for (int[] d : TerrainSnapshot.HORIZONTAL) {
            GridPos side = from.offset(d[0], 0, d[1]);
            if (terrain.standable(side, !settings.wallMode())) {
                add(moves, side, Action.WALK, walkCost(1.0) + risk(side));
            } else if (settings.wallMode() && terrain.wallSupported(from) && terrain.wallSupported(side)) {
                add(moves, side, Action.WALL, 2.8 + terrain.exposure(side));
            }
            GridPos up = side.above();
            if (terrain.standable(up, !settings.wallMode()) && terrain.bodyClear(from.above())) {
                Action action = settings.wallMode() && terrain.wallDirection(from) >= 0 ? Action.WALL : Action.STEP;
                if (grounded || action == Action.WALL) {
                    add(moves, up, action, settings.costAware() ? 3.4 + risk(up) : jumpCost(1.6));
                }
            }
            addDrop(moves, from, side, grounded);
        }
        if (grounded) {
            addDiagonals(moves, from);
            if (settings.parkourEnabled() && (terrain.flags(from.below()) & TerrainSnapshot.SUPPORT) != 0
                && (terrain.flags(from) & TerrainSnapshot.WATER) == 0) addParkour(moves, from);
        }
        if (settings.wallMode() && terrain.wallSupported(from)) {
            if (terrain.wallSupported(from.above())) add(moves, from.above(), Action.WALL, 3.4);
            if (occupiable(from.below())) add(moves, from.below(), Action.WALL, 2.8);
        } else if (!settings.wallMode() && terrain.ladder(from)) {
            if (terrain.bodyClear(from.above()) && (terrain.ladder(from.above())
                || terrain.standable(from.above(), true))) add(moves, from.above(), Action.LADDER,
                    settings.costAware() ? 2.4 : jumpCost(0.75));
            if (terrain.standable(from.below(), true)) add(moves, from.below(), Action.LADDER,
                settings.costAware() ? 1.8 : 0.8);
        }
        return moves;
    }

    private void addDiagonals(List<Move> moves, GridPos from) {
        for (int dx : new int[]{-1, 1}) {
            for (int dz : new int[]{-1, 1}) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (settings.costAware() && dy != 0) continue;
                    GridPos to = from.offset(dx, dy, dz);
                    int y = Math.max(from.y(), to.y());
                    if (!terrain.standable(to, !settings.wallMode())
                        || !terrain.bodyClear(new GridPos(from.x() + dx, y, from.z()))
                        || !terrain.bodyClear(new GridPos(from.x(), y, from.z() + dz))
                        || (!terrain.standable(from.offset(dx, 0, 0), !settings.wallMode())
                            || !terrain.standable(from.offset(0, 0, dz), !settings.wallMode()))
                        || (dy > 0 && !terrain.bodyClear(from.above()))) continue;
                    add(moves, to, dy > 0 ? Action.STEP : dy < 0 ? Action.DROP : Action.WALK,
                        (dy == 0 ? walkCost(Math.sqrt(2)) : settings.costAware() ? 2.8 : jumpCost(2.0)) + risk(to));
                }
            }
        }
    }

    private void addDrop(List<Move> moves, GridPos from, GridPos side, boolean grounded) {
        if (!grounded && !settings.wallMode()) return;
        int maxDrop = settings.costAware() ? Math.min(3, settings.maxDrop()) : settings.maxDrop();
        for (int drop = 1; drop <= maxDrop; drop++) {
            GridPos landing = side.offset(0, -drop, 0);
            if (!terrain.bodyClear(landing)) return;
            if (!terrain.standable(landing, !settings.wallMode())) continue;
            // Check the complete falling column, including the departure height.
            for (int y = landing.y(); y <= from.y(); y++) {
                if (!terrain.bodyClear(new GridPos(side.x(), y, side.z()))) return;
            }
            add(moves, landing, Action.DROP, settings.costAware()
                ? 1.5 + drop + drop * drop * 0.35 + risk(landing) : 0.65 + drop * 0.35);
            return;
        }
    }

    private void addParkour(List<Move> moves, GridPos from) {
        int maxForward = Math.min(6, settings.parkourGap() + 1);
        for (int dx = -maxForward; dx <= maxForward; dx++) {
            for (int dz = -maxForward; dz <= maxForward; dz++) {
                int distanceSquared = dx * dx + dz * dz;
                if (distanceSquared < 2 || distanceSquared > maxForward * maxForward) continue;
                double distance = Math.sqrt(distanceSquared);
                for (int rise = -Math.min(4, settings.maxDrop()); rise <= 1; rise++) {
                    GridPos to = from.offset(dx, rise, dz);
                    if (!terrain.standable(to, false) || !jumpClear(from, to, distance)) continue;
                    double cost = settings.combatMode()
                        ? Math.sqrt(to.distanceSquared(from)) + 0.6 + Math.max(0, -rise) * 0.5 + risk(to)
                        : jumpCost(0.55 + distance * 0.17 + Math.max(0, rise) * 0.18);
                    add(moves, to, Action.PARKOUR, cost);
                }
            }
        }
    }

    private boolean jumpClear(GridPos from, GridPos to, double distance) {
        boolean gap = to.x() != from.x() && to.z() != from.z()
            && (!terrain.standable(from.offset(Integer.signum(to.x() - from.x()), 0, 0), false)
                || !terrain.standable(from.offset(0, 0, Integer.signum(to.z() - from.z())), false));
        int samples = (int) Math.ceil(distance * 4);
        for (int step = 1; step < samples; step++) {
            double part = step / (double) samples;
            int x = (int) Math.floor(from.x() + 0.5 + (to.x() - from.x()) * part);
            int z = (int) Math.floor(from.z() + 0.5 + (to.z() - from.z()) * part);
            if (!terrain.standable(new GridPos(x, from.y(), z), false)) { gap = true; break; }
        }
        if (!gap) return false;
        double velocity = JumpTrajectoryPlanner.parkourVelocity(settings.jumpTendency(), to.y() - from.y());
        int ticks = JumpTrajectoryPlanner.estimateFlightTicks(from.y(), velocity, to.y());
        if (distance / ticks > JumpTrajectoryPlanner.MAX_PARKOUR_SPEED) return false;
        double y = from.y();
        // Sweep the complete body, including platform sides and the last landing tick.
        for (int tick = 1; tick <= ticks; tick++) {
            double previousY = y;
            y += velocity;
            if (tick == ticks) y = Math.max(y, to.y());
            for (int substep = 1; substep <= 4; substep++) {
                double part = substep / 4.0;
                double fraction = (tick - 1 + part) / ticks;
                if (!terrain.jumpBodyClear(from.x() + 0.5 + (to.x() - from.x()) * fraction,
                    previousY + (y - previousY) * part,
                    from.z() + 0.5 + (to.z() - from.z()) * fraction)) return false;
            }
            velocity = (velocity - 0.08) * 0.98;
        }
        return true;
    }

    private static void add(List<Move> moves, GridPos to, Action action, double cost) {
        moves.add(new Move(to, action, cost));
    }

    private double walkCost(double base) {
        return settings.costAware() ? base : base * (1.12 - settings.walkTendency() * 0.0024);
    }

    private double jumpCost(double base) { return base * (1.35 - settings.jumpTendency() * 0.006); }
    private double risk(GridPos pos) { return settings.costAware() ? terrain.exposure(pos) : 0; }

    private double heuristic(GridPos pos, GridPos goal, int radius) {
        if (tactics != null && tactics.ranged()) return tactics.heuristic(pos);
        if (settings.costAware()) {
            // Admissible: every new-mode transition costs at least its Euclidean length.
            return Math.max(0, Math.sqrt(pos.distanceSquared(goal)) - radius);
        }
        double dx = pos.x() - goal.x(), dz = pos.z() - goal.z();
        int dy = goal.y() - pos.y();
        return Math.sqrt(dx * dx + dz * dz) + (dy > 0 ? dy * 2.4 : -dy * 1.25);
    }

    private double partialScore(GridPos pos, GridPos goal) {
        if (tactics != null && tactics.ranged()) {
            return tactics.heuristic(pos) + (terrain.lineOfSight(pos, tactics.threat()) ? 0 : 2);
        }
        double dx = pos.x() - goal.x(), dz = pos.z() - goal.z();
        int dy = pos.y() - goal.y();
        return Math.sqrt(dx * dx + dz * dz) + (dy < 0 ? -dy * 8.5 : dy * 3.0);
    }

    private boolean reachedGoal(GridPos position, GridPos goal, int radius) {
        return tactics != null && tactics.ranged() ? tactics.accepts(terrain, position)
            : position.distanceSquared(goal) <= radius * radius;
    }
}
