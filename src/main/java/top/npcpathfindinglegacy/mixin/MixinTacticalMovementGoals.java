package top.npcpathfindinglegacy.mixin;

import noppes.npcs.ai.EntityAISprintToTarget;
import noppes.npcs.ai.EntityAIPounceTarget;
import noppes.npcs.entity.EntityNPCInterface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.npcpathfindinglegacy.tactics.TacticsRuntime;

/** Sprint's MOVE flag otherwise excludes the weapon goals while ranged tactics keep their distance. */
@Mixin(value = {EntityAISprintToTarget.class, EntityAIPounceTarget.class}, remap = false)
public abstract class MixinTacticalMovementGoals {
    @Shadow(remap = false) private EntityNPCInterface npc;

    @Inject(method = {"m_8036_", "m_8045_"}, at = @At("HEAD"), cancellable = true, remap = false)
    private void npcpathfindinglegacy$releaseMovementGoal(CallbackInfoReturnable<Boolean> cir) {
        if (TacticsRuntime.active(npc)) cir.setReturnValue(false);
    }
}
