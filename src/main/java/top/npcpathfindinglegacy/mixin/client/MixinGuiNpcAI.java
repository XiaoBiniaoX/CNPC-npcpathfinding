package top.npcpathfindinglegacy.mixin.client;

import net.minecraft.client.gui.GuiButton;
import noppes.npcs.client.gui.mainmenu.GuiNpcAI;
import noppes.npcs.client.gui.util.GuiNpcButton;
import noppes.npcs.entity.data.DataAI;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.npcpathfindinglegacy.client.gui.SubGuiNpcPathfinding;

@Mixin(value = GuiNpcAI.class, remap = false)
public abstract class MixinGuiNpcAI {
    private static final int PATHFINDING_BUTTON_ID = 990;

    @Shadow
    private DataAI ai;

    @Inject(method = "func_73866_w_", at = @At("TAIL"), remap = false)
    private void npcpathfindinglegacy$addPathfindingButton(CallbackInfo ci) {
        GuiNpcAI self = (GuiNpcAI) (Object) this;
        self.addButton(new GuiNpcButton(
            PATHFINDING_BUTTON_ID,
            self.guiLeft + 230,
            self.guiTop + 160,
            90,
            20,
            "npcpathfindinglegacy.gui.open"
        ));
    }

    @Inject(method = "func_146284_a", at = @At("HEAD"), cancellable = true, remap = false)
    private void npcpathfindinglegacy$openPathfindingGui(GuiButton button, CallbackInfo ci) {
        if (button.id != PATHFINDING_BUTTON_ID) {
            return;
        }
        GuiNpcAI self = (GuiNpcAI) (Object) this;
        self.setSubGui(new SubGuiNpcPathfinding(this.ai));
        ci.cancel();
    }
}
