package top.npcpathfindinglegacy.pathfinding;

/** Immutable coordinates shared with the worker; no world or entity references. */
public record GridPos(int x, int y, int z) {
    public GridPos offset(int dx, int dy, int dz) {
        return new GridPos(x + dx, y + dy, z + dz);
    }

    public GridPos above() { return offset(0, 1, 0); }
    public GridPos below() { return offset(0, -1, 0); }

    public double distanceSquared(GridPos other) {
        double dx = (double) x - other.x;
        double dy = (double) y - other.y;
        double dz = (double) z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public int manhattan(GridPos other) {
        return Math.abs(x - other.x) + Math.abs(y - other.y) + Math.abs(z - other.z);
    }
}
