package top.npcpathfindinglegacy.pathfinding;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraft.world.storage.MapStorage;

/** The world default applies only at the editor's new NPC creation entry. */
public final class DefaultPathfinding extends WorldSavedData {
    public static final String REQUEST_KEY="NPC_SetDefaultPathfinding", SYNC_KEY="NPC_DefaultPathfindingMode";
    private static final String NAME="npcpathfindinglegacy_defaults";
    private int mode;
    public DefaultPathfinding() { super(NAME); }
    public DefaultPathfinding(String name) { super(name); }
    public int mode() { return mode; }
    public void setMode(int value) {
        if (value<0 || value>7) throw new IllegalArgumentException("Default pathfinding mode must be 0..7");
        if (mode!=value) { mode=value;markDirty(); }
    }
    public static DefaultPathfinding get(WorldServer level) {
        MapStorage storage=level.getMinecraftServer().getWorld(0).getMapStorage();
        DefaultPathfinding data=(DefaultPathfinding)storage.getOrLoadData(DefaultPathfinding.class,NAME);
        if (data==null) { data=new DefaultPathfinding();storage.setData(NAME,data); }
        return data;
    }
    public static DefaultPathfinding load(NBTTagCompound tag) {
        DefaultPathfinding data=new DefaultPathfinding();data.readFromNBT(tag);return data;
    }
    @Override public void readFromNBT(NBTTagCompound tag) { int stored=tag.getInteger("PathfindingMode");mode=stored>=0 && stored<=7 ? stored : 0; }
    @Override public NBTTagCompound writeToNBT(NBTTagCompound tag) { tag.setInteger("PathfindingMode",mode);return tag; }
    public NBTTagCompound save(NBTTagCompound tag) { return writeToNBT(tag); }
}
