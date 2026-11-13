package top.npcpathfindinglegacy.pathfinding;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** A world default is used only by the editor creation hook, never while loading NPC NBT. */
public final class DefaultPathfinding extends SavedData {
    public static final String REQUEST_KEY = "NPC_SetDefaultPathfinding";
    public static final String SYNC_KEY = "NPC_DefaultPathfindingMode";
    private int mode;
    public int mode() { return mode; }
    public void setMode(int value) {
        if (value < 0 || value > 7) throw new IllegalArgumentException("Default pathfinding mode must be 0..7");
        if (mode != value) { mode = value; setDirty(); }
    }
    public static DefaultPathfinding get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(DefaultPathfinding::load,
            DefaultPathfinding::new,"npcpathfindinglegacy_defaults");
    }
    public static DefaultPathfinding load(CompoundTag tag) {
        DefaultPathfinding data = new DefaultPathfinding();
        int stored = tag.getInt("PathfindingMode");
        data.mode = stored >= 0 && stored <= 7 ? stored : 0;
        return data;
    }
    @Override public CompoundTag save(CompoundTag tag) { tag.putInt("PathfindingMode",mode); return tag; }
}
