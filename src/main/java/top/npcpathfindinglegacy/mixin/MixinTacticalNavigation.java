package top.npcpathfindinglegacy.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import noppes.npcs.entity.EntityNPCInterface;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.npcpathfindinglegacy.tactics.TacticsRuntime;
import top.npcpathfindinglegacy.traffic.TrafficController;

@Mixin(PathNavigation.class)
public abstract class MixinTacticalNavigation {
    @Shadow @Final protected Mob mob;
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void npcpathfindinglegacy$queueWithoutReplanning(CallbackInfo ci) {
        if (mob instanceof EntityNPCInterface npc && TrafficController.holdNavigation(npc)) ci.cancel();
    }
    @Inject(method = "stop", at = @At("HEAD"))
    private void npcpathfindinglegacy$releasePassage(CallbackInfo ci) {
        if (mob instanceof EntityNPCInterface npc) TrafficController.cancel(npc);
    }
    @Inject(method = "moveTo(DDDD)Z", at = @At("HEAD"), cancellable = true)
    private void npcpathfindinglegacy$coordinateMove(double x, double y, double z, double speed, CallbackInfoReturnable<Boolean> cir) {
        if (mob instanceof EntityNPCInterface npc && TacticsRuntime.redirects(npc)) cir.setReturnValue(true);
    }
    @Inject(method = "moveTo(Lnet/minecraft/world/entity/Entity;D)Z", at = @At("HEAD"), cancellable = true)
    private void npcpathfindinglegacy$coordinateChase(Entity entity, double speed, CallbackInfoReturnable<Boolean> cir) {
        if (mob instanceof EntityNPCInterface npc && TacticsRuntime.redirects(npc)) cir.setReturnValue(true);
    }
    @Inject(method = "moveTo(Lnet/minecraft/world/level/pathfinder/Path;D)Z", at = @At("HEAD"), cancellable = true)
    private void npcpathfindinglegacy$coordinatePath(Path path, double speed, CallbackInfoReturnable<Boolean> cir) {
        if (mob instanceof EntityNPCInterface npc && TacticsRuntime.redirects(npc)) cir.setReturnValue(true);
    }
}
