package top.npcpathfindinglegacy.traffic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.entity.Entity;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.EntityMoveHelper;
import net.minecraft.pathfinding.Path;
import net.minecraft.pathfinding.PathPoint;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import noppes.npcs.entity.EntityNPCInterface;
import top.npcpathfindinglegacy.NpcPathfindingConfig;
import top.npcpathfindinglegacy.compat.LegacyCollision;
import top.npcpathfindinglegacy.compat.LegacyMath;
import top.npcpathfindinglegacy.pathfinding.EnhancedPathNavigator;
import top.npcpathfindinglegacy.pathfinding.GridPos;
import top.npcpathfindinglegacy.pathfinding.GroundSurface;
import top.npcpathfindinglegacy.pathfinding.IDataAIPathfinding;
import top.npcpathfindinglegacy.tactics.ITacticsData;
import top.npcpathfindinglegacy.tactics.TacticalPositions;
import top.npcpathfindinglegacy.tactics.TacticsRuntime;
import top.npcpathfindinglegacy.traffic.OrcaSolver.Agent;
import top.npcpathfindinglegacy.traffic.OrcaSolver.Velocity;
import top.npcpathfindinglegacy.traffic.PathReservationManager.Permission;

/** Server adapter: no world, NPC, or navigation object is captured by worker jobs. */
public final class TrafficController {
    private static final int[][] HORIZONTAL = {{1,0},{-1,0},{0,1},{0,-1}};
    private static final class Sample {
        final int kind;
        final long expires;
        Sample(int kind,long expires) { this.kind = kind; this.expires = expires; }
    }
    private static final class Scan {
        final UUID requester;
        final GridPos seed;
        final NarrowPassageScanner scanner;
        Scan(UUID requester,GridPos seed) { this.requester = requester; this.seed = seed; scanner = new NarrowPassageScanner(seed); }
    }
    private static final class Motion {
        long touched, gateTick = Long.MIN_VALUE, motionTick = Long.MIN_VALUE, fullUntil, maneuverUntil;
        long region;
        Permission permission = Permission.FREE;
        final ArrayDeque<Vec3d> maneuver = new ArrayDeque<>();
        Velocity directLimit;
        boolean stopped;
    }
    private static final class WorldTraffic {
        final PathReservationManager reservations = new PathReservationManager();
        final DeadlockRecovery recovery = new DeadlockRecovery();
        final Map<UUID,Motion> motions = new HashMap<>();
        final Map<Long,Map<GridPos,Sample>> samples = new HashMap<>();
        final ArrayDeque<Scan> scans = new ArrayDeque<>();
    }
    private static final Map<WorldServer,WorldTraffic> WORLDS = new WeakHashMap<>();
    private TrafficController() {}

    private static boolean server(EntityNPCInterface npc) {
        return npc.world instanceof WorldServer && npc.world.getMinecraftServer().isCallingFromMinecraftThread();
    }
    private static boolean enabled(EntityNPCInterface npc) {
        return NpcPathfindingConfig.enabled && npc.ais instanceof IDataAIPathfinding && npc.ais.movementType == 0
            && (((IDataAIPathfinding)npc.ais).npcpathfindinglegacy$getMode() > 0
                || npc.ais instanceof ITacticsData && ((ITacticsData)npc.ais).npcpathfindinglegacy$getTactic() > 0)
            && npc.isEntityAlive() && !npc.isRiding();
    }

    /** Shared gate for enhanced and native navigation. Waiting does not advance the path cursor. */
    public static boolean holdNavigation(EntityNPCInterface npc) {
        if (!server(npc) || !enabled(npc)) return false;
        WorldTraffic world = WORLDS.computeIfAbsent((WorldServer)npc.world,key -> new WorldTraffic());
        Motion motion = world.motions.computeIfAbsent(npc.getUniqueID(),key -> new Motion());
        long now = npc.world.getTotalWorldTime(); motion.touched = now;
        observe(world,npc,now);
        if (now >= motion.maneuverUntil) motion.maneuver.clear();
        if (!npc.onGround) return false;
        if (!motion.maneuver.isEmpty()) return true;
        if (motion.gateTick == now) return !motion.permission.allowed;
        Path path = npc.getNavigator().getPath();
        if (path == null || path.isFinished()) {
            world.reservations.cancel(npc.getUniqueID()); motion.region = 0; motion.permission = Permission.FREE;
            return false;
        }
        motion.gateTick = now;
        GridPos seed = null;
        long region = 0;
        // Also sample between nodes: a smoothed path can skip the whole doorway.
        Vec3d from = npc.getPositionVector();
        double remaining = Math.max(3,npc.width + 1);
        int start = path.getCurrentPathIndex();
        outer: for (int i = start; i < Math.min(path.getCurrentPathLength(),start + 6); i++) {
            Vec3d to = pathPosition(npc,path,i);
            double distance = from.distanceTo(to);
            int count = Math.max(1,(int)Math.ceil(Math.min(remaining,distance) * 2));
            for (int s = 0; s <= count; s++) {
                Vec3d point = LegacyMath.lerp(from,to,distance < 1.0E-6 ? 0 : Math.min(1,s * 0.5 / distance));
                GridPos cell = grid(new BlockPos(point.x,point.y + 0.05,point.z));
                region = world.reservations.regionAt(cell);
                if (region != 0 || kind(world,npc,cell,now) == 2) { seed = cell; break outer; }
            }
            remaining -= distance;
            if (remaining <= 0) break;
            from = to;
        }
        if (seed == null) {
            if (motion.region != 0 && world.reservations.occupies(motion.region,npc.getUniqueID())) region = motion.region;
            else world.reservations.cancel(npc.getUniqueID());
        } else if (region == 0) {
            // One outstanding scan per requester; round-robin work is advanced by WorldTickEvent.
            final GridPos entrance = seed;
            if (world.scans.stream().noneMatch(scan -> scan.requester.equals(npc.getUniqueID()) || scan.seed.equals(entrance)))
                world.scans.addLast(new Scan(npc.getUniqueID(),entrance));
            motion.permission = Permission.WAIT;
            return true;
        }
        motion.region = region;
        motion.permission = region == 0 ? Permission.FREE : world.reservations.request(npc.getUniqueID(),region,priority(npc),now);
        return !motion.permission.allowed;
    }

    private static Vec3d pathPosition(EntityNPCInterface npc,Path path,int index) {
        if (!(npc.getNavigator() instanceof EnhancedPathNavigator)) return path.getVectorFromIndex(npc,index);
        PathPoint node = path.getPathPointFromIndex(index);
        return new Vec3d(node.x + 0.5,node.y,node.z + 0.5);
    }

    /** Runs after route/goal updates and before the move helper consumes this tick's command. */
    public static void tick(EntityNPCInterface npc) {
        if (!server(npc)) return;
        if (!enabled(npc)) { cancel(npc); return; }
        boolean held = holdNavigation(npc);
        WorldTraffic world = WORLDS.get(npc.world);
        UUID id = npc.getUniqueID();
        Motion motion = world.motions.get(id);
        long now = npc.world.getTotalWorldTime();
        motion.motionTick = now; motion.stopped = false; motion.directLimit = null;
        Vec3d position = npc.getPositionVector();
        if (!motion.maneuver.isEmpty()) world.recovery.reposition(id,npc.posX,npc.posZ,now);
        while (!motion.maneuver.isEmpty() && position.squareDistanceTo(motion.maneuver.peekFirst()) < 0.09)
            motion.maneuver.removeFirst();
        boolean maneuvering = !motion.maneuver.isEmpty() && npc.onGround;
        EntityMoveHelper control = npc.getMoveHelper();
        EnhancedPathNavigator enhanced = npc.getNavigator() instanceof EnhancedPathNavigator ? (EnhancedPathNavigator)npc.getNavigator() : null;
        boolean direct = !maneuvering && enhanced != null && enhanced.trafficDirectMotion();
        boolean commanded = control.isUpdating() && control.getSpeed() > 0;
        if (!held && !maneuvering && !direct && !commanded) {
            if (npc.getNavigator().noPath()) world.recovery.update(id,npc.posX,npc.posZ,false,false,null,priority(npc),now);
            return;
        }
        Vec3d wanted = maneuvering ? motion.maneuver.peekFirst() : new Vec3d(control.getX(),control.getY(),control.getZ());
        Path path = npc.getNavigator().getPath();
        if (held && !maneuvering && path != null && !path.isFinished()) {
            wanted = pathPosition(npc,path,path.getCurrentPathIndex());
        }
        double attribute = npc.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).getAttributeValue();
        double dx = wanted.x - npc.posX, dz = wanted.z - npc.posZ, length = Math.hypot(dx,dz);
        Vec3d delta = LegacyMath.motion(npc);
        double maxSpeed = maneuvering ? Math.min(0.16,attribute) : direct ? LegacyMath.horizontal(delta)
            : Math.min(4,attribute * control.getSpeed());
        Velocity heading = length > 1.0E-6 ? new Velocity(dx / length,dz / length) : Velocity.ZERO;
        Velocity preferred = direct ? new Velocity(delta.x,delta.z) : heading.scale(Math.min(length,maxSpeed));
        boolean waiting = held && !maneuvering && !direct;
        boolean locked = direct || !npc.onGround || !waiting && wanted.y > npc.posY + 0.5;
        AxisAlignedBB body = npc.getEntityBoundingBox();
        double comfort = comfort(npc), radius = Math.max(8,comfort + 9 + bodyRadius(npc));
        List<EntityNPCInterface> nearby = npc.world.getEntitiesWithinAABB(EntityNPCInterface.class,body.grow(radius,0.5,radius),
            other -> other != npc && other.isEntityAlive() && !other.isRiding()
                && other.getEntityBoundingBox().maxY > body.minY + 0.1 && other.getEntityBoundingBox().minY < body.maxY - 0.1);
        nearby.sort(Comparator.comparingDouble(npc::getDistanceSq));
        List<Agent> agents = new ArrayList<>();
        UUID blocker = motion.permission.waitingOn;
        for (EntityNPCInterface other : nearby) {
            observe(world,other,now);
            if (agents.size() == 24) continue;
            Motion otherMotion = world.motions.get(other.getUniqueID());
            boolean reciprocal = enabled(other) && !other.getNavigator().noPath()
                && (otherMotion == null || otherMotion.permission.allowed && otherMotion.maneuver.isEmpty());
            agents.add(new Agent(other.getEntityId(),other.posX,other.posZ,other.motionX,other.motionZ,bodyRadius(other),comfort(other),reciprocal));
            if (blocker == null && heading.dot(new Velocity(other.posX - npc.posX,other.posZ - npc.posZ)) > 0
                && npc.getDistanceSq(other) < 9) blocker = other.getUniqueID();
        }
        Agent self = new Agent(npc.getEntityId(),npc.posX,npc.posZ,delta.x,delta.z,bodyRadius(npc),comfort,true);
        Velocity velocity = waiting ? Velocity.ZERO : OrcaSolver.solve(self,agents,preferred,maxSpeed,motion.region != 0,locked);
        if (!waiting && !locked && velocity.subtract(preferred).length() > 1.0E-5
            && !safeSegment(npc,position,position.addVector(velocity.x * 2,0,velocity.z * 2))) {
            velocity = OrcaSolver.solve(self,agents,preferred,maxSpeed,true,true);
        }
        boolean replan = false;
        if (!maneuvering && !direct && npc.onGround) {
            boolean impeded = waiting || velocity.length() < maxSpeed * 0.35 || npc.collidedHorizontally;
            DeadlockRecovery.Action action = world.recovery.update(id,npc.posX,npc.posZ,true,
                waiting && !motion.permission.retreat && (motion.permission == Permission.WAIT || world.reservations.waitingForProgress(id,now)),
                impeded ? blocker : null,priority(npc),now);
            if (action == DeadlockRecovery.Action.BACKOFF) {
                if (localManeuver(world,npc,motion,heading,true,now) && (motion.permission.retreat || !waiting))
                    world.reservations.yield(id,now + 80);
            } else if (action == DeadlockRecovery.Action.LOCAL) localManeuver(world,npc,motion,heading,false,now);
            else if (action == DeadlockRecovery.Action.FULL_PATH) {
                motion.fullUntil = now + 200;
                if (enhanced != null) enhanced.trafficReplan(); else npc.getNavigator().updatePath();
                replan = true;
            }
        }
        motion.stopped = replan || waiting || velocity.length() < 0.005 && preferred.length() > 0.005;
        if (motion.stopped) {
            control.setMoveTo(npc.posX,npc.posY,npc.posZ,0);
            npc.setSprinting(false);
            LegacyMath.motion(npc,0,delta.y,0);
        } else if (direct && velocity.subtract(preferred).length() > 1.0E-5) {
            motion.directLimit = velocity;
            LegacyMath.motion(npc,velocity.x,delta.y,velocity.z);
        } else if (!direct && attribute > 0 && velocity.length() > 0.005
            && (maneuvering || velocity.subtract(preferred).length() > 1.0E-5)) {
            double aim = Math.max(0.25,Math.min(0.8,length)) / velocity.length();
            control.setMoveTo(npc.posX + velocity.x * aim,wanted.y,npc.posZ + velocity.z * aim,velocity.length() / attribute);
        }
    }

    /** The jump helper runs after the move helper; suppress its queued jump only when traffic stops movement. */
    public static void afterControls(EntityNPCInterface npc) {
        if (!server(npc)) return;
        Motion motion = motion(npc);
        if (motion != null && motion.motionTick == npc.world.getTotalWorldTime() && motion.stopped) {
            npc.setJumping(false); npc.setMoveForward(0); npc.setMoveStrafing(0);
        }
    }

    public static void afterAi(EntityNPCInterface npc) {
        if (!server(npc)) return;
        Motion motion = motion(npc);
        if (motion == null || motion.motionTick != npc.world.getTotalWorldTime()) return;
        if (motion.stopped) LegacyMath.motion(npc,0,npc.motionY,0);
        else if (motion.directLimit != null && LegacyMath.horizontal(LegacyMath.motion(npc)) > motion.directLimit.length())
            LegacyMath.motion(npc,motion.directLimit.x,npc.motionY,motion.directLimit.z);
    }

    private static boolean localManeuver(WorldTraffic world,EntityNPCInterface npc,Motion motion,Velocity heading,boolean backoff,long now) {
        Vec3d direction = heading.length() > 0.001 ? new Vec3d(heading.x,0,heading.z)
            : LegacyMath.multiply(npc.getLookVec(),1,0,1).normalize();
        if (backoff) direction = direction.scale(-1);
        GridPos start = grid(new BlockPos(npc.posX,npc.getEntityBoundingBox().minY + 0.05,npc.posZ));
        ArrayDeque<GridPos> queue = new ArrayDeque<>(); queue.add(start);
        Map<GridPos,GridPos> parents = new HashMap<>(); parents.put(start,null);
        GridPos best = null;
        double bestScore = -Double.MAX_VALUE;
        Vec3d position = npc.getPositionVector();
        while (!queue.isEmpty()) {
            GridPos current = queue.removeFirst();
            for (int[] d : HORIZONTAL) {
                GridPos next = current.offset(d[0],0,d[1]);
                if (parents.containsKey(next) || Math.abs(next.x() - start.x()) > 3 || Math.abs(next.z() - start.z()) > 3) continue;
                Vec3d from = current.equals(start) ? position : GroundSurface.center(npc.world,block(current));
                Vec3d to = GroundSurface.center(npc.world,block(next));
                if (!safeSegment(npc,from,to)) continue;
                long region = world.reservations.regionAt(next);
                if (region != 0 && !world.reservations.mayTraverse(region,npc.getUniqueID())) continue;
                AxisAlignedBB body = npc.getEntityBoundingBox().offset(to.x - position.x,to.y - position.y,to.z - position.z);
                if (!npc.world.getEntitiesWithinAABB(EntityNPCInterface.class,body,other -> other != npc && other.isEntityAlive()).isEmpty()) continue;
                parents.put(next,current); queue.addLast(next);
                Vec3d offset = to.subtract(position);
                double along = offset.dotProduct(direction);
                double score = along - offset.lengthVector() * 0.15 + (backoff && region == 0 ? 3 : 0);
                if ((backoff ? along > 0.2 : along > -0.2) && score > bestScore) { best = next; bestScore = score; }
            }
        }
        if (best == null) return false;
        for (GridPos p = best; !p.equals(start); p = parents.get(p)) motion.maneuver.addFirst(GroundSurface.center(npc.world,block(p)));
        motion.maneuverUntil = now + 60;
        return true;
    }

    private static boolean safeSegment(EntityNPCInterface npc,Vec3d from,Vec3d to) {
        int samples = Math.max(1,(int)Math.ceil(from.distanceTo(to) * 4));
        for (int i = 1; i <= samples; i++) {
            Vec3d point = LegacyMath.lerp(from,to,(double)i / samples);
            BlockPos feet = new BlockPos(point.x,point.y + 0.05,point.z);
            if (!TacticalPositions.standable(npc,feet)) return false;
            double floor = GroundSurface.bodyY(npc.world,feet);
            if (Math.abs(floor - from.y) > 0.5) return false;
            AxisAlignedBB body = npc.getEntityBoundingBox().offset(point.x - npc.posX,floor - npc.getEntityBoundingBox().minY,point.z - npc.posZ).shrink(1.0E-5);
            if (!LegacyCollision.loaded(npc.world,body) || !LegacyCollision.blockBoxes(npc.world,body,npc).isEmpty()) return false;
        }
        return true;
    }

    private static void processScans(WorldServer level,WorldTraffic world,long now) {
        int visits = Math.min(world.scans.size(),12);
        while (visits-- > 0) {
            Scan scan = world.scans.removeFirst();
            Entity entity = level.getEntityFromUuid(scan.requester);
            if (!(entity instanceof EntityNPCInterface) || !enabled((EntityNPCInterface)entity)
                || world.reservations.regionAt(scan.seed) != 0) continue;
            EntityNPCInterface npc = (EntityNPCInterface)entity;
            if (!scan.scanner.advance(cell -> kind(world,npc,cell,now),4)) { world.scans.addLast(scan); continue; }
            Set<GridPos> cells = scan.scanner.cells();
            if (cells.isEmpty()) continue;
            world.reservations.register(cells,now);
            int minX = Integer.MAX_VALUE,minY = Integer.MAX_VALUE,minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE,maxY = Integer.MIN_VALUE,maxZ = Integer.MIN_VALUE;
            for (GridPos cell : cells) {
                minX = Math.min(minX,cell.x()); minY = Math.min(minY,cell.y()); minZ = Math.min(minZ,cell.z());
                maxX = Math.max(maxX,cell.x()); maxY = Math.max(maxY,cell.y()); maxZ = Math.max(maxZ,cell.z());
            }
            AxisAlignedBB bounds = new AxisAlignedBB(minX - 1,minY - 1,minZ - 1,maxX + 2,maxY + 3,maxZ + 2);
            for (EntityNPCInterface occupant : level.getEntitiesWithinAABB(EntityNPCInterface.class,bounds,EntityNPCInterface::isEntityAlive))
                observe(world,occupant,now);
        }
    }

    private static int kind(WorldTraffic world,EntityNPCInterface npc,GridPos pos,long now) {
        long shape = ((long)Float.floatToIntBits(npc.width) << 32) | (Float.floatToIntBits(npc.height) & 0xffffffffL);
        Map<GridPos,Sample> samples = world.samples.computeIfAbsent(shape,key -> new HashMap<>());
        Sample cached = samples.get(pos);
        if (cached != null && cached.expires > now) return cached.kind;
        int kind = 0;
        if (TacticalPositions.standable(npc,block(pos))) {
            kind = 2;
            outer: for (int x = -1; x <= 1; x += 2) for (int z = -1; z <= 1; z += 2)
                if (TacticalPositions.standable(npc,block(pos.offset(x,0,0)))
                    && TacticalPositions.standable(npc,block(pos.offset(0,0,z)))
                    && TacticalPositions.standable(npc,block(pos.offset(x,0,z)))) { kind = 1; break outer; }
        }
        if (samples.size() >= 16384) samples.clear();
        samples.put(pos,new Sample(kind,now + 80));
        return kind;
    }

    private static void observe(WorldTraffic world,EntityNPCInterface npc,long now) {
        world.reservations.observe(npc.getUniqueID(),npc.posX,npc.getEntityBoundingBox().minY,npc.posZ,bodyRadius(npc),now);
    }
    public static double bodyRadius(EntityNPCInterface npc) {
        AxisAlignedBB box = npc.getEntityBoundingBox();
        return Math.max(box.maxX - box.minX,box.maxZ - box.minZ) / 2;
    }
    public static double comfort(EntityNPCInterface npc) {
        return npc.ais instanceof IDataAIPathfinding ? ((IDataAIPathfinding)npc.ais).npcpathfindinglegacy$getComfortDistance() : 1.4;
    }
    private static int priority(EntityNPCInterface npc) {
        top.npcpathfindinglegacy.tactics.TacticsState state = TacticsRuntime.state(npc);
        return npc.getHealth() < npc.getMaxHealth() * 0.25 ? 2 : state != null && state.active.id() == 5 ? 1 : 0;
    }
    private static Motion motion(EntityNPCInterface npc) {
        WorldTraffic world = WORLDS.get(npc.world);
        return world == null ? null : world.motions.get(npc.getUniqueID());
    }
    public static boolean avoidsFlow(EntityNPCInterface npc) {
        if (!server(npc)) return false;
        Motion motion = motion(npc);
        return motion != null && motion.fullUntil > npc.world.getTotalWorldTime();
    }
    public static boolean tracksProgress(EntityNPCInterface npc) { return server(npc) && enabled(npc); }
    public static boolean overridesMovement(EntityNPCInterface npc) {
        if (!server(npc)) return false;
        Motion motion = motion(npc);
        return motion != null && (!motion.maneuver.isEmpty() || motion.stopped && motion.motionTick == npc.world.getTotalWorldTime());
    }
    public static void cancel(EntityNPCInterface npc) {
        if (!server(npc)) return;
        WorldTraffic world = WORLDS.get(npc.world);
        if (world == null) return;
        UUID id = npc.getUniqueID();
        world.scans.removeIf(scan -> scan.requester.equals(id));
        world.reservations.cancel(id); world.recovery.remove(id); world.motions.remove(id);
    }
    public static void removed(EntityNPCInterface npc) {
        if (!server(npc)) return;
        WorldTraffic world = WORLDS.get(npc.world);
        if (world != null) { cancel(npc); world.reservations.remove(npc.getUniqueID()); }
    }

    @SubscribeEvent public static void tickWorld(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.START || !(event.world instanceof WorldServer)
            || !event.world.getMinecraftServer().isCallingFromMinecraftThread()) return;
        WorldServer level = (WorldServer)event.world;
        WorldTraffic world = WORLDS.get(level);
        if (world == null) return;
        long now = level.getTotalWorldTime();
        processScans(level,world,now);
        if (now % 20 != 0) return;
        world.motions.entrySet().removeIf(entry -> {
            Entity entity = level.getEntityFromUuid(entry.getKey());
            if (entity != null && entity.isEntityAlive() && now - entry.getValue().touched <= 100) return false;
            world.reservations.remove(entry.getKey()); world.recovery.remove(entry.getKey()); return true;
        });
        world.reservations.expire(now); world.recovery.expire(now);
        world.samples.values().removeIf(samples -> { samples.values().removeIf(sample -> sample.expires <= now); return samples.isEmpty(); });
    }
    @SubscribeEvent public static void died(LivingDeathEvent event) {
        if (event.getEntityLiving() instanceof EntityNPCInterface) removed((EntityNPCInterface)event.getEntityLiving());
    }
    @SubscribeEvent public static void unload(WorldEvent.Unload event) {
        if (event.getWorld() instanceof WorldServer && event.getWorld().getMinecraftServer().isCallingFromMinecraftThread()) WORLDS.remove(event.getWorld());
    }
    public static void stopped() { WORLDS.clear(); }
    private static GridPos grid(BlockPos pos) { return new GridPos(pos.getX(),pos.getY(),pos.getZ()); }
    private static BlockPos block(GridPos pos) { return new BlockPos(pos.x(),pos.y(),pos.z()); }
}
