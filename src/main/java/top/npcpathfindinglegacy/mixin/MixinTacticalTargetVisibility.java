package top.npcpathfindinglegacy.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import net.minecraft.world.entity.ai.sensing.Sensing;
import noppes.npcs.ai.target.NpcNearestAttackableTargetGoal;
import noppes.npcs.entity.EntityNPCInterface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import top.npcpathfindinglegacy.tactics.TacticsRuntime;

/** Taking cover must not reset the acquired target just because the unit intentionally breaks sight. */
@Mixin(value = NpcNearestAttackableTargetGoal.class, remap = false)
public abstract class MixinTacticalTargetVisibility extends TargetGoal {
    protected MixinTacticalTargetVisibility(Mob mob, boolean mustSee) { super(mob,mustSee); }

    @Redirect(method = "canContinueToUse", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/ai/sensing/Sensing;hasLineOfSight(Lnet/minecraft/world/entity/Entity;)Z"), remap = false)
    private boolean npcpathfindinglegacy$rememberCoveredTarget(Sensing sensing, Entity target) {
        // The original method still enforces alive/attackable, team and follow-range checks.
        return mob instanceof EntityNPCInterface npc && TacticsRuntime.retainHiddenTarget(npc,target)
            || sensing.hasLineOfSight(target);
    }
}
