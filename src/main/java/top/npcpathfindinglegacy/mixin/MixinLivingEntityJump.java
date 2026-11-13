package top.npcpathfindinglegacy.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import noppes.npcs.entity.EntityNPCInterface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.npcpathfindinglegacy.pathfinding.EnhancedPathNavigator;

@Mixin(LivingEntity.class)
public abstract class MixinLivingEntityJump {
    @Inject(method = "jumpFromGround", at = @At("HEAD"), cancellable = true)
    private void npcpathfindinglegacy$protectControlledJump(CallbackInfo ci) {
        if ((Object) this instanceof EntityNPCInterface npc && !npc.level().isClientSide
            && npc.getNavigation() instanceof EnhancedPathNavigator navigation && navigation.controlsJump()) ci.cancel();
    }

    @Inject(method = "jumpFromGround", at = @At("TAIL"))
    private void npcpathfindinglegacy$capLegacyJump(CallbackInfo ci) {
        if ((Object) this instanceof EntityNPCInterface npc && !npc.level().isClientSide
            && npc.getNavigation() instanceof EnhancedPathNavigator navigation && navigation.controlledMode()) {
            Vec3 motion = npc.getDeltaMovement();
            npc.setDeltaMovement(motion.x, Math.min(motion.y, 0.50D), motion.z);
        }
    }
}
