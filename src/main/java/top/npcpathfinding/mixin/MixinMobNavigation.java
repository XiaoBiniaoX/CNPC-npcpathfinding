package top.npcpathfinding.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import noppes.npcs.entity.EntityNPCInterface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.npcpathfinding.pathfinding.IDataAIPathfinding;
import top.npcpathfinding.pathfinding.INpcPathfindingEntity;

@Mixin(Mob.class)
public class MixinMobNavigation {
    @Inject(method = "getNavigation", at = @At("HEAD"), cancellable = true)
    private void npcpathfinding$getNavigation(CallbackInfoReturnable<PathNavigation> cir) {
        Object self = this;
        if (!(self instanceof EntityNPCInterface npc) || !(self instanceof INpcPathfindingEntity pathing)) {
            return;
        }
        pathing.npcpathfinding$refreshNavigationMode();
        if (npc.ais instanceof IDataAIPathfinding data && data.npcpathfinding$getMode() == 1) {
            PathNavigation navigation = pathing.npcpathfinding$getBaritoneNavigation();
            if (navigation != null) {
                cir.setReturnValue(navigation);
            }
        } else {
            PathNavigation navigation = pathing.npcpathfinding$getVanillaNavigation();
            if (navigation != null) {
                cir.setReturnValue(navigation);
            }
        }
    }
}
