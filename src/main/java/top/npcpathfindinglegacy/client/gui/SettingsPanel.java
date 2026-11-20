package top.npcpathfindinglegacy.client.gui;
import java.io.IOException;
import java.util.*;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.resources.I18n;
import noppes.npcs.client.gui.util.*;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import top.npcpathfindinglegacy.pathfinding.IDataAIPathfinding;

/** Same fixed list/detail/footer regions using legacy CNPC's native widgets. */
abstract class SettingsPanel extends SubGuiInterface {
    static final int TEXT=0xEAEAEA,MUTED=0xB8B8B8,SELECTED=0xE0C66A;
    int descriptionScroll;
    SettingsPanel() { xSize=420;ySize=270;drawDefaultBackground=false;closeOnEsc=true;background=null; }
    @Override public void initGui() { xSize=Math.min(420,Math.max(280,width-12));ySize=Math.min(270,Math.max(220,height-12));super.initGui(); }
    int split() { return Math.min(142,xSize/3+5); }
    int right() { return guiLeft+split()+12; }
    int detailWidth() { return xSize-split()-24; }
    GuiCustomScroll list(GuiCustomScroll scroll,int top,List<String> keys,String selected) {
        if (scroll==null) scroll=new GuiCustomScroll(this,30);
        scroll.setUnsortedList(new ArrayList<String>(keys));scroll.guiLeft=guiLeft+12;scroll.guiTop=top;
        scroll.setSize(split()-20,guiTop+ySize-40-top);scroll.setSelected(selected);addScroll(scroll);return scroll;
    }
    void button(int id,int x,int y,int w,String key,boolean selected) {
        addButton(new GuiNpcButton(id,x,y,w,20,key));if (selected) getButton(id).displayString="\u00a7e"+I18n.format(key);
    }
    void number(int id,int x,int y,int value,int minimum,int maximum) {
        addTextField(new GuiNpcTextField(id,this,x,y,48,18,Integer.toString(value)));
        getTextField(id).numbersOnly=true;getTextField(id).setMinMaxDefault(minimum,maximum,value);
    }
    void text(String key,int x,int y,int color) { fontRenderer.drawString(I18n.format(key),x,y,color); }
    void comfortDistance(int id,IDataAIPathfinding data) {
        addTextField(new GuiNpcTextField(id,this,guiLeft + xSize - 66,guiTop + ySize - 58,48,18,
            Double.toString(data.npcpathfindinglegacy$getComfortDistance())));
        getTextField(id).setMaxStringLength(8);
    }
    void saveComfortDistance(GuiNpcTextField field,IDataAIPathfinding data) {
        try { data.npcpathfindinglegacy$setComfortDistance(Double.parseDouble(field.getText())); }
        catch (NumberFormatException invalid) { /* Keep the previous setting. */ }
        field.setText(Double.toString(data.npcpathfindinglegacy$getComfortDistance()));
    }
    int wrapped(String key,int x,int y,int w,int color) {
        for (String line:fontRenderer.listFormattedStringToWidth(I18n.format(key),w)) { fontRenderer.drawString(line,x,y,color);y+=11; }return y;
    }
    void description(int top,int bottom,String... keys) {
        int total=0;for (String key:keys) total+=fontRenderer.listFormattedStringToWidth(I18n.format(key),detailWidth()).size()*11+7;
        int visible=Math.max(11,bottom-top);boolean scrollable=total>visible;if (scrollable) visible=Math.max(11,visible-12);
        descriptionScroll=Math.max(0,Math.min(descriptionScroll,Math.max(0,total-visible)));
        int scale=new ScaledResolution(mc).getScaleFactor();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);GL11.glScissor(right()*scale,mc.displayHeight-(top+visible)*scale,detailWidth()*scale,visible*scale);
        int y=top-descriptionScroll;for (int i=0;i<keys.length;i++) y=wrapped(keys[i],right(),y,detailWidth(),i==0?TEXT:MUTED)+7;
        GL11.glDisable(GL11.GL_SCISSOR_TEST);if (scrollable) text("npcpathfindinglegacy.gui.scroll_hint",right(),top+visible+2,MUTED);
    }
    @Override public void handleMouseInput() throws IOException {
        int wheel=Mouse.getEventDWheel();int x=Mouse.getEventX()*width/mc.displayWidth;int y=height-Mouse.getEventY()*height/mc.displayHeight-1;
        if (wheel!=0 && x>=right() && x<guiLeft+xSize-8 && y>=guiTop+30 && y<guiTop+ySize-34) {
            descriptionScroll=Math.max(0,descriptionScroll-(wheel>0?22:-22));return;
        }
        super.handleMouseInput();
    }
    void panel(String title) {
        drawRect(0,0,width,height,0xA0000000);drawRect(guiLeft-1,guiTop-1,guiLeft+xSize+1,guiTop+ySize+1,0xFF111111);
        drawRect(guiLeft,guiTop,guiLeft+xSize,guiTop+ySize,0xFF777777);drawRect(guiLeft+2,guiTop+2,guiLeft+xSize-2,guiTop+ySize-2,0xFF484848);
        drawRect(guiLeft+8,guiTop+30,guiLeft+split()-4,guiTop+ySize-34,0xFF343434);drawRect(guiLeft+split()+2,guiTop+30,guiLeft+xSize-8,guiTop+ySize-34,0xFF3C3C3C);
        drawRect(guiLeft+10,guiTop+ySize-30,guiLeft+xSize-10,guiTop+ySize-29,0xFF6D6D6D);text(title,guiLeft+12,guiTop+11,TEXT);
    }
}
