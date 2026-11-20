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
import net.minecraft.entity.EntityLiving;
import net.minecraft.pathfinding.Path;
import net.minecraft.pathfinding.PathNavigate;
@Mixin(value=PathNavigate.class,remap=false)
public abstract class MixinTacticalNavigation {
    @Shadow(remap=false) protected EntityLiving field_75515_a;
    @Inject(method="func_75501_e()V",at=@At("HEAD"),cancellable=true,remap=false)
    private void npcpathfindinglegacy$trafficGate(CallbackInfo ci) {
        if (LegacyVanillaHooks.holdNavigation(field_75515_a)) ci.cancel();
    }
    @Inject(method="func_179677_a(Lnet/minecraft/util/math/Vec3d;)V",at=@At("HEAD"),cancellable=true,remap=false)
    private void npcpathfindinglegacy$trafficProgress(CallbackInfo ci) {
        if (LegacyVanillaHooks.trafficTracksProgress(field_75515_a)) ci.cancel();
    }
    @Inject(method="func_75499_g()V",at=@At("HEAD"),remap=false)
    private void npcpathfindinglegacy$trafficCancel(CallbackInfo ci) { LegacyVanillaHooks.trafficCancel(field_75515_a,this); }
    @Inject(method="func_75492_a(DDDD)Z",at=@At("HEAD"),cancellable=true,remap=false)
    private void npcpathfindinglegacy$coordinateMove(double x,double y,double z,double speed,CallbackInfoReturnable<Boolean> cir) {
        if (LegacyVanillaHooks.redirects(field_75515_a)) cir.setReturnValue(true);
    }
    @Inject(method="func_75497_a(Lnet/minecraft/entity/Entity;D)Z",at=@At("HEAD"),cancellable=true,remap=false)
    private void npcpathfindinglegacy$coordinateChase(Entity entity,double speed,CallbackInfoReturnable<Boolean> cir) {
        if (LegacyVanillaHooks.redirects(field_75515_a)) cir.setReturnValue(true);
    }
    @Inject(method="func_75484_a(Lnet/minecraft/pathfinding/Path;D)Z",at=@At("HEAD"),cancellable=true,remap=false)
    private void npcpathfindinglegacy$coordinatePath(Path path,double speed,CallbackInfoReturnable<Boolean> cir) {
        if (LegacyVanillaHooks.redirects(field_75515_a)) cir.setReturnValue(true);
    }
}
