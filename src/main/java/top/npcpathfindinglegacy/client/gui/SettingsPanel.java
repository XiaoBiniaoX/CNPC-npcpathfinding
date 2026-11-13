package top.npcpathfindinglegacy.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import noppes.npcs.shared.client.gui.components.GuiBasic;
import noppes.npcs.shared.client.gui.components.GuiButtonNop;
import noppes.npcs.shared.client.gui.components.GuiTextFieldNop;
import noppes.npcs.shared.client.gui.components.GuiCustomScrollNop;
import noppes.npcs.shared.client.gui.listeners.IGuiInterface;

/** Native widgets on a compact stone panel; list, explanation and footer have fixed regions. */
abstract class SettingsPanel extends GuiBasic {
    static final int TEXT = 0xEAEAEA, MUTED = 0xB8B8B8, SELECTED = 0xE0C66A;
    int descriptionScroll;
    SettingsPanel() { imageWidth = 420; imageHeight = 270; drawDefaultBackground = false; closeOnEsc = true; }
    @Override public void init() {
        imageWidth = Math.min(420, Math.max(280, width - 12));
        imageHeight = Math.min(270, Math.max(220, height - 12));
        super.init();
    }
    int split() { return Math.min(142, imageWidth / 3 + 5); }
    int right() { return guiLeft + split() + 12; }
    int detailWidth() { return imageWidth - split() - 24; }
    GuiCustomScrollNop list(GuiCustomScrollNop scroll, int top, java.util.List<String> keys, String selected) {
        if (scroll == null) {
            scroll = new GuiCustomScrollNop(this, 30);
            scroll.disabledSearch();
        }
        scroll.setUnsortedList(new java.util.ArrayList<>(keys));
        scroll.guiLeft = guiLeft + 12; scroll.guiTop = top;
        scroll.setSize(split() - 20, guiTop + imageHeight - 40 - top);
        scroll.setSelected(selected);
        addScroll(scroll);
        return scroll;
    }
    void button(int id, int x, int y, int w, String key, boolean selected) {
        addButton(new GuiButtonNop((IGuiInterface)this, id, x, y, w, 20, key));
        if (selected) getButton(id).setMessage(Component.translatable(key).withStyle(net.minecraft.ChatFormatting.YELLOW));
    }
    void number(int id, int x, int y, int value, int minimum, int maximum) {
        addTextField(new GuiTextFieldNop(id, this, x, y, 48, 18, Integer.toString(value)));
        getTextField(id).numbersOnly = true; getTextField(id).setMinMaxDefault(minimum, maximum, value);
    }
    void text(GuiGraphics graphics, String key, int x, int y, int color) {
        graphics.drawString(font, Component.translatable(key), x, y, color, false);
    }
    int wrapped(GuiGraphics graphics, String key, int x, int y, int w, int color) {
        for (var line : font.split(Component.translatable(key), w)) { graphics.drawString(font, line, x, y, color, false); y += 11; }
        return y;
    }
    void description(GuiGraphics graphics, int top, int bottom, String... keys) {
        int total = 0;
        for (String key : keys) total += font.split(Component.translatable(key), detailWidth()).size() * 11 + 7;
        int visible = Math.max(11, bottom - top);
        boolean scrollable = total > visible;
        if (scrollable) visible = Math.max(11, visible - 12);
        descriptionScroll = Math.max(0, Math.min(descriptionScroll, Math.max(0, total - visible)));
        graphics.enableScissor(right(), top, right() + detailWidth(), top + visible);
        int y = top - descriptionScroll;
        for (int i = 0; i < keys.length; i++) y = wrapped(graphics, keys[i], right(), y, detailWidth(), i == 0 ? TEXT : MUTED) + 7;
        graphics.disableScissor();
        if (scrollable) text(graphics,"npcpathfindinglegacy.gui.scroll_hint",right(),top + visible + 2,MUTED);
    }
    @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double delta) {
        if (mouseX >= right() && mouseX < guiLeft + imageWidth - 8
            && mouseY >= guiTop + 30 && mouseY < guiTop + imageHeight - 34) {
            descriptionScroll = Math.max(0, descriptionScroll - (int) (delta * 22)); return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, delta);
    }
    void panel(GuiGraphics graphics, String title) {
        graphics.fill(0,0,width,height,0xA0000000);
        graphics.fill(guiLeft - 1,guiTop - 1,guiLeft + imageWidth + 1,guiTop + imageHeight + 1,0xFF111111);
        graphics.fill(guiLeft,guiTop,guiLeft + imageWidth,guiTop + imageHeight,0xFF777777);
        graphics.fill(guiLeft + 2,guiTop + 2,guiLeft + imageWidth - 2,guiTop + imageHeight - 2,0xFF484848);
        graphics.fill(guiLeft + 8,guiTop + 30,guiLeft + split() - 4,guiTop + imageHeight - 34,0xFF343434);
        graphics.fill(guiLeft + split() + 2,guiTop + 30,guiLeft + imageWidth - 8,guiTop + imageHeight - 34,0xFF3C3C3C);
        graphics.fill(guiLeft + 10,guiTop + imageHeight - 30,guiLeft + imageWidth - 10,guiTop + imageHeight - 29,0xFF6D6D6D);
        text(graphics,title,guiLeft + 12,guiTop + 11,TEXT);
    }
}
