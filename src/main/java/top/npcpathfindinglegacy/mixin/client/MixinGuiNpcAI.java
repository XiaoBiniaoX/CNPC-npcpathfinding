package top.npcpathfindinglegacy.mixin.client;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.nbt.NBTTagCompound;
import noppes.npcs.client.gui.mainmenu.GuiNpcAI;
import noppes.npcs.client.gui.util.GuiNpcButton;
import noppes.npcs.entity.data.DataAI;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.npcpathfindinglegacy.client.gui.*;
import top.npcpathfindinglegacy.pathfinding.DefaultPathfinding;

@Mixin(value=GuiNpcAI.class,remap=false)
public abstract class MixinGuiNpcAI extends GuiScreen {
    @Shadow private DataAI ai;
    @Inject(method="setGuiData",at=@At("HEAD"),cancellable=true,remap=false)
    private void npcpathfindinglegacy$ignoreLateDefaultReply(NBTTagCompound tag,CallbackInfo ci) {
        if (tag.hasKey(DefaultPathfinding.SYNC_KEY) && !tag.hasKey("NPC_PathfindingMode")) ci.cancel();
    }
    @Inject(method="func_73866_w_",at=@At("HEAD"),remap=false)
    private void npcpathfindinglegacy$collapseOldVariants(CallbackInfo ci) { ai.tacticalVariant=0; }
    @Inject(method="func_73866_w_",at=@At("TAIL"),remap=false)
    private void npcpathfindinglegacy$buttons(CallbackInfo ci) {
        GuiNpcAI self=(GuiNpcAI)(Object)this;
        buttonList.remove(self.getButton(17));
        self.addButton(new GuiNpcButton(17,self.guiLeft+230,self.guiTop+135,60,20,"npcpathfindinglegacy.tactics.configure"));
        self.getButton(17).setEnabled(ai.onAttack==0);
        self.addButton(new GuiNpcButton(990,self.guiLeft+230,self.guiTop+160,60,20,"npcpathfindinglegacy.gui.open"));
    }
    @Inject(method="func_146284_a",at=@At("HEAD"),cancellable=true,remap=false)
    private void npcpathfindinglegacy$open(GuiButton button,CallbackInfo ci) {
        GuiNpcAI self=(GuiNpcAI)(Object)this;
        if (button.id==17) { self.setSubGui(new SubGuiNpcTactics(ai));ci.cancel(); }
        else if (button.id==990) { self.setSubGui(new SubGuiNpcPathfinding(ai));ci.cancel(); }
    }
}
