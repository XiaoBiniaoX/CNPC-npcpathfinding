package top.npcpathfindinglegacy.client.gui;

import noppes.npcs.entity.data.DataAI;
import noppes.npcs.client.gui.util.GuiNpcButton;
import noppes.npcs.client.gui.util.GuiNpcTextField;
import noppes.npcs.client.gui.util.ITextfieldListener;
import noppes.npcs.client.gui.util.ICustomScrollListener;
import noppes.npcs.client.gui.util.GuiCustomScroll;
import top.npcpathfindinglegacy.pathfinding.IDataAIPathfinding;
import top.npcpathfindinglegacy.pathfinding.DefaultPathfinding;
import net.minecraft.nbt.NBTTagCompound;
import noppes.npcs.constants.EnumPacketServer;
import noppes.npcs.client.Client;
import noppes.npcs.client.gui.util.IGuiData;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.gui.GuiButton;

public final class SubGuiNpcPathfinding extends SettingsPanel implements ITextfieldListener, ICustomScrollListener, IGuiData {
    public static final String[] MODES = {"vanilla","enhanced","wall","optimal","combat","theta","jps","segmented"};
    private final IDataAIPathfinding settings;
    private int mode;
    private GuiCustomScroll modes;
    private boolean firstInit = true;
    private int defaultMode;
    private boolean defaultKnown, savingDefault;
    public SubGuiNpcPathfinding(DataAI ai) { settings = (IDataAIPathfinding) ai; mode = settings.npcpathfindinglegacy$getMode(); }
    @Override public void initGui() {
        super.initGui();
        modes = list(modes, guiTop + 36, java.util.Arrays.stream(MODES)
            .map(m -> "npcpathfindinglegacy.gui.mode." + m).collect(java.util.stream.Collectors.toList()), "npcpathfindinglegacy.gui.mode." + MODES[mode]);
        if (firstInit) {
            modes.scrollTo(modes.getSelected()); firstInit = false;
            Client.sendData(EnumPacketServer.MainmenuAIGet);
        }
        int y = controlsTop();
        if (mode == 1) {
            number(1,guiLeft + xSize - 66,y,settings.npcpathfindinglegacy$getWalkTendency(),0,100);
            number(2,guiLeft + xSize - 66,y + 23,settings.npcpathfindinglegacy$getJumpTendency(),0,100);
            number(3,guiLeft + xSize - 66,y + 46,settings.npcpathfindinglegacy$getParkourMaxDist(),0,5);
            number(4,guiLeft + xSize - 66,y + 69,settings.npcpathfindinglegacy$getChaseJumpInterval(),0,200);
        } else if (mode == 4) number(3,guiLeft + xSize - 66,y,settings.npcpathfindinglegacy$getParkourMaxDist(),0,5);
        else if (mode == 3 || mode >= 5) button(5,guiLeft + xSize - 76,y,58,
            settings.npcpathfindinglegacy$getAllowSprint() ? "gui.yes" : "gui.no",false);
        comfortDistance(6,settings);
        addButton(new GuiNpcButton(67,guiLeft + 12,guiTop + ySize - 25,split() - 20,20,"npcpathfindinglegacy.gui.set_default") {
            @Override public void drawButton(net.minecraft.client.Minecraft minecraft, int mouseX, int mouseY, float partialTicks) {
                if (enabled) { super.drawButton(minecraft,mouseX,mouseY,partialTicks); return; }
                drawRect(x,y,x + width,y + height,0xFF080808);
                drawCenteredString(fontRenderer,displayString,x + width / 2,y + 6,MUTED);
            }
        });
        updateDefaultButton();
        button(66,guiLeft + xSize - 76,guiTop + ySize - 25,64,"npcpathfindinglegacy.gui.done",false);
    }
    private void updateDefaultButton() {
        GuiNpcButton button = getButton(67);
        if (button == null) return;
        boolean isDefault = mode == defaultMode;
        button.displayString = I18n.format(isDefault ? "npcpathfindinglegacy.gui.is_default" : "npcpathfindinglegacy.gui.set_default");
        button.setEnabled(defaultKnown && !savingDefault && !isDefault);
    }
    @Override public void setGuiData(NBTTagCompound data) {
        if (!data.hasKey(DefaultPathfinding.SYNC_KEY,3)) return;
        int synced = data.getInteger(DefaultPathfinding.SYNC_KEY);
        if (synced < 0 || synced > 7) return;
        defaultMode = synced; defaultKnown = true; savingDefault = false; updateDefaultButton();
    }
    private int controlsTop() { return guiTop + ySize - 61 - (mode == 1 ? 92 : mode == 4 || mode == 3 || mode >= 5 ? 23 : 0); }
    @Override public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        panel("npcpathfindinglegacy.gui.title");
        int x = right(), y = guiTop + 40;
        text("npcpathfindinglegacy.gui.mode." + MODES[mode],x,y,SELECTED);
        description(y + 19,controlsTop() - 6,"npcpathfindinglegacy.gui.scene." + MODES[mode],
            "npcpathfindinglegacy.gui.limit." + MODES[mode],"npcpathfindinglegacy.gui.default_hint");
        y = controlsTop() + 5;
        if (mode == 1) {
            text("npcpathfindinglegacy.gui.walkTendency",x,y,TEXT);
            text("npcpathfindinglegacy.gui.jumpTendency",x,y + 23,TEXT);
            text("npcpathfindinglegacy.gui.parkourMaxDist",x,y + 46,TEXT);
            text("npcpathfindinglegacy.gui.chaseJumpInterval",x,y + 69,TEXT);
        } else if (mode == 4) text("npcpathfindinglegacy.gui.parkourMaxDist",x,y,TEXT);
        else if (mode == 3 || mode >= 5) text("npcpathfindinglegacy.gui.allowSprint",x,y,TEXT);
        text("npcpathfindinglegacy.gui.comfortDistance",x,guiTop + ySize - 54,TEXT);
        super.drawScreen(mouseX,mouseY,partialTicks);
    }
    @Override protected void actionPerformed(GuiButton button) {
        if (button.id == 5) { GuiNpcTextField.unfocus(); settings.npcpathfindinglegacy$setAllowSprint(!settings.npcpathfindinglegacy$getAllowSprint()); initGui(); }
        else if (button.id == 67) {
            if (!defaultKnown || savingDefault || mode == defaultMode) return;
            GuiNpcTextField.unfocus();
            savingDefault = true; updateDefaultButton();
            NBTTagCompound request = new NBTTagCompound();
            request.setInteger(DefaultPathfinding.REQUEST_KEY,mode);
            Client.sendData(EnumPacketServer.MainmenuAISave,request);
        }
        else if (button.id == 66) { GuiNpcTextField.unfocus(); close(); }
    }
    @Override public void scrollClicked(int x, int y, int button, GuiCustomScroll scroll) {
        int index = scroll.selected;
        if (index < 0 || index >= MODES.length) return;
        GuiNpcTextField.unfocus(); mode = index; descriptionScroll = 0;
        settings.npcpathfindinglegacy$setMode(mode); initGui();
    }
    @Override public void scrollDoubleClicked(String selected, GuiCustomScroll scroll) {}
    @Override public void unFocused(GuiNpcTextField field) {
        switch (field.getId()) {
            case 1: settings.npcpathfindinglegacy$setWalkTendency(field.getInteger()); break;
            case 2: settings.npcpathfindinglegacy$setJumpTendency(field.getInteger()); break;
            case 3: settings.npcpathfindinglegacy$setParkourMaxDist(field.getInteger()); break;
            case 4: settings.npcpathfindinglegacy$setChaseJumpInterval(field.getInteger()); break;
            case 6: saveComfortDistance(field,settings); break;
        }
    }
}
