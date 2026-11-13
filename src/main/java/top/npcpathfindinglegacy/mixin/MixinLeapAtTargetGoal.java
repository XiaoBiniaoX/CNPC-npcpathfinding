package top.npcpathfindinglegacy.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.LeapAtTargetGoal;
import noppes.npcs.entity.EntityNPCInterface;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.npcpathfindinglegacy.pathfinding.EnhancedPathNavigator;

@Mixin(LeapAtTargetGoal.class)
public abstract class MixinLeapAtTargetGoal {
    @Shadow @Final private Mob mob;

    @Inject(method = {"canUse", "canContinueToUse"}, at = @At("HEAD"), cancellable = true)
    private void npcpathfindinglegacy$preventWallLeap(CallbackInfoReturnable<Boolean> cir) {
        if (mob instanceof EntityNPCInterface npc && (mob.getNavigation() instanceof EnhancedPathNavigator
            || top.npcpathfindinglegacy.tactics.TacticsRuntime.active(npc))) cir.setReturnValue(false);
    }
}
