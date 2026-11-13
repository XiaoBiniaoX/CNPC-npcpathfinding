package top.npcpathfindinglegacy.mixin;

import net.minecraft.pathfinding.PathNavigate;
import noppes.npcs.ai.EntityAIAttackTarget;
import noppes.npcs.entity.EntityNPCInterface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import top.npcpathfindinglegacy.tactics.TacticsRuntime;

@Mixin(value = EntityAIAttackTarget.class, remap = false)
public abstract class MixinMeleeTacticalNavigation {
    @Shadow private EntityNPCInterface npc;
    @Redirect(method = "func_75251_c", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/pathfinding/PathNavigate;func_75499_g()V"), remap = false)
    private void npcpathfindinglegacy$preserveTacticMovement(PathNavigate navigation) {
        if (!TacticsRuntime.active(npc)) navigation.clearPath();
    }
}
