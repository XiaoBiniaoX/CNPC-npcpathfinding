package top.npcpathfindinglegacy.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import noppes.npcs.entity.EntityNPCInterface;
import noppes.npcs.items.ItemNpcWand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import top.npcpathfindinglegacy.pathfinding.DefaultPathfinding;
import top.npcpathfindinglegacy.pathfinding.IDataAIPathfinding;

@Mixin(value = ItemNpcWand.class, remap = false)
public abstract class MixinNpcWandDefaultPathfinding {
    @Redirect(method = "m_6225_", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/Level;m_7967_(Lnet/minecraft/world/entity/Entity;)Z"), remap = false)
    private boolean npcpathfindinglegacy$applyNewNpcDefault(Level level, Entity entity) {
        if (level instanceof ServerLevel server && server.getServer().isSameThread()
            && entity instanceof EntityNPCInterface npc && npc.ais instanceof IDataAIPathfinding settings)
            settings.npcpathfindinglegacy$setMode(DefaultPathfinding.get(server).mode());
        return level.addFreshEntity(entity);
    }
}
