package top.npcpathfindinglegacy.traffic;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import top.npcpathfindinglegacy.pathfinding.GridPos;

/** Per-world regional rights; physical occupancy is independent of a navigation request. */
public final class PathReservationManager {
    public static final class Permission {
        static final Permission FREE = new Permission(true, null, false), WAIT = new Permission(false, null, false);
        final boolean allowed, retreat;
        final UUID waitingOn;
        Permission(boolean allowed, UUID waitingOn, boolean retreat) {
            this.allowed = allowed; this.waitingOn = waitingOn; this.retreat = retreat;
        }
    }
    private static final class Presence {
        final double x, y, z, radius;
        final long touched;
        Presence(double x, double y, double z, double radius, long touched) {
            this.x = x; this.y = y; this.z = z; this.radius = radius; this.touched = touched;
        }
    }
    private static final class Request {
        long region, joined, touched;
        final long order;
        int priority;
        Request(long region,long now,long order) { this.region = region; joined = touched = now; this.order = order; }
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
    private long sequence, arrivalOrder;

    long register(Set<GridPos> cells, long now) {
        Set<Long> overlaps = new HashSet<>();
        for (GridPos cell : cells) if (index.containsKey(cell)) overlaps.add(index.get(cell));
        long id = overlaps.stream().min(Long::compare).orElseGet(() -> ++sequence);
        Region region = regions.computeIfAbsent(id, key -> new Region());
        for (long overlap : overlaps) {
            if (overlap == id) continue;
            Region merged = regions.remove(overlap);
            region.cells.addAll(merged.cells);
            if (region.owner == null && merged.owner != null) {
                region.owner = merged.owner; region.granted = merged.granted; region.progressed = merged.progressed;
                region.ownerX = merged.ownerX; region.ownerZ = merged.ownerZ; region.entered = merged.entered;
            }
            for (Request request : requests.values()) if (request.region == overlap) request.region = id;
        }
        region.cells.addAll(cells); region.touched = now;
        for (GridPos cell : region.cells) index.put(cell, id);
        return id;
    }

    long regionAt(GridPos cell) { return index.getOrDefault(cell, 0L); }
    boolean occupies(long regionId, UUID id) {
        Region region = regions.get(regionId); Presence p = presence.get(id);
        return region != null && p != null && inside(region,p);
    }
    boolean mayTraverse(long regionId, UUID id) {
        Region region = regions.get(regionId);
        return region != null && (id.equals(region.owner) || occupies(regionId,id));
    }
    boolean waitingForProgress(UUID id, long now) {
        Request request = requests.get(id);
        Region region = request == null ? null : regions.get(request.region);
        return region != null && (region.owner != null && now - region.progressed < 120 || now - request.joined < 120);
    }
    void observe(UUID id, double x, double y, double z, double radius, long now) {
        presence.put(id, new Presence(x,y,z,radius,now));
    }

    Permission request(UUID id, long regionId, int priority, long now) {
        Region region = regions.get(regionId);
        if (region == null) { cancel(id); return Permission.FREE; }
        Request request = requests.get(id);
        if (request != null && request.region != regionId) cancel(id);
        request = requests.computeIfAbsent(id, key -> new Request(regionId,now,++arrivalOrder));
        request.touched = now; request.priority = priority; region.touched = now;
        maintainOwner(region,now);
        boolean inside = occupies(regionId,id);
        if (yielding.getOrDefault(id,0L) > now) return new Permission(false,region.owner,inside);
        if (region.owner == null) {
            UUID occupant = occupant(region,null,now);
            Request occupiedRequest = requests.get(occupant);
            if (occupant != null && (occupiedRequest == null || occupiedRequest.region != regionId
                || yielding.getOrDefault(occupant,0L) > now)) return new Permission(false,occupant,inside);
            UUID next = occupant != null ? occupant : requests.entrySet().stream()
                .filter(e -> e.getValue().region == regionId && now - e.getValue().touched <= 40
                    && yielding.getOrDefault(e.getKey(),0L) <= now)
                .min(Comparator.<Map.Entry<UUID,Request>>comparingLong(e -> -(e.getValue().priority + (now - e.getValue().joined) / 40))
                    .thenComparingLong(e -> e.getValue().order))
                .map(Map.Entry::getKey).orElse(null);
            if (next != null) {
                region.owner = next; region.granted = region.progressed = now; region.entered = occupant != null;
                Presence p = presence.get(next);
                if (p != null) { region.ownerX = p.x; region.ownerZ = p.z; }
            }
        }
        if (!id.equals(region.owner)) return new Permission(false,region.owner,inside);
        UUID blocker = occupant(region,id,now);
        return blocker == null ? Permission.FREE : new Permission(false,blocker,false);
    }

    private void maintainOwner(Region region, long now) {
        UUID owner = region.owner;
        if (owner == null) return;
        Presence p = presence.get(owner); Request request = requests.get(owner);
        if (p == null || now - p.touched > 40 || request == null || now - request.touched > 40) {
            region.owner = null; return;
        }
        boolean inside = inside(region,p);
        if (region.entered && !inside) { region.owner = null; requests.remove(owner); return; }
        region.entered |= inside;
        double dx = p.x - region.ownerX, dz = p.z - region.ownerZ;
        if (dx * dx + dz * dz >= 0.04) { region.progressed = now; region.ownerX = p.x; region.ownerZ = p.z; }
        if ((!region.entered && now - region.granted > 80) || now - region.progressed > 120) yield(owner,now + 80);
    }

    private UUID occupant(Region region, UUID except, long now) {
        return presence.entrySet().stream().filter(e -> !e.getKey().equals(except)
            && now - e.getValue().touched <= 40 && inside(region,e.getValue())).map(Map.Entry::getKey).min(UUID::compareTo).orElse(null);
    }
    private boolean inside(Region region, Presence p) {
        for (int x = (int)Math.floor(p.x - p.radius); x <= (int)Math.floor(p.x + p.radius); x++)
            for (int z = (int)Math.floor(p.z - p.radius); z <= (int)Math.floor(p.z + p.radius); z++)
                for (int y = (int)Math.floor(p.y - 0.1); y <= (int)Math.floor(p.y + 0.1); y++)
                    if (region.cells.contains(new GridPos(x,y,z))) return true;
        return false;
    }

    void yield(UUID id, long until) { cancel(id); yielding.put(id,until); }
    void cancel(UUID id) {
        requests.remove(id);
        for (Region region : regions.values()) if (id.equals(region.owner)) region.owner = null;
    }
    void remove(UUID id) { cancel(id); presence.remove(id); yielding.remove(id); }
    void expire(long now) {
        presence.values().removeIf(p -> now - p.touched > 40);
        requests.values().removeIf(r -> now - r.touched > 40);
        yielding.values().removeIf(until -> until <= now);
        for (Region region : regions.values()) maintainOwner(region,now);
        regions.entrySet().removeIf(e -> e.getValue().owner == null && now - e.getValue().touched > 200
            && requests.values().stream().noneMatch(r -> r.region == e.getKey()));
        index.values().removeIf(id -> !regions.containsKey(id));
    }
}
