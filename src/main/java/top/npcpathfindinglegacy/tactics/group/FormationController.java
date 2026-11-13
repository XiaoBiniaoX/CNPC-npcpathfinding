package top.npcpathfindinglegacy.tactics.group;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import noppes.npcs.entity.EntityNPCInterface;
import top.npcpathfindinglegacy.tactics.*;
import top.npcpathfindinglegacy.tactics.flow.FlowFieldService;

/** Idle and combat formations share exactly one heading, slot assignment and follower controller. */
public final class FormationController {
    private FormationController() {}
    public static void tick(EntityNPCInterface npc, ITacticsData data) {
        TacticsState state = TacticsRuntime.state(npc);
        if (state == null || !(npc.level() instanceof ServerLevel level)) return;
        state.active = TacticType.FORMATION;
        SquadService.Membership group = SquadService.membership(npc,data.npcpathfindinglegacy$getSquad(),true);
        boolean wasFollowing = state.activeMovement && state.leader != null && !state.leader.equals(npc.getUUID());
        if (!group.leader().equals(state.leader)) state.formationReady = false;
        state.leader = group.leader(); state.allowAttack = true; state.concealing = false;
        state.nextDecision = level.getGameTime() + 6;
        var target = npc.getTarget();
        boolean combat = target != null && target.isAlive() && !target.isRemoved();
        state.target = combat ? target.getUUID() : null;
        if (group.leaderUnit() && !combat) {
            // The leader keeps its original wand path, waypoint order and pauses.
            state.activeMovement = false; state.destination = null;
            TacticalPositions.release(npc.getUUID());
            if (wasFollowing) npc.getNavigation().stop();
            if (!npc.getNavigation().isDone()) npc.getNavigation().setSpeedModifier(SquadService.assembled(npc,group,2.5) ? 1 : 0.7);
            return;
        }
        Vec3 desired;
        double speed;
        if (group.leaderUnit()) {
            boolean assembled = SquadService.assembled(npc,group,state.formationReady ? 2.5 : 1.0);
            state.formationReady = assembled;
            double range = npc.inventory.getProjectile() == null ? 1.0 : Math.max(1,npc.stats.ranged.getRange() * 0.6);
            desired = assembled ? target.position().add(group.approach().scale(range)) : npc.position();
            speed = 0.85;
        } else if (level.getEntity(group.leader()) instanceof EntityNPCInterface leader) {
            desired = SquadService.position(leader,group);
            double error = npc.position().subtract(desired).horizontalDistance();
            speed = Math.min(1.6,Math.max(1.0,1.05 + error * 0.06));
        } else return;
        BlockPos destination = TacticalPositions.resolve(npc,desired,false);
        state.activeMovement = true; state.destination = destination;
        TacticalPositions.release(npc.getUUID()); TacticalPositions.reserve(npc,destination);
        net.minecraft.world.entity.Entity flowLeader = level.getEntity(group.leader());
        BlockPos flowAnchor = !group.leaderUnit() && flowLeader instanceof EntityNPCInterface ? ((EntityNPCInterface)flowLeader).blockPosition() : destination;
        BlockPos waypoint = npc.position().distanceToSqr(top.npcpathfindinglegacy.pathfinding.GroundSurface.center(npc.level(),destination)) > 64
            ? FlowFieldService.waypoint(npc,destination,data.npcpathfindinglegacy$getSquad(),flowAnchor) : destination;
        if (!TacticalPositions.standable(npc,waypoint)) waypoint = destination;
        if (!waypoint.equals(destination)) {
            waypoint = TacticalPositions.resolve(npc,top.npcpathfindinglegacy.pathfinding.GroundSurface.center(npc.level(),waypoint),false);
            TacticalPositions.reserve(npc,waypoint);
        }
        TacticsRuntime.move(npc,waypoint,speed);
    }
}
