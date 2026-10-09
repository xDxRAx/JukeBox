package io.github.cloraj1.jukebox;

import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

public final class JukeBoxSounds {
    private JukeBoxSounds() {}
    public static SimpleSoundInstance importError() { return SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BIT,0.5F); }
}
