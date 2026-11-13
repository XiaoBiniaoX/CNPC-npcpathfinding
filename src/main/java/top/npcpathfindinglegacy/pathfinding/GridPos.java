package top.npcpathfindinglegacy.pathfinding;

/** Immutable coordinates shared with the worker; no world or entity references. */
public final class GridPos {
private final int x;
private final int y;
private final int z;
public GridPos(int x, int y, int z) {
this.x=x;this.y=y;this.z=z;}
public int x() { return x; }
public int y() { return y; }
public int z() { return z; }
@Override public int hashCode() { return java.util.Objects.hash(x,y,z); }
@Override public boolean equals(Object value) { if (this==value) return true; if (!(value instanceof GridPos)) return false; GridPos other=(GridPos)value; return java.util.Objects.equals(x,other.x) && java.util.Objects.equals(y,other.y) && java.util.Objects.equals(z,other.z); }

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
