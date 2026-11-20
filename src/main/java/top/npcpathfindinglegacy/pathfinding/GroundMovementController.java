package top.npcpathfindinglegacy.pathfinding;

import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import noppes.npcs.entity.EntityNPCInterface;
import top.npcpathfindinglegacy.pathfinding.PlannedRoute.Action;

/** Cost-aware ground/ladder mode. Ordinary steps use Minecraft's normal jump control. */
final class GroundMovementController {
    private final EnhancedPathNavigator navigation;
    private final EntityNPCInterface npc;
    private Vec3d ladderMotion;

    GroundMovementController(EnhancedPathNavigator navigation, EntityNPCInterface npc) {
        this.navigation = navigation; this.npc = npc;
    }

    void reset() { ladderMotion = null; }
    boolean directMotion() { return ladderMotion != null; }

    void follow() {
        reset();
        Vec3d target = navigation.nextPosition();
        if (navigation.nextAction() == Action.LADDER || npc.isOnLadder()) {
            double dx = target.x - npc.posX, dz = target.z - npc.posZ;
            double dy = target.y - npc.getEntityBoundingBox().minY;
            ladderMotion = new Vec3d(MathHelper.clamp(dx * 0.4, -0.12, 0.12),
                MathHelper.clamp(dy * 0.6, -0.12, 0.22), MathHelper.clamp(dz * 0.4, -0.12, 0.12));
            applyLadder();
            return;
        }
        boolean sprint = navigation.maySprint() && (navigation.chaseEntity() != null || navigation.combatMode())
            && navigation.nextAction() == Action.WALK && !npc.collidedHorizontally;
        npc.setSprinting(sprint);
        double speed = navigation.moveSpeed() * (sprint ? 1.15 : 1.0);
        npc.getMoveHelper().setMoveTo(target.x, target.y, target.z, speed);
    }

    private void applyLadder() {
        navigation.haltMovement();
        top.npcpathfindinglegacy.compat.LegacyMath.motion(npc,ladderMotion); npc.fallDistance = 0; npc.velocityChanged = true;
    }

    void applyPostAiMovement() {
        if (!navigation.maySprint()) npc.setSprinting(false);
        if (ladderMotion != null && navigation.getPath() != null && !navigation.getPath().isFinished()) applyLadder();
    }
}
