package top.npcpathfindinglegacy.traffic;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import top.npcpathfindinglegacy.pathfinding.GridPos;

/** Per-level traffic rights. The server adapter supplies positions; no entity/world references are kept. */
public final class PathReservationManager {
    public record Permission(boolean allowed, UUID waitingOn, boolean retreat) {
        public static final Permission FREE = new Permission(true, null, false);
    }
    private record Presence(double x, double y, double z, double radius, long touched) {}
    private static final class Request {
        long region, joined, touched;
        int priority;
        Request(long region, long now, int priority) { this.region = region; joined = touched = now; this.priority = priority; }
    }
    private static final class Region {
        final Set<GridPos> cells = new HashSet<>();
        UUID owner;
        long granted, progressed, touched;
        double ownerX, ownerZ;
        boolean entered;
    }
    private final Map<Long, Region> regions = new LinkedHashMap<>();
    private final Map<GridPos, Long> index = new HashMap<>();
    private final Map<UUID, Request> requests = new HashMap<>();
    private final Map<UUID, Presence> presence = new HashMap<>();
    private final Map<UUID, Long> yielding = new HashMap<>();
    private long sequence;

    public long register(Set<GridPos> cells, long now) {
        Set<Long> overlaps = new HashSet<>();
        for (GridPos cell : cells) if (index.containsKey(cell)) overlaps.add(index.get(cell));
        long id = overlaps.stream().min(Long::compare).orElseGet(() -> ++sequence);
        Region region = regions.computeIfAbsent(id, key -> new Region());
        for (long overlap : overlaps) {
            if (overlap == id) continue;
            Region merged = regions.remove(overlap);
            region.cells.addAll(merged.cells);
            if (region.owner == null && merged.owner != null) {
                region.owner = merged.owner; region.granted = merged.granted;
                region.progressed = merged.progressed; region.ownerX = merged.ownerX;
                region.ownerZ = merged.ownerZ; region.entered = merged.entered;
            }
            // Physical occupants remain blockers even when two independently scanned components merge.
            for (Request request : requests.values()) if (request.region == overlap) request.region = id;
        }
        region.cells.addAll(cells); region.touched = now;
        for (GridPos cell : region.cells) index.put(cell, id);
        return id;
    }

    public long regionAt(GridPos cell) { return index.getOrDefault(cell, 0L); }
    public boolean occupies(long regionId, UUID id) {
        Region region = regions.get(regionId); Presence p = presence.get(id);
        return region != null && p != null && inside(region,p);
    }
    public boolean mayTraverse(long regionId, UUID id) {
        Region region = regions.get(regionId);
        return region != null && (id.equals(region.owner) || occupies(regionId,id));
    }
    public boolean waitingForProgress(UUID id, long now) {
        Request request = requests.get(id);
        Region region = request == null ? null : regions.get(request.region);
        return region != null && (region.owner != null && now - region.progressed < 120
            || now - request.joined < 120);
    }

    public void observe(UUID id, double x, double y, double z, double radius, long now) {
        presence.put(id, new Presence(x, y, z, radius, now));
    }

    public Permission request(UUID id, long regionId, int priority, long now) {
        Region region = regions.get(regionId);
        if (region == null) { cancel(id); return Permission.FREE; }
        Request request = requests.get(id);
        if (request != null && request.region != regionId) cancel(id);
        request = requests.computeIfAbsent(id, key -> new Request(regionId, now, priority));
        request.touched = now; request.priority = priority; region.touched = now;
        maintainOwner(region, now);
        Presence here = presence.get(id);
        boolean inside = here != null && inside(region, here);
        if (yielding.getOrDefault(id, 0L) > now) return new Permission(false, region.owner, inside);
        if (region.owner == null) {
            UUID occupant = occupant(region, null);
            UUID next = occupant != null ? occupant : requests.entrySet().stream()
                .filter(e -> e.getValue().region == regionId && now - e.getValue().touched <= 40
                    && yielding.getOrDefault(e.getKey(), 0L) <= now)
                .min(Comparator.<Map.Entry<UUID, Request>>comparingLong(e ->
                    -(e.getValue().priority + (now - e.getValue().joined) / 40))
                    .thenComparingLong(e -> e.getValue().joined).thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey).orElse(null);
            if (next != null && requests.containsKey(next)) {
                region.owner = next; region.granted = region.progressed = now; region.entered = false;
                Presence p = presence.get(next);
                if (p != null) { region.ownerX = p.x; region.ownerZ = p.z; }
            } else if (occupant != null) return new Permission(false, occupant, inside);
        }
        if (!id.equals(region.owner)) return new Permission(false, region.owner, inside);
        UUID blocker = occupant(region, id);
        return blocker == null ? Permission.FREE : new Permission(false, blocker, false);
    }

    private void maintainOwner(Region region, long now) {
        UUID owner = region.owner;
        if (owner == null) return;
        Presence p = presence.get(owner);
        Request request = requests.get(owner);
        if (p == null || now - p.touched > 40 || request == null || now - request.touched > 40) {
            region.owner = null; return;
        }
        boolean inside = inside(region, p);
        if (region.entered && !inside) { region.owner = null; requests.remove(owner); return; }
        region.entered |= inside;
        double dx = p.x - region.ownerX, dz = p.z - region.ownerZ;
        if (dx * dx + dz * dz >= 0.04) { region.progressed = now; region.ownerX = p.x; region.ownerZ = p.z; }
        if ((!region.entered && now - region.granted > 80) || now - region.progressed > 120) {
            this.yield(owner, now + 80); // Never grant through an immobile body: occupancy is checked separately.
        }
    }

    private UUID occupant(Region region, UUID except) {
        return presence.entrySet().stream().filter(e -> !e.getKey().equals(except) && inside(region, e.getValue()))
            .map(Map.Entry::getKey).min(UUID::compareTo).orElse(null);
    }

    private boolean inside(Region region, Presence p) {
        for (int x = (int)Math.floor(p.x - p.radius); x <= (int)Math.floor(p.x + p.radius); x++)
            for (int z = (int)Math.floor(p.z - p.radius); z <= (int)Math.floor(p.z + p.radius); z++)
                for (int y = (int)Math.floor(p.y - 0.1); y <= (int)Math.floor(p.y + 0.1); y++)
                    if (region.cells.contains(new GridPos(x,y,z))) return true;
        return false;
    }

    public void yield(UUID id, long until) { cancel(id); yielding.put(id, until); }
    public void cancel(UUID id) {
        requests.remove(id);
        for (Region region : regions.values()) if (id.equals(region.owner)) region.owner = null;
    }
    public void remove(UUID id) { cancel(id); presence.remove(id); yielding.remove(id); }
    public void expire(long now) {
        presence.entrySet().removeIf(e -> now - e.getValue().touched > 40);
        requests.entrySet().removeIf(e -> now - e.getValue().touched > 40);
        yielding.values().removeIf(until -> until <= now);
        for (Region region : regions.values()) maintainOwner(region, now);
        regions.entrySet().removeIf(e -> e.getValue().owner == null && now - e.getValue().touched > 200
            && requests.values().stream().noneMatch(r -> r.region == e.getKey()));
        index.entrySet().removeIf(e -> !regions.containsKey(e.getValue()));
    }
}
