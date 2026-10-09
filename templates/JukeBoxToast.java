package io.github.cloraj1.jukebox;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.@GRAPHICS@;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.resources.language.I18n;

/** Uses Minecraft's Toast manager for stacking, entry/exit animation and menus. */
public final class JukeBoxToast implements Toast {
    public int width() { return Math.min(MusicToastView.WIDTH,Minecraft.getInstance().getWindow().getGuiScaledWidth()-8); }
    public int height() { return MusicToastView.HEIGHT; }
    private void draw(@GRAPHICS@ graphics,Font font) {
        if(Config.get().alwaysPlaybackNotification || JukeBoxClient.musicUiOpen(Minecraft.getInstance())) return;
        MusicToastView.draw(new JukeBoxCanvas(graphics,font),JukeBoxClient.session,k -> I18n.get("jukebox."+k),width());
    }
    public static void renderPinned(@GRAPHICS@ graphics) {
        if(JukeBoxClient.session==null || !Config.get().alwaysPlaybackNotification || JukeBoxClient.musicUiOpen(Minecraft.getInstance())) return;
        Minecraft client=Minecraft.getInstance(); int width=Math.min(MusicToastView.WIDTH,graphics.guiWidth()-8);
        @PIN_STRATUM@
        MusicToastView.draw(new JukeBoxCanvas(graphics,client.font,graphics.guiWidth()-width-4,4),JukeBoxClient.session,k -> I18n.get("jukebox."+k),width);
    }
    @TOAST_METHODS@
}
