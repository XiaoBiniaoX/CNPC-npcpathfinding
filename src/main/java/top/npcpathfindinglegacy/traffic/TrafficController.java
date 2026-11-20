package top.npcpathfindinglegacy.traffic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.bus.api.SubscribeEvent;
import noppes.npcs.entity.EntityNPCInterface;
import top.npcpathfindinglegacy.NpcPathfindingConfig;
import top.npcpathfindinglegacy.pathfinding.EnhancedPathNavigator;
import top.npcpathfindinglegacy.pathfinding.GridPos;
import top.npcpathfindinglegacy.pathfinding.GroundSurface;
import top.npcpathfindinglegacy.pathfinding.IDataAIPathfinding;
import top.npcpathfindinglegacy.tactics.ITacticsData;
import top.npcpathfindinglegacy.tactics.TacticalPositions;
import top.npcpathfindinglegacy.tactics.TacticsRuntime;
import top.npcpathfindinglegacy.traffic.OrcaSolver.Agent;
import top.npcpathfindinglegacy.traffic.OrcaSolver.Velocity;

/** Server adapter. All world, entity, collision and navigation access is confined to the server thread. */
public final class TrafficController {
    private record Shape(int width, int height) {}
    private record Sample(int kind, long expires) {}
    private record ScanKey(Shape shape, GridPos seed, UUID requester) {}
    private static final class Geometry {
        final Map<GridPos, Sample> samples = new HashMap<>();
        final Map<ScanKey, NarrowPassageScanner> scans = new HashMap<>();
        long touched;
    }
    private static final class Motion {
        long touched, gateTick = Long.MIN_VALUE, fullUntil, motionTick = Long.MIN_VALUE;
        long region;
        PathReservationManager.Permission permission = PathReservationManager.Permission.FREE;
        final ArrayDeque<Vec3> maneuver = new ArrayDeque<>();
        long maneuverUntil;
        Velocity velocity = Velocity.ZERO;
        boolean direct, stopped;
    }
    private static final class WorldTraffic {
        final PathReservationManager reservations = new PathReservationManager();
        final DeadlockRecovery recovery = new DeadlockRecovery();
        final Map<UUID, Motion> motions = new HashMap<>();
        final Map<Shape, Geometry> geometry = new HashMap<>();
        final ArrayDeque<ScanKey> scans = new ArrayDeque<>();
    }
    // Values contain only numbers, UUIDs and positions, so weak keys do not retain stopped worlds.
    private static final Map<ServerLevel, WorldTraffic> WORLDS = new WeakHashMap<>();
    private TrafficController() {}

    private static boolean server(EntityNPCInterface npc) {
        return npc.level() instanceof ServerLevel level && level.getServer().isSameThread();
    }
    private static boolean enabled(EntityNPCInterface npc) {
        return NpcPathfindingConfig.enabled && npc.ais != null && npc.ais.movementType == 0
            && npc.ais instanceof IDataAIPathfinding data && (data.npcpathfindinglegacy$getMode() > 0
                || npc.ais instanceof ITacticsData tactics && tactics.npcpathfindinglegacy$getTactic() > 0)
            && npc.isAlive() && !npc.isRemoved() && !npc.isPassenger();
    }

    /** Invoked before either native or enhanced route following; waiting does not count as navigation failure. */
    public static boolean holdNavigation(EntityNPCInterface npc) {
        if (!server(npc) || !enabled(npc)) return false;
        WorldTraffic world = WORLDS.computeIfAbsent((ServerLevel)npc.level(), key -> new WorldTraffic());
        Motion motion = world.motions.computeIfAbsent(npc.getUUID(), key -> new Motion());
        long now = npc.level().getGameTime(); motion.touched = now;
        observe(world, npc, now);
        if (!motion.maneuver.isEmpty()) return true;
        if (!npc.onGround()) return false;
        if (motion.gateTick == now) return !motion.permission.allowed();
        motion.gateTick = now;
        Path path = npc.getNavigation().getPath();
        if (path == null || path.isDone()) {
            world.reservations.cancel(npc.getUUID()); motion.region = 0;
            motion.permission = PathReservationManager.Permission.FREE; return false;
        }
        GridPos seed = null;
        long region = 0;
        // Sample between nodes too: Theta* may put the next node beyond an entire doorway.
        Vec3 from = npc.position();
        double remaining = 3;
        outer: for (int i = path.getNextNodeIndex(); i < Math.min(path.getNodeCount(), path.getNextNodeIndex() + 6); i++) {
            var node = path.getNode(i);
            Vec3 to = new Vec3(node.x + 0.5, node.y, node.z + 0.5);
            double distance = from.distanceTo(to);
            int count = Math.max(1, (int)Math.ceil(Math.min(remaining, distance) * 2));
            for (int s = 0; s <= count; s++) {
                Vec3 point = from.lerp(to, distance < 1.0E-6 ? 0 : Math.min(1, s * 0.5 / distance));
                GridPos cell = grid(BlockPos.containing(point.x, point.y + 0.05, point.z));
                region = world.reservations.regionAt(cell);
                if (region != 0 || kind(world,npc,cell,now) == 2) { seed = cell; break outer; }
            }
            remaining -= distance;
            if (remaining <= 0) break;
            from = to;
        }
        if (seed == null) {
            if (motion.region != 0 && world.reservations.occupies(motion.region,npc.getUUID())) region = motion.region;
            else { world.reservations.cancel(npc.getUUID()); motion.region = 0; }
        } else if (region == 0) {
            Geometry geometry = geometry(world,npc,now);
            ScanKey key = new ScanKey(new Shape((int)Math.ceil(npc.getBbWidth() * 10),
                (int)Math.ceil(npc.getBbHeight() * 10)),seed,npc.getUUID());
            if (!geometry.scans.containsKey(key) && geometry.scans.size() >= 8) {
                motion.permission = new PathReservationManager.Permission(false,null,false); return true;
            }
            if (!geometry.scans.containsKey(key)) {
                geometry.scans.put(key,new NarrowPassageScanner(seed)); world.scans.addLast(key);
            }
            motion.permission = new PathReservationManager.Permission(false,null,false); return true;
        }
        motion.region = region;
        motion.permission = region == 0 ? PathReservationManager.Permission.FREE
            : world.reservations.request(npc.getUUID(),region,priority(npc),now);
        return !motion.permission.allowed();
    }

    /** Mob.serverAiStep calls this after navigation/custom AI and immediately before MoveControl.tick. */
    public static void tick(EntityNPCInterface npc) {
        if (!server(npc)) return;
        WorldTraffic world = WORLDS.get(npc.level());
        if (!enabled(npc)) { if (world != null) cancel(npc); return; }
        boolean held = holdNavigation(npc);
        world = WORLDS.get(npc.level());
        Motion motion = world.motions.get(npc.getUUID());
        long now = npc.level().getGameTime();
        var control = npc.getMoveControl();
        Path activePath = npc.getNavigation().getPath();
        // An asynchronous request without a route yet is not a blocked moving body.
        boolean moving = activePath != null && !activePath.isDone() || control.hasWanted() && control.getSpeedModifier() > 0;
        if (!moving && motion.maneuver.isEmpty()) {
            world.recovery.update(npc.getUUID(),npc.getX(),npc.getZ(),false,false,null,priority(npc),now);
            motion.stopped = motion.direct = false; return;
        }
        if (now >= motion.maneuverUntil) motion.maneuver.clear();
        while (!motion.maneuver.isEmpty() && npc.position().distanceToSqr(motion.maneuver.peekFirst()) < 0.09) motion.maneuver.removeFirst();
        boolean maneuvering = !motion.maneuver.isEmpty();
        Vec3 wanted = maneuvering ? motion.maneuver.peekFirst()
            : new Vec3(control.getWantedX(),control.getWantedY(),control.getWantedZ());
        boolean direct = !maneuvering && npc.getNavigation() instanceof EnhancedPathNavigator enhanced && enhanced.trafficDirectMotion();
        boolean lockedTrajectory = direct || !npc.onGround() || wanted.y > npc.getY() + 0.5
            || npc.getNavigation() instanceof EnhancedPathNavigator enhanced && enhanced.trafficLockedMotion();
        double attribute = npc.getAttributeValue(Attributes.MOVEMENT_SPEED);
        Vec3 delta = npc.getDeltaMovement();
        double dx = wanted.x - npc.getX(), dz = wanted.z - npc.getZ(), length = Math.hypot(dx,dz);
        double maxSpeed = maneuvering ? Math.min(0.16, attribute) : direct ? delta.horizontalDistance()
            : Math.min(4, attribute * control.getSpeedModifier());
        Velocity preferred = direct ? new Velocity(delta.x,delta.z)
            : length > 1.0E-6 ? new Velocity(dx,dz).scale(Math.min(length,maxSpeed) / length) : Velocity.ZERO;
        if (held && !maneuvering) preferred = Velocity.ZERO;
        double searchRadius = Math.max(8,comfort(npc) + 8 + npc.getBbWidth() + 1);
        List<EntityNPCInterface> nearby = npc.level().getEntitiesOfClass(EntityNPCInterface.class,
            npc.getBoundingBox().inflate(searchRadius,0.5,searchRadius),
            other -> other != npc && other.isAlive() && !other.isRemoved() && !other.isPassenger()
                && other.getBoundingBox().maxY > npc.getBoundingBox().minY + 0.1
                && other.getBoundingBox().minY < npc.getBoundingBox().maxY - 0.1);
        nearby.sort(Comparator.comparingDouble(npc::distanceToSqr));
        List<Agent> agents = new ArrayList<>();
        UUID blocker = motion.permission.waitingOn();
        double closest = Double.POSITIVE_INFINITY;
        for (EntityNPCInterface other : nearby) {
            observe(world,other,now);
            if (agents.size() >= 24) continue;
            Vec3 v = other.getDeltaMovement();
            Motion otherMotion = world.motions.get(other.getUUID());
            boolean reciprocal = enabled(other) && !other.getNavigation().isDone()
                && (otherMotion == null || otherMotion.permission.allowed() && otherMotion.maneuver.isEmpty());
            agents.add(new Agent(other.getId(),other.getX(),other.getZ(),v.x,v.z,other.getBbWidth() / 2,
                comfort(other),reciprocal));
            double separation = npc.distanceToSqr(other);
            if (!held && separation < closest && preferred.dot(new Velocity(other.getX() - npc.getX(),other.getZ() - npc.getZ())) > 0) {
                closest = separation; blocker = other.getUUID();
            }
        }
        Agent self = new Agent(npc.getId(),npc.getX(),npc.getZ(),delta.x,delta.z,npc.getBbWidth() / 2,comfort(npc),true);
        Velocity velocity = held && !maneuvering ? Velocity.ZERO
            : OrcaSolver.solve(self,List.copyOf(agents),preferred,maxSpeed,motion.region != 0);
        // A jump/climb keeps its planned horizontal axis; avoiding another body must not steer it off a platform.
        if (lockedTrajectory) {
            double fraction = preferred.dot(preferred) < 1.0E-9 ? 0
                : Math.max(0, Math.min(1, velocity.dot(preferred) / preferred.dot(preferred)));
            velocity = preferred.scale(fraction);
        } else if (!direct && velocity.subtract(preferred).length() > 1.0E-5 && !safeSegment(npc,npc.position(),
            npc.position().add(velocity.x() * 2,0,velocity.z() * 2))) velocity = Velocity.ZERO;
        if (!maneuvering) {
            boolean impeded = held || velocity.length() < maxSpeed * 0.35;
            DeadlockRecovery.Action action = world.recovery.update(npc.getUUID(),npc.getX(),npc.getZ(),moving,
                held && (motion.region == 0 || world.reservations.waitingForProgress(npc.getUUID(),now)),
                impeded ? blocker : null,priority(npc),now);
            if (motion.permission.retreat() || action == DeadlockRecovery.Action.BACKOFF) {
                world.reservations.yield(npc.getUUID(),now + 80);
                localManeuver(world,npc,motion,preferred,true,now);
            } else if (action == DeadlockRecovery.Action.LOCAL) localManeuver(world,npc,motion,preferred,false,now);
            else if (action == DeadlockRecovery.Action.FULL_PATH) {
                motion.fullUntil = now + 200;
                if (npc.getNavigation() instanceof EnhancedPathNavigator enhanced) enhanced.trafficReplan();
                else npc.getNavigation().recomputePath();
            }
        }
        motion.motionTick = now; motion.velocity = velocity; motion.direct = direct;
        motion.stopped = velocity.length() < 0.005 && (held || maxSpeed > 0.005);
        if (motion.stopped) {
            control.setWantedPosition(npc.getX(),npc.getY(),npc.getZ(),0);
            npc.setZza(0); npc.setXxa(0); npc.setSprinting(false); npc.setJumping(false);
            npc.setDeltaMovement(0,delta.y,0);
        } else if (direct) npc.setDeltaMovement(velocity.x(),delta.y,velocity.z());
        else if (velocity.length() > 0.005 && attribute > 0) {
            double aim = Math.max(0.25, Math.min(0.8,length));
            control.setWantedPosition(npc.getX() + velocity.x() / velocity.length() * aim,wanted.y,
                npc.getZ() + velocity.z() / velocity.length() * aim,velocity.length() / attribute);
        }
    }

    public static void afterControls(EntityNPCInterface npc) {
        if (!server(npc)) return;
        WorldTraffic world = WORLDS.get(npc.level());
        Motion motion = world == null ? null : world.motions.get(npc.getUUID());
        if (motion != null && motion.motionTick == npc.level().getGameTime() && motion.stopped) {
            npc.setJumping(false); npc.setZza(0); npc.setXxa(0);
        }
    }

    /** Reapply only direct controller locks, after CNPC's own aiStep return hooks. */
    public static void afterAi(EntityNPCInterface npc) {
        if (!server(npc)) return;
        WorldTraffic world = WORLDS.get(npc.level());
        Motion motion = world == null ? null : world.motions.get(npc.getUUID());
        if (motion != null && motion.motionTick == npc.level().getGameTime() && (motion.direct || motion.stopped)) {
            Vec3 delta = npc.getDeltaMovement();
            npc.setDeltaMovement(motion.velocity.x(),delta.y,motion.velocity.z());
        }
    }

    private static void localManeuver(WorldTraffic world, EntityNPCInterface npc, Motion motion,
                                      Velocity preferred, boolean backoff, long now) {
        Vec3 direction = preferred.length() > 0.001 ? new Vec3(preferred.x(),0,preferred.z()).normalize()
            : npc.getLookAngle().multiply(1,0,1).normalize();
        if (backoff) direction = direction.scale(-1);
        // Small local BFS only; it never changes the navigation target or submits a world-wide search.
        GridPos start = grid(npc.blockPosition());
        ArrayDeque<GridPos> queue = new ArrayDeque<>(); queue.add(start);
        Map<GridPos,GridPos> parents = new HashMap<>(); parents.put(start,null);
        GridPos best = null; double bestScore = -Double.MAX_VALUE;
        while (!queue.isEmpty() && parents.size() < 64) {
            GridPos current = queue.removeFirst();
            for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                GridPos next = current.offset(d[0],0,d[1]);
                if (parents.containsKey(next) || Math.abs(next.x() - start.x()) > 3 || Math.abs(next.z() - start.z()) > 3) continue;
                Vec3 from = GroundSurface.center(npc.level(),block(current)), to = GroundSurface.center(npc.level(),block(next));
                if (!safeSegment(npc,from,to)) continue;
                long region = world.reservations.regionAt(next);
                if (region != 0 && !world.reservations.mayTraverse(region,npc.getUUID())) continue;
                if (npc.level().getEntitiesOfClass(EntityNPCInterface.class,npc.getBoundingBox().move(to.subtract(npc.position())),
                    other -> other != npc && other.isAlive()).size() > 0) continue;
                parents.put(next,current); queue.addLast(next);
                Vec3 offset = to.subtract(npc.position());
                double along = offset.dot(direction);
                double score = along - offset.length() * 0.15 + (backoff && region == 0 ? 3 : 0);
                if (along > 0.4 && score > bestScore) { bestScore = score; best = next; }
            }
        }
        if (best == null) return;
        ArrayDeque<Vec3> route = new ArrayDeque<>();
        for (GridPos p = best; p != null && !p.equals(start); p = parents.get(p)) route.addFirst(GroundSurface.center(npc.level(),block(p)));
        motion.maneuver.clear(); motion.maneuver.addAll(route); motion.maneuverUntil = now + 60;
    }

    private static boolean safeSegment(EntityNPCInterface npc, Vec3 from, Vec3 to) {
        int samples = Math.max(1,(int)Math.ceil(from.distanceTo(to) * 4));
        for (int i = 1; i <= samples; i++) {
            Vec3 point = from.lerp(to,(double)i / samples);
            BlockPos feet = BlockPos.containing(point.x,point.y + 0.05,point.z);
            if (!TacticalPositions.standable(npc,feet)) return false;
            double floor = GroundSurface.bodyY(npc.level(),feet);
            if (Math.abs(floor - from.y) > 0.5) return false;
            var body = npc.getBoundingBox().move(point.x - npc.getX(),floor - npc.getY(),point.z - npc.getZ()).deflate(1.0E-5);
            for (int x = (int)Math.floor(body.minX); x <= (int)Math.floor(body.maxX); x++)
                for (int z = (int)Math.floor(body.minZ); z <= (int)Math.floor(body.maxZ); z++)
                    if (!npc.level().hasChunkAt(new BlockPos(x,feet.getY(),z))) return false;
            if (npc.level().getBlockCollisions(npc,body).iterator().hasNext()) return false;
        }
        return true;
    }

    private static Geometry geometry(WorldTraffic world, EntityNPCInterface npc, long now) {
        Shape shape = new Shape((int)Math.ceil(npc.getBbWidth() * 10),(int)Math.ceil(npc.getBbHeight() * 10));
        Geometry result = world.geometry.computeIfAbsent(shape,key -> new Geometry()); result.touched = now; return result;
    }
    private static void processScans(ServerLevel level, WorldTraffic world, long now) {
        int visits = world.scans.size(), budget = 48;
        while (visits-- > 0 && budget >= 4 && !world.scans.isEmpty()) {
            ScanKey key = world.scans.removeFirst();
            Geometry geometry = world.geometry.get(key.shape());
            NarrowPassageScanner scan = geometry == null ? null : geometry.scans.get(key);
            var entity = level.getEntity(key.requester());
            if (scan == null) continue;
            if (!(entity instanceof EntityNPCInterface npc) || !npc.isAlive() || !enabled(npc)) {
                geometry.scans.remove(key); continue;
            }
            budget -= 4;
            if (!scan.advance(cell -> kind(world,npc,cell,now),4)) { world.scans.addLast(key); continue; }
            geometry.scans.remove(key);
            var cells = scan.cells();
            if (cells.isEmpty()) continue;
            world.reservations.register(cells,now);
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (GridPos cell : cells) {
                minX = Math.min(minX,cell.x()); minY = Math.min(minY,cell.y()); minZ = Math.min(minZ,cell.z());
                maxX = Math.max(maxX,cell.x()); maxY = Math.max(maxY,cell.y()); maxZ = Math.max(maxZ,cell.z());
            }
            var bounds = new net.minecraft.world.phys.AABB(minX - 1,minY - 1,minZ - 1,maxX + 2,maxY + 3,maxZ + 2);
            for (EntityNPCInterface occupant : level.getEntitiesOfClass(EntityNPCInterface.class,bounds,
                other -> other.isAlive() && !other.isRemoved())) observe(world,occupant,now);
        }
    }
    private static int kind(WorldTraffic world, EntityNPCInterface npc, GridPos pos, long now) {
        Geometry geometry = geometry(world,npc,now);
        Sample cached = geometry.samples.get(pos);
        if (cached != null && cached.expires > now) return cached.kind;
        int kind = 0;
        if (TacticalPositions.standable(npc,block(pos))) {
            int exits = 0, diagonals = 0;
            for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                for (int dy : new int[]{0,1,-1}) if (TacticalPositions.standable(npc,block(pos.offset(d[0],dy,d[1])))) { exits++; break; }
            }
            for (int x : new int[]{-1,1}) for (int z : new int[]{-1,1})
                if (TacticalPositions.standable(npc,block(pos.offset(x,0,z)))) diagonals++;
            kind = NarrowPassageScanner.classify(exits,diagonals);
        }
        if (geometry.samples.size() > 16384) geometry.samples.clear();
        geometry.samples.put(pos,new Sample(kind,now + 80)); return kind;
    }
    private static void observe(WorldTraffic world, EntityNPCInterface npc, long now) {
        world.reservations.observe(npc.getUUID(),npc.getX(),npc.getBoundingBox().minY,npc.getZ(),npc.getBbWidth() / 2,now);
    }
    public static double comfort(EntityNPCInterface npc) {
        return npc.ais instanceof IDataAIPathfinding data ? data.npcpathfindinglegacy$getComfortDistance() : 1.4;
    }
    private static int priority(EntityNPCInterface npc) {
        var state = TacticsRuntime.state(npc);
        return npc.getHealth() < npc.getMaxHealth() * 0.25 ? 2 : state != null && state.active.id() == 5 ? 1 : 0;
    }
    public static boolean avoidsFlow(EntityNPCInterface npc) {
        if (!server(npc)) return false;
        WorldTraffic world = WORLDS.get(npc.level()); Motion motion = world == null ? null : world.motions.get(npc.getUUID());
        return motion != null && motion.fullUntil > npc.level().getGameTime();
    }
    public static void cancel(EntityNPCInterface npc) {
        if (!server(npc)) return;
        WorldTraffic world = WORLDS.get(npc.level());
        if (world != null) {
            world.scans.removeIf(key -> key.requester().equals(npc.getUUID()));
            world.geometry.values().forEach(geometry -> geometry.scans.keySet().removeIf(key -> key.requester().equals(npc.getUUID())));
            world.reservations.cancel(npc.getUUID()); world.recovery.remove(npc.getUUID());
            Motion motion = world.motions.get(npc.getUUID());
            if (motion != null) {
                motion.maneuver.clear(); motion.region = 0; motion.gateTick = Long.MIN_VALUE; motion.motionTick = Long.MIN_VALUE;
                motion.permission = PathReservationManager.Permission.FREE; motion.stopped = motion.direct = false;
            }
        }
    }
    public static void removed(EntityNPCInterface npc) {
        if (!server(npc)) return;
        WorldTraffic world = WORLDS.get(npc.level());
        if (world != null) {
            cancel(npc); world.reservations.remove(npc.getUUID()); world.recovery.remove(npc.getUUID()); world.motions.remove(npc.getUUID());
        }
    }
    public static boolean overridesMovement(EntityNPCInterface npc) {
        if (!server(npc)) return false;
        WorldTraffic world = WORLDS.get(npc.level()); Motion motion = world == null ? null : world.motions.get(npc.getUUID());
        return motion != null && (!motion.maneuver.isEmpty() || motion.stopped && motion.motionTick == npc.level().getGameTime());
    }
    @SubscribeEvent public static void tickLevel(LevelTickEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()) return;
        WorldTraffic world = WORLDS.get(level);
        if (world == null) return;
        long now = level.getGameTime();
        processScans(level,world,now);
        if (now % 20 != 0) return;
        world.motions.entrySet().removeIf(e -> {
            var entity = level.getEntity(e.getKey());
            boolean expired = entity == null || !entity.isAlive() || now - e.getValue().touched > 100;
            if (expired) { world.reservations.remove(e.getKey()); world.recovery.remove(e.getKey()); }
            return expired;
        });
        world.reservations.expire(now); world.recovery.expire(now);
        world.geometry.values().removeIf(g -> now - g.touched > 200);
    }
    @SubscribeEvent public static void died(LivingDeathEvent event) { if (event.getEntity() instanceof EntityNPCInterface npc) removed(npc); }
    @SubscribeEvent public static void unload(LevelEvent.Unload event) { if (event.getLevel() instanceof ServerLevel level) WORLDS.remove(level); }
    @SubscribeEvent public static void stopped(ServerStoppingEvent event) { WORLDS.clear(); }
    private static GridPos grid(BlockPos pos) { return new GridPos(pos.getX(),pos.getY(),pos.getZ()); }
    private static BlockPos block(GridPos pos) { return new BlockPos(pos.x(),pos.y(),pos.z()); }
}
