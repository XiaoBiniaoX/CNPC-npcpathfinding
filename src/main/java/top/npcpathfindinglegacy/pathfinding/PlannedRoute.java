package top.npcpathfindinglegacy.pathfinding;

import java.util.List;

public final class PlannedRoute {
private final List<Step> steps;
private final GridPos target;
private final boolean reached;
private final double cost;
public PlannedRoute(List<Step> steps, GridPos target, boolean reached, double cost) { steps = top.npcpathfindinglegacy.compat.Immutable.copyList(steps); 
this.steps=steps;this.target=target;this.reached=reached;this.cost=cost;}
public List<Step> steps() { return steps; }
public GridPos target() { return target; }
public boolean reached() { return reached; }
public double cost() { return cost; }
@Override public int hashCode() { return java.util.Objects.hash(steps,target,reached,cost); }
@Override public boolean equals(Object value) { if (this==value) return true; if (!(value instanceof PlannedRoute)) return false; PlannedRoute other=(PlannedRoute)value; return java.util.Objects.equals(steps,other.steps) && java.util.Objects.equals(target,other.target) && java.util.Objects.equals(reached,other.reached) && java.util.Objects.equals(cost,other.cost); }

    
    public enum Action { WALK, STEP, DROP, PARKOUR, LADDER, WALL }
    public static final class Step {
private final GridPos position;
private final Action action;
public Step(GridPos position, Action action) {
this.position=position;this.action=action;}
public GridPos position() { return position; }
public Action action() { return action; }
@Override public int hashCode() { return java.util.Objects.hash(position,action); }
@Override public boolean equals(Object value) { if (this==value) return true; if (!(value instanceof Step)) return false; Step other=(Step)value; return java.util.Objects.equals(position,other.position) && java.util.Objects.equals(action,other.action); }
}
    public static final class Edge {
private final GridPos from;
private final GridPos to;
public Edge(GridPos from, GridPos to) {
this.from=from;this.to=to;}
public GridPos from() { return from; }
public GridPos to() { return to; }
@Override public int hashCode() { return java.util.Objects.hash(from,to); }
@Override public boolean equals(Object value) { if (this==value) return true; if (!(value instanceof Edge)) return false; Edge other=(Edge)value; return java.util.Objects.equals(from,other.from) && java.util.Objects.equals(to,other.to); }
}
}
