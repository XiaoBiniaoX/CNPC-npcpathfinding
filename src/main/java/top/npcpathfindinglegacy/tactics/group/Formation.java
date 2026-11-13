package top.npcpathfindinglegacy.tactics.group;
import top.npcpathfindinglegacy.tactics.*;
public final class Formation {
    public enum Shape { WEDGE, LINE, COLUMN, BOX, STAGGERED_COLUMN }
    public static Shape choose(TacticalContext c) {
        if (c.narrow()) return Shape.COLUMN;
        if (c.health() < 0.4 || c.leaderHealth() < 0.5) return Shape.BOX;
        if (c.rough()) return Shape.STAGGERED_COLUMN;
        if (c.visible() && c.distance() < 10 && c.role().ranged()) return Shape.LINE;
        return Shape.WEDGE;
    }
    public static double[] offset(Shape shape, int slot) {
        if (slot == 0) return new double[]{0,0};
        int rank = (slot + 1) / 2; double side = (slot & 1) == 1 ? -1 : 1;
        switch (shape) {
            case WEDGE: return new double[]{rank * 1.7, side * rank * 1.8};
            case LINE: return new double[]{0, side * rank * 2};
            case COLUMN: return new double[]{slot * 2, 0};
            case STAGGERED_COLUMN: return new double[]{slot * 1.8, side * 0.9};
            case BOX: return new double[]{((slot - 1) / 4 + 1) * 2 * (((slot - 1) % 4 < 2) ? 1 : -1), side * 2};
            default: throw new IllegalArgumentException("Unknown formation shape");
}
    }
    public static double[] position(double anchorX, double anchorZ, double awayX, double awayZ,
                                    Shape shape, int slot, double spacing) {
        double[] offset = offset(shape,slot);
        double scale = spacing / 2;
        return new double[]{anchorX + (awayX * offset[0] - awayZ * offset[1]) * scale,
            anchorZ + (awayZ * offset[0] + awayX * offset[1]) * scale};
    }
    /** Assign nearby members to nearby slots once, so they do not cross the entire formation to sort by UUID. */
    public static int[] assign(double[] x, double[] z, int leader, Shape shape, double awayX, double awayZ, double spacing) {
        int[] order = new int[x.length]; boolean[] used = new boolean[x.length];
        order[0] = leader; used[leader] = true;
        for (int slot = 1; slot < order.length; slot++) {
            double[] point = position(x[leader],z[leader],awayX,awayZ,shape,slot,spacing);
            int best = -1; double distance = Double.POSITIVE_INFINITY;
            for (int member = 0; member < x.length; member++) {
                if (used[member]) continue;
                double dx = x[member] - point[0], dz = z[member] - point[1], score = dx * dx + dz * dz;
                if (score < distance) { best = member; distance = score; }
            }
            order[slot] = best; used[best] = true;
        }
        return order;
    }
    public static Decision decide(TacticalContext c) {
        double[] offset = offset(choose(c), c.slot());
        return new Decision(c.preferredRange() + offset[0], offset[1], 0.9, false, true);
    }
    public static double leaderScore(double health, WeaponRole role, double distanceToCenter, boolean hurt) {
        return health * 5 + (role == WeaponRole.HYBRID ? 1 : role == WeaponRole.MELEE ? 0.5 : 0)
            - Math.min(8,distanceToCenter) * 0.08 - (hurt ? 0.8 : 0);
    }
}
