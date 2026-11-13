package top.npcpathfindinglegacy.pathfinding;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import noppes.npcs.entity.EntityNPCInterface;
import top.npcpathfindinglegacy.pathfinding.PlannedRoute.Action;

/** Cost-aware ground/ladder mode. Ordinary steps use Minecraft's normal jump control. */
final class GroundMovementController {
    private final EnhancedPathNavigator navigation;
    private final EntityNPCInterface npc;
    private Vec3 ladderMotion;

    GroundMovementController(EnhancedPathNavigator navigation, EntityNPCInterface npc) {
        this.navigation = navigation; this.npc = npc;
    }

    void reset() { ladderMotion = null; }

    void follow() {
        reset();
        Vec3 target = navigation.nextPosition();
        if (navigation.nextAction() == Action.LADDER || npc.onClimbable()) {
            double dx = target.x - npc.getX(), dz = target.z - npc.getZ();
            double dy = target.y - npc.getBoundingBox().minY;
            ladderMotion = new Vec3(Mth.clamp(dx * 0.4, -0.12, 0.12),
                Mth.clamp(dy * 0.6, -0.12, 0.22), Mth.clamp(dz * 0.4, -0.12, 0.12));
            applyLadder();
            return;
        }
        boolean sprint = navigation.maySprint() && (navigation.chaseEntity() != null || navigation.combatMode())
            && navigation.nextAction() == Action.WALK && !npc.horizontalCollision;
        npc.setSprinting(sprint);
        double speed = navigation.moveSpeed() * (sprint ? 1.15 : 1.0);
        npc.getMoveControl().setWantedPosition(target.x, target.y, target.z, speed);
    }

    private void applyLadder() {
        navigation.haltMovement();
        npc.setDeltaMovement(ladderMotion); npc.fallDistance = 0; npc.hasImpulse = true;
    }

    void applyPostAiMovement() {
        if (!navigation.maySprint()) npc.setSprinting(false);
        if (ladderMotion != null && navigation.getPath() != null && !navigation.getPath().isDone()) applyLadder();
    }
}
