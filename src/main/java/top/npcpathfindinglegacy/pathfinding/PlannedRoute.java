package top.npcpathfindinglegacy.pathfinding;

import java.util.List;

public record PlannedRoute(List<Step> steps, GridPos target, boolean reached, double cost) {
    public PlannedRoute { steps = List.copyOf(steps); }
    public enum Action { WALK, STEP, DROP, PARKOUR, LADDER, WALL }
    public record Step(GridPos position, Action action) {}
    public record Edge(GridPos from, GridPos to) {}
}
