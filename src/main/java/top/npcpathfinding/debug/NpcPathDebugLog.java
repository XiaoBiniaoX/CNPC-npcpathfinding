package top.npcpathfinding.debug;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Opt-in diagnostic log. Default OFF for TPS.
 * Enable: -Dnpcpathfinding.debug=true  or set ENABLED = true for local debug.
 */
public final class NpcPathDebugLog {
    /** Default false — production TPS. Set true only while debugging. */
    public static boolean ENABLED = Boolean.getBoolean("npcpathfinding.debug");

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final Object LOCK = new Object();
    private static java.nio.file.Path logFile;
    private static BufferedWriter writer;
    private static boolean initFailed;
    private static long lastEventMs;
    private static int eventsThisSecond;
    private static long lastSnapshotMs;
    private static int linesSinceFlush;

    private NpcPathDebugLog() {}

    public static void sessionStart() {
        if (!ENABLED) return;
        init();
        line("======== SESSION " + LocalDateTime.now() + " ========");
        line("cwd=" + Paths.get("").toAbsolutePath());
        if (logFile != null) line("file=" + logFile.toAbsolutePath());
    }

    public static void line(String msg) {
        if (!ENABLED) return;
        init();
        if (writer == null) return;
        String out = TS.format(LocalDateTime.now()) + " | " + msg;
        synchronized (LOCK) {
            try {
                writer.write(out);
                writer.newLine();
                linesSinceFlush++;
                if (linesSinceFlush >= 16) {
                    writer.flush();
                    linesSinceFlush = 0;
                }
            } catch (IOException ignored) {
            }
        }
    }

    public static void event(Mob mob, String tag, String detail) {
        if (!ENABLED) return;
        long now = System.currentTimeMillis();
        if (now / 1000L != lastEventMs / 1000L) {
            lastEventMs = now;
            eventsThisSecond = 0;
        }
        eventsThisSecond++;
        // Hard cap all tags (incl. BAN/CLIMB/PARKOUR) to protect TPS.
        if (eventsThisSecond > 20) return;
        if (mob == null) {
            line("[" + tag + "] " + detail);
            return;
        }
        line(String.format("[%s] id=%d pos=%.2f,%.2f,%.2f onG=%s collH=%s | %s",
                tag, mob.getId(), mob.getX(), mob.getY(), mob.getZ(),
                mob.onGround(), mob.horizontalCollision, detail));
    }

    public static void pathSnapshot(String tag, Path path, BlockPos goal) {
        if (!ENABLED) return;
        long now = System.currentTimeMillis();
        if (now - lastSnapshotMs < 200L) return;
        lastSnapshotMs = now;
        if (path == null) {
            line("[" + tag + "] path=null goal=" + pos(goal));
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(tag).append("] nodes=").append(path.getNodeCount())
                .append(" idx=").append(path.getNextNodeIndex())
                .append(" done=").append(path.isDone())
                .append(" reach=").append(path.canReach())
                .append(" goal=").append(pos(goal)).append(" | ");
        int from = Math.max(0, path.getNextNodeIndex() - 1);
        int to = Math.min(path.getNodeCount(), path.getNextNodeIndex() + 6);
        for (int i = from; i < to; i++) {
            Node n = path.getNode(i);
            if (i == path.getNextNodeIndex()) sb.append(">");
            sb.append(n.x).append(',').append(n.y).append(',').append(n.z);
            if (i + 1 < to) sb.append(" -> ");
        }
        line(sb.toString());
    }

    public static void followTick(Mob mob, long gameTime, BlockPos goal, Path path,
                                  String branch, boolean needsElev, boolean onPad,
                                  boolean trapped, int jumpCd, boolean inHop,
                                  BlockPos hopTarget, int hopAir, Vec3 motion) {
        if (!ENABLED) return;
        StringBuilder sb = new StringBuilder();
        sb.append("FOLLOW t=").append(gameTime)
                .append(" branch=").append(branch)
                .append(" elev=").append(needsElev)
                .append(" pad=").append(onPad)
                .append(" trap=").append(trapped)
                .append(" jCd=").append(jumpCd)
                .append(" hop=").append(inHop)
                .append(" hopT=").append(pos(hopTarget))
                .append(" air=").append(hopAir)
                .append(" goal=").append(pos(goal))
                .append(" mot=").append(String.format("%.3f,%.3f,%.3f", motion.x, motion.y, motion.z));
        if (path != null && !path.isDone()) {
            int i = path.getNextNodeIndex();
            if (i < path.getNodeCount()) {
                Node n = path.getNode(i);
                sb.append(" next=").append(n.x).append(',').append(n.y).append(',').append(n.z);
            }
            sb.append(" pIdx=").append(i).append('/').append(path.getNodeCount());
        } else {
            sb.append(" path=").append(path == null ? "null" : "done");
        }
        event(mob, "AI", sb.toString());
    }

    public static String pos(BlockPos p) {
        return p == null ? "null" : (p.getX() + "," + p.getY() + "," + p.getZ());
    }

    private static void init() {
        if (!ENABLED || writer != null || initFailed) return;
        synchronized (LOCK) {
            if (writer != null || initFailed) return;
            try {
                java.nio.file.Path cwd = Paths.get("").toAbsolutePath();
                java.nio.file.Path logs = cwd.resolve("logs");
                Files.createDirectories(logs);
                logFile = logs.resolve("npcpathfinding-debug.log");
                // Truncate on session so old thrash logs don't grow forever.
                writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                linesSinceFlush = 0;
            } catch (IOException e) {
                initFailed = true;
                logFile = null;
                writer = null;
            }
        }
    }
}
