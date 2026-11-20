package top.npcpathfindinglegacy.pathfinding;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import noppes.npcs.entity.EntityNPCInterface;
import top.npcpathfindinglegacy.pathfinding.PlannedRoute.Action;

/** Wall attachment and ledge exit; never launches parkour or enables sprinting. */
final class WallMovementController {
    private final EnhancedPathNavigator navigation;
    private final EntityNPCInterface npc;
    private final TerrainSampler terrain;
    private Vec3d climbMotion;

    WallMovementController(EnhancedPathNavigator navigation, EntityNPCInterface npc, TerrainSampler terrain) {
        this.navigation = navigation; this.npc = npc; this.terrain = terrain;
    }

    void reset() { climbMotion = null; }
    boolean directMotion() { return climbMotion != null; }

    void follow() {
        reset(); npc.setSprinting(false);
        Vec3d target = navigation.nextPosition();
        if (navigation.nextAction() != Action.WALL) {
            npc.getMoveHelper().setMoveTo(target.x, target.y, target.z, Math.min(1, navigation.moveSpeed()));
            return;
        }
        BlockPos feet = navigation.currentFeetPos();
        EnumFacing wall = terrain.wallDirection(feet);
        if (wall == null) wall = terrain.wallDirection(feet.down());
        if (wall == null && !npc.onGround) { navigation.recoverJump(null, null); return; }
        double dx = target.x - npc.posX, dz = target.z - npc.posZ;
        double dy = target.y - npc.getEntityBoundingBox().minY;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double speed = Math.min(0.12, horizontal * 0.4);
        double x = horizontal > 1.0E-4 ? dx / horizontal * speed : 0;
        double z = horizontal > 1.0E-4 ? dz / horizontal * speed : 0;
        boolean ledgeExit = horizontal > 0.35 && target.y > npc.getEntityBoundingBox().minY - 0.1;
        double y = MathHelper.clamp((dy + (ledgeExit ? 0.08 : 0)) * 0.5, -0.12, 0.16);
        if (ledgeExit && npc.getEntityBoundingBox().minY < target.y + 0.02) { x = 0; z = 0; }
        if (wall != null) { x += wall.getFrontOffsetX() * 0.035; z += wall.getFrontOffsetZ() * 0.035; }
        climbMotion = new Vec3d(x, y, z);
        applyClimb();
    }

    private void applyClimb() {
        navigation.haltMovement(); top.npcpathfindinglegacy.compat.LegacyMath.motion(npc,climbMotion);
        npc.fallDistance = 0; npc.velocityChanged = true;
    }

    void applyPostAiMovement() {
        npc.setSprinting(false);
        if (climbMotion != null && navigation.getPath() != null && !navigation.getPath().isFinished()) applyClimb();
    }
}
