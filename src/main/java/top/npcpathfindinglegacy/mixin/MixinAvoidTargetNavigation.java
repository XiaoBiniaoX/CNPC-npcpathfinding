package top.npcpathfindinglegacy.mixin;

import net.minecraft.world.entity.ai.navigation.PathNavigation;
import noppes.npcs.ai.EntityAIAvoidTarget;
import noppes.npcs.entity.EntityNPCInterface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Avoidance goals cache navigation at construction, before subsequent mode switches. */
@Mixin(value = EntityAIAvoidTarget.class, remap = false)
public abstract class MixinAvoidTargetNavigation {
    @Shadow private EntityNPCInterface npc;
    @Shadow private PathNavigation entityPathNavigate;

    @Inject(method = {"m_8036_", "m_8045_"}, at = @At("HEAD"), remap = false)
    private void npcpathfindinglegacy$refreshQueryNavigation(CallbackInfoReturnable<Boolean> cir) {
        entityPathNavigate = npc.getNavigation();
    }

    @Inject(method = "m_8056_", at = @At("HEAD"), remap = false)
    private void npcpathfindinglegacy$refreshStartNavigation(CallbackInfo ci) {
        entityPathNavigate = npc.getNavigation();
    }
}
