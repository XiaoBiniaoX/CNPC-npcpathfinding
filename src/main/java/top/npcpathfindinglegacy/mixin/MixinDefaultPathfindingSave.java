package top.npcpathfindinglegacy.mixin;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.WorldServer;
import net.minecraft.util.text.TextComponentTranslation;
import noppes.npcs.*;
import noppes.npcs.constants.EnumPacketServer;
import noppes.npcs.constants.EnumPacketClient;
import noppes.npcs.entity.EntityNPCInterface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.npcpathfindinglegacy.pathfinding.DefaultPathfinding;

/** Consume only default requests; ordinary AI saves retain the original payload. */
@Mixin(value=PacketHandlerServer.class,remap=false)
public abstract class MixinDefaultPathfindingSave {
    @Inject(method="handlePacket",at=@At("HEAD"),cancellable=true,remap=false)
    private void npcpathfindinglegacy$saveDefault(EnumPacketServer type,ByteBuf buffer,EntityPlayerMP player,EntityNPCInterface npc,CallbackInfo ci) throws java.io.IOException {
        if (type!=EnumPacketServer.MainmenuAISave || npc==null) return;
        int index=buffer.readerIndex();NBTTagCompound tag;
        try { tag=Server.readNBT(buffer); } finally { buffer.readerIndex(index); }
        if (tag==null || !tag.hasKey(DefaultPathfinding.REQUEST_KEY)) return;
        ci.cancel();
        if (!tag.hasKey(DefaultPathfinding.REQUEST_KEY,3) || !(npc.world instanceof WorldServer)
            || !npc.world.getMinecraftServer().isCallingFromMinecraftThread()) return;
        DefaultPathfinding defaults=DefaultPathfinding.get((WorldServer)npc.world);
        if (CustomNpcsPermissions.hasPermission(player,CustomNpcsPermissions.NPC_CREATE)) {
            int mode=tag.getInteger(DefaultPathfinding.REQUEST_KEY);if (mode<0 || mode>7) return;
            defaults.setMode(mode);player.sendMessage(new TextComponentTranslation("npcpathfindinglegacy.gui.default_saved"));
        }
        NBTTagCompound reply=new NBTTagCompound();reply.setInteger(DefaultPathfinding.SYNC_KEY,defaults.mode());
        Server.sendData(player,EnumPacketClient.GUI_DATA,reply);
    }
}
