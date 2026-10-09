package io.github.cloraj1.jukebox;

import java.util.function.LongSupplier;

/** One renewable notification, independent of world/player presence and playback state. */
public final class PlaybackNotice {
    private final LongSupplier clock;
    private long until;
    private boolean requested;
    public PlaybackNotice() { this(System::nanoTime); }
    PlaybackNotice(LongSupplier clock) { this.clock=clock; }
    public void show() { requested=true; until=clock.getAsLong()+5_000_000_000L; }
    public boolean visible() {
        return visible(false);
    }
    public boolean visible(boolean musicUiOpen) {
        return !musicUiOpen && (Config.get().alwaysPlaybackNotification ||
            (Config.get().playbackNotifications && requested && clock.getAsLong()-until<0));
    }
    public boolean actionBarEnabled(boolean musicUiOpen) {
        return !musicUiOpen && !Config.get().playbackNotifications;
    }
}
