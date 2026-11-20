package top.npcpathfindinglegacy.pathfinding;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import noppes.npcs.entity.EntityNPCInterface;
import top.npcpathfindinglegacy.pathfinding.PlannedRoute.Action;

/** Wall attachment and ledge exit; never launches parkour or enables sprinting. */
final class WallMovementController {
    private final EnhancedPathNavigator navigation;
    private final EntityNPCInterface npc;
    private final TerrainSampler terrain;
    private Vec3 climbMotion;

    WallMovementController(EnhancedPathNavigator navigation, EntityNPCInterface npc, TerrainSampler terrain) {
        this.navigation = navigation; this.npc = npc; this.terrain = terrain;
    }

    void reset() { climbMotion = null; }
    boolean directMovement() { return climbMotion != null; }

    void follow() {
        reset(); npc.setSprinting(false);
        Vec3 target = navigation.nextPosition();
        if (navigation.nextAction() != Action.WALL) {
            npc.getMoveControl().setWantedPosition(target.x, target.y, target.z, Math.min(1, navigation.moveSpeed()));
            return;
        }
        BlockPos feet = navigation.currentFeetPos();
        Direction wall = terrain.wallDirection(feet);
        if (wall == null) wall = terrain.wallDirection(feet.below());
        if (wall == null && !npc.onGround()) { navigation.recoverJump(null, null); return; }
        double dx = target.x - npc.getX(), dz = target.z - npc.getZ();
        double dy = target.y - npc.getBoundingBox().minY;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double speed = Math.min(0.12, horizontal * 0.4);
        double x = horizontal > 1.0E-4 ? dx / horizontal * speed : 0;
        double z = horizontal > 1.0E-4 ? dz / horizontal * speed : 0;
        boolean ledgeExit = horizontal > 0.35 && target.y > npc.getBoundingBox().minY - 0.1;
        double y = Mth.clamp((dy + (ledgeExit ? 0.08 : 0)) * 0.5, -0.12, 0.16);
        if (ledgeExit && npc.getBoundingBox().minY < target.y + 0.02) { x = 0; z = 0; }
        if (wall != null) { x += wall.getStepX() * 0.035; z += wall.getStepZ() * 0.035; }
        climbMotion = new Vec3(x, y, z);
        applyClimb();
    }

    private void applyClimb() {
        navigation.haltMovement(); npc.setDeltaMovement(climbMotion);
        npc.fallDistance = 0; npc.hasImpulse = true;
    }

    void applyPostAiMovement() {
        npc.setSprinting(false);
        if (climbMotion != null && navigation.getPath() != null && !navigation.getPath().isDone()) applyClimb();
    }
}
