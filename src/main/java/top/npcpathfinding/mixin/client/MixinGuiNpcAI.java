package top.npcpathfinding.mixin.client;

import noppes.npcs.client.gui.mainmenu.GuiNpcAI;
import noppes.npcs.entity.data.DataAI;
import noppes.npcs.shared.client.gui.components.GuiButtonNop;
import noppes.npcs.shared.client.gui.listeners.IGuiInterface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.npcpathfinding.client.gui.GuiNpcPathfinding;

@Mixin(value = GuiNpcAI.class, remap = false)
public class MixinGuiNpcAI {
    @Shadow
    private DataAI ai;

    @Inject(method = "m_7856_", at = @At("TAIL"), remap = false)
    private void onInit(CallbackInfo ci) {
        GuiNpcAI self = (GuiNpcAI) (Object) this;
        self.addButton(new GuiButtonNop(
            (IGuiInterface) self,
            999,
            self.guiLeft + 230,
            self.guiTop + 160,
            80, 20,
            "npcpathfinding.gui.pathfinding"
        ));
    }

    @Inject(method = "buttonEvent", at = @At("HEAD"), remap = false, cancellable = true)
    private void onButtonEvent(GuiButtonNop button, CallbackInfo ci) {
        if (button.id == 999) {
            GuiNpcAI self = (GuiNpcAI) (Object) this;
            self.setSubGui(new GuiNpcPathfinding(self, ai));
            ci.cancel();
        }
    }
}
