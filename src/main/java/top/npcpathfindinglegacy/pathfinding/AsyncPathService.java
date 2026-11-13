package top.npcpathfindinglegacy.pathfinding;

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import top.npcpathfindinglegacy.pathfinding.PlannedRoute.Edge;

/** Bounded daemon workers. Submitted closures contain immutable algorithm input only. */
public final class AsyncPathService {
    private static ThreadPoolExecutor executor;
    private static int snapshots;

    private AsyncPathService() {}

    static synchronized boolean beginSnapshot() {
        if (snapshots >= 16) return false;
        snapshots++;
        return true;
    }

    static synchronized int snapshotBlockAllowance() { return Math.max(256,8192/Math.max(1,snapshots)); }

    static synchronized void endSnapshot() { snapshots = Math.max(0, snapshots - 1); }

    static synchronized Future<PlannedRoute> submit(TerrainSnapshot terrain, RoutePlanner.Settings settings,
                                                   GridPos start, GridPos target, int accuracy, Set<Edge> failures,
                                                   CombatTactics tactics, int tieSeed) {
        if (executor == null) {
            executor = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), runnable -> {
                Thread thread = new Thread(runnable, "npc-pathfinding");
                thread.setDaemon(true);
                return thread;
            });
            executor.allowCoreThreadTimeOut(true);
        }
        RoutePlanner planner = new RoutePlanner(terrain, settings, failures, tactics,tieSeed);
        try {
            return executor.submit(() -> planner.search(start, target, accuracy));
        } catch (RejectedExecutionException full) {
            return null;
        }
    }

    static synchronized void cancel(Future<PlannedRoute> task) {
        if (task == null) return;
        task.cancel(true);
        if (executor != null) executor.purge();
    }

    public static synchronized void onServerStopping(FMLServerStoppingEvent event) {
        if (executor != null) { executor.shutdownNow(); executor = null; }
        snapshots = 0;
        PathComputationBudget.reset();
    }
}
