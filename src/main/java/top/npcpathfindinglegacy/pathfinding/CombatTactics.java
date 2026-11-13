package top.npcpathfindinglegacy.pathfinding;

/** Immutable combat input; entity and world state stay on the server thread. */
public record CombatTactics(GridPos threat, boolean retreat, double minimumRange, double maximumRange) {
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
