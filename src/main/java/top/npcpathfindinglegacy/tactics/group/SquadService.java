package top.npcpathfindinglegacy.tactics.group;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.bus.api.SubscribeEvent;
import noppes.npcs.entity.EntityNPCInterface;
import top.npcpathfindinglegacy.tactics.*;

public final class SquadService {
    private record Key(ResourceLocation dimension, int faction, int squad, UUID target, boolean formation, UUID cohort) {
        boolean matches(Key other) {
            return dimension.equals(other.dimension) && faction == other.faction && squad == other.squad
                && java.util.Objects.equals(target,other.target) && formation == other.formation;
        }
    }
    private record Squad(List<UUID> members, UUID leader, Formation.Shape shape, Vec3 approach,
        Vec3 targetPosition, double spacing, int flankSide, long shapeChanged, long created) {}
    public record Membership(int members, int slot, UUID leader, double leaderHealth, boolean leaderUnit, Formation.Shape shape,
        Vec3 approach, double spacing, int flankSide) {}
    private static final LinkedHashMap<Key, Squad> SQUADS = new LinkedHashMap<>();
    private SquadService() {}

    public static Membership membership(EntityNPCInterface npc, int squad) {
        return membership(npc,squad,((ITacticsData)npc.ais).npcpathfindinglegacy$getTactic() == TacticType.FORMATION.id());
    }
    public static Membership membership(EntityNPCInterface npc, int squad, boolean formation) {
        ServerLevel level = (ServerLevel)npc.level(); long time = level.getGameTime();
        UUID target = formation || npc.getTarget() == null ? null : npc.getTarget().getUUID();
        Key requested = new Key(level.dimension().location(),npc.getFaction().id,squad,target,formation,npc.getUUID());
        SQUADS.entrySet().removeIf(e -> time - e.getValue().created() > 60);
        // Keep nearby cohorts stable without splitting a marching column at coordinate boundaries.
        Key found = SQUADS.entrySet().stream().filter(e -> e.getKey().matches(requested) && e.getValue().members().contains(npc.getUUID()))
            .map(java.util.Map.Entry::getKey).findFirst().orElse(null);
        if (found == null) found = SQUADS.entrySet().stream().filter(e -> e.getKey().matches(requested) && e.getValue().members().size() < 32
            && level.getEntity(e.getValue().leader()) instanceof EntityNPCInterface leader && eligible(leader,requested)
            && leader.distanceToSqr(npc) <= (formation ? 72 * 72 : 32 * 32))
            .map(java.util.Map.Entry::getKey).findFirst().orElse(null);
        Key key = found == null ? requested : found;
        Squad cached = SQUADS.get(key);
        if (cached == null || !cached.members().contains(npc.getUUID()) || time - cached.created() >= 20 || !(level.getEntity(cached.leader()) instanceof EntityNPCInterface leader)
            || !eligible(leader,key)) {
            EntityNPCInterface scanCenter = cached != null && level.getEntity(cached.leader()) instanceof EntityNPCInterface oldLeader
                && eligible(oldLeader,key) ? oldLeader : npc;
            List<EntityNPCInterface> members = level.getEntitiesOfClass(EntityNPCInterface.class,scanCenter.getBoundingBox().inflate(formation ? 72 : 32), other -> eligible(other,key)
                && SQUADS.entrySet().stream().noneMatch(e -> e.getKey().matches(key) && !e.getKey().equals(key) && e.getValue().members().contains(other.getUUID())));
            if (!members.contains(npc)) members.add(npc);
            members.sort(Comparator.comparing(EntityNPCInterface::getUUID));
            if (members.size() > 32) {
                members = new ArrayList<>(members.subList(0,32));
                if (!members.contains(npc)) members.set(31,npc);
            }
            double centerX = members.stream().mapToDouble(EntityNPCInterface::getX).average().orElse(npc.getX());
            double centerZ = members.stream().mapToDouble(EntityNPCInterface::getZ).average().orElse(npc.getZ());
            UUID previous = cached == null ? null : cached.leader();
            EntityNPCInterface elected = members.stream().max(Comparator.comparingDouble(other ->
                Formation.leaderScore(other.getHealth() / Math.max(1,other.getMaxHealth()),
                    WeaponRole.classify(other.inventory.getProjectile() != null,other.stats.ranged.getMeleeRange()),
                    Math.hypot(other.getX() - centerX,other.getZ() - centerZ),other.hurtTime > 0)
                    + (formation && other.ais.getMovingType() == 2 && other.ais.getMovingPath().size() > 1 ? 2 : 0)
                    + (other.getUUID().equals(previous) ? 0.8 : 0))).orElse(npc);
            Vec3 targetPosition = headingTarget(elected,cached);
            Vec3 approach = elected.position().subtract(targetPosition).multiply(1,0,1).normalize();
            if (approach.lengthSqr() < 0.01) approach = elected.getLookAngle().multiply(-1,0,-1).normalize();
            if (approach.lengthSqr() < 0.01) approach = new Vec3(0,0,1);
            if (cached != null && cached.leader().equals(elected.getUUID()) && cached.targetPosition().distanceToSqr(targetPosition) < 9) {
                approach = cached.approach(); targetPosition = cached.targetPosition();
            }
            Formation.Shape shape = formationShape(elected,members.size(),time,approach);
            long shapeChanged = time;
            if (cached != null && cached.leader().equals(elected.getUUID()) && shape != Formation.Shape.COLUMN && time - cached.shapeChanged() < 60) shape = cached.shape();
            if (cached != null && cached.shape() == shape) shapeChanged = cached.shapeChanged();
            double spacing = Math.max(2,members.stream().mapToDouble(EntityNPCInterface::getBbWidth).max().orElse(0.6) + 0.8);
            List<UUID> ids = members.stream().map(EntityNPCInterface::getUUID).toList();
            if (formation) {
                if (cached != null && cached.leader().equals(elected.getUUID()) && cached.shape() == shape
                    && cached.members().size() == ids.size() && cached.members().containsAll(ids)) ids = cached.members();
                else {
                    double[] x = members.stream().mapToDouble(EntityNPCInterface::getX).toArray();
                    double[] z = members.stream().mapToDouble(EntityNPCInterface::getZ).toArray();
                    int[] order = Formation.assign(x,z,members.indexOf(elected),shape,approach.x,approach.z,spacing);
                    List<UUID> assigned = new ArrayList<>();
                    for (int index : order) assigned.add(members.get(index).getUUID());
                    ids = List.copyOf(assigned);
                }
            }
            int flankSide = cached == null ? (npc.getRandom().nextBoolean() ? 1 : -1) : cached.flankSide();
            cached = new Squad(ids,elected.getUUID(),shape,approach,targetPosition,spacing,flankSide,shapeChanged,time);
            if (SQUADS.size() >= 64) SQUADS.remove(SQUADS.keySet().iterator().next());
            SQUADS.put(key,cached);
        }
        int slot = cached.members().indexOf(npc.getUUID());
        if (slot < 0) return new Membership(1,0,npc.getUUID(),npc.getHealth() / Math.max(1,npc.getMaxHealth()),
            true,cached.shape(),cached.approach(),cached.spacing(),cached.flankSide());
        if (!formation) {
            int leaderSlot = cached.members().indexOf(cached.leader());
            if (slot == leaderSlot) slot = 0; else if (slot < leaderSlot) slot++;
        }
        EntityNPCInterface leader = level.getEntity(cached.leader()) instanceof EntityNPCInterface entity ? entity : npc;
        return new Membership(cached.members().size(),slot,cached.leader(),leader.getHealth() / Math.max(1,leader.getMaxHealth()),
            leader == npc,cached.shape(),cached.approach(),cached.spacing(),cached.flankSide());
    }
    private static boolean eligible(EntityNPCInterface npc, Key key) {
        if (!npc.isAlive() || npc.isRemoved() || npc.isInteracting() || npc.isOnFire() || npc.ais.onAttack != 0 || npc.ais.movementType != 0
            || !(npc.ais instanceof ITacticsData data) || data.npcpathfindinglegacy$getSquad() != key.squad() || npc.getFaction().id != key.faction()) return false;
        TacticType selected = TacticType.byId(data.npcpathfindinglegacy$getTactic());
        boolean formation = selected == TacticType.FORMATION || selected == TacticType.AUTO && TacticsRuntime.state(npc) != null
            && TacticsRuntime.state(npc).active == TacticType.FORMATION;
        return key.formation() ? formation : selected.group() && selected != TacticType.FORMATION
            && npc.getTarget() != null && npc.getTarget().getUUID().equals(key.target());
    }
    private static Vec3 headingTarget(EntityNPCInterface leader, Squad previous) {
        if (leader.getTarget() != null && leader.getTarget().isAlive()) return leader.getTarget().position();
        BlockPos destination = leader.getNavigation().getTargetPos();
        if (destination != null && !leader.getNavigation().isDone()) return top.npcpathfindinglegacy.pathfinding.GroundSurface.center(leader.level(),destination);
        if (leader.ais.getMovingType() == 2 && leader.ais.getMovingPath().size() > 1) {
            int[] point = leader.ais.getCurrentMovingPath();
            return new Vec3(point[0] + 0.5,point[1],point[2] + 0.5);
        }
        return leader.position().add(previous != null && previous.leader().equals(leader.getUUID())
            ? previous.approach().scale(-8) : leader.getLookAngle().multiply(1,0,1).scale(8));
    }
    public static Vec3 position(EntityNPCInterface leader, Membership membership) {
        double[] position = Formation.position(leader.getX(),leader.getZ(),membership.approach().x,membership.approach().z,
            membership.shape(),membership.slot(),membership.spacing());
        return new Vec3(position[0],leader.getY(),position[1]);
    }
    public static boolean assembled(EntityNPCInterface leader, Membership membership) {
        return assembled(leader,membership,1.0);
    }
    public static boolean assembled(EntityNPCInterface leader, Membership membership, double tolerance) {
        for (Squad group : SQUADS.values()) {
            if (!group.leader().equals(membership.leader()) || group.shape() != membership.shape()) continue;
            for (int slot = 1; slot < group.members().size(); slot++) {
                var entity = ((ServerLevel)leader.level()).getEntity(group.members().get(slot));
                if (!(entity instanceof EntityNPCInterface follower) || !follower.isAlive()) continue;
                double[] expected = Formation.position(leader.getX(),leader.getZ(),membership.approach().x,membership.approach().z,
                    membership.shape(),slot,membership.spacing());
                // Use the same terrain/reservation adjustment as the follower, including blocked ideal slots.
                BlockPos resolved = TacticalPositions.resolve(follower,new Vec3(expected[0],leader.getY(),expected[1]),false);
                if (follower.position().distanceToSqr(top.npcpathfindinglegacy.pathfinding.GroundSurface.center(follower.level(),resolved)) > tolerance * tolerance) return false;
            }
            return true;
        }
        return true;
    }
    public static boolean ambushTriggered(EntityNPCInterface npc, Membership membership) {
        for (Squad group : SQUADS.values()) {
            if (!group.leader().equals(membership.leader())) continue;
            for (UUID id : group.members()) {
                var entity = ((ServerLevel)npc.level()).getEntity(id);
                if (entity instanceof EntityNPCInterface member) {
                    var state = TacticsRuntime.state(member);
                    if (state != null && state.ambushTriggered && npc.getTarget().getUUID().equals(state.target)) return true;
                }
            }
        }
        return false;
    }
    private static Formation.Shape formationShape(EntityNPCInterface leader, int members, long time, Vec3 away) {
        var target = leader.getTarget();
        Vec3 side = new Vec3(-away.z,0,away.x);
        var feet = leader.blockPosition();
        boolean narrow = !TacticalPositions.standable(leader,feet.offset((int)Math.round(side.x * 2),0,(int)Math.round(side.z * 2)))
            || !TacticalPositions.standable(leader,feet.offset((int)Math.round(-side.x * 2),0,(int)Math.round(-side.z * 2)));
        boolean rough = !TacticalPositions.standable(leader,feet.offset((int)Math.round(-away.x * 3),0,(int)Math.round(-away.z * 3)));
        double health = leader.getHealth() / Math.max(1,leader.getMaxHealth());
        return Formation.choose(new TacticalContext(WeaponRole.classify(leader.inventory.getProjectile() != null,leader.stats.ranged.getMeleeRange()),
            health,target == null ? 1 : target.getHealth() / Math.max(1,target.getMaxHealth()),target == null ? 30 : leader.distanceTo(target),
            leader.stats.ranged.getRange(),leader.stats.ranged.getMeleeRange(),target != null && leader.getSensing().hasLineOfSight(target),
            leader.hurtTime > 0,false,members,0,time,leader.getId(),health,narrow,rough));
    }
    @SubscribeEvent public static void stopped(ServerStoppingEvent event) { SQUADS.clear(); }
}
