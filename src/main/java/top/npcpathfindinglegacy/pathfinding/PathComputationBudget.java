package top.npcpathfindinglegacy.pathfinding;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import top.npcpathfindinglegacy.NpcPathfindingConfig;

final class PathComputationBudget {
    private static Object tickOwner;
    private static long currentTick = Long.MIN_VALUE;
    private static int used;
    private static int sampledBlocks;

    private PathComputationBudget() {
    }

    static synchronized boolean tryAcquire(Level level) {
        MinecraftServer server = level.getServer();
        Object owner = server == null ? level : server;
        long tick = server == null ? level.getGameTime() : server.getTickCount();
        if (tickOwner != owner || currentTick != tick) {
            tickOwner = owner;
            currentTick = tick;
            used = 0;
            sampledBlocks = 0;
        }
        if (used >= NpcPathfindingConfig.maxEnhancedSearchesPerTick) {
            return false;
        }
        used++;
        return true;
    }

    static synchronized int acquireSnapshotBlocks(Level level) {
        MinecraftServer server = level.getServer();
        Object owner = server == null ? level : server;
        long tick = server == null ? level.getGameTime() : server.getTickCount();
        if (tickOwner != owner || currentTick != tick) {
            tickOwner = owner;
            currentTick = tick;
            used = 0;
            sampledBlocks = 0;
        }
        int count = Math.min(4096, Math.max(0, 8192 - sampledBlocks));
        sampledBlocks += count;
        return count;
    }

    static synchronized void reset() {
        tickOwner = null;
        currentTick = Long.MIN_VALUE;
        used = 0;
        sampledBlocks = 0;
    }
}
