package top.npcpathfindinglegacy.pathfinding;

/** Immutable combat input; entity and world state stay on the server thread. */
public final class CombatTactics {
private final GridPos threat;
private final boolean retreat;
private final double minimumRange;
private final double maximumRange;
public CombatTactics(GridPos threat, boolean retreat, double minimumRange, double maximumRange) {
this.threat=threat;this.retreat=retreat;this.minimumRange=minimumRange;this.maximumRange=maximumRange;}
public GridPos threat() { return threat; }
public boolean retreat() { return retreat; }
public double minimumRange() { return minimumRange; }
public double maximumRange() { return maximumRange; }
@Override public int hashCode() { return java.util.Objects.hash(threat,retreat,minimumRange,maximumRange); }
@Override public boolean equals(Object value) { if (this==value) return true; if (!(value instanceof CombatTactics)) return false; CombatTactics other=(CombatTactics)value; return java.util.Objects.equals(threat,other.threat) && java.util.Objects.equals(retreat,other.retreat) && java.util.Objects.equals(minimumRange,other.minimumRange) && java.util.Objects.equals(maximumRange,other.maximumRange); }

    public boolean ranged() { return !retreat && maximumRange > 0; }

    public boolean accepts(TerrainSnapshot terrain, GridPos position) {
        double distance = Math.sqrt(position.distanceSquared(threat));
        return ranged() && distance >= minimumRange && distance <= maximumRange
            && terrain.lineOfSight(position, threat);
    }

    public double proximityCost(GridPos position) {
        if (!retreat) return 0;
        double distance = Math.sqrt(position.distanceSquared(threat));
        return Math.max(0, 8 - distance) * 0.6;
    }

    public double heuristic(GridPos position) {
        double distance = Math.sqrt(position.distanceSquared(threat));
        return Math.max(0, Math.max(minimumRange - distance, distance - maximumRange));
    }
}
