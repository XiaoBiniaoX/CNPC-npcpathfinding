package top.npcpathfindinglegacy.client.gui;

import java.util.Arrays;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import noppes.npcs.entity.data.DataAI;
import noppes.npcs.shared.client.gui.components.GuiButtonNop;
import noppes.npcs.shared.client.gui.components.GuiCustomScrollNop;
import noppes.npcs.shared.client.gui.components.GuiTextFieldNop;
import noppes.npcs.shared.client.gui.listeners.ICustomScrollListener;
import noppes.npcs.shared.client.gui.listeners.ITextfieldListener;
import top.npcpathfindinglegacy.tactics.ITacticsData;
import top.npcpathfindinglegacy.tactics.TacticType;

public final class SubGuiNpcTactics extends SettingsPanel implements ITextfieldListener, ICustomScrollListener {
    private final ITacticsData data;
    private TacticType selected, preview;
    private int tab;
    private GuiCustomScrollNop tactics;
    public SubGuiNpcTactics(DataAI ai) {
        data = (ITacticsData) ai; selected = TacticType.byId(data.npcpathfindinglegacy$getTactic());
        preview = selected; tab = selected.group() || selected == TacticType.ASSAULT ? 1 : 2;
    }
    private List<TacticType> choices() {
        return Arrays.stream(TacticType.values()).filter(t -> tab == 1 ? t.group()
            : !t.group() && t != TacticType.ASSAULT).toList();
    }
    @Override public void init() {
        super.init();
        button(0,guiLeft + 12,guiTop + 36,split() - 20,"npcpathfindinglegacy.tactics.default",selected == TacticType.ASSAULT);
        int tabWidth = (split() - 20) / 2;
        for (int i = 1; i <= 2; i++) button(10 + i,guiLeft + 12 + (i - 1) * tabWidth,guiTop + 62,tabWidth - 2,
            "npcpathfindinglegacy.tactics.tab." + i,i == tab);
        tactics = list(tactics,guiTop + 88,choices().stream().map(TacticType::key).toList(),selected.key());
        if (preview != TacticType.ASSAULT) {
            number(2,guiLeft + imageWidth - 66,guiTop + imageHeight - 65,data.npcpathfindinglegacy$getTacticRadius(),3,24);
            if (preview.group()) number(1,guiLeft + imageWidth - 66,guiTop + imageHeight - 88,data.npcpathfindinglegacy$getSquad(),0,9999);
        }
        button(66,guiLeft + imageWidth - 76,guiTop + imageHeight - 25,64,"npcpathfindinglegacy.gui.done",false);
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        panel(graphics,"ai.tacticalvariant");
        int x = right(), y = guiTop + 40;
        text(graphics,preview.key(),x,y,SELECTED);
        int bottom = guiTop + imageHeight - (preview == TacticType.ASSAULT ? 40 : preview.group() ? 96 : 73);
        if (preview == TacticType.FORMATION) description(graphics,y + 19,bottom,preview.key() + ".hint",
            "npcpathfindinglegacy.tactics.weapon_hint","npcpathfindinglegacy.tactics.formation_hint");
        else description(graphics,y + 19,bottom,preview.key() + ".hint","npcpathfindinglegacy.tactics.weapon_hint");
        if (preview != TacticType.ASSAULT) {
            text(graphics,"npcpathfindinglegacy.tactics.radius",x,guiTop + imageHeight - 61,TEXT);
            if (preview.group()) text(graphics,"npcpathfindinglegacy.tactics.squad",x,guiTop + imageHeight - 84,TEXT);
        }
        graphics.drawString(font,Component.translatable("npcpathfindinglegacy.tactics.current",
            Component.translatable(selected.key())),guiLeft + 12,guiTop + imageHeight - 19,MUTED,false);
        super.render(graphics,mouseX,mouseY,partialTicks);
    }
    private void select(TacticType type) {
        GuiTextFieldNop.unfocus(); selected = preview = type; descriptionScroll = 0;
        data.npcpathfindinglegacy$setTactic(type.id()); init();
    }
    @Override public void buttonEvent(GuiButtonNop button) {
        if (button.id == 0) select(TacticType.ASSAULT);
        else if (button.id == 11 || button.id == 12) {
            GuiTextFieldNop.unfocus(); tab = button.id - 10;
            preview = choices().contains(selected) ? selected : choices().get(0);
            descriptionScroll = 0; init();
        } else if (button.id == 66) { GuiTextFieldNop.unfocus(); close(); }
    }
    @Override public void scrollClicked(double x, double y, int button, GuiCustomScrollNop scroll) {
        int index = scroll.getSelectedIndex();
        List<TacticType> choices = choices();
        if (index >= 0 && index < choices.size()) select(choices.get(index));
    }
    @Override public void scrollDoubleClicked(String selected, GuiCustomScrollNop scroll) {}
    @Override public void unFocused(GuiTextFieldNop field) {
        if (field.id == 1) data.npcpathfindinglegacy$setSquad(field.getInteger());
        if (field.id == 2) data.npcpathfindinglegacy$setTacticRadius(field.getInteger());
    }
}
