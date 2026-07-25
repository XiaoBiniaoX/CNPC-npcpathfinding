package top.npcpathfinding.client.gui;

import net.minecraft.client.gui.screens.Screen;
import noppes.npcs.client.gui.util.GuiNPCInterface2;
import noppes.npcs.entity.data.DataAI;
import noppes.npcs.shared.client.gui.components.GuiButtonNop;
import noppes.npcs.shared.client.gui.components.GuiLabel;
import noppes.npcs.shared.client.gui.components.GuiTextFieldNop;
import noppes.npcs.shared.client.gui.listeners.IGuiInterface;
import noppes.npcs.shared.client.gui.listeners.ITextfieldListener;
import top.npcpathfinding.pathfinding.IDataAIPathfinding;

public class GuiNpcPathfinding extends noppes.npcs.shared.client.gui.components.GuiBasic implements ITextfieldListener {
    private final DataAI ai;
    private final IDataAIPathfinding accessor;
    private int mode;
    private int parkourMaxDist;

    public GuiNpcPathfinding(GuiNPCInterface2 parent, DataAI ai) {
        this.ai = ai;
        this.accessor = (IDataAIPathfinding) ai;
        this.mode = accessor.npcpathfinding$getMode();
        this.parkourMaxDist = accessor.npcpathfinding$getParkourMaxDist();
        this.setBackground("menubg.png");
        this.imageWidth = 256;
        this.imageHeight = 216;
    }

    @Override
    public void init() {
        super.init();
        int y = this.guiTop + 10;

        this.addLabel(new GuiLabel(0, "npcpathfinding.gui.mode", this.guiLeft + 10, y + 5));
        this.addButton(new GuiButtonNop(
            (IGuiInterface) this,
            0,
            this.guiLeft + 100, y,
            120, 20,
            new String[]{
                "npcpathfinding.gui.mode.vanilla",
                "npcpathfinding.gui.mode.baritone"
            },
            this.mode
        ));

        y += 30;
        if (this.mode == 1) {
            this.addLabel(new GuiLabel(1, "npcpathfinding.gui.walkTendency", this.guiLeft + 10, y + 5));
            this.addTextField(new GuiTextFieldNop(1, (Screen) this, this.guiLeft + 130, y, 50, 20,
                    "" + accessor.npcpathfinding$getWalkTendency()));
            this.getTextField(1).numbersOnly = true;
            this.getTextField(1).setMinMaxDefault(0, 100, 50);

            y += 25;
            this.addLabel(new GuiLabel(2, "npcpathfinding.gui.jumpTendency", this.guiLeft + 10, y + 5));
            this.addTextField(new GuiTextFieldNop(2, (Screen) this, this.guiLeft + 130, y, 50, 20,
                    "" + accessor.npcpathfinding$getJumpTendency()));
            this.getTextField(2).numbersOnly = true;
            this.getTextField(2).setMinMaxDefault(0, 100, 50);

            y += 25;
            this.addLabel(new GuiLabel(3, "npcpathfinding.gui.parkourMaxDist", this.guiLeft + 10, y + 5));
            this.addButton(new GuiButtonNop(
                (IGuiInterface) this,
                3,
                this.guiLeft + 130, y,
                90, 20,
                new String[]{"0", "1", "2", "3", "4", "5"},
                this.parkourMaxDist
            ));

            y += 25;
            this.addLabel(new GuiLabel(4, "npcpathfinding.gui.chaseJumpInterval", this.guiLeft + 10, y + 5));
            this.addTextField(new GuiTextFieldNop(4, (Screen) this, this.guiLeft + 130, y, 50, 20,
                    "" + accessor.npcpathfinding$getChaseJumpInterval()));
            this.getTextField(4).numbersOnly = true;
            this.getTextField(4).setMinMaxDefault(0, 200, 40);
        }

        this.addButton(new GuiButtonNop(
            (IGuiInterface) this,
            66,
            this.guiLeft + 190,
            this.guiTop + 190,
            60, 20,
            "npcpathfinding.gui.done"
        ));
    }

    @Override
    public void buttonEvent(GuiButtonNop button) {
        if (button.id == 0) {
            this.mode = button.getValue();
            accessor.npcpathfinding$setMode(this.mode);
        } else if (button.id == 3) {
            this.parkourMaxDist = button.getValue();
            accessor.npcpathfinding$setParkourMaxDist(this.parkourMaxDist);
        } else if (button.id == 66) {
            this.close();
        }
        this.init();
    }

    @Override
    public void unFocused(GuiTextFieldNop textfield) {
        if (textfield.id == 1) {
            accessor.npcpathfinding$setWalkTendency(textfield.getInteger());
        } else if (textfield.id == 2) {
            accessor.npcpathfinding$setJumpTendency(textfield.getInteger());
        } else if (textfield.id == 4) {
            accessor.npcpathfinding$setChaseJumpInterval(textfield.getInteger());
        }
    }
}
