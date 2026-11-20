package top.npcpathfindinglegacy.tactics.flow;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import noppes.npcs.entity.EntityNPCInterface;

/** Bounded shared fields. Capture is server-thread only; workers receive primitive arrays. */
public final class FlowFieldService {
    private static final int SIZE = 33;
    private static final class Key {
private final String dimension;
private final int faction;
private final int squad;
private final int x;
private final int y;
private final int z;
private final int width;
private final int height;
public Key(String dimension, int faction, int squad, int x, int y, int z, int width, int height) {
this.dimension=dimension;this.faction=faction;this.squad=squad;this.x=x;this.y=y;this.z=z;this.width=width;this.height=height;}
public String dimension() { return dimension; }
public int faction() { return faction; }
public int squad() { return squad; }
public int x() { return x; }
public int y() { return y; }
public int z() { return z; }
public int width() { return width; }
public int height() { return height; }
@Override public int hashCode() { return java.util.Objects.hash(dimension,faction,squad,x,y,z,width,height); }
@Override public boolean equals(Object value) { if (this==value) return true; if (!(value instanceof Key)) return false; Key other=(Key)value; return java.util.Objects.equals(dimension,other.dimension) && java.util.Objects.equals(faction,other.faction) && java.util.Objects.equals(squad,other.squad) && java.util.Objects.equals(x,other.x) && java.util.Objects.equals(y,other.y) && java.util.Objects.equals(z,other.z) && java.util.Objects.equals(width,other.width) && java.util.Objects.equals(height,other.height); }
}
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
    private static int sampled, nextField;
    private FlowFieldService() {}

    public static BlockPos waypoint(EntityNPCInterface npc, BlockPos destination, int squad) {
        return waypoint(npc,destination,squad,destination);
    }
    public static BlockPos waypoint(EntityNPCInterface npc, BlockPos destination, int squad, BlockPos fieldAnchor) {
        if (!(npc.world instanceof WorldServer) || !((WorldServer)(npc.world)).getMinecraftServer().isCallingFromMinecraftThread()) return destination;
        if (top.npcpathfindinglegacy.traffic.TrafficController.avoidsFlow(npc)) return destination;
        long time = ((WorldServer)(npc.world)).getMinecraftServer().getTickCounter();
        if (budgetTick != time) { budgetTick = time; sampled = 0; }
        FIELDS.entrySet().removeIf(e -> {
            boolean expired = time - e.getValue().touched > 80 || e.getValue().field != null && time - e.getValue().created > 200;
            if (expired && e.getValue().future != null) e.getValue().future.cancel(true);
            return expired;
        });
        int gx = Math.floorDiv(fieldAnchor.getX(), 4) * 4 + 2;
        int gz = Math.floorDiv(fieldAnchor.getZ(), 4) * 4 + 2;
        Key key = new Key(Integer.toString(npc.world.provider.getDimension()), npc.getFaction().id, squad, gx, fieldAnchor.getY(), gz,
            Math.max(1, (int) Math.ceil(npc.width * 10)), Math.max(1, (int) Math.ceil(npc.height * 10)));
        if (Math.abs(npc.posX - gx) > 15 || Math.abs(npc.posZ - gz) > 15) return destination;
        Work work = FIELDS.get(key);
        if (work == null) {
            if (FIELDS.size() >= 48) {
                Key oldest = FIELDS.keySet().iterator().next(); Work removed = FIELDS.remove(oldest);
                if (removed.future != null) removed.future.cancel(true);
            }
            work = new Work(time); FIELDS.put(key, work);
        }
        work.touched = time;
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
            try { work.field = work.future.get(); work.created = time; }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (java.util.concurrent.ExecutionException | java.util.concurrent.CancellationException failed) { FIELDS.remove(key); }
            work.future = null;
        }
        if (work.field == null) return destination;
        int x = (int) Math.floor(npc.posX) - gx + 16, z = (int) Math.floor(npc.posZ) - gz + 16;
        int index = z * SIZE + x;
        if (x < 0 || x >= SIZE || z < 0 || z >= SIZE || work.field.next(index) < 0) return destination;
        int initial = index;
        for (int step = 0; step < 5; step++) {
            int next = work.field.next(index);
            if (next < 0 || next == index) break;
            index = next;
        }
        if (index == initial || work.field.distance(index) < 3) return destination;
        BlockPos waypoint = new BlockPos(gx - 16 + index % SIZE, work.field.height(index), gz - 16 + index / SIZE);
        // A shared approach must never pull a rear slot back into the leader's position.
        if (!fieldAnchor.equals(destination) && waypoint.distanceSq(destination) + 1
            >= npc.getPositionVector().squareDistanceTo(top.npcpathfindinglegacy.compat.LegacyMath.bottom(destination))) return destination;
        return waypoint;
    }


    /** Main-thread, round-robin capture: decision throttling never stalls field generation. */
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        net.minecraft.server.MinecraftServer server = net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null || !server.isCallingFromMinecraftThread()) return;
        long time = server.getTickCounter();
        if (budgetTick != time) { budgetTick = time; sampled = 0; }
        java.util.List<Map.Entry<Key, Work>> pending = new java.util.ArrayList<>(FIELDS.entrySet());
        int idle = 0;
        while (!pending.isEmpty() && sampled < 192 && idle < pending.size()) {
            Map.Entry<Key, Work> entry = pending.get(Math.floorMod(nextField++,pending.size()));
            Key key = entry.getKey(); Work work = entry.getValue();
            if (work.cursor >= work.heights.length) { idle++; continue; }
            WorldServer level = server.getWorld(Integer.parseInt(key.dimension()));
            if (level == null || time - work.touched > 80) { idle++; continue; }
            int cursor = work.cursor++;
            work.heights[cursor] = sample(level,key.x() - 16 + cursor % SIZE,key.y(),key.z() - 16 + cursor / SIZE,
                key.width() / 10.0,key.height() / 10.0);
            sampled++; idle = 0;
        }
    }

    private static int nearestGoal(int[] heights) {
        int best = -1, score = Integer.MAX_VALUE;
        for (int z = 12; z <= 20; z++) for (int x = 12; x <= 20; x++) {
            int index = z * SIZE + x, distance = (x - 16) * (x - 16) + (z - 16) * (z - 16);
            if (heights[index] != Integer.MIN_VALUE && distance < score) { best = index; score = distance; }
        }
        return best;
    }

    private static int sample(WorldServer level,int x,int desiredY,int z,double width,double height) {
        for (int d=0;d<=3;d++) for (int sign:new int[]{1,-1}) {
            int y=desiredY+d*sign;BlockPos feet=new BlockPos(x,y,z);
            if (y<=0 || y+height>=256 || !level.isBlockLoaded(feet)) continue;
            if (!top.npcpathfindinglegacy.compat.LegacyCollision.safeSupport(level,feet.down())) continue;
            double floor=top.npcpathfindinglegacy.pathfinding.GroundSurface.bodyY(level,feet);
            AxisAlignedBB body=new AxisAlignedBB(x+0.5-width/2,floor+1.0E-5,z+0.5-width/2,x+0.5+width/2,floor+height,z+0.5+width/2);
            if (top.npcpathfindinglegacy.compat.LegacyCollision.loaded(level,body) && top.npcpathfindinglegacy.compat.LegacyCollision.blockBoxes(level,body,null).isEmpty()
                && !level.getBlockState(feet).getMaterial().isLiquid()) return y;
        }
        return Integer.MIN_VALUE;
    }

    public static void stopped(FMLServerStoppingEvent event) {
        for (Work work : FIELDS.values()) if (work.future != null) work.future.cancel(true);
        FIELDS.clear(); if (executor != null) executor.shutdownNow(); executor = null; budgetTick = Long.MIN_VALUE; sampled = 0; nextField = 0;
    }
}
