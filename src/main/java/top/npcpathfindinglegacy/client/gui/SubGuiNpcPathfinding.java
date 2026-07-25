package top.npcpathfindinglegacy.client.gui;

import net.minecraft.client.gui.screens.Screen;
import noppes.npcs.entity.data.DataAI;
import noppes.npcs.shared.client.gui.components.GuiBasic;
import noppes.npcs.shared.client.gui.components.GuiButtonNop;
import noppes.npcs.shared.client.gui.components.GuiLabel;
import noppes.npcs.shared.client.gui.components.GuiTextFieldNop;
import noppes.npcs.shared.client.gui.listeners.IGuiInterface;
import noppes.npcs.shared.client.gui.listeners.ITextfieldListener;
import top.npcpathfindinglegacy.pathfinding.IDataAIPathfinding;

public final class SubGuiNpcPathfinding extends GuiBasic implements ITextfieldListener {
    private final IDataAIPathfinding settings;
    private int mode;
    private int parkourMaxDistance;

    public SubGuiNpcPathfinding(DataAI ai) {
        this.settings = (IDataAIPathfinding) ai;
        this.mode = this.settings.npcpathfindinglegacy$getMode();
        this.parkourMaxDistance = this.settings.npcpathfindinglegacy$getParkourMaxDist();
        this.setBackground("menubg.png");
        this.imageWidth = 256;
        this.imageHeight = 216;
        this.closeOnEsc = true;
    }

    @Override
    public void init() {
        super.init();
        int y = this.guiTop + 10;
        this.addLabel(new GuiLabel(10, "npcpathfindinglegacy.gui.title", this.guiLeft + 10, y + 5));

        y += 24;
        this.addLabel(new GuiLabel(0, "npcpathfindinglegacy.gui.mode", this.guiLeft + 10, y + 5));
        this.addButton(new GuiButtonNop(
            (IGuiInterface) this,
            0,
            this.guiLeft + 126,
            y,
            120,
            20,
            new String[]{
                "npcpathfindinglegacy.gui.mode.vanilla",
                "npcpathfindinglegacy.gui.mode.enhanced"
            },
            this.mode
        ));

        if (this.mode == 1) {
            y += 28;
            this.addLabel(new GuiLabel(1, "npcpathfindinglegacy.gui.walkTendency", this.guiLeft + 10, y + 5));
            this.addTextField(new GuiTextFieldNop(
                1,
                (Screen) this,
                this.guiLeft + 156,
                y,
                50,
                20,
                Integer.toString(this.settings.npcpathfindinglegacy$getWalkTendency())
            ));
            this.getTextField(1).numbersOnly = true;
            this.getTextField(1).setMinMaxDefault(0, 100, 50);

            y += 25;
            this.addLabel(new GuiLabel(2, "npcpathfindinglegacy.gui.jumpTendency", this.guiLeft + 10, y + 5));
            this.addTextField(new GuiTextFieldNop(
                2,
                (Screen) this,
                this.guiLeft + 156,
                y,
                50,
                20,
                Integer.toString(this.settings.npcpathfindinglegacy$getJumpTendency())
            ));
            this.getTextField(2).numbersOnly = true;
            this.getTextField(2).setMinMaxDefault(0, 100, 50);

            y += 25;
            this.addLabel(new GuiLabel(3, "npcpathfindinglegacy.gui.parkourMaxDist", this.guiLeft + 10, y + 5));
            this.addButton(new GuiButtonNop(
                (IGuiInterface) this,
                3,
                this.guiLeft + 156,
                y,
                70,
                20,
                new String[]{"0", "1", "2", "3", "4", "5"},
                this.parkourMaxDistance
            ));

            y += 25;
            this.addLabel(new GuiLabel(4, "npcpathfindinglegacy.gui.chaseJumpInterval", this.guiLeft + 10, y + 5));
            this.addTextField(new GuiTextFieldNop(
                4,
                (Screen) this,
                this.guiLeft + 156,
                y,
                50,
                20,
                Integer.toString(this.settings.npcpathfindinglegacy$getChaseJumpInterval())
            ));
            this.getTextField(4).numbersOnly = true;
            this.getTextField(4).setMinMaxDefault(0, 200, 40);
        }

        this.addButton(new GuiButtonNop(
            (IGuiInterface) this,
            66,
            this.guiLeft + 186,
            this.guiTop + 190,
            60,
            20,
            "npcpathfindinglegacy.gui.done"
        ));
    }

    @Override
    public void buttonEvent(GuiButtonNop button) {
        if (button.id == 0) {
            this.mode = button.getValue();
            this.settings.npcpathfindinglegacy$setMode(this.mode);
            this.init();
        } else if (button.id == 3) {
            this.parkourMaxDistance = button.getValue();
            this.settings.npcpathfindinglegacy$setParkourMaxDist(this.parkourMaxDistance);
        } else if (button.id == 66) {
            GuiTextFieldNop.unfocus();
            this.close();
        }
    }

    @Override
    public void unFocused(GuiTextFieldNop textfield) {
        if (textfield.id == 1) {
            this.settings.npcpathfindinglegacy$setWalkTendency(textfield.getInteger());
        } else if (textfield.id == 2) {
            this.settings.npcpathfindinglegacy$setJumpTendency(textfield.getInteger());
        } else if (textfield.id == 4) {
            this.settings.npcpathfindinglegacy$setChaseJumpInterval(textfield.getInteger());
        }
    }
}
