package top.npcpathfindinglegacy.mixin;

import net.minecraft.world.entity.ai.navigation.PathNavigation;
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
    @Redirect(method = "m_8041_", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/ai/navigation/PathNavigation;m_26573_()V"), remap = false)
    private void npcpathfindinglegacy$preserveTacticMovement(PathNavigation navigation) {
        if (!TacticsRuntime.active(npc)) navigation.stop();
    }
}
