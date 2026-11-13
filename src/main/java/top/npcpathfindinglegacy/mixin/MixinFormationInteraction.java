package top.npcpathfindinglegacy.mixin;

import noppes.npcs.ai.selector.NPCInteractSelector;
import noppes.npcs.entity.EntityNPCInterface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.npcpathfindinglegacy.tactics.TacticsRuntime;

/** Automatic NPC conversations must not pull a unit out of its formation slot. */
@Mixin(value = NPCInteractSelector.class, remap = false)
public abstract class MixinFormationInteraction {
    @Shadow private EntityNPCInterface npc;
    @Inject(method = "isEntityApplicable", at = @At("HEAD"), cancellable = true, remap = false)
    private void npcpathfindinglegacy$keepFormation(EntityNPCInterface other, CallbackInfoReturnable<Boolean> cir) {
        if (TacticsRuntime.formationMember(npc) || other != null && TacticsRuntime.formationMember(other)) cir.setReturnValue(false);
    }
}
