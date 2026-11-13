package top.npcpathfindinglegacy.pathfinding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Comparator;
import top.npcpathfindinglegacy.pathfinding.PlannedRoute.Action;
import top.npcpathfindinglegacy.pathfinding.PlannedRoute.Step;

/** Classic uniform eight-neighbor JPS, with no corner cutting. No stairs, fluids or parkour.
 * Pruning follows the no-corner-cutting reference in game-ai-cheatsheet/jps.py (CanReader, MIT).
 */
public final class JumpPointPlanner {
    private static final int[][] DIRECTIONS = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
    private record Entry(GridPos pos, double g, double f) implements Comparable<Entry> {
        public int compareTo(Entry other) { return Double.compare(f, other.f); }
    }
    private final TerrainSnapshot terrain;
    private final int maxNodes;
    private final int tieSeed;
    private GridPos goal;
    private int expanded;

    public JumpPointPlanner(TerrainSnapshot terrain, int maxNodes) { this(terrain,maxNodes,0); }
    public JumpPointPlanner(TerrainSnapshot terrain, int maxNodes, int tieSeed) { this.terrain = terrain; this.maxNodes = maxNodes; this.tieSeed = tieSeed; }
    public int expandedNodes() { return expanded; }

    public PlannedRoute search(GridPos start, GridPos target) {
        goal = target; expanded = 0;
        if (start.y() != goal.y() || !open(start) || !open(goal)) return null;
        PriorityQueue<Entry> queue = new PriorityQueue<>(Comparator.comparingDouble(Entry::f)
            .thenComparingInt(entry -> RoutePlanner.tieOrder(entry.pos(),tieSeed)));
        Map<GridPos, Double> costs = new HashMap<>();
        Map<GridPos, GridPos> parents = new HashMap<>();
        costs.put(start, 0.0); queue.add(new Entry(start, 0, octile(start, goal)));
        while (!queue.isEmpty() && expanded < maxNodes) {
            if (Thread.currentThread().isInterrupted()) return null;
            Entry current = queue.poll();
            if (current.g() > costs.get(current.pos()) + 1.0E-9) continue;
            if (current.pos().equals(goal)) return reconstruct(parents, current.g());
            expanded++;
            for (int[] direction : directions(current.pos(), parents.get(current.pos()))) {
                GridPos next = jump(current.pos(), direction[0], direction[1]);
                if (next == null) continue;
                double cost = current.g() + octile(current.pos(), next);
                if (cost >= costs.getOrDefault(next, Double.POSITIVE_INFINITY)) continue;
                costs.put(next, cost); parents.put(next, current.pos());
                queue.add(new Entry(next, cost, cost + octile(next, goal)));
            }
        }
        return null;
    }

    private boolean open(GridPos pos) {
        return terrain.standable(pos, false) && terrain.fullSupport(pos)
            && (terrain.flags(pos) & (TerrainSnapshot.WATER | TerrainSnapshot.LADDER)) == 0;
    }
    private boolean canMove(GridPos pos, int dx, int dz) {
        return open(pos.offset(dx, 0, dz)) && (dx == 0 || dz == 0
            || open(pos.offset(dx, 0, 0)) && open(pos.offset(0, 0, dz)));
    }
    private List<int[]> forced(GridPos pos, int dx, int dz) {
        ArrayList<int[]> result = new ArrayList<>(2);
        for (int side : new int[]{-1, 1}) {
            if (dx != 0) {
                if (open(pos.offset(0, 0, side)) && !open(pos.offset(-dx, 0, side))) result.add(new int[]{0, side});
            } else if (open(pos.offset(side, 0, 0)) && !open(pos.offset(side, 0, -dz))) result.add(new int[]{side, 0});
        }
        return result;
    }
    private GridPos straight(GridPos pos, int dx, int dz) {
        while (canMove(pos, dx, dz)) {
            if (Thread.currentThread().isInterrupted()) return null;
            pos = pos.offset(dx, 0, dz);
            if (pos.equals(goal) || !forced(pos, dx, dz).isEmpty()) return pos;
        }
        return null;
    }
    private GridPos jump(GridPos pos, int dx, int dz) {
        if (dx == 0 || dz == 0) return straight(pos, dx, dz);
        while (canMove(pos, dx, dz)) {
            if (Thread.currentThread().isInterrupted()) return null;
            pos = pos.offset(dx, 0, dz);
            if (pos.equals(goal) || straight(pos, dx, 0) != null || straight(pos, 0, dz) != null) return pos;
        }
        return null;
    }
    private List<int[]> directions(GridPos pos, GridPos parent) {
        if (parent == null) return List.of(DIRECTIONS);
        int dx = Integer.signum(pos.x() - parent.x()), dz = Integer.signum(pos.z() - parent.z());
        if (dx != 0 && dz != 0) return List.of(new int[]{dx,0}, new int[]{0,dz}, new int[]{dx,dz});
        ArrayList<int[]> result = new ArrayList<>(); result.add(new int[]{dx,dz});
        for (int[] side : forced(pos, dx, dz)) { result.add(side); result.add(new int[]{dx + side[0], dz + side[1]}); }
        return result;
    }
    private PlannedRoute reconstruct(Map<GridPos, GridPos> parents, double cost) {
        ArrayList<GridPos> chain = new ArrayList<>();
        for (GridPos pos = goal; pos != null; pos = parents.get(pos)) chain.add(pos);
        Collections.reverse(chain);
        ArrayList<Step> path = new ArrayList<>(); path.add(new Step(chain.get(0), Action.WALK));
        for (int i = 1; i < chain.size(); i++) {
            GridPos pos = chain.get(i - 1), end = chain.get(i);
            int dx = Integer.signum(end.x() - pos.x()), dz = Integer.signum(end.z() - pos.z());
            while (!pos.equals(end)) { pos = pos.offset(dx, 0, dz); path.add(new Step(pos, Action.WALK)); }
        }
        return new PlannedRoute(path, goal, true, cost);
    }
    private static double octile(GridPos a, GridPos b) {
        double x = Math.abs(a.x() - b.x()), z = Math.abs(a.z() - b.z());
        return Math.max(x, z) + (Math.sqrt(2) - 1) * Math.min(x, z);
    }
}
