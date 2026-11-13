package top.npcpathfindinglegacy.mixin;

import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;
import noppes.npcs.ai.EntityAIPanic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.npcpathfindinglegacy.pathfinding.EnhancedPathNavigator;

@Mixin(value = EntityAIPanic.class, remap = false)
public abstract class MixinPanicNavigation {
    @Shadow private PathfinderMob entityCreature;
    @Shadow private double randPosX, randPosY, randPosZ;

    @Inject(method = "canUse", at = @At("HEAD"), cancellable = true, remap = false)
    private void npcpathfindinglegacy$fleeThreat(CallbackInfoReturnable<Boolean> cir) {
        if (entityCreature.getTarget() != null
            && entityCreature.getNavigation() instanceof EnhancedPathNavigator navigation && navigation.combatMode()) {
            Vec3 away = DefaultRandomPos.getPosAway(entityCreature, 10, 5, entityCreature.getTarget().position());
            if (away != null) {
                randPosX = away.x; randPosY = away.y; randPosZ = away.z;
                cir.setReturnValue(true);
            }
        }
    }

    @Inject(method = "canContinueToUse", at = @At("HEAD"), cancellable = true, remap = false)
    private void npcpathfindinglegacy$continueBurningEscape(CallbackInfoReturnable<Boolean> cir) {
        if (entityCreature.getNavigation() instanceof EnhancedPathNavigator navigation) {
            cir.setReturnValue((entityCreature.getTarget() != null || entityCreature.isOnFire()) && !navigation.isDone());
        }
    }
}
