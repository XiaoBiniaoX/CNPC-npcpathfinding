package top.npcpathfindinglegacy.mixin;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.WorldServer;
import noppes.npcs.*;
import noppes.npcs.constants.EnumPacketServer;
import noppes.npcs.constants.EnumPacketClient;
import noppes.npcs.entity.EntityNPCInterface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.npcpathfindinglegacy.pathfinding.DefaultPathfinding;

/** handlePacket runs after CNPC's tool/NPC/permission checks and server scheduling. */
@Mixin(value=PacketHandlerServer.class,remap=false)
public abstract class MixinDefaultPathfindingGet {
    @Inject(method="handlePacket",at=@At("HEAD"),cancellable=true,remap=false)
    private void npcpathfindinglegacy$getDefault(EnumPacketServer type,ByteBuf buffer,EntityPlayerMP player,EntityNPCInterface npc,CallbackInfo ci) {
        if (type!=EnumPacketServer.MainmenuAIGet || npc==null || !(npc.world instanceof WorldServer)) return;
        if (!npc.world.getMinecraftServer().isCallingFromMinecraftThread()) return;
        NBTTagCompound tag=npc.ais.writeToNBT(new NBTTagCompound());
        tag.setInteger(DefaultPathfinding.SYNC_KEY,DefaultPathfinding.get((WorldServer)npc.world).mode());
        Server.sendData(player,EnumPacketClient.GUI_DATA,tag);ci.cancel();
    }
}
