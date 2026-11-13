package top.npcpathfindinglegacy.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import noppes.npcs.constants.EnumMenuType;
import noppes.npcs.packets.PacketServerBasic;
import noppes.npcs.packets.client.PacketGuiData;
import noppes.npcs.packets.server.SPacketMenuGet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import top.npcpathfindinglegacy.pathfinding.DefaultPathfinding;

/** Attach world defaults to the existing authenticated editor response, without adding NPC NBT fields. */
@Mixin(value = SPacketMenuGet.class, remap = false)
public abstract class MixinDefaultPathfindingGet extends PacketServerBasic {
    @Shadow private EnumMenuType type;
    @Redirect(method = "handle", at = @At(value = "NEW", target = "noppes/npcs/packets/client/PacketGuiData"), remap = false)
    private PacketGuiData npcpathfindinglegacy$syncDefault(CompoundTag data) {
        if (type == EnumMenuType.AI && npc != null && npc.level() instanceof ServerLevel level)
            data.putInt(DefaultPathfinding.SYNC_KEY,DefaultPathfinding.get(level).mode());
        return new PacketGuiData(data);
    }
}
