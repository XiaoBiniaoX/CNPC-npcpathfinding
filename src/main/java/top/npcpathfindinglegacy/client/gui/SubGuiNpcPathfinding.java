package top.npcpathfindinglegacy.client.gui;

import net.minecraft.client.gui.GuiButton;
import noppes.npcs.client.gui.util.GuiNpcButton;
import noppes.npcs.client.gui.util.GuiNpcLabel;
import noppes.npcs.client.gui.util.GuiNpcTextField;
import noppes.npcs.client.gui.util.ITextfieldListener;
import noppes.npcs.client.gui.util.SubGuiInterface;
import noppes.npcs.entity.data.DataAI;
import top.npcpathfindinglegacy.pathfinding.IDataAIPathfinding;

public final class SubGuiNpcPathfinding extends SubGuiInterface implements ITextfieldListener {
    private final IDataAIPathfinding settings;
    private int mode;
    private int parkourMaxDistance;

    public SubGuiNpcPathfinding(DataAI ai) {
        this.settings = (IDataAIPathfinding) ai;
        this.mode = this.settings.npcpathfindinglegacy$getMode();
        this.parkourMaxDistance = this.settings.npcpathfindinglegacy$getParkourMaxDist();
        this.setBackground("menubg.png");
        this.xSize = 256;
        this.ySize = 216;
        this.closeOnEsc = true;
    }

    @Override
    public void initGui() {
        super.initGui();
        int y = this.guiTop + 10;
        this.addLabel(new GuiNpcLabel(10, "npcpathfindinglegacy.gui.title", this.guiLeft + 10, y + 5));

        y += 24;
        this.addLabel(new GuiNpcLabel(0, "npcpathfindinglegacy.gui.mode", this.guiLeft + 10, y + 5));
        this.addButton(new GuiNpcButton(
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
            this.addLabel(new GuiNpcLabel(1, "npcpathfindinglegacy.gui.walkTendency", this.guiLeft + 10, y + 5));
            this.addTextField(new GuiNpcTextField(
                1,
                this,
                this.guiLeft + 156,
                y,
                50,
                20,
                Integer.toString(this.settings.npcpathfindinglegacy$getWalkTendency())
            ));
            this.getTextField(1).numbersOnly = true;
            this.getTextField(1).setMinMaxDefault(0, 100, 50);

            y += 25;
            this.addLabel(new GuiNpcLabel(2, "npcpathfindinglegacy.gui.jumpTendency", this.guiLeft + 10, y + 5));
            this.addTextField(new GuiNpcTextField(
                2,
                this,
                this.guiLeft + 156,
                y,
                50,
                20,
                Integer.toString(this.settings.npcpathfindinglegacy$getJumpTendency())
            ));
            this.getTextField(2).numbersOnly = true;
            this.getTextField(2).setMinMaxDefault(0, 100, 50);

            y += 25;
            this.addLabel(new GuiNpcLabel(3, "npcpathfindinglegacy.gui.parkourMaxDist", this.guiLeft + 10, y + 5));
            this.addButton(new GuiNpcButton(
                3,
                this.guiLeft + 156,
                y,
                70,
                20,
                new String[]{"0", "1", "2", "3", "4", "5"},
                this.parkourMaxDistance
            ));

            y += 25;
            this.addLabel(new GuiNpcLabel(4, "npcpathfindinglegacy.gui.chaseJumpInterval", this.guiLeft + 10, y + 5));
            this.addTextField(new GuiNpcTextField(
                4,
                this,
                this.guiLeft + 156,
                y,
                50,
                20,
                Integer.toString(this.settings.npcpathfindinglegacy$getChaseJumpInterval())
            ));
            this.getTextField(4).numbersOnly = true;
            this.getTextField(4).setMinMaxDefault(0, 200, 40);
        }

        this.addButton(new GuiNpcButton(
            66,
            this.guiLeft + 186,
            this.guiTop + 190,
            60,
            20,
            "npcpathfindinglegacy.gui.done"
        ));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        GuiNpcButton npcButton = (GuiNpcButton) button;
        if (npcButton.id == 0) {
            this.mode = npcButton.getValue();
            this.settings.npcpathfindinglegacy$setMode(this.mode);
            this.initGui();
        } else if (npcButton.id == 3) {
            this.parkourMaxDistance = npcButton.getValue();
            this.settings.npcpathfindinglegacy$setParkourMaxDist(this.parkourMaxDistance);
        } else if (npcButton.id == 66) {
            GuiNpcTextField.unfocus();
            this.close();
        }
    }

    @Override
    public void unFocused(GuiNpcTextField textfield) {
        if (textfield.getId() == 1) {
            this.settings.npcpathfindinglegacy$setWalkTendency(textfield.getInteger());
        } else if (textfield.getId() == 2) {
            this.settings.npcpathfindinglegacy$setJumpTendency(textfield.getInteger());
        } else if (textfield.getId() == 4) {
            this.settings.npcpathfindinglegacy$setChaseJumpInterval(textfield.getInteger());
        }
    }
}
