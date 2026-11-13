package top.npcpathfindinglegacy.mixin.client;

import noppes.npcs.client.gui.mainmenu.GuiNpcAI;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import noppes.npcs.entity.data.DataAI;
import noppes.npcs.shared.client.gui.components.GuiButtonNop;
import noppes.npcs.shared.client.gui.listeners.IGuiInterface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.npcpathfindinglegacy.client.gui.SubGuiNpcPathfinding;
import top.npcpathfindinglegacy.client.gui.SubGuiNpcTactics;
import net.minecraft.nbt.CompoundTag;
import top.npcpathfindinglegacy.pathfinding.DefaultPathfinding;

@Mixin(value = GuiNpcAI.class, remap = false)
public abstract class MixinGuiNpcAI extends Screen {
    private static final int PATHFINDING_BUTTON_ID = 990;

    @Shadow
    private DataAI ai;

    protected MixinGuiNpcAI(Component title) { super(title); }

    @Inject(method = "setGuiData", at = @At("HEAD"), cancellable = true, remap = false)
    private void npcpathfindinglegacy$ignoreLateDefaultReply(CompoundTag data, CallbackInfo ci) {
        // A save acknowledgement may arrive after the sub-GUI closes; it is not an NPC AI snapshot.
        if (data.contains(DefaultPathfinding.SYNC_KEY) && !data.contains("NPC_PathfindingMode")) ci.cancel();
    }

    // CNPC jar keeps Minecraft SRG names; GuiNpcAI.init is m_7856_ at runtime.
    @Inject(method = "m_7856_", at = @At("TAIL"), remap = false)
    private void npcpathfindinglegacy$addPathfindingButton(CallbackInfo ci) {
        GuiNpcAI self = (GuiNpcAI) (Object) this;
        self.getWrapper().labels.remove(21);
        self.getWrapper().textfields.remove(3);
        // addButton replaces only the ID map; the old button remains in Screen's render/input lists.
        removeWidget(self.getButton(17));
        self.addButton(new GuiButtonNop((IGuiInterface)self,17,self.guiLeft + 230,self.guiTop + 135,60,20,
            "npcpathfindinglegacy.tactics.configure"));
        self.getButton(17).setEnabled(this.ai.onAttack == 0);
        self.addButton(new GuiButtonNop(
            (IGuiInterface) self,
            PATHFINDING_BUTTON_ID,
            self.guiLeft + 230,
            self.guiTop + 160,
            60,
            20,
            "npcpathfindinglegacy.gui.open"
        ));
    }

    @Inject(method = "buttonEvent", at = @At("HEAD"), cancellable = true, remap = false)
    private void npcpathfindinglegacy$openPathfindingGui(GuiButtonNop button, CallbackInfo ci) {
        if (button.id == 17) {
            GuiNpcAI self = (GuiNpcAI)(Object)this;
            self.setSubGui(new SubGuiNpcTactics(this.ai)); ci.cancel(); return;
        }
        if (button.id != PATHFINDING_BUTTON_ID) {
            return;
        }
        GuiNpcAI self = (GuiNpcAI) (Object) this;
        self.setSubGui(new SubGuiNpcPathfinding(this.ai));
        ci.cancel();
    }
}
