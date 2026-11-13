package top.npcpathfindinglegacy.pathfinding;

import net.minecraft.nbt.NBTTagCompound;

final class DefaultPathfindingSelfCheck {
    static void run() {
        require(DefaultPathfinding.load(new NBTTagCompound()).mode() == 0,"An old world with no default must use vanilla");
        for (int mode = 0; mode <= 7; mode++) {
            DefaultPathfinding world = new DefaultPathfinding();
            world.setMode(mode);
            NBTTagCompound saved = new NBTTagCompound();
            require(world.save(saved) == saved && saved.getInteger("PathfindingMode") == mode,"Save the selected default in world data");
            DefaultPathfinding reopened = DefaultPathfinding.load(saved);
            require(reopened.mode() == mode,"Default mode must survive reopening the world");
            reopened.setDirty(false); reopened.setMode(mode);
            require(!reopened.isDirty(),"Repeated default selection should not dirty world data");
            reopened.setMode((mode + 1) % 8);
            require(reopened.isDirty(),"A changed default must be persisted on world save");
        }
        DefaultPathfinding world = new DefaultPathfinding(); world.setMode(5);
        for (int invalid : new int[]{-1,8,Integer.MAX_VALUE}) {
            try { world.setMode(invalid); throw new AssertionError("Invalid default was accepted"); }
            catch (IllegalArgumentException expected) { require(world.mode() == 5,"Invalid defaults must leave the saved selection intact"); }
            NBTTagCompound broken = new NBTTagCompound(); broken.setInteger("PathfindingMode",invalid);
            require(DefaultPathfinding.load(broken).mode() == 0,"Invalid stored data must fall back to vanilla");
        }
        System.out.println("Default pathfinding checks passed: old-world fallback, all eight modes, save/reload, dirty tracking and invalid IDs");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
