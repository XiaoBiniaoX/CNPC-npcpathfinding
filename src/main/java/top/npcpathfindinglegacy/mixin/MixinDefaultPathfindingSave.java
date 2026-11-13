package top.npcpathfindinglegacy.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import noppes.npcs.CustomNpcsPermissions;
import noppes.npcs.constants.EnumMenuType;
import noppes.npcs.packets.PacketServerBasic;
import noppes.npcs.packets.server.SPacketMenuSave;
import noppes.npcs.packets.Packets;
import noppes.npcs.packets.client.PacketGuiData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.npcpathfindinglegacy.pathfinding.DefaultPathfinding;

/** Reuse CNPC's authenticated editor packet; consume the request without changing this NPC's AI. */
@Mixin(value = SPacketMenuSave.class, remap = false)
public abstract class MixinDefaultPathfindingSave extends PacketServerBasic {
    @Shadow private EnumMenuType type;
    @Shadow private CompoundTag data;
    @Inject(method = "handle", at = @At("HEAD"), cancellable = true, remap = false)
    private void npcpathfindinglegacy$saveDefault(CallbackInfo ci) {
        if (type != EnumMenuType.AI || data == null || !data.contains(DefaultPathfinding.REQUEST_KEY)) return;
        ci.cancel();
        if (!data.contains(DefaultPathfinding.REQUEST_KEY,net.minecraft.nbt.Tag.TAG_INT)) return;
        if (player == null || npc == null || !(npc.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) return;
        DefaultPathfinding defaults = DefaultPathfinding.get(level);
        if (!CustomNpcsPermissions.hasPermission(player,CustomNpcsPermissions.NPC_CREATE)) {
            npcpathfindinglegacy$replyDefault(defaults.mode()); return;
        }
        int mode = data.getInt(DefaultPathfinding.REQUEST_KEY);
        if (mode < 0 || mode > 7) return;
        defaults.setMode(mode);
        npcpathfindinglegacy$replyDefault(defaults.mode());
        player.sendSystemMessage(Component.translatable("npcpathfindinglegacy.gui.default_saved"));
    }
    private void npcpathfindinglegacy$replyDefault(int mode) {
        CompoundTag reply = new CompoundTag(); reply.putInt(DefaultPathfinding.SYNC_KEY,mode);
        Packets.send(player,new PacketGuiData(reply));
    }
}
