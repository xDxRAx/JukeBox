package io.github.cloraj1.jukebox;

import net.minecraft.client.gui.@GRAPHICS@;
import net.minecraft.client.gui.Font;

public final class JukeBoxCanvas implements MusicView.Canvas {
    private final @GRAPHICS@ graphics;
    private final Font font;
    private final int offsetX,offsetY;
    public JukeBoxCanvas(@GRAPHICS@ graphics,Font font) { this(graphics,font,0,0); }
    public JukeBoxCanvas(@GRAPHICS@ graphics,Font font,int offsetX,int offsetY) { this.graphics=graphics; this.font=font; this.offsetX=offsetX; this.offsetY=offsetY; }
    public void fill(int x1,int y1,int x2,int y2,int color) { graphics.fill(x1+offsetX,y1+offsetY,x2+offsetX,y2+offsetY,color); }
    public void text(String text,int x,int y,int color) { graphics.@DRAW@(font,text,x+offsetX,y+offsetY,color,false); }
    public int width(String text) { return font.width(text); }
    public boolean cover(Track track,int x,int y,int size) { return JukeBoxArtwork.draw(graphics,track,x+offsetX,y+offsetY,size); }
}
