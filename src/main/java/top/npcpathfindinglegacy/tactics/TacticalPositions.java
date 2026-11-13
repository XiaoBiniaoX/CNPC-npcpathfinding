package top.npcpathfindinglegacy.tactics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import noppes.npcs.entity.EntityNPCInterface;

/** Cheap candidate filtering before cover rays; short reservations keep units from stacking. */
public final class TacticalPositions {
    private static final class Key {
private final String dimension;
private final BlockPos position;
public Key(String dimension, BlockPos position) {
this.dimension=dimension;this.position=position;}
public String dimension() { return dimension; }
public BlockPos position() { return position; }
@Override public int hashCode() { return java.util.Objects.hash(dimension,position); }
@Override public boolean equals(Object value) { if (this==value) return true; if (!(value instanceof Key)) return false; Key other=(Key)value; return java.util.Objects.equals(dimension,other.dimension) && java.util.Objects.equals(position,other.position); }
}
    private static final class Reservation {
private final UUID owner;
private final long expires;
public Reservation(UUID owner, long expires) {
this.owner=owner;this.expires=expires;}
public UUID owner() { return owner; }
public long expires() { return expires; }
@Override public int hashCode() { return java.util.Objects.hash(owner,expires); }
@Override public boolean equals(Object value) { if (this==value) return true; if (!(value instanceof Reservation)) return false; Reservation other=(Reservation)value; return java.util.Objects.equals(owner,other.owner) && java.util.Objects.equals(expires,other.expires); }
}
    private static final Map<Key, Reservation> RESERVED = new LinkedHashMap<>();
    private static long rayTick = Long.MIN_VALUE;
    private static int rays;
    private TacticalPositions() {}

    public static BlockPos resolve(EntityNPCInterface npc, Vec3d desired, boolean cover) {
        if (cover) return findCover(npc, TacticsRuntime.state(npc), 12);
        WorldServer level = (WorldServer) npc.world; long time = ((WorldServer)(npc.world)).getTotalWorldTime();
        RESERVED.entrySet().removeIf(e -> e.getValue().expires() <= time);
        BlockPos best = null; double score = Double.POSITIVE_INFINITY;
        for (int radius = 0; radius <= 3; radius++) {
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                if (radius > 0 && Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                BlockPos column = new BlockPos(desired.x + dx, desired.y, desired.z + dz);
                for (int dy : new int[]{0,1,-1,2,-2,3,-3}) {
                    BlockPos pos = column.add(0, dy, 0);
                    if (!standable(npc, pos)) continue;
                    Key key = new Key(Integer.toString(npc.world.provider.getDimension()), pos);
                    Reservation reservation = RESERVED.get(key);
                    if (reservation != null && !reservation.owner().equals(npc.getUniqueID())) continue;
                    double cost = pos.distanceSq(new BlockPos(desired)) + npc.getPosition().distanceSq(pos) * 0.1;
                    if (cost >= score) continue;
                    if (cost < score) { score = cost; best = pos; }
                    break;
                }
            }
            if (best != null) break;
        }
        if (best == null) return npc.getPosition();
        return best;
    }

    public static boolean hidden(EntityNPCInterface npc, BlockPos pos) {
        if (npc.getAttackTarget() == null) return false;
        Vec3d from = npc.getAttackTarget().getPositionEyes(1), center = top.npcpathfindinglegacy.pathfinding.GroundSurface.center(npc.world,pos);
        Vec3d side = top.npcpathfindinglegacy.compat.LegacyMath.multiply(center.subtract(from),1,0,1).normalize();
        side = new Vec3d(-side.z,0,side.x).scale(npc.width * 0.45);
        // Head and both shoulders must be concealed, rather than just a single eye ray.
        return blocked(npc,from,center.addVector(0,npc.getEyeHeight(),0))
            && blocked(npc,from,center.add(side).addVector(0,npc.height * 0.55,0))
            && blocked(npc,from,center.subtract(side).addVector(0,npc.height * 0.55,0));
    }
    private static boolean blocked(EntityNPCInterface npc, Vec3d from, Vec3d to) {
        if (!lineLoaded(npc,from,to)) return false;
        return npc.world.rayTraceBlocks(from,to,false,true,false) != null;
    }
    private static boolean lineLoaded(EntityNPCInterface npc, Vec3d from, Vec3d to) {
        int steps = Math.max(1,(int)Math.ceil(from.distanceTo(to)));
        for (int i = 0; i <= steps; i++) {
            if (!npc.world.isBlockLoaded(new BlockPos(top.npcpathfindinglegacy.compat.LegacyMath.lerp(from,to,(double)i / steps)))) return false;
        }
        return true;
    }
    private static boolean available(EntityNPCInterface npc, BlockPos pos) {
        Reservation other = RESERVED.get(new Key(Integer.toString(npc.world.provider.getDimension()),pos));
        return other == null || other.expires() <= npc.world.getTotalWorldTime() || other.owner().equals(npc.getUniqueID());
    }
    public static BlockPos findCover(EntityNPCInterface npc, TacticsState state, int radius) {
        if (state == null || npc.getAttackTarget() == null) return null;
        long now = npc.world.getTotalWorldTime();
        if (state.coverDestination != null && standable(npc,state.coverDestination)
            && available(npc,state.coverDestination) && hidden(npc,state.coverDestination)) return state.coverDestination;
        state.coverDestination = null;
        if (now < state.nextCoverSearch) return null;
        state.nextCoverSearch = now + 10;
        long tick = npc.world.getMinecraftServer().getTickCounter();
        if (rayTick != tick) { rayTick = tick; rays = 0; }
        int rings = Math.max(4, Math.min(12, radius / 2)), count = rings * 16;
        BlockPos origin = state.ambushOrigin == null ? npc.getPosition() : state.ambushOrigin;
        BlockPos best = null; double score = Double.POSITIVE_INFINITY;
        for (int attempt = 0; attempt < 32 && rays + 3 <= 192; attempt++) {
            int candidate = Math.floorMod(state.coverCursor++,count);
            int distance = (candidate / 16 + 1) * 2;
            double angle = (candidate % 16) * Math.PI / 8;
            BlockPos column = origin.add((int)Math.round(Math.cos(angle) * distance),0,(int)Math.round(Math.sin(angle) * distance));
            BlockPos pos = null;
            for (int dy : new int[]{0,1,-1,2,-2}) {
                BlockPos feet = column.add(0,dy,0);
                if (standable(npc,feet) && available(npc,feet)) { pos = feet; break; }
            }
            if (pos == null) continue;
            rays += 3;
            if (!hidden(npc,pos)) continue;
            double cost = npc.getPosition().distanceSq(pos) + origin.distanceSq(pos) * 0.2;
            if (cost < score && reachable(npc,pos)) { best = pos; score = cost; }
        }
        state.coverDestination = best;
        return best;
    }
    private static boolean reachable(EntityNPCInterface npc, BlockPos pos) {
        if (npc.getPosition().distanceSq(pos) < 2) return true;
        net.minecraft.pathfinding.PathNavigate navigation = npc instanceof top.npcpathfindinglegacy.pathfinding.INpcPathfindingEntity
            ? ((top.npcpathfindinglegacy.pathfinding.INpcPathfindingEntity)(npc)).npcpathfindinglegacy$getVanillaNavigation() : npc.getNavigator();
        if (navigation == null) return false;
        net.minecraft.pathfinding.Path path = navigation.getPathToPos(pos);
        return path != null && path.getFinalPathPoint() != null && new BlockPos(path.getFinalPathPoint().x,path.getFinalPathPoint().y,path.getFinalPathPoint().z).distanceSq(pos) <= 1;
    }
    public static BlockPos peek(EntityNPCInterface npc, BlockPos cover) {
        net.minecraft.entity.EntityLivingBase target = npc.getAttackTarget();
        if (target == null) return null;
        BlockPos best = null; double score = Double.POSITIVE_INFINITY;
        for (int distance = 1; distance <= 3; distance++) for (int angle = 0; angle < 8; angle++) {
            double heading = angle * Math.PI / 4;
            BlockPos pos = cover.add((int)Math.round(Math.cos(heading) * distance),0,(int)Math.round(Math.sin(heading) * distance));
            if (!standable(npc,pos) || !available(npc,pos)) continue;
            Vec3d eyes = top.npcpathfindinglegacy.pathfinding.GroundSurface.center(npc.world,pos).addVector(0,npc.getEyeHeight(),0);
            if (eyes.squareDistanceTo(target.getPositionEyes(1)) > npc.stats.ranged.getRange() * npc.stats.ranged.getRange()
                || !lineLoaded(npc,eyes,target.getPositionEyes(1))
                || blocked(npc,eyes,target.getPositionEyes(1))) continue;
            double cost = cover.distanceSq(pos);
            if (cost < score && reachable(npc,pos)) { best = pos; score = cost; }
        }
        return best;
    }

    public static void reserve(EntityNPCInterface npc, BlockPos destination) {
        if (RESERVED.size() >= 256) RESERVED.remove(RESERVED.keySet().iterator().next());
        RESERVED.put(new Key(Integer.toString(npc.world.provider.getDimension()), destination), new Reservation(npc.getUniqueID(), npc.world.getTotalWorldTime() + 35));
    }

    public static boolean standable(EntityNPCInterface npc,BlockPos pos) {
        if (!(npc.world instanceof WorldServer) || pos.getY()<=0 || pos.getY()+npc.height>=256 || !npc.world.isBlockLoaded(pos)) return false;
        if (!top.npcpathfindinglegacy.compat.LegacyCollision.safeSupport(npc.world,pos.down())) return false;
        double floor=top.npcpathfindinglegacy.pathfinding.GroundSurface.bodyY(npc.world,pos);
        AxisAlignedBB body=new AxisAlignedBB(pos.getX()+0.5-npc.width/2,floor+1.0E-5,pos.getZ()+0.5-npc.width/2,
            pos.getX()+0.5+npc.width/2,floor+npc.height,pos.getZ()+0.5+npc.width/2);
        // Teammates remain dynamic obstacles, rather than changing the formation's terrain classification.
        return top.npcpathfindinglegacy.compat.LegacyCollision.loaded(npc.world,body)
            && top.npcpathfindinglegacy.compat.LegacyCollision.blockBoxes(npc.world,body,npc).isEmpty()
            && !npc.world.getBlockState(pos).getMaterial().isLiquid();
    }
    public static void release(UUID owner) { RESERVED.entrySet().removeIf(e -> e.getValue().owner().equals(owner)); }
    public static void stopped(FMLServerStoppingEvent event) { RESERVED.clear(); }
}
