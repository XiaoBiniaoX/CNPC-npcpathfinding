package top.npcpathfinding.pathfinding;

public enum PathfindingMode {
    VANILLA(0, "CNPC原版寻路"),
    BARITONE(1, "Baritone寻路");

    public final int id;
    public final String displayName;

    PathfindingMode(int id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public static PathfindingMode fromId(int id) {
        for (var mode : values()) {
            if (mode.id == id) return mode;
        }
        return VANILLA;
    }

    public PathfindingMode next() {
        return switch (this) {
            case VANILLA -> BARITONE;
            case BARITONE -> VANILLA;
        };
    }
}
