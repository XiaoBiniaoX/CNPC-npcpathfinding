package top.npcpathfindinglegacy.tactics.group;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.WorldServer;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import noppes.npcs.entity.EntityNPCInterface;
import top.npcpathfindinglegacy.tactics.*;

public final class SquadService {
    private static final class Key {
private final String dimension;
private final int faction;
private final int squad;
private final UUID target;
private final boolean formation;
private final UUID cohort;
public Key(String dimension, int faction, int squad, UUID target, boolean formation, UUID cohort) {
this.dimension=dimension;this.faction=faction;this.squad=squad;this.target=target;this.formation=formation;this.cohort=cohort;}
public String dimension() { return dimension; }
public int faction() { return faction; }
public int squad() { return squad; }
public UUID target() { return target; }
public boolean formation() { return formation; }
public UUID cohort() { return cohort; }
@Override public int hashCode() { return java.util.Objects.hash(dimension,faction,squad,target,formation,cohort); }
@Override public boolean equals(Object value) { if (this==value) return true; if (!(value instanceof Key)) return false; Key other=(Key)value; return java.util.Objects.equals(dimension,other.dimension) && java.util.Objects.equals(faction,other.faction) && java.util.Objects.equals(squad,other.squad) && java.util.Objects.equals(target,other.target) && java.util.Objects.equals(formation,other.formation) && java.util.Objects.equals(cohort,other.cohort); }

        boolean matches(Key other) {
            return dimension.equals(other.dimension) && faction == other.faction && squad == other.squad
                && java.util.Objects.equals(target,other.target) && formation == other.formation;
        }
    }
    private static final class Squad {
private final List<UUID> members;
private final UUID leader;
private final Formation.Shape shape;
private final Vec3d approach;
private final Vec3d targetPosition;
private final double spacing;
private final int flankSide;
private final long shapeChanged;
private final long created;
public Squad(List<UUID> members, UUID leader, Formation.Shape shape, Vec3d approach, Vec3d targetPosition, double spacing, int flankSide, long shapeChanged, long created) {
this.members=members;this.leader=leader;this.shape=shape;this.approach=approach;this.targetPosition=targetPosition;this.spacing=spacing;this.flankSide=flankSide;this.shapeChanged=shapeChanged;this.created=created;}
public List<UUID> members() { return members; }
public UUID leader() { return leader; }
public Formation.Shape shape() { return shape; }
public Vec3d approach() { return approach; }
public Vec3d targetPosition() { return targetPosition; }
public double spacing() { return spacing; }
public int flankSide() { return flankSide; }
public long shapeChanged() { return shapeChanged; }
public long created() { return created; }
@Override public int hashCode() { return java.util.Objects.hash(members,leader,shape,approach,targetPosition,spacing,flankSide,shapeChanged,created); }
@Override public boolean equals(Object value) { if (this==value) return true; if (!(value instanceof Squad)) return false; Squad other=(Squad)value; return java.util.Objects.equals(members,other.members) && java.util.Objects.equals(leader,other.leader) && java.util.Objects.equals(shape,other.shape) && java.util.Objects.equals(approach,other.approach) && java.util.Objects.equals(targetPosition,other.targetPosition) && java.util.Objects.equals(spacing,other.spacing) && java.util.Objects.equals(flankSide,other.flankSide) && java.util.Objects.equals(shapeChanged,other.shapeChanged) && java.util.Objects.equals(created,other.created); }
}
    public static final class Membership {
private final int members;
private final int slot;
private final UUID leader;
private final double leaderHealth;
private final boolean leaderUnit;
private final Formation.Shape shape;
private final Vec3d approach;
private final double spacing;
private final int flankSide;
public Membership(int members, int slot, UUID leader, double leaderHealth, boolean leaderUnit, Formation.Shape shape, Vec3d approach, double spacing, int flankSide) {
this.members=members;this.slot=slot;this.leader=leader;this.leaderHealth=leaderHealth;this.leaderUnit=leaderUnit;this.shape=shape;this.approach=approach;this.spacing=spacing;this.flankSide=flankSide;}
public int members() { return members; }
public int slot() { return slot; }
public UUID leader() { return leader; }
public double leaderHealth() { return leaderHealth; }
public boolean leaderUnit() { return leaderUnit; }
public Formation.Shape shape() { return shape; }
public Vec3d approach() { return approach; }
public double spacing() { return spacing; }
public int flankSide() { return flankSide; }
@Override public int hashCode() { return java.util.Objects.hash(members,slot,leader,leaderHealth,leaderUnit,shape,approach,spacing,flankSide); }
@Override public boolean equals(Object value) { if (this==value) return true; if (!(value instanceof Membership)) return false; Membership other=(Membership)value; return java.util.Objects.equals(members,other.members) && java.util.Objects.equals(slot,other.slot) && java.util.Objects.equals(leader,other.leader) && java.util.Objects.equals(leaderHealth,other.leaderHealth) && java.util.Objects.equals(leaderUnit,other.leaderUnit) && java.util.Objects.equals(shape,other.shape) && java.util.Objects.equals(approach,other.approach) && java.util.Objects.equals(spacing,other.spacing) && java.util.Objects.equals(flankSide,other.flankSide); }
}
    private static final LinkedHashMap<Key, Squad> SQUADS = new LinkedHashMap<>();
    private SquadService() {}

    public static Membership membership(EntityNPCInterface npc, int squad) {
        return membership(npc,squad,((ITacticsData)npc.ais).npcpathfindinglegacy$getTactic() == TacticType.FORMATION.id());
    }
    public static Membership membership(EntityNPCInterface npc, int squad, boolean formation) {
        WorldServer level = (WorldServer)npc.world; long time = level.getTotalWorldTime();
        UUID target = formation || npc.getAttackTarget() == null ? null : npc.getAttackTarget().getUniqueID();
        Key requested = new Key(Integer.toString(level.provider.getDimension()),npc.getFaction().id,squad,target,formation,npc.getUniqueID());
        SQUADS.entrySet().removeIf(e -> time - e.getValue().created() > 60);
        // Keep nearby cohorts stable without splitting a marching column at coordinate boundaries.
        Key found = SQUADS.entrySet().stream().filter(e -> e.getKey().matches(requested) && e.getValue().members().contains(npc.getUniqueID()))
            .map(java.util.Map.Entry::getKey).findFirst().orElse(null);
        if (found == null) found = SQUADS.entrySet().stream().filter(e -> e.getKey().matches(requested) && e.getValue().members().size() < 32
            && level.getEntityFromUuid(e.getValue().leader()) instanceof EntityNPCInterface && eligible((EntityNPCInterface)level.getEntityFromUuid(e.getValue().leader()),requested)
            && level.getEntityFromUuid(e.getValue().leader()).getDistanceSq(npc) <= (formation ? 72 * 72 : 32 * 32))
            .map(java.util.Map.Entry::getKey).findFirst().orElse(null);
        Key key = found == null ? requested : found;
        Squad cached = SQUADS.get(key);
        if (cached == null || !cached.members().contains(npc.getUniqueID()) || time - cached.created() >= 20 || !(level.getEntityFromUuid(cached.leader()) instanceof EntityNPCInterface)
            || !eligible((EntityNPCInterface)level.getEntityFromUuid(cached.leader()),key)) {
            EntityNPCInterface scanCenter = cached != null && level.getEntityFromUuid(cached.leader()) instanceof EntityNPCInterface
                && eligible((EntityNPCInterface)level.getEntityFromUuid(cached.leader()),key) ? (EntityNPCInterface)level.getEntityFromUuid(cached.leader()) : npc;
            List<EntityNPCInterface> members = level.getEntitiesWithinAABB(EntityNPCInterface.class,scanCenter.getEntityBoundingBox().grow(formation ? 72 : 32), other -> eligible(other,key)
                && SQUADS.entrySet().stream().noneMatch(e -> e.getKey().matches(key) && !e.getKey().equals(key) && e.getValue().members().contains(other.getUniqueID())));
            if (!members.contains(npc)) members.add(npc);
            members.sort(Comparator.comparing(EntityNPCInterface::getUniqueID));
            if (members.size() > 32) {
                members = new ArrayList<>(members.subList(0,32));
                if (!members.contains(npc)) members.set(31,npc);
            }
            double centerX = members.stream().mapToDouble(npcValue -> npcValue.posX).average().orElse(npc.posX);
            double centerZ = members.stream().mapToDouble(npcValue -> npcValue.posZ).average().orElse(npc.posZ);
            UUID previous = cached == null ? null : cached.leader();
            EntityNPCInterface elected = members.stream().max(Comparator.comparingDouble(other ->
                Formation.leaderScore(other.getHealth() / Math.max(1,other.getMaxHealth()),
                    WeaponRole.classify(other.inventory.getProjectile() != null,other.stats.ranged.getMeleeRange()),
                    Math.hypot(other.posX - centerX,other.posZ - centerZ),other.hurtTime > 0)
                    + (formation && other.ais.getMovingType() == 2 && other.ais.getMovingPath().size() > 1 ? 2 : 0)
                    + (other.getUniqueID().equals(previous) ? 0.8 : 0))).orElse(npc);
            Vec3d targetPosition = headingTarget(elected,cached);
            Vec3d approach = top.npcpathfindinglegacy.compat.LegacyMath.multiply(elected.getPositionVector().subtract(targetPosition),1,0,1).normalize();
            if (approach.lengthSquared() < 0.01) approach = top.npcpathfindinglegacy.compat.LegacyMath.multiply(elected.getLookVec(),-1,0,-1).normalize();
            if (approach.lengthSquared() < 0.01) approach = new Vec3d(0,0,1);
            if (cached != null && cached.leader().equals(elected.getUniqueID()) && cached.targetPosition().squareDistanceTo(targetPosition) < 9) {
                approach = cached.approach(); targetPosition = cached.targetPosition();
            }
            Formation.Shape shape = formationShape(elected,members.size(),time,approach);
            long shapeChanged = time;
            if (cached != null && cached.leader().equals(elected.getUniqueID()) && shape != Formation.Shape.COLUMN && time - cached.shapeChanged() < 60) shape = cached.shape();
            if (cached != null && cached.shape() == shape) shapeChanged = cached.shapeChanged();
            double spacing = Math.max(2,members.stream().mapToDouble(member ->
                2 * top.npcpathfindinglegacy.traffic.TrafficController.bodyRadius(member)
                    + top.npcpathfindinglegacy.traffic.TrafficController.comfort(member)).max().orElse(2));
            List<UUID> ids = members.stream().map(EntityNPCInterface::getUniqueID).collect(java.util.stream.Collectors.toList());
            if (formation) {
                if (cached != null && cached.leader().equals(elected.getUniqueID()) && cached.shape() == shape
                    && cached.members().size() == ids.size() && cached.members().containsAll(ids)) ids = cached.members();
                else {
                    double[] x = members.stream().mapToDouble(npcValue -> npcValue.posX).toArray();
                    double[] z = members.stream().mapToDouble(npcValue -> npcValue.posZ).toArray();
                    int[] order = Formation.assign(x,z,members.indexOf(elected),shape,approach.x,approach.z,spacing);
                    List<UUID> assigned = new ArrayList<>();
                    for (int index : order) assigned.add(members.get(index).getUniqueID());
                    ids = top.npcpathfindinglegacy.compat.Immutable.copyList(assigned);
                }
            }
            int flankSide = cached == null ? (npc.getRNG().nextBoolean() ? 1 : -1) : cached.flankSide();
            cached = new Squad(ids,elected.getUniqueID(),shape,approach,targetPosition,spacing,flankSide,shapeChanged,time);
            if (SQUADS.size() >= 64) SQUADS.remove(SQUADS.keySet().iterator().next());
            SQUADS.put(key,cached);
        }
        int slot = cached.members().indexOf(npc.getUniqueID());
        if (slot < 0) return new Membership(1,0,npc.getUniqueID(),npc.getHealth() / Math.max(1,npc.getMaxHealth()),
            true,cached.shape(),cached.approach(),cached.spacing(),cached.flankSide());
        if (!formation) {
            int leaderSlot = cached.members().indexOf(cached.leader());
            if (slot == leaderSlot) slot = 0; else if (slot < leaderSlot) slot++;
        }
        EntityNPCInterface leader = level.getEntityFromUuid(cached.leader()) instanceof EntityNPCInterface ? (EntityNPCInterface)level.getEntityFromUuid(cached.leader()) : npc;
        return new Membership(cached.members().size(),slot,cached.leader(),leader.getHealth() / Math.max(1,leader.getMaxHealth()),
            leader == npc,cached.shape(),cached.approach(),cached.spacing(),cached.flankSide());
    }
    private static boolean eligible(EntityNPCInterface npc, Key key) {
        if (!npc.isEntityAlive() || npc.isDead || npc.isInteracting() || npc.isBurning() || npc.ais.onAttack != 0 || npc.ais.movementType != 0
            || !(npc.ais instanceof ITacticsData) || ((ITacticsData)npc.ais).npcpathfindinglegacy$getSquad() != key.squad() || npc.getFaction().id != key.faction()) return false;
        TacticType selected = TacticType.byId(((ITacticsData)npc.ais).npcpathfindinglegacy$getTactic());
        boolean formation = selected == TacticType.FORMATION || selected == TacticType.AUTO && TacticsRuntime.state(npc) != null
            && TacticsRuntime.state(npc).active == TacticType.FORMATION;
        return key.formation() ? formation : selected.group() && selected != TacticType.FORMATION
            && npc.getAttackTarget() != null && npc.getAttackTarget().getUniqueID().equals(key.target());
    }
    private static Vec3d headingTarget(EntityNPCInterface leader, Squad previous) {
        if (leader.getAttackTarget() != null && leader.getAttackTarget().isEntityAlive()) return leader.getAttackTarget().getPositionVector();
        BlockPos destination = top.npcpathfindinglegacy.compat.LegacyMath.destination(leader.getNavigator());
        if (destination != null && !leader.getNavigator().noPath()) return top.npcpathfindinglegacy.pathfinding.GroundSurface.center(leader.world,destination);
        if (leader.ais.getMovingType() == 2 && leader.ais.getMovingPath().size() > 1) {
            int[] point = leader.ais.getCurrentMovingPath();
            return new Vec3d(point[0] + 0.5,point[1],point[2] + 0.5);
        }
        return leader.getPositionVector().add(previous != null && previous.leader().equals(leader.getUniqueID())
            ? previous.approach().scale(-8) : top.npcpathfindinglegacy.compat.LegacyMath.multiply(leader.getLookVec(),1,0,1).scale(8));
    }
    public static Vec3d position(EntityNPCInterface leader, Membership membership) {
        double[] position = Formation.position(leader.posX,leader.posZ,membership.approach().x,membership.approach().z,
            membership.shape(),membership.slot(),membership.spacing());
        return new Vec3d(position[0],leader.posY,position[1]);
    }
    public static boolean assembled(EntityNPCInterface leader, Membership membership) {
        return assembled(leader,membership,1.0);
    }
    public static boolean assembled(EntityNPCInterface leader, Membership membership, double tolerance) {
        for (Squad group : SQUADS.values()) {
            if (!group.leader().equals(membership.leader()) || group.shape() != membership.shape()) continue;
            for (int slot = 1; slot < group.members().size(); slot++) {
                net.minecraft.entity.Entity entity = ((WorldServer)leader.world).getEntityFromUuid(group.members().get(slot));
                if (!(entity instanceof EntityNPCInterface) || !entity.isEntityAlive()) continue;
                EntityNPCInterface follower = (EntityNPCInterface)entity;
                double[] expected = Formation.position(leader.posX,leader.posZ,membership.approach().x,membership.approach().z,
                    membership.shape(),slot,membership.spacing());
                // Use the same terrain/reservation adjustment as the follower, including blocked ideal slots.
                BlockPos resolved = TacticalPositions.resolve(follower,new Vec3d(expected[0],leader.posY,expected[1]),false);
                if (follower.getPositionVector().squareDistanceTo(top.npcpathfindinglegacy.pathfinding.GroundSurface.center(follower.world,resolved)) > tolerance * tolerance) return false;
            }
            return true;
        }
        return true;
    }
    public static boolean ambushTriggered(EntityNPCInterface npc, Membership membership) {
        for (Squad group : SQUADS.values()) {
            if (!group.leader().equals(membership.leader())) continue;
            for (UUID id : group.members()) {
                net.minecraft.entity.Entity entity = ((WorldServer)npc.world).getEntityFromUuid(id);
                if (entity instanceof EntityNPCInterface) {
                    EntityNPCInterface member = (EntityNPCInterface)entity;
                    TacticsState state = TacticsRuntime.state(member);
                    if (state != null && state.ambushTriggered && npc.getAttackTarget().getUniqueID().equals(state.target)) return true;
                }
            }
        }
        return false;
    }
    private static Formation.Shape formationShape(EntityNPCInterface leader, int members, long time, Vec3d away) {
        net.minecraft.entity.EntityLivingBase target = leader.getAttackTarget();
        Vec3d side = new Vec3d(-away.z,0,away.x);
        net.minecraft.util.math.BlockPos feet = leader.getPosition();
        boolean narrow = !TacticalPositions.standable(leader,feet.add((int)Math.round(side.x * 2),0,(int)Math.round(side.z * 2)))
            || !TacticalPositions.standable(leader,feet.add((int)Math.round(-side.x * 2),0,(int)Math.round(-side.z * 2)));
        boolean rough = !TacticalPositions.standable(leader,feet.add((int)Math.round(-away.x * 3),0,(int)Math.round(-away.z * 3)));
        double health = leader.getHealth() / Math.max(1,leader.getMaxHealth());
        return Formation.choose(new TacticalContext(WeaponRole.classify(leader.inventory.getProjectile() != null,leader.stats.ranged.getMeleeRange()),
            health,target == null ? 1 : target.getHealth() / Math.max(1,target.getMaxHealth()),target == null ? 30 : leader.getDistance(target),
            leader.stats.ranged.getRange(),leader.stats.ranged.getMeleeRange(),target != null && leader.getEntitySenses().canSee(target),
            leader.hurtTime > 0,false,members,0,time,leader.getEntityId(),health,narrow,rough));
    }
    public static void stopped(FMLServerStoppingEvent event) { SQUADS.clear(); }
}
