package top.npcpathfindinglegacy;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import top.npcpathfindinglegacy.pathfinding.AsyncPathService;
import top.npcpathfindinglegacy.tactics.TacticalPositions;
import top.npcpathfindinglegacy.tactics.flow.FlowFieldService;
import top.npcpathfindinglegacy.tactics.group.SquadService;
import top.npcpathfindinglegacy.traffic.TrafficController;

/** Use the real Forge event hierarchy without launching Minecraft. */
public final class LegacyLifecycleSelfCheck {
    public static void main(String[] args) throws Exception {
        Class<?>[] services = {AsyncPathService.class, FlowFieldService.class,
            SquadService.class, TacticalPositions.class, TrafficController.class};
        for (Class<?> service : services) {
            for (Method method : service.getDeclaredMethods()) {
                if (method.isAnnotationPresent(SubscribeEvent.class)) {
                    require(method.getParameterCount() == 1
                        && Event.class.isAssignableFrom(method.getParameterTypes()[0]),
                        "Invalid Forge event subscriber: " + method);
                }
            }
        }
        Method stopping = NpcPathfindingLegacy.class.getDeclaredMethod("serverStopping", FMLServerStoppingEvent.class);
        require(stopping.isAnnotationPresent(Mod.EventHandler.class), "Missing FML lifecycle handler");

        // Seed state to verify that the actual FML entry still shuts down both workers and clears caches.
        ThreadPoolExecutor paths = executor(), flow = executor();
        field(AsyncPathService.class, "executor").set(null, paths);
        field(AsyncPathService.class, "snapshots").setInt(null, 3);
        field(FlowFieldService.class, "executor").set(null, flow);
        cache(SquadService.class, "SQUADS").put(new Object(), new Object());
        cache(TacticalPositions.class, "RESERVED").put(new Object(), new Object());
        Object world = new Object();
        cache(TrafficController.class, "WORLDS").put(world, new Object());
        try {
            new NpcPathfindingLegacy().serverStopping(null);
            require(paths.isShutdown() && flow.isShutdown(), "Worker pools must shut down");
            require(field(AsyncPathService.class, "executor").get(null) == null
                && field(FlowFieldService.class, "executor").get(null) == null, "Pools must reset for the next world");
            require(field(AsyncPathService.class, "snapshots").getInt(null) == 0, "Snapshot budget must reset");
            require(cache(FlowFieldService.class, "FIELDS").isEmpty()
                && cache(SquadService.class, "SQUADS").isEmpty()
                && cache(TacticalPositions.class, "RESERVED").isEmpty()
                && !cache(TrafficController.class, "WORLDS").containsKey(world), "World caches must clear");
        } finally {
            paths.shutdownNow(); flow.shutdownNow();
        }
        System.out.println("Legacy lifecycle checks passed: real Forge subscriber types, FML stopping entry, worker shutdown and world cache reset");
    }
    private static ThreadPoolExecutor executor() {
        return new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
    }
    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); return field;
    }
    @SuppressWarnings("unchecked")
    private static Map<Object, Object> cache(Class<?> owner, String name) throws Exception {
        return (Map<Object, Object>) field(owner, name).get(null);
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
