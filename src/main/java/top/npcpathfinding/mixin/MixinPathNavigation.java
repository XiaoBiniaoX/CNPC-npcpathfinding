package top.npcpathfinding.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.npcpathfinding.pathfinding.NpcPathNodeEvaluator;

@Mixin(PathNavigation.class)
public abstract class MixinPathNavigation {
    @Shadow public NodeEvaluator nodeEvaluator;
    @Shadow @Final @Mutable private net.minecraft.world.level.pathfinder.PathFinder pathFinder;

    @Inject(method = "setCanFloat", at = @At("HEAD"), cancellable = true)
    private void npcpathfinding$guardNullNodeEvaluator(boolean canFloat, CallbackInfo ci) {
        if (this.nodeEvaluator != null) return;
        ci.cancel();
    }
}
