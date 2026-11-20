package top.npcpathfindinglegacy.mixin;

import net.minecraft.world.entity.Mob;
import noppes.npcs.entity.EntityNPCInterface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.npcpathfindinglegacy.traffic.TrafficController;

@Mixin(Mob.class)
public abstract class MixinMobTraffic {
    @Inject(method = "serverAiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/control/MoveControl;tick()V"))
    private void npcpathfindinglegacy$steerActualMovement(CallbackInfo ci) {
        if ((Object)this instanceof EntityNPCInterface npc) TrafficController.tick(npc);
    }
    @Inject(method = "serverAiStep", at = @At("RETURN"))
    private void npcpathfindinglegacy$holdJumpWhileQueued(CallbackInfo ci) {
        if ((Object)this instanceof EntityNPCInterface npc) TrafficController.afterControls(npc);
    }
}
