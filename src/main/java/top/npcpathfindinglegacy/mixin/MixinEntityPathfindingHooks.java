package top.npcpathfindinglegacy.mixin;

import net.minecraft.world.entity.Entity;
import noppes.npcs.entity.EntityNPCInterface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.npcpathfindinglegacy.pathfinding.EnhancedPathNavigator;
import top.npcpathfindinglegacy.pathfinding.INpcPathfindingEntity;
import top.npcpathfindinglegacy.tactics.TacticsRuntime;

@Mixin(Entity.class)
public abstract class MixinEntityPathfindingHooks {
    @Inject(method = "setSprinting", at = @At("HEAD"), cancellable = true)
    private void npcpathfindinglegacy$preventWallSprint(boolean sprinting, CallbackInfo ci) {
        if (sprinting && (Object) this instanceof EntityNPCInterface npc && !npc.level().isClientSide
            && npc.getNavigation() instanceof EnhancedPathNavigator navigation && !navigation.maySprint()) ci.cancel();
    }

    @Inject(method = "remove", at = @At("HEAD"))
    private void npcpathfindinglegacy$cancelRemovedNpc(Entity.RemovalReason reason, CallbackInfo ci) {
        if ((Object)this instanceof EntityNPCInterface npc) top.npcpathfindinglegacy.traffic.TrafficController.removed(npc);
        if ((Object) this instanceof EntityNPCInterface npc && !npc.level().isClientSide) TacticsRuntime.removed(npc);
        if ((Object) this instanceof INpcPathfindingEntity pathing) pathing.npcpathfindinglegacy$clearEnhancedState();
    }
}
