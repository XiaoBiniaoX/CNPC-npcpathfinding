package top.npcpathfindinglegacy.traffic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Wait-for graph and timed escalation; ordinary queuing never requests a new path. */
public final class DeadlockRecovery {
    public enum Action { NONE, LOCAL, BACKOFF, FULL_PATH }
    private static final class Track {
        double x, z, goodDistance;
        long progressed, nextCheck, fullAfter, touched, forcedUntil;
        int failures, priority;
        UUID blocker;
        boolean initialized;
    }
    private final Map<UUID, Track> tracks = new HashMap<>();

    public Action update(UUID id, double x, double z, boolean moving, boolean queued,
                         UUID blocker, int priority, long now) {
        Track track = tracks.computeIfAbsent(id, key -> new Track());
        track.touched = now; track.blocker = blocker; track.priority = priority;
        double distance = Math.hypot(x - track.x, z - track.z);
        if (!track.initialized || !moving || distance >= 0.2) {
            boolean hadPosition = track.initialized;
            track.initialized = true; track.x = x; track.z = z; track.progressed = now;
            if (hadPosition && distance >= 0.2) track.goodDistance += distance;
            if (!moving || track.goodDistance >= 1.5) { track.failures = 0; track.goodDistance = 0; }
        }
        if (!moving || now < track.nextCheck || now - track.progressed < 40) return Action.NONE;
        track.nextCheck = now + 40;
        UUID victim = cycleVictim(id, now);
        if (victim != null) {
            tracks.get(victim).forcedUntil = now + 80;
            if (!victim.equals(id)) return Action.NONE;
        }
        if (queued && track.forcedUntil <= now) return Action.NONE;
        if (++track.failures == 1) return Action.LOCAL;
        if (track.failures >= 4 && now >= track.fullAfter) {
            track.fullAfter = now + 200; track.failures = 2; return Action.FULL_PATH;
        }
        return Action.BACKOFF;
    }

    public UUID cycleVictim(UUID start, long now) {
        List<UUID> chain = new ArrayList<>();
        UUID current = start;
        while (current != null && chain.size() < 64) {
            int repeated = chain.indexOf(current);
            if (repeated >= 0) {
                return chain.subList(repeated, chain.size()).stream().min((a,b) -> {
                    int order = Integer.compare(tracks.get(a).priority, tracks.get(b).priority);
                    return order != 0 ? order : b.compareTo(a);
                }).orElse(null);
            }
            Track track = tracks.get(current);
            if (track == null || now - track.touched > 40) return null;
            chain.add(current); current = track.blocker;
        }
        return null;
    }
    public boolean yielding(UUID id, long now) { Track t = tracks.get(id); return t != null && t.forcedUntil > now; }
    public void remove(UUID id) { tracks.remove(id); }
    public void expire(long now) { tracks.values().removeIf(t -> now - t.touched > 100); }
}
