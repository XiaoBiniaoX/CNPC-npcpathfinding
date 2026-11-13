package top.npcpathfindinglegacy.mixin;

import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.ai.RandomPositionGenerator;
import net.minecraft.util.math.Vec3d;
import noppes.npcs.ai.EntityAIPanic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.npcpathfindinglegacy.pathfinding.EnhancedPathNavigator;

@Mixin(value = EntityAIPanic.class, remap = false)
public abstract class MixinPanicNavigation {
    @Shadow private EntityCreature entityCreature;
    @Shadow private double randPosX, randPosY, randPosZ;

    @Inject(method = "func_75250_a", at = @At("HEAD"), cancellable = true, remap = false)
    private void npcpathfindinglegacy$fleeThreat(CallbackInfoReturnable<Boolean> cir) {
        if (entityCreature.getAttackTarget() != null
            && entityCreature.getNavigator() instanceof EnhancedPathNavigator && ((EnhancedPathNavigator)(entityCreature.getNavigator())).combatMode()) {
            Vec3d away = RandomPositionGenerator.findRandomTargetBlockAwayFrom(entityCreature, 10, 5, entityCreature.getAttackTarget().getPositionVector());
            if (away != null) {
                randPosX = away.x; randPosY = away.y; randPosZ = away.z;
                cir.setReturnValue(true);
            }
        }
    }

    @Inject(method = "func_75253_b", at = @At("HEAD"), cancellable = true, remap = false)
    private void npcpathfindinglegacy$continueBurningEscape(CallbackInfoReturnable<Boolean> cir) {
        if (entityCreature.getNavigator() instanceof EnhancedPathNavigator) {
            cir.setReturnValue((entityCreature.getAttackTarget() != null || entityCreature.isBurning()) && !((EnhancedPathNavigator)(entityCreature.getNavigator())).noPath());
        }
    }
}
