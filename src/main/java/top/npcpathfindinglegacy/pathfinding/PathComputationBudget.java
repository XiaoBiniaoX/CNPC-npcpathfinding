package top.npcpathfindinglegacy.pathfinding;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import top.npcpathfindinglegacy.NpcPathfindingConfig;

final class PathComputationBudget {
    private static Object tickOwner;
    private static long currentTick = Long.MIN_VALUE;
    private static int used;

    private PathComputationBudget() {
    }

    static synchronized boolean tryAcquire(World world) {
        MinecraftServer server = world.getMinecraftServer();
        Object owner = server == null ? world : server;
        long tick = server == null ? world.getTotalWorldTime() : server.getTickCounter();
        if (tickOwner != owner || currentTick != tick) {
            tickOwner = owner;
            currentTick = tick;
            used = 0;
        }
        if (used >= NpcPathfindingConfig.maxEnhancedSearchesPerTick) {
            return false;
        }
        used++;
        return true;
    }
}
