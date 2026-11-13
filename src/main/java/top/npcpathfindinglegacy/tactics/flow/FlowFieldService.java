package top.npcpathfindinglegacy.tactics.flow;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import noppes.npcs.entity.EntityNPCInterface;

/** Bounded shared fields. Capture is server-thread only; workers receive primitive arrays. */
public final class FlowFieldService {
    private static final int SIZE = 33;
    private record Key(ResourceLocation dimension, int faction, int squad, int x, int y, int z, int width, int height) {}
    private static final class Work {
        final int[] heights = new int[SIZE * SIZE];
        int cursor;
        long touched, created;
        Future<FlowField> future;
        FlowField field;
        Work(long time) { touched = created = time; Arrays.fill(heights, Integer.MIN_VALUE); }
    }
    private static final Map<Key, Work> FIELDS = new LinkedHashMap<>(64, 0.75f, true);
    private static ThreadPoolExecutor executor;
    private static long budgetTick = Long.MIN_VALUE;
    private static int sampled;
    private FlowFieldService() {}

    public static BlockPos waypoint(EntityNPCInterface npc, BlockPos destination, int squad) {
        if (!(npc.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) return destination;
        long time = level.getServer().getTickCount();
        if (budgetTick != time) { budgetTick = time; sampled = 0; }
        FIELDS.entrySet().removeIf(e -> {
            boolean expired = time - e.getValue().touched > 80 || time - e.getValue().created > 200;
            if (expired && e.getValue().future != null) e.getValue().future.cancel(true);
            return expired;
        });
        int gx = Math.floorDiv(destination.getX(), 4) * 4 + 2;
        int gz = Math.floorDiv(destination.getZ(), 4) * 4 + 2;
        Key key = new Key(level.dimension().location(), npc.getFaction().id, squad, gx, destination.getY(), gz,
            Math.max(1, (int) Math.ceil(npc.getBbWidth() * 10)), Math.max(1, (int) Math.ceil(npc.getBbHeight() * 10)));
        if (Math.abs(npc.getX() - gx) > 15 || Math.abs(npc.getZ() - gz) > 15) return destination;
        Work work = FIELDS.get(key);
        if (work == null) {
            if (FIELDS.size() >= 48) {
                Key oldest = FIELDS.keySet().iterator().next(); Work removed = FIELDS.remove(oldest);
                if (removed.future != null) removed.future.cancel(true);
            }
            work = new Work(time); FIELDS.put(key, work);
        }
        work.touched = time;
        while (work.cursor < work.heights.length && sampled < 192) {
            int x = gx - 16 + work.cursor % SIZE, z = gz - 16 + work.cursor / SIZE;
            work.heights[work.cursor++] = sample(level, x, key.y(), z, key.width() / 10.0, key.height() / 10.0);
            sampled++;
        }
        if (work.cursor == work.heights.length && work.future == null && work.field == null) {
            if (executor == null) {
                executor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), r -> {
                    Thread thread = new Thread(r, "npc-flow-field"); thread.setDaemon(true); return thread;
                });
                executor.allowCoreThreadTimeOut(true);
            }
            int[] frozen = work.heights.clone(); int goal = nearestGoal(frozen);
            try { work.future = executor.submit(() -> new FlowField(SIZE, SIZE, frozen, goal)); }
            catch (java.util.concurrent.RejectedExecutionException full) { return destination; }
        }
        if (work.future != null && work.future.isDone()) {
            try { work.field = work.future.get(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (java.util.concurrent.ExecutionException | java.util.concurrent.CancellationException failed) { FIELDS.remove(key); }
            work.future = null;
        }
        if (work.field == null) return destination;
        int x = (int) Math.floor(npc.getX()) - gx + 16, z = (int) Math.floor(npc.getZ()) - gz + 16;
        int index = z * SIZE + x;
        if (x < 0 || x >= SIZE || z < 0 || z >= SIZE || work.field.next(index) < 0) return destination;
        int initial = index;
        for (int step = 0; step < 5; step++) {
            int next = work.field.next(index);
            if (next < 0 || next == index) break;
            index = next;
        }
        if (index == initial || work.field.distance(index) < 3) return destination;
        return new BlockPos(gx - 16 + index % SIZE, work.field.height(index), gz - 16 + index / SIZE);
    }

    private static int nearestGoal(int[] heights) {
        int best = -1, score = Integer.MAX_VALUE;
        for (int z = 12; z <= 20; z++) for (int x = 12; x <= 20; x++) {
            int index = z * SIZE + x, distance = (x - 16) * (x - 16) + (z - 16) * (z - 16);
            if (heights[index] != Integer.MIN_VALUE && distance < score) { best = index; score = distance; }
        }
        return best;
    }

    private static int sample(ServerLevel level, int x, int desiredY, int z, double width, double height) {
        for (int d = 0; d <= 3; d++) for (int sign : new int[]{1, -1}) {
            int y = desiredY + d * sign;
            BlockPos feet = new BlockPos(x, y, z);
            if (y <= level.getMinBuildHeight() || y + height >= level.getMaxBuildHeight() || !level.hasChunkAt(feet)) continue;
            BlockPos below = feet.below();
            var support = level.getBlockState(below);
            if (!support.getFluidState().isEmpty() || support.getCollisionShape(level, below).isEmpty()
                || !support.isFaceSturdy(level, below, net.minecraft.core.Direction.UP)
                || top.npcpathfindinglegacy.NpcPathfindingConfig.isBlacklisted(support.getBlock())
                || support.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)
                || support.is(net.minecraft.world.level.block.Blocks.CACTUS)
                || support.is(net.minecraft.world.level.block.Blocks.CAMPFIRE)
                || support.is(net.minecraft.world.level.block.Blocks.SOUL_CAMPFIRE)) continue;
            double floor=top.npcpathfindinglegacy.pathfinding.GroundSurface.bodyY(level,feet);
            AABB body = new AABB(x + 0.5 - width / 2, floor + 1.0E-5, z + 0.5 - width / 2,
                x + 0.5 + width / 2, floor + height, z + 0.5 + width / 2);
            boolean loaded = true;
            for (int px = (int) Math.floor(body.minX); px <= (int) Math.floor(body.maxX); px++)
                for (int pz = (int) Math.floor(body.minZ); pz <= (int) Math.floor(body.maxZ); pz++)
                    if (!level.hasChunkAt(new BlockPos(px, y, pz))) loaded = false;
            if (loaded && level.noCollision(body) && level.getFluidState(feet).isEmpty()) return y;
        }
        return Integer.MIN_VALUE;
    }

    @SubscribeEvent public static void stopped(ServerStoppingEvent event) {
        for (Work work : FIELDS.values()) if (work.future != null) work.future.cancel(true);
        FIELDS.clear(); if (executor != null) executor.shutdownNow(); executor = null; budgetTick = Long.MIN_VALUE;
    }
}
