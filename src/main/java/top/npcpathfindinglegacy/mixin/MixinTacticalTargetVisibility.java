package top.npcpathfindinglegacy.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.npcpathfindinglegacy.pathfinding.LegacyVanillaHooks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.ai.EntityAITarget;
import net.minecraft.entity.ai.EntitySenses;
import org.spongepowered.asm.mixin.Final;
@Mixin(value=EntityAITarget.class,remap=false)
public abstract class MixinTacticalTargetVisibility {
    @Shadow(remap=false) @Final protected EntityCreature field_75299_d;
    @Redirect(method="func_75253_b",at=@At(value="INVOKE",target="Lnet/minecraft/entity/ai/EntitySenses;func_75522_a(Lnet/minecraft/entity/Entity;)Z"),remap=false)
    private boolean npcpathfindinglegacy$remember(EntitySenses senses,Entity target) {
        return LegacyVanillaHooks.remembers(field_75299_d,target) || senses.canSee(target);
    }
}
