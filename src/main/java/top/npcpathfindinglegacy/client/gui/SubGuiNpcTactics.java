package top.npcpathfindinglegacy.client.gui;

import java.util.Arrays;
import java.util.List;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.gui.GuiButton;
import noppes.npcs.entity.data.DataAI;
import noppes.npcs.client.gui.util.GuiNpcButton;
import noppes.npcs.client.gui.util.GuiCustomScroll;
import noppes.npcs.client.gui.util.GuiNpcTextField;
import noppes.npcs.client.gui.util.ICustomScrollListener;
import noppes.npcs.client.gui.util.ITextfieldListener;
import top.npcpathfindinglegacy.tactics.ITacticsData;
import top.npcpathfindinglegacy.tactics.TacticType;
import top.npcpathfindinglegacy.pathfinding.IDataAIPathfinding;

public final class SubGuiNpcTactics extends SettingsPanel implements ITextfieldListener, ICustomScrollListener {
    private final ITacticsData data;
    private final IDataAIPathfinding pathfinding;
    private TacticType selected, preview;
    private int tab;
    private GuiCustomScroll tactics;
    public SubGuiNpcTactics(DataAI ai) {
        data = (ITacticsData) ai; selected = TacticType.byId(data.npcpathfindinglegacy$getTactic());
        pathfinding = (IDataAIPathfinding) ai;
        preview = selected; tab = selected.group() || selected == TacticType.ASSAULT ? 1 : 2;
    }
    private List<TacticType> choices() {
        return Arrays.stream(TacticType.values()).filter(t -> tab == 1 ? t.group()
            : !t.group() && t != TacticType.ASSAULT).collect(java.util.stream.Collectors.toList());
    }
    @Override public void initGui() {
        super.initGui();
        button(0,guiLeft + 12,guiTop + 36,split() - 20,"npcpathfindinglegacy.tactics.default",selected == TacticType.ASSAULT);
        int tabWidth = (split() - 20) / 2;
        for (int i = 1; i <= 2; i++) button(10 + i,guiLeft + 12 + (i - 1) * tabWidth,guiTop + 62,tabWidth - 2,
            "npcpathfindinglegacy.tactics.tab." + i,i == tab);
        tactics = list(tactics,guiTop + 88,choices().stream().map(TacticType::key).collect(java.util.stream.Collectors.toList()),selected.key());
        if (preview != TacticType.ASSAULT) {
            number(2,guiLeft + xSize - 66,guiTop + ySize - 81,data.npcpathfindinglegacy$getTacticRadius(),3,24);
            if (preview.group()) number(1,guiLeft + xSize - 66,guiTop + ySize - 104,data.npcpathfindinglegacy$getSquad(),0,9999);
        }
        comfortDistance(3,pathfinding);
        button(66,guiLeft + xSize - 76,guiTop + ySize - 25,64,"npcpathfindinglegacy.gui.done",false);
    }
    @Override public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        panel("ai.tacticalvariant");
        int x = right(), y = guiTop + 40;
        text(preview.key(),x,y,SELECTED);
        int bottom = guiTop + ySize - (preview == TacticType.ASSAULT ? 66 : preview.group() ? 112 : 89);
        if (preview == TacticType.FORMATION) description(y + 19,bottom,preview.key() + ".hint",
            "npcpathfindinglegacy.tactics.weapon_hint","npcpathfindinglegacy.tactics.formation_hint");
        else description(y + 19,bottom,preview.key() + ".hint","npcpathfindinglegacy.tactics.weapon_hint");
        if (preview != TacticType.ASSAULT) {
            text("npcpathfindinglegacy.tactics.radius",x,guiTop + ySize - 77,TEXT);
            if (preview.group()) text("npcpathfindinglegacy.tactics.squad",x,guiTop + ySize - 100,TEXT);
        }
        text("npcpathfindinglegacy.gui.comfortDistance",x,guiTop + ySize - 54,TEXT);
        fontRenderer.drawString(I18n.format("npcpathfindinglegacy.tactics.current",I18n.format(selected.key())),guiLeft + 12,guiTop + ySize - 19,MUTED);
        super.drawScreen(mouseX,mouseY,partialTicks);
    }
    private void select(TacticType type) {
        GuiNpcTextField.unfocus(); selected = preview = type; descriptionScroll = 0;
        data.npcpathfindinglegacy$setTactic(type.id()); initGui();
    }
    @Override protected void actionPerformed(GuiButton button) {
        if (button.id == 0) select(TacticType.ASSAULT);
        else if (button.id == 11 || button.id == 12) {
            GuiNpcTextField.unfocus(); tab = button.id - 10;
            preview = choices().contains(selected) ? selected : choices().get(0);
            descriptionScroll = 0; initGui();
        } else if (button.id == 66) { GuiNpcTextField.unfocus(); close(); }
    }
    @Override public void scrollClicked(int x, int y, int button, GuiCustomScroll scroll) {
        int index = scroll.selected;
        List<TacticType> choices = choices();
        if (index >= 0 && index < choices.size()) select(choices.get(index));
    }
    @Override public void scrollDoubleClicked(String selected, GuiCustomScroll scroll) {}
    @Override public void unFocused(GuiNpcTextField field) {
        if (field.getId() == 1) data.npcpathfindinglegacy$setSquad(field.getInteger());
        if (field.getId() == 2) data.npcpathfindinglegacy$setTacticRadius(field.getInteger());
        if (field.getId() == 3) saveComfortDistance(field,pathfinding);
    }
}
