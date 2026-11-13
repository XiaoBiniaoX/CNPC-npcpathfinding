package top.npcpathfindinglegacy.tactics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.bus.api.SubscribeEvent;
import noppes.npcs.entity.EntityNPCInterface;

/** Cheap candidate filtering before cover rays; short reservations keep units from stacking. */
public final class TacticalPositions {
    private record Key(net.minecraft.resources.ResourceLocation dimension, BlockPos position) {}
    private record Reservation(UUID owner, long expires) {}
    private static final Map<Key, Reservation> RESERVED = new LinkedHashMap<>();
    private static long rayTick = Long.MIN_VALUE;
    private static int rays;
    private TacticalPositions() {}

    public static BlockPos resolve(EntityNPCInterface npc, Vec3 desired, boolean cover) {
        if (cover) return findCover(npc, TacticsRuntime.state(npc), 12);
        ServerLevel level = (ServerLevel) npc.level(); long time = level.getGameTime();
        RESERVED.entrySet().removeIf(e -> e.getValue().expires() <= time);
        BlockPos best = null; double score = Double.POSITIVE_INFINITY;
        for (int radius = 0; radius <= 3; radius++) {
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                if (radius > 0 && Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                BlockPos column = BlockPos.containing(desired.x + dx, desired.y, desired.z + dz);
                for (int dy : new int[]{0,1,-1,2,-2,3,-3}) {
                    BlockPos pos = column.offset(0, dy, 0);
                    if (!standable(npc, pos)) continue;
                    Key key = new Key(level.dimension().location(), pos);
                    Reservation reservation = RESERVED.get(key);
                    if (reservation != null && !reservation.owner().equals(npc.getUUID())) continue;
                    double cost = pos.distSqr(BlockPos.containing(desired)) + npc.blockPosition().distSqr(pos) * 0.1;
                    if (cost >= score) continue;
                    if (cost < score) { score = cost; best = pos; }
                    break;
                }
            }
            if (best != null) break;
        }
        if (best == null) return npc.blockPosition();
        return best;
    }

    public static boolean hidden(EntityNPCInterface npc, BlockPos pos) {
        if (npc.getTarget() == null) return false;
        Vec3 from = npc.getTarget().getEyePosition(), center = top.npcpathfindinglegacy.pathfinding.GroundSurface.center(npc.level(),pos);
        Vec3 side = center.subtract(from).multiply(1,0,1).normalize();
        side = new Vec3(-side.z,0,side.x).scale(npc.getBbWidth() * 0.45);
        // Head and both shoulders must be concealed, rather than just a single eye ray.
        return blocked(npc,from,center.add(0,npc.getEyeHeight(),0))
            && blocked(npc,from,center.add(side).add(0,npc.getBbHeight() * 0.55,0))
            && blocked(npc,from,center.subtract(side).add(0,npc.getBbHeight() * 0.55,0));
    }
    private static boolean blocked(EntityNPCInterface npc, Vec3 from, Vec3 to) {
        if (!lineLoaded(npc,from,to)) return false;
        return npc.level().clip(new ClipContext(from,to,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,npc))
            .getType() != HitResult.Type.MISS;
    }
    private static boolean lineLoaded(EntityNPCInterface npc, Vec3 from, Vec3 to) {
        int steps = Math.max(1,(int)Math.ceil(from.distanceTo(to)));
        for (int i = 0; i <= steps; i++) {
            if (!npc.level().hasChunkAt(BlockPos.containing(from.lerp(to,(double)i / steps)))) return false;
        }
        return true;
    }
    private static boolean available(EntityNPCInterface npc, BlockPos pos) {
        Reservation other = RESERVED.get(new Key(npc.level().dimension().location(),pos));
        return other == null || other.expires() <= npc.level().getGameTime() || other.owner().equals(npc.getUUID());
    }
    public static BlockPos findCover(EntityNPCInterface npc, TacticsState state, int radius) {
        if (state == null || npc.getTarget() == null) return null;
        long now = npc.level().getGameTime();
        if (state.coverDestination != null && standable(npc,state.coverDestination)
            && available(npc,state.coverDestination) && hidden(npc,state.coverDestination)) return state.coverDestination;
        state.coverDestination = null;
        if (now < state.nextCoverSearch) return null;
        state.nextCoverSearch = now + 10;
        long tick = npc.level().getServer().getTickCount();
        if (rayTick != tick) { rayTick = tick; rays = 0; }
        int rings = Math.max(4, Math.min(12, radius / 2)), count = rings * 16;
        BlockPos origin = state.ambushOrigin == null ? npc.blockPosition() : state.ambushOrigin;
        BlockPos best = null; double score = Double.POSITIVE_INFINITY;
        for (int attempt = 0; attempt < 32 && rays + 3 <= 192; attempt++) {
            int candidate = Math.floorMod(state.coverCursor++,count);
            int distance = (candidate / 16 + 1) * 2;
            double angle = (candidate % 16) * Math.PI / 8;
            BlockPos column = origin.offset((int)Math.round(Math.cos(angle) * distance),0,(int)Math.round(Math.sin(angle) * distance));
            BlockPos pos = null;
            for (int dy : new int[]{0,1,-1,2,-2}) {
                BlockPos feet = column.offset(0,dy,0);
                if (standable(npc,feet) && available(npc,feet)) { pos = feet; break; }
            }
            if (pos == null) continue;
            rays += 3;
            if (!hidden(npc,pos)) continue;
            double cost = npc.blockPosition().distSqr(pos) + origin.distSqr(pos) * 0.2;
            if (cost < score && reachable(npc,pos)) { best = pos; score = cost; }
        }
        state.coverDestination = best;
        return best;
    }
    private static boolean reachable(EntityNPCInterface npc, BlockPos pos) {
        if (npc.blockPosition().distSqr(pos) < 2) return true;
        var navigation = npc instanceof top.npcpathfindinglegacy.pathfinding.INpcPathfindingEntity entity
            ? entity.npcpathfindinglegacy$getVanillaNavigation() : npc.getNavigation();
        if (navigation == null) return false;
        var path = navigation.createPath(pos,0);
        return path != null && path.canReach();
    }
    public static BlockPos peek(EntityNPCInterface npc, BlockPos cover) {
        var target = npc.getTarget();
        if (target == null) return null;
        BlockPos best = null; double score = Double.POSITIVE_INFINITY;
        for (int distance = 1; distance <= 3; distance++) for (int angle = 0; angle < 8; angle++) {
            double heading = angle * Math.PI / 4;
            BlockPos pos = cover.offset((int)Math.round(Math.cos(heading) * distance),0,(int)Math.round(Math.sin(heading) * distance));
            if (!standable(npc,pos) || !available(npc,pos)) continue;
            Vec3 eyes = top.npcpathfindinglegacy.pathfinding.GroundSurface.center(npc.level(),pos).add(0,npc.getEyeHeight(),0);
            if (eyes.distanceToSqr(target.getEyePosition()) > npc.stats.ranged.getRange() * npc.stats.ranged.getRange()
                || !lineLoaded(npc,eyes,target.getEyePosition())
                || blocked(npc,eyes,target.getEyePosition())) continue;
            double cost = cover.distSqr(pos);
            if (cost < score && reachable(npc,pos)) { best = pos; score = cost; }
        }
        return best;
    }

    public static void reserve(EntityNPCInterface npc, BlockPos destination) {
        if (RESERVED.size() >= 256) RESERVED.remove(RESERVED.keySet().iterator().next());
        RESERVED.put(new Key(npc.level().dimension().location(), destination), new Reservation(npc.getUUID(), npc.level().getGameTime() + 35));
    }

    public static boolean standable(EntityNPCInterface npc, BlockPos pos) {
        if (!(npc.level() instanceof ServerLevel level) || pos.getY() <= level.getMinBuildHeight()
            || pos.getY() + npc.getBbHeight() >= level.getMaxBuildHeight() || !level.hasChunkAt(pos)) return false;
        var support = level.getBlockState(pos.below());
        if (!support.isFaceSturdy(level, pos.below(), net.minecraft.core.Direction.UP)
            || !support.getFluidState().isEmpty()
            || top.npcpathfindinglegacy.NpcPathfindingConfig.isBlacklisted(support.getBlock())
            || support.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)
            || support.is(net.minecraft.world.level.block.Blocks.CACTUS)
            || support.is(net.minecraft.world.level.block.Blocks.CAMPFIRE)
            || support.is(net.minecraft.world.level.block.Blocks.SOUL_CAMPFIRE)) return false;
        double floor=top.npcpathfindinglegacy.pathfinding.GroundSurface.bodyY(level,pos);
        AABB body = new AABB(pos.getX() + 0.5 - npc.getBbWidth() / 2, floor + 1.0E-5,
            pos.getZ() + 0.5 - npc.getBbWidth() / 2, pos.getX() + 0.5 + npc.getBbWidth() / 2,
            floor + npc.getBbHeight(), pos.getZ() + 0.5 + npc.getBbWidth() / 2);
        for (int x = (int) Math.floor(body.minX); x <= (int) Math.floor(body.maxX); x++)
            for (int z = (int) Math.floor(body.minZ); z <= (int) Math.floor(body.maxZ); z++)
                if (!level.hasChunkAt(new BlockPos(x, pos.getY(), z))) return false;
        // Teammates are dynamic obstacles, not walls that should change the chosen formation.
        return !level.getBlockCollisions(npc, body).iterator().hasNext() && level.getFluidState(pos).isEmpty();
    }
    public static void release(UUID owner) { RESERVED.entrySet().removeIf(e -> e.getValue().owner().equals(owner)); }
    @SubscribeEvent public static void stopped(ServerStoppingEvent event) { RESERVED.clear(); }
}
