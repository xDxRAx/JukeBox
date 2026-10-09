package io.github.cloraj1.jukebox;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class VolumeControlTest {
    @TempDir Path temp;
    private MusicSession session() {
        Config.load(temp.resolve("config"),temp); return new MusicSession(temp.resolve("music"));
    }
    private CommandDispatcher<MusicSession> dispatcher() {
        var dispatcher=new CommandDispatcher<MusicSession>(); VolumeCommands.register(dispatcher,(session,value) -> session.setVolume(value)); return dispatcher;
    }
    @Test void commandSetsValidIntegerValuesAndSupportsCompletion() throws Exception {
        try(MusicSession session=session()) {
            var dispatcher=dispatcher();
            for(int value:new int[]{1,37,100}) { assertEquals(1,dispatcher.execute("volume "+value,session)); assertEquals(value,Config.get().volume); }
            assertTrue(dispatcher.getCompletionSuggestions(dispatcher.parse("vol",session)).get().getList().stream().anyMatch(s -> s.getText().equals("volume")));
            Config.load(temp.resolve("config"),temp); assertEquals(100,Config.get().volume);
        }
    }
    @Test void missingNonIntegerOutOfRangeAndExtraArgumentsCannotChangeSavedVolume() throws Exception {
        try(MusicSession session=session()) {
            session.setVolume(42); var dispatcher=dispatcher(); byte[] saved=Files.readAllBytes(temp.resolve("config/jukebox.json"));
            for(String input:List.of("volume","volume 0","volume 101","volume -1","volume 1.5","volume abc","volume 100 extra","volume 2147483648")) {
                assertThrows(CommandSyntaxException.class,() -> dispatcher.execute(input,session),input);
                assertEquals(42,Config.get().volume); assertArrayEquals(saved,Files.readAllBytes(temp.resolve("config/jukebox.json")));
            }
        }
    }
    @Test void volumeStepsClampAndPersistWithoutRestartingOrUnpausingTheTrack() throws Exception {
        Config.load(temp.resolve("config"),temp); Path folder=temp.resolve("music"); Files.createDirectories(folder);
        Path song=folder.resolve("Artist - Song.wav"); try(var input=getClass().getResourceAsStream("/tone.wav")) { Files.copy(input,song); }
        byte[] audio=Files.readAllBytes(song); AtomicInteger opens=new AtomicInteger();
        try(MusicSession session=new MusicSession(folder,new MusicPlayer(format -> {
            opens.incrementAndGet(); return new MusicPlayer.Output() {
                volatile boolean closed;
                public void write(byte[] b,int o,int n) { try { if(!closed) Thread.sleep(100); } catch(InterruptedException ignored) {} }
                public void pause(boolean p) {} public void drain() {} public double seconds() { return .25; } public void close() { closed=true; }
            };
        }))) {
            long deadline=System.nanoTime()+5_000_000_000L;
            while(session.library().isEmpty() && System.nanoTime()<deadline) Thread.sleep(5);
            Track track=session.library().get(0); session.play(track,session.library());
            while(session.player.position()==0 && System.nanoTime()<deadline) Thread.sleep(5);
            assertEquals(1,opens.get()); session.player.pauseToggle(); assertTrue(session.player.paused());
            assertEquals(1,MusicSession.VOLUME_KEY_STEP);
            session.setVolume(95); assertEquals(96,session.adjustVolume(MusicSession.VOLUME_KEY_STEP));
            assertEquals(95,session.adjustVolume(-MusicSession.VOLUME_KEY_STEP));
            session.setVolume(99); assertEquals(100,session.adjustVolume(MusicSession.VOLUME_KEY_STEP)); assertEquals(100,session.adjustVolume(MusicSession.VOLUME_KEY_STEP));
            session.setVolume(1); assertEquals(0,session.adjustVolume(-MusicSession.VOLUME_KEY_STEP)); assertEquals(0,session.adjustVolume(-MusicSession.VOLUME_KEY_STEP));
            assertEquals(100,session.adjustVolume(Integer.MAX_VALUE)); assertEquals(0,session.adjustVolume(Integer.MIN_VALUE));
            assertEquals(track,session.player.current()); assertTrue(session.player.paused()); assertEquals(1,opens.get());
            Config.load(temp.resolve("config"),temp); assertEquals(0,Config.get().volume); assertArrayEquals(audio,Files.readAllBytes(song));
        }
    }
    @Test void nativeActionBarComponentsFollowLiveLanguageAndKeepPlaybackColors() throws Exception {
        var playing=JukeBoxMessages.playback(new MusicSession.PlaybackFeedback("actionPlaying","Zedd - Clarity"));
        var paused=JukeBoxMessages.playback(new MusicSession.PlaybackFeedback("actionPaused","Zedd - Clarity"));
        var volume=JukeBoxMessages.volume(100);
        try(var language=new TestLanguage("ko_kr")) {
            assertEquals("현재 볼륨: 100%",volume.getString());
            assertEquals("♬ 재생 중: Zedd - Clarity ♬",playing.getString()); assertEquals("♪ 정지 됨: Zedd - Clarity ♪",paused.getString());
        }
        try(var language=new TestLanguage("en_us")) {
            assertEquals("Current Volume: 100%",volume.getString());
            assertEquals("♬ Playing: Zedd - Clarity ♬",playing.getString()); assertEquals("♪ Paused: Zedd - Clarity ♪",paused.getString());
            assertEquals("Current Volume: 0%",JukeBoxMessages.volume(0).getString());
            assertEquals("♬ Playing: 100% Pure ♬",JukeBoxMessages.playback(new MusicSession.PlaybackFeedback("actionPlaying","100% Pure")).getString());
        }
        assertEquals(0x55FF55,playing.getStyle().getColor().getValue());
        assertEquals(0xFF5555,paused.getStyle().getColor().getValue());
    }
    @Test void sharedViewHasNoUnconfiguredArrowVolumeShortcut() throws Exception {
        try(MusicSession session=session()) {
            MusicView view=new MusicView(session,key -> key); view.resize(480,270); session.setVolume(42);
            assertFalse(view.key(265)); assertFalse(view.key(264)); assertEquals(42,Config.get().volume);
            view.draw(new MusicView.Canvas() {
                public void fill(int x1,int y1,int x2,int y2,int color) {} public void text(String text,int x,int y,int color) {} public int width(String text) { return text.length()*5; }
            },-1,-1);
            assertTrue(view.click(20,129,0)); assertTrue(view.dialogInputActive());
            assertFalse(view.key(265)); assertFalse(view.key(264)); assertEquals(42,Config.get().volume);
        }
    }
}
