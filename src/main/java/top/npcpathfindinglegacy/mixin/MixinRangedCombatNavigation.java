package top.npcpathfindinglegacy.mixin;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.pathfinding.PathNavigate;
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
    @Shadow private EntityNPCInterface npc;
    @Shadow private EntityLivingBase attackTarget;

    @Inject(method = "func_75246_d", at = @At("HEAD"), remap = false)
    private void npcpathfindinglegacy$positionRangedNpc(CallbackInfo ci) {
        if (!TacticsRuntime.active(npc) && npc.ais.onAttack == 0 && attackTarget != null && attackTarget.isEntityAlive()
            && npc.getNavigator() instanceof EnhancedPathNavigator && ((EnhancedPathNavigator)(npc.getNavigator())).combatMode()) {
            ((EnhancedPathNavigator)(npc.getNavigator())).tryMoveToEntityLiving(attackTarget, 1.0);
        }
    }

    @Redirect(method = "func_75246_d", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/pathfinding/PathNavigate;func_75499_g()V"), remap = false)
    private void npcpathfindinglegacy$preserveRangedPositioning(PathNavigate navigation) {
        if (TacticsRuntime.active(npc)) return;
        if (!(navigation instanceof EnhancedPathNavigator) || !((EnhancedPathNavigator)(navigation)).combatMode()) navigation.clearPath();
    }

    @Redirect(method = "func_75251_c", at = @At(value = "INVOKE",
        target = "Lnoppes/npcs/entity/EntityNPCInterface;func_70624_b(Lnet/minecraft/entity/EntityLivingBase;)V"), remap = false)
    private void npcpathfindinglegacy$preserveHybridTarget(EntityNPCInterface entity, EntityLivingBase replacement) {
        EntityLivingBase current = entity.getAttackTarget();
        // Switching from ranged to melee must not discard the hybrid unit's combat target.
        if (replacement == null && TacticsRuntime.active(entity) && current != null && current.isEntityAlive()
            && entity.stats.ranged.getMeleeRange() > 0
            && entity.isInRange(current, entity.stats.ranged.getMeleeRange())) return;
        entity.setAttackTarget(replacement);
    }

    @Redirect(method = "func_75251_c", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/pathfinding/PathNavigate;func_75499_g()V"), remap = false)
    private void npcpathfindinglegacy$preserveHybridMovement(PathNavigate navigation) {
        if (!TacticsRuntime.active(npc)) navigation.clearPath();
    }
}
