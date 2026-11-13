package top.npcpathfindinglegacy.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.npcpathfindinglegacy.pathfinding.LegacyVanillaHooks;
import net.minecraft.entity.Entity;
@Mixin(value=Entity.class,remap=false)
public abstract class MixinEntityPathfindingHooks {
    @Inject(method="func_70031_b",at=@At("HEAD"),cancellable=true,remap=false)
    private void npcpathfindinglegacy$preventWallSprint(boolean sprinting,CallbackInfo ci) {
        if (sprinting && LegacyVanillaHooks.preventSprint(this)) ci.cancel();
    }
    @Inject(method="func_70106_y",at=@At("HEAD"),remap=false)
    private void npcpathfindinglegacy$cancelRemovedNpc(CallbackInfo ci) { LegacyVanillaHooks.removed(this); }
}
