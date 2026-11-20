package top.npcpathfindinglegacy.mixin;

import net.minecraft.entity.EntityLiving;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.npcpathfindinglegacy.pathfinding.LegacyVanillaHooks;

@Mixin(value = EntityLiving.class, remap = false)
public abstract class MixinMobTraffic {
    @Inject(method = "func_70626_be()V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/entity/ai/EntityMoveHelper;func_75641_c()V"), remap = false)
    private void npcpathfindinglegacy$beforeMoveControl(CallbackInfo ci) { LegacyVanillaHooks.trafficTick(this); }

    @Inject(method = "func_70626_be()V", at = @At("RETURN"), remap = false)
    private void npcpathfindinglegacy$afterMoveControls(CallbackInfo ci) { LegacyVanillaHooks.trafficAfterControls(this); }
}
