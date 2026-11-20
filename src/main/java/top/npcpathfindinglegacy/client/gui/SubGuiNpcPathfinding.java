package top.npcpathfindinglegacy.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import noppes.npcs.entity.data.DataAI;
import noppes.npcs.shared.client.gui.components.GuiButtonNop;
import noppes.npcs.shared.client.gui.components.GuiTextFieldNop;
import noppes.npcs.shared.client.gui.listeners.ITextfieldListener;
import noppes.npcs.shared.client.gui.listeners.ICustomScrollListener;
import noppes.npcs.shared.client.gui.components.GuiCustomScrollNop;
import top.npcpathfindinglegacy.pathfinding.IDataAIPathfinding;
import top.npcpathfindinglegacy.pathfinding.DefaultPathfinding;
import net.minecraft.nbt.CompoundTag;
import noppes.npcs.constants.EnumMenuType;
import noppes.npcs.packets.Packets;
import noppes.npcs.packets.server.SPacketMenuSave;
import noppes.npcs.packets.server.SPacketMenuGet;
import noppes.npcs.shared.client.gui.listeners.IGuiData;
import net.minecraft.network.chat.Component;

public final class SubGuiNpcPathfinding extends SettingsPanel implements ITextfieldListener, ICustomScrollListener, IGuiData {
    public static final String[] MODES = {"vanilla","enhanced","wall","optimal","combat","theta","jps","segmented"};
    private final IDataAIPathfinding settings;
    private int mode;
    private GuiCustomScrollNop modes;
    private boolean firstInit = true;
    private int defaultMode;
    private boolean defaultKnown, savingDefault;
    public SubGuiNpcPathfinding(DataAI ai) { settings = (IDataAIPathfinding) ai; mode = settings.npcpathfindinglegacy$getMode(); }
    @Override public void init() {
        super.init();
        modes = list(modes, guiTop + 36, java.util.Arrays.stream(MODES)
            .map(m -> "npcpathfindinglegacy.gui.mode." + m).toList(), "npcpathfindinglegacy.gui.mode." + MODES[mode]);
        if (firstInit) {
            modes.scrollTo(modes.getSelected()); firstInit = false;
            Packets.sendServer(new SPacketMenuGet(EnumMenuType.AI));
        }
        int y = controlsTop();
        if (mode == 1) {
            number(1,guiLeft + imageWidth - 66,y,settings.npcpathfindinglegacy$getWalkTendency(),0,100);
            number(2,guiLeft + imageWidth - 66,y + 23,settings.npcpathfindinglegacy$getJumpTendency(),0,100);
            number(3,guiLeft + imageWidth - 66,y + 46,settings.npcpathfindinglegacy$getParkourMaxDist(),0,5);
            number(4,guiLeft + imageWidth - 66,y + 69,settings.npcpathfindinglegacy$getChaseJumpInterval(),0,200);
        } else if (mode == 4) number(3,guiLeft + imageWidth - 66,y,settings.npcpathfindinglegacy$getParkourMaxDist(),0,5);
        else if (mode == 3 || mode >= 5) button(5,guiLeft + imageWidth - 76,y,58,
            settings.npcpathfindinglegacy$getAllowSprint() ? "gui.yes" : "gui.no",false);
        comfortDistance(6,guiTop + imageHeight - 58,settings.npcpathfindinglegacy$getComfortDistance());
        addButton(new GuiButtonNop(this,67,guiLeft + 12,guiTop + imageHeight - 25,split() - 20,20,"npcpathfindinglegacy.gui.set_default") {
            @Override public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
                if (active) { super.renderWidget(graphics,mouseX,mouseY,partialTicks); return; }
                graphics.fill(getX(),getY(),getX() + getWidth(),getY() + getHeight(),0xFF080808);
                graphics.drawCenteredString(font,getMessage(),getX() + getWidth() / 2,getY() + 6,MUTED);
            }
        });
        updateDefaultButton();
        button(66,guiLeft + imageWidth - 76,guiTop + imageHeight - 25,64,"npcpathfindinglegacy.gui.done",false);
    }
    private void updateDefaultButton() {
        var button = getButton(67);
        if (button == null) return;
        boolean isDefault = mode == defaultMode;
        button.setMessage(Component.translatable(isDefault ? "npcpathfindinglegacy.gui.is_default" : "npcpathfindinglegacy.gui.set_default"));
        button.setEnabled(defaultKnown && !savingDefault && !isDefault);
    }
    @Override public void setGuiData(CompoundTag data) {
        if (!data.contains(DefaultPathfinding.SYNC_KEY,net.minecraft.nbt.Tag.TAG_INT)) return;
        int synced = data.getInt(DefaultPathfinding.SYNC_KEY);
        if (synced < 0 || synced > 7) return;
        defaultMode = synced; defaultKnown = true; savingDefault = false; updateDefaultButton();
    }
    private int controlsTop() { return guiTop + imageHeight - 61 - (mode == 1 ? 92 : mode == 4 || mode == 3 || mode >= 5 ? 23 : 0); }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        panel(graphics,"npcpathfindinglegacy.gui.title");
        int x = right(), y = guiTop + 40;
        text(graphics,"npcpathfindinglegacy.gui.mode." + MODES[mode],x,y,SELECTED);
        description(graphics,y + 19,controlsTop() - 6,"npcpathfindinglegacy.gui.scene." + MODES[mode],
            "npcpathfindinglegacy.gui.limit." + MODES[mode],"npcpathfindinglegacy.gui.traffic_hint","npcpathfindinglegacy.gui.default_hint");
        y = controlsTop() + 5;
        if (mode == 1) {
            text(graphics,"npcpathfindinglegacy.gui.walkTendency",x,y,TEXT);
            text(graphics,"npcpathfindinglegacy.gui.jumpTendency",x,y + 23,TEXT);
            text(graphics,"npcpathfindinglegacy.gui.parkourMaxDist",x,y + 46,TEXT);
            text(graphics,"npcpathfindinglegacy.gui.chaseJumpInterval",x,y + 69,TEXT);
        } else if (mode == 4) text(graphics,"npcpathfindinglegacy.gui.parkourMaxDist",x,y,TEXT);
        else if (mode == 3 || mode >= 5) text(graphics,"npcpathfindinglegacy.gui.allowSprint",x,y,TEXT);
        text(graphics,"npcpathfindinglegacy.gui.comfortDistance",x,guiTop + imageHeight - 54,TEXT);
        super.render(graphics,mouseX,mouseY,partialTicks);
    }
    @Override public void buttonEvent(GuiButtonNop button) {
        if (button.id == 5) { settings.npcpathfindinglegacy$setAllowSprint(!settings.npcpathfindinglegacy$getAllowSprint()); init(); }
        else if (button.id == 67) {
            if (!defaultKnown || savingDefault || mode == defaultMode) return;
            GuiTextFieldNop.unfocus();
            savingDefault = true; updateDefaultButton();
            CompoundTag request = new CompoundTag();
            request.putInt(DefaultPathfinding.REQUEST_KEY,mode);
            Packets.sendServer(new SPacketMenuSave(EnumMenuType.AI,request));
        }
        else if (button.id == 66) { GuiTextFieldNop.unfocus(); close(); }
    }
    @Override public void scrollClicked(double x, double y, int button, GuiCustomScrollNop scroll) {
        int index = scroll.getSelectedIndex();
        if (index < 0 || index >= MODES.length) return;
        GuiTextFieldNop.unfocus(); mode = index; descriptionScroll = 0;
        settings.npcpathfindinglegacy$setMode(mode); init();
    }
    @Override public void scrollDoubleClicked(String selected, GuiCustomScrollNop scroll) {}
    @Override public void unFocused(GuiTextFieldNop field) {
        switch (field.id) {
            case 1 -> settings.npcpathfindinglegacy$setWalkTendency(field.getInteger());
            case 2 -> settings.npcpathfindinglegacy$setJumpTendency(field.getInteger());
            case 3 -> settings.npcpathfindinglegacy$setParkourMaxDist(field.getInteger());
            case 4 -> settings.npcpathfindinglegacy$setChaseJumpInterval(field.getInteger());
            case 6 -> settings.npcpathfindinglegacy$setComfortDistance(field.getFloat());
        }
    }
}
