package top.npcpathfindinglegacy.tactics.flow;

import java.util.Arrays;
import java.util.PriorityQueue;

/** Reverse Dijkstra field over immutable sampled ground cells. */
public final class FlowField {
    private static final class Cell implements Comparable<Cell> {
private final int index;
private final double cost;
public Cell(int index, double cost) {
this.index=index;this.cost=cost;}
public int index() { return index; }
public double cost() { return cost; }
@Override public int hashCode() { return java.util.Objects.hash(index,cost); }
@Override public boolean equals(Object value) { if (this==value) return true; if (!(value instanceof Cell)) return false; Cell other=(Cell)value; return java.util.Objects.equals(index,other.index) && java.util.Objects.equals(cost,other.cost); }

        public int compareTo(Cell other) { return Double.compare(cost, other.cost); }
    }
    private final int width, depth;
    private final int[] heights, next;
    private final double[] distance;
    public FlowField(int width, int depth, int[] heights, int goal) {
        if (width <= 0 || depth <= 0 || heights.length != width * depth) throw new IllegalArgumentException("Invalid field");
        this.width = width; this.depth = depth; this.heights = heights.clone();
        next = new int[heights.length]; distance = new double[heights.length];
        Arrays.fill(next, -1); Arrays.fill(distance, Double.POSITIVE_INFINITY);
        if (goal < 0 || goal >= heights.length || heights[goal] == Integer.MIN_VALUE) return;
        PriorityQueue<Cell> queue = new PriorityQueue<>();
        queue.add(new Cell(goal, 0)); distance[goal] = 0; next[goal] = goal;
        while (!queue.isEmpty()) {
            if (Thread.currentThread().isInterrupted()) return;
            Cell cell = queue.poll();
            if (cell.cost() > distance[cell.index()]) continue;
            int x = cell.index() % width, z = cell.index() / width;
            for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                int px = x + d[0], pz = z + d[1];
                if (px < 0 || px >= width || pz < 0 || pz >= depth) continue;
                int previous = pz * width + px;
                if (heights[previous] == Integer.MIN_VALUE || Math.abs(heights[previous] - heights[cell.index()]) > 1) continue;
                double cost = cell.cost() + 1 + Math.abs(heights[previous] - heights[cell.index()]) * 1.2;
                if (cost >= distance[previous]) continue;
                distance[previous] = cost; next[previous] = cell.index(); queue.add(new Cell(previous, cost));
            }
        }
    }
    public int next(int index) { return index < 0 || index >= next.length ? -1 : next[index]; }
    public int height(int index) { return heights[index]; }
    public double distance(int index) { return distance[index]; }
}
