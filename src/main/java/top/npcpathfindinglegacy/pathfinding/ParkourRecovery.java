package top.npcpathfindinglegacy.pathfinding;

/** Keep the original chase goal separate from the mandatory return to takeoff. */
final class ParkourRecovery {
    private GridPos takeoff;

    void failed(GridPos from) { if (takeoff == null) takeoff = from; }
    boolean returning() { return takeoff != null; }
    GridPos target(GridPos chaseGoal) { return takeoff == null ? chaseGoal : takeoff; }
    void returned() { takeoff = null; }
    void reset() { takeoff = null; }
}
