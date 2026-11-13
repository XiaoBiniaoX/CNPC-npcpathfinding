package top.npcpathfindinglegacy.tactics.group;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.util.math.Vec3d;
import noppes.npcs.entity.EntityNPCInterface;
import top.npcpathfindinglegacy.tactics.*;
import top.npcpathfindinglegacy.tactics.flow.FlowFieldService;

/** Idle and combat formations share exactly one heading, slot assignment and follower controller. */
public final class FormationController {
    private FormationController() {}
    public static void tick(EntityNPCInterface npc, ITacticsData data) {
        TacticsState state = TacticsRuntime.state(npc);
        if (state == null || !(npc.world instanceof WorldServer)) return;
        state.active = TacticType.FORMATION;
        SquadService.Membership group = SquadService.membership(npc,data.npcpathfindinglegacy$getSquad(),true);
        boolean wasFollowing = state.activeMovement && state.leader != null && !state.leader.equals(npc.getUniqueID());
        if (!group.leader().equals(state.leader)) state.formationReady = false;
        state.leader = group.leader(); state.allowAttack = true; state.concealing = false;
        state.nextDecision = ((WorldServer)(npc.world)).getTotalWorldTime() + 6;
        net.minecraft.entity.EntityLivingBase target = npc.getAttackTarget();
        boolean combat = target != null && target.isEntityAlive() && !target.isDead;
        state.target = combat ? target.getUniqueID() : null;
        if (group.leaderUnit() && !combat) {
            // The leader keeps its original wand path, waypoint order and pauses.
            state.activeMovement = false; state.destination = null;
            TacticalPositions.release(npc.getUniqueID());
            if (wasFollowing) npc.getNavigator().clearPath();
            if (!npc.getNavigator().noPath()) npc.getNavigator().setSpeed(SquadService.assembled(npc,group,2.5) ? 1 : 0.7);
            return;
        }
        Vec3d desired;
        double speed;
        if (group.leaderUnit()) {
            boolean assembled = SquadService.assembled(npc,group,state.formationReady ? 2.5 : 1.0);
            state.formationReady = assembled;
            double range = npc.inventory.getProjectile() == null ? 1.0 : Math.max(1,npc.stats.ranged.getRange() * 0.6);
            desired = assembled ? target.getPositionVector().add(group.approach().scale(range)) : npc.getPositionVector();
            speed = 0.85;
        } else if (((WorldServer)(npc.world)).getEntityFromUuid(group.leader()) instanceof EntityNPCInterface) {
            EntityNPCInterface leader = (EntityNPCInterface)((WorldServer)npc.world).getEntityFromUuid(group.leader());
            desired = SquadService.position(leader,group);
            double error = top.npcpathfindinglegacy.compat.LegacyMath.horizontal(npc.getPositionVector().subtract(desired));
            speed = Math.min(1.6,Math.max(1.0,1.05 + error * 0.06));
        } else return;
        BlockPos destination = TacticalPositions.resolve(npc,desired,false);
        state.activeMovement = true; state.destination = destination;
        TacticalPositions.release(npc.getUniqueID()); TacticalPositions.reserve(npc,destination);
        net.minecraft.entity.Entity flowLeader = ((WorldServer)npc.world).getEntityFromUuid(group.leader());
        BlockPos flowAnchor = !group.leaderUnit() && flowLeader instanceof EntityNPCInterface ? ((EntityNPCInterface)flowLeader).getPosition() : destination;
        BlockPos waypoint = npc.getPositionVector().squareDistanceTo(top.npcpathfindinglegacy.pathfinding.GroundSurface.center(npc.world,destination)) > 64
            ? FlowFieldService.waypoint(npc,destination,data.npcpathfindinglegacy$getSquad(),flowAnchor) : destination;
        if (!TacticalPositions.standable(npc,waypoint)) waypoint = destination;
        if (!waypoint.equals(destination)) {
            waypoint = TacticalPositions.resolve(npc,top.npcpathfindinglegacy.pathfinding.GroundSurface.center(npc.world,waypoint),false);
            TacticalPositions.reserve(npc,waypoint);
        }
        TacticsRuntime.move(npc,waypoint,speed);
    }
}
