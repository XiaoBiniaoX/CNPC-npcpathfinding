package top.npcpathfindinglegacy.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import noppes.npcs.ai.EntityAIRangedAttack;
import noppes.npcs.entity.EntityNPCInterface;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.npcpathfindinglegacy.pathfinding.EnhancedPathNavigator;
import top.npcpathfindinglegacy.tactics.TacticsRuntime;

@Mixin(value = EntityAIRangedAttack.class, remap = false)
public abstract class MixinRangedCombatNavigation {
    @Shadow @Final private EntityNPCInterface npc;
    @Shadow private LivingEntity attackTarget;

    @Inject(method = "tick", at = @At("HEAD"), remap = false)
    private void npcpathfindinglegacy$positionRangedNpc(CallbackInfo ci) {
        if (!TacticsRuntime.active(npc) && npc.ais.onAttack == 0 && attackTarget != null && attackTarget.isAlive()
            && npc.getNavigation() instanceof EnhancedPathNavigator navigation && navigation.combatMode()) {
            navigation.moveTo(attackTarget, 1.0);
        }
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/ai/navigation/PathNavigation;stop()V"), remap = false)
    private void npcpathfindinglegacy$preserveRangedPositioning(PathNavigation navigation) {
        if (TacticsRuntime.active(npc)) return;
        if (!(navigation instanceof EnhancedPathNavigator enhanced) || !enhanced.combatMode()) navigation.stop();
    }

    @Redirect(method = "stop", at = @At(value = "INVOKE",
        target = "Lnoppes/npcs/entity/EntityNPCInterface;setTarget(Lnet/minecraft/world/entity/LivingEntity;)V"), remap = false)
    private void npcpathfindinglegacy$preserveHybridTarget(EntityNPCInterface entity, LivingEntity replacement) {
        LivingEntity current = entity.getTarget();
        // Switching from ranged to melee must not discard the hybrid unit's combat target.
        if (replacement == null && TacticsRuntime.active(entity) && current != null && current.isAlive()
            && entity.stats.ranged.getMeleeRange() > 0
            && entity.isInRange(current, entity.stats.ranged.getMeleeRange())) return;
        entity.setTarget(replacement);
    }

    @Redirect(method = "stop", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/ai/navigation/PathNavigation;stop()V"), remap = false)
    private void npcpathfindinglegacy$preserveHybridMovement(PathNavigation navigation) {
        if (!TacticsRuntime.active(npc)) navigation.stop();
    }
}
