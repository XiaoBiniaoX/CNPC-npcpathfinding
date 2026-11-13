package top.npcpathfindinglegacy.mixin;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import noppes.npcs.items.ItemNpcWand;
import noppes.npcs.entity.EntityNPCInterface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import top.npcpathfindinglegacy.pathfinding.*;

@Mixin(value=ItemNpcWand.class,remap=false)
public abstract class MixinNpcWandDefaultPathfinding {
    @Redirect(method="func_180614_a",at=@At(value="INVOKE",target="Lnet/minecraft/world/World;func_72838_d(Lnet/minecraft/entity/Entity;)Z"),remap=false)
    private boolean npcpathfindinglegacy$applyDefault(World world,Entity entity) {
        if (world instanceof WorldServer && world.getMinecraftServer().isCallingFromMinecraftThread()
            && entity instanceof EntityNPCInterface && ((EntityNPCInterface)entity).ais instanceof IDataAIPathfinding) {
            ((IDataAIPathfinding)((EntityNPCInterface)entity).ais).npcpathfindinglegacy$setMode(DefaultPathfinding.get((WorldServer)world).mode());
        }
        return world.spawnEntity(entity);
    }
}
