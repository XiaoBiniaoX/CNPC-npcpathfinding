package top.npcpathfindinglegacy.pathfinding;

/** Bounded intermediate goals, keeping the externally requested final destination intact. */
public final class SegmentedPlanning {
    private SegmentedPlanning() {}
    public static GridPos nextGoal(GridPos start, GridPos goal) {
        double distance = Math.sqrt(start.distanceSquared(goal));
        if (distance <= 16) return goal;
        double scale = 16 / distance;
        return new GridPos(start.x() + (int) Math.round((goal.x() - start.x()) * scale),
            start.y() + (int) Math.round((goal.y() - start.y()) * scale),
            start.z() + (int) Math.round((goal.z() - start.z()) * scale));
    }
}
