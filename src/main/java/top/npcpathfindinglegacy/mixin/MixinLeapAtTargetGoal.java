package top.npcpathfindinglegacy.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.npcpathfindinglegacy.pathfinding.LegacyVanillaHooks;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.ai.EntityAILeapAtTarget;
@Mixin(value=EntityAILeapAtTarget.class,remap=false)
public abstract class MixinLeapAtTargetGoal {
    @Shadow(remap=false) private EntityLiving field_75328_a;
    @Inject(method={"func_75250_a","func_75253_b"},at=@At("HEAD"),cancellable=true,remap=false)
    private void npcpathfindinglegacy$preventWallLeap(CallbackInfoReturnable<Boolean> cir) {
        if (LegacyVanillaHooks.preventLeap(field_75328_a)) cir.setReturnValue(false);
    }
}
