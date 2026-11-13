package top.npcpathfindinglegacy.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.npcpathfindinglegacy.pathfinding.LegacyVanillaHooks;
import net.minecraft.entity.EntityLivingBase;
@Mixin(value=EntityLivingBase.class,remap=false)
public abstract class MixinLivingEntityJump {
    @Inject(method="func_70664_aZ",at=@At("HEAD"),cancellable=true,remap=false)
    private void npcpathfindinglegacy$protectControlledJump(CallbackInfo ci) {
        if (LegacyVanillaHooks.preventJump(this)) ci.cancel();
    }
    @Inject(method="func_70664_aZ",at=@At("TAIL"),remap=false)
    private void npcpathfindinglegacy$capLegacyJump(CallbackInfo ci) { LegacyVanillaHooks.capJump(this); }
}
