package io.github.cloraj1.jukebox;

import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

/** Translatable components follow the current game language, including live language changes. */
public final class JukeBoxMessages {
    private JukeBoxMessages() {}
    public static Component playback(MusicSession.PlaybackFeedback feedback) {
        return Component.translatable("jukebox."+feedback.key(),feedback.song())
            .withStyle(feedback.key().equals("actionPaused")?ChatFormatting.RED:ChatFormatting.GREEN);
    }
    public static Component volume(int value) { return Component.translatable("jukebox.currentVolume",value); }
}
