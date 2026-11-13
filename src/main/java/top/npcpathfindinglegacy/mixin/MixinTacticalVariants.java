package top.npcpathfindinglegacy.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import noppes.npcs.entity.EntityNPCInterface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.npcpathfindinglegacy.tactics.*;

@Mixin(value = EntityNPCInterface.class, remap = false)
public abstract class MixinTacticalVariants implements ITacticsEntity {
    @Unique private final TacticsState npcpathfindinglegacy$tactics = new TacticsState();
    public TacticsState npcpathfindinglegacy$tacticsState() { return npcpathfindinglegacy$tactics; }

    @Inject(method = "setResponse", at = @At("HEAD"), remap = false)
    private void npcpathfindinglegacy$removeOldVariants(CallbackInfo ci) {
        // Keep assault and the original melee/ranged attack goals. Never construct old variant goals.
        ((EntityNPCInterface)(Object)this).ais.tacticalVariant = 0;
        npcpathfindinglegacy$tactics.reset();
    }

    @Inject(method = "func_70652_k", at = @At("HEAD"), cancellable = true, remap = false)
    private void npcpathfindinglegacy$holdMelee(Entity target, CallbackInfoReturnable<Boolean> cir) {
        EntityNPCInterface npc = (EntityNPCInterface)(Object)this;
        if (TacticsRuntime.active(npc) && !npcpathfindinglegacy$tactics.allowAttack) cir.setReturnValue(false);
    }
    @Inject(method = "func_70652_k", at = @At("RETURN"), remap = false)
    private void npcpathfindinglegacy$meleeContact(Entity target, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) TacticsRuntime.attacked((EntityNPCInterface)(Object)this);
    }
    @Inject(method = "func_82196_d", at = @At("HEAD"), cancellable = true, remap = false)
    private void npcpathfindinglegacy$holdRanged(EntityLivingBase target, float power, CallbackInfo ci) {
        EntityNPCInterface npc = (EntityNPCInterface)(Object)this;
        if (TacticsRuntime.active(npc) && !npcpathfindinglegacy$tactics.allowAttack) ci.cancel();
    }
    @Inject(method = "func_82196_d", at = @At("TAIL"), remap = false)
    private void npcpathfindinglegacy$rangedContact(EntityLivingBase target, float power, CallbackInfo ci) {
        TacticsRuntime.attacked((EntityNPCInterface)(Object)this);
    }
}
