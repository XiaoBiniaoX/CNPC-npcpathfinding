package top.npcpathfindinglegacy.pathfinding;

/** Frozen block classifications. Unknown/unloaded cells are always blocked. */
public final class TerrainSnapshot {
    public static final int BODY = 1;
    public static final int SUPPORT = 2;
    public static final int LADDER = 4;
    public static final int WATER = 8;
    public static final int WALL_NORTH = 16;
    public static final int WALL_SOUTH = 32;
    public static final int WALL_WEST = 64;
    public static final int WALL_EAST = 128;
    static final int[][] HORIZONTAL = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
    private static final int[] WALL_FACES = {WALL_SOUTH, WALL_NORTH, WALL_EAST, WALL_WEST};

    private final GridPos minimum;
    private final int sizeX, sizeY, sizeZ, bodyHeight, bodyRadius;
    private final byte[] blocks;
    private final byte[] sightBlocked;
    private final short[] supportHeights;
    private final boolean swimming;
    private final double bodyWidth, actualBodyHeight;

    public TerrainSnapshot(GridPos minimum, int sizeX, int sizeY, int sizeZ,
                           int bodyHeight, int bodyRadius, boolean swimming, byte[] blocks) {
        this(minimum, sizeX, sizeY, sizeZ, bodyHeight, bodyRadius, swimming, blocks,
            bodyRadius * 2 + 0.6, bodyHeight);
    }

    public TerrainSnapshot(GridPos minimum, int sizeX, int sizeY, int sizeZ,
                           int bodyHeight, int bodyRadius, boolean swimming, byte[] blocks,
                           double bodyWidth, double actualBodyHeight) {
        this(minimum, sizeX, sizeY, sizeZ, bodyHeight, bodyRadius, swimming, blocks,
            bodyWidth, actualBodyHeight, null);
    }

    public TerrainSnapshot(GridPos minimum, int sizeX, int sizeY, int sizeZ,
                           int bodyHeight, int bodyRadius, boolean swimming, byte[] blocks,
                           double bodyWidth, double actualBodyHeight, byte[] sightBlocked) {
        this(minimum, sizeX, sizeY, sizeZ, bodyHeight, bodyRadius, swimming, blocks,
            bodyWidth, actualBodyHeight, sightBlocked, null);
    }

    public TerrainSnapshot(GridPos minimum, int sizeX, int sizeY, int sizeZ,
                           int bodyHeight, int bodyRadius, boolean swimming, byte[] blocks,
                           double bodyWidth, double actualBodyHeight, byte[] sightBlocked, short[] supportHeights) {
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0
            || (long) sizeX * sizeY * sizeZ != blocks.length) {
            throw new IllegalArgumentException("Invalid snapshot dimensions");
        }
        this.minimum = minimum;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.bodyHeight = bodyHeight;
        this.bodyRadius = bodyRadius;
        this.swimming = swimming;
        this.bodyWidth = bodyWidth;
        this.actualBodyHeight = actualBodyHeight;
        this.blocks = blocks.clone();
        if (sightBlocked != null && sightBlocked.length != blocks.length) throw new IllegalArgumentException("Invalid sight snapshot");
        this.sightBlocked = sightBlocked == null ? null : sightBlocked.clone();
        if (supportHeights != null && supportHeights.length != blocks.length) throw new IllegalArgumentException("Invalid height snapshot");
        this.supportHeights = supportHeights == null ? null : supportHeights.clone();
    }

    public boolean fullSupport(GridPos feet) {
        if (snowHeight(feet)>0 || (flags(feet.below()) & SUPPORT) == 0) return false;
        if (supportHeights == null) return true;
        GridPos below = feet.below();
        int x = below.x() - minimum.x(), y = below.y() - minimum.y(), z = below.z() - minimum.z();
        return supportHeights[(y * sizeZ + z) * sizeX + x] == 1000;
    }

    public boolean groundLineClear(GridPos from, GridPos to) {
        if (from.y() != to.y()) return false;
        int samples = Math.max(1, (int) Math.ceil(Math.sqrt(from.distanceSquared(to)) * 10));
        for (int step = 0; step <= samples; step++) {
            double part = step / (double) samples;
            double x = from.x() + 0.5 + (to.x() - from.x()) * part;
            double z = from.z() + 0.5 + (to.z() - from.z()) * part;
            GridPos feet = new GridPos((int) Math.floor(x), from.y(), (int) Math.floor(z));
            if (!fullSupport(feet) || (flags(feet) & (WATER | LADDER)) != 0
                || !jumpBodyClear(x, from.y(), z) || !shortcutCollisionClear(x, from.y(), z)) return false;
        }
        return true;
    }

    private boolean shortcutCollisionClear(double x, double y, double z) {
        if (sightBlocked == null) return true;
        double half = bodyWidth * 0.5;
        for (int py = (int)Math.floor(y + 1.0E-6); py <= (int)Math.floor(y + actualBodyHeight - 1.0E-6); py++)
            for (int px = (int)Math.floor(x - half + 1.0E-6); px <= (int)Math.floor(x + half - 1.0E-6); px++)
                for (int pz = (int)Math.floor(z - half + 1.0E-6); pz <= (int)Math.floor(z + half - 1.0E-6); pz++) {
                    int sx = px - minimum.x(), sy = py - minimum.y(), sz = pz - minimum.z();
                    if (sx < 0 || sy < 0 || sz < 0 || sx >= sizeX || sy >= sizeY || sz >= sizeZ
                        || sightBlocked[(sy * sizeZ + sz) * sizeX + sx] != 0) return false;
                }
        return true;
    }

    public boolean lineOfSight(GridPos from, GridPos target) {
        double dx = target.x() - from.x(), dy = target.y() - from.y(), dz = target.z() - from.z();
        int samples = Math.max(1, (int) Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz) * 4));
        for (int step = 0; step <= samples; step++) {
            double part = step / (double) samples;
            GridPos point = new GridPos((int) Math.floor(from.x() + 0.5 + dx * part),
                (int) Math.floor(from.y() + Math.min(1.5, actualBodyHeight * 0.85) + dy * part),
                (int) Math.floor(from.z() + 0.5 + dz * part));
            if (sightBlocked == null) {
                if ((flags(point) & BODY) == 0) return false;
            } else {
                int x = point.x() - minimum.x(), y = point.y() - minimum.y(), z = point.z() - minimum.z();
                if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ
                    || sightBlocked[(y * sizeZ + z) * sizeX + x] != 0) return false;
            }
        }
        return true;
    }

    private double snowHeight(GridPos pos) {
        if (supportHeights==null) return -1;
        int x=pos.x()-minimum.x(),y=pos.y()-minimum.y(),z=pos.z()-minimum.z();
        if (x<0 || y<0 || z<0 || x>=sizeX || y>=sizeY || z>=sizeZ) return -1;
        short value=supportHeights[(y*sizeZ+z)*sizeX+x];
        return value<0?(-value-1)/1000.0:-1;
    }

    public boolean jumpBodyClear(double x, double y, double z) {
        double halfWidth = bodyWidth * 0.5;
        int minX = (int) Math.floor(x - halfWidth + 1.0E-6), maxX = (int) Math.floor(x + halfWidth - 1.0E-6);
        int minZ = (int) Math.floor(z - halfWidth + 1.0E-6), maxZ = (int) Math.floor(z + halfWidth - 1.0E-6);
        int minY = (int) Math.floor(y + 1.0E-6), maxY = (int) Math.floor(y + actualBodyHeight - 1.0E-6);
        for (int py = minY; py <= maxY; py++) {
            for (int px = minX; px <= maxX; px++) {
                for (int pz = minZ; pz <= maxZ; pz++) {
                    GridPos pos=new GridPos(px,py,pz);
                    double snow=snowHeight(pos);
                    if ((flags(pos)&BODY)==0 || snow>0 && py+snow>y+1.0E-6) return false;
                }
            }
        }
        return true;
    }

    public int flags(GridPos pos) {
        int x = pos.x() - minimum.x(), y = pos.y() - minimum.y(), z = pos.z() - minimum.z();
        if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) return 0;
        return Byte.toUnsignedInt(blocks[(y * sizeZ + z) * sizeX + x]);
    }

    public boolean bodyClear(GridPos feet) {
        for (int y = 0; y < bodyHeight; y++) {
            for (int dx = -bodyRadius; dx <= bodyRadius; dx++) {
                for (int dz = -bodyRadius; dz <= bodyRadius; dz++) {
                    if ((flags(feet.offset(dx, y, dz)) & BODY) == 0) return false;
                }
            }
        }
        double snow=snowHeight(feet);
        return supportHeights==null || jumpBodyClear(feet.x()+0.5,feet.y()+Math.max(0,snow),feet.z()+0.5);
    }

    public boolean standable(GridPos feet, boolean ladders) {
        if (!bodyClear(feet)) return false;
        return (flags(feet.below()) & SUPPORT) != 0
            || (swimming && (flags(feet) & WATER) != 0)
            || (ladders && ladder(feet));
    }

    public boolean ladder(GridPos feet) {
        return ((flags(feet) | flags(feet.below())) & LADDER) != 0;
    }

    public int wallDirection(GridPos feet) {
        for (int i = 0; i < HORIZONTAL.length; i++) {
            int[] d = HORIZONTAL[i];
            if ((flags(feet.offset(d[0], 0, d[1])) & WALL_FACES[i]) != 0
                || (flags(feet.offset(d[0], 1, d[1])) & WALL_FACES[i]) != 0) return i;
        }
        return -1;
    }

    public boolean wallSupported(GridPos feet) {
        return bodyClear(feet) && wallDirection(feet) >= 0;
    }

    public double exposure(GridPos feet) {
        int exposed = 0;
        for (int[] d : HORIZONTAL) {
            if (!standable(feet.offset(d[0], 0, d[1]), false)) exposed++;
        }
        return exposed * 0.15 + ((flags(feet) & WATER) != 0 ? 2.0 : 0.0);
    }
}
