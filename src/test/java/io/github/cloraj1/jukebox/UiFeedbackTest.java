package io.github.cloraj1.jukebox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class UiFeedbackTest {
    @TempDir Path temp;
    private MusicSession session() throws Exception {
        Config.load(temp.resolve("config"),temp);
        Path folder=temp.resolve("music"); Files.createDirectories(folder.resolve("Folder"));
        for(String name:new String[]{"Artist - First.wav","Folder/Artist - Second.wav"}) {
            try(var input=getClass().getResourceAsStream("/tone.wav")) { Files.copy(input,folder.resolve(name)); }
        }
        MusicSession session=new MusicSession(folder,new MusicPlayer(format -> new MusicPlayer.Output() {
            volatile boolean closed;
            public void write(byte[] b,int o,int n) { try { if(!closed) Thread.sleep(100); } catch(InterruptedException ignored) {} }
            public void pause(boolean p) {} public void drain() {} public double seconds() { return 0; } public void close() { closed=true; }
        }));
        long deadline=System.nanoTime()+5_000_000_000L;
        while((session.loading() || session.library().size()!=2 || session.library().stream().anyMatch(t -> session.duration(t)==0)) && System.nanoTime()<deadline) Thread.sleep(5);
        assertEquals(2,session.library().size()); return session;
    }
    private static final class Ui {
        final AtomicInteger sounds=new AtomicInteger();
        final MusicView view;
        final MusicView.Canvas canvas=new MusicView.Canvas() {
            public void fill(int x1,int y1,int x2,int y2,int color) {}
            public void text(String text,int x,int y,int color) {}
            public int width(String text) { return text.length()*5; }
        };
        Ui(MusicSession session) { view=new MusicView(session,key -> key,sounds::incrementAndGet); view.resize(480,270); }
        void click(int x,int y,int button,int soundCount) {
            view.draw(canvas,-1,-1); int before=sounds.get();
            assertTrue(view.click(x,y,button),"Unhandled click at "+x+","+y);
            if(button==0) view.release(x,y,button);
            assertEquals(before+soundCount,sounds.get(),"Sound count at "+x+","+y);
        }
        void click(int x,int y) { click(x,y,0,1); }
        void unhandled(int x,int y,int button) {
            view.draw(canvas,-1,-1); int before=sounds.get();
            assertFalse(view.click(x,y,button)); assertEquals(before,sounds.get());
        }
    }
    @Test void navigationTrackActionsAndTransportButtonsSoundOnce() throws Exception {
        try(MusicSession session=session()) {
            String id=session.createPlaylist("Mix"); session.addPlaylistTrack(id,session.library().get(0)); Ui ui=new Ui(session);
            ui.click(20,45); ui.click(ui.view.searchX()-17,12); ui.click(ui.view.searchX()+ui.view.searchWidth()+13,12);
            ui.click(20,45); ui.click(20,65); ui.click(20,85); ui.click(20,150); ui.click(20,45);
            ui.click(190,222); assertTrue(Config.get().shuffle);
            ui.click(214,222); ui.click(266,222); ui.click(290,222); assertEquals("ALL",Config.get().repeat);
            Track track=session.library().get(0); ui.click(435,84); assertTrue(session.liked(track));
            ui.click(460,84); assertEquals(1,session.queue().size());
            ui.click(410,49);
        }
    }
    @Test void modalButtonsSettingsAndManagementRowsSoundOnce() throws Exception {
        try(MusicSession session=session()) {
            Ui ui=new Ui(session);
            ui.click(20,129); ui.view.dialogInput("Sound mix"); ui.click(80,187);
            String id=session.playlists().get(0).id(); assertEquals("Sound mix",session.playlistName(id));
            ui.click(20,45); ui.click(463,17);
            ui.click(355,78); ui.click(355,108); ui.click(355,138);
            assertFalse(Config.get().playbackNotifications); assertTrue(Config.get().alwaysPlaybackNotification); assertTrue(Config.get().showAlbumArt);
            ui.click(100,170); ui.click(300,170);
            ui.click(80,220); assertFalse(ui.view.dialogOpen());
            ui.click(390,49); ui.click(400,55); assertFalse(ui.view.dialogOpen());
            Track track=session.library().get(0); ui.click(200,80,1,1); ui.click(220,110);
            ui.view.songArtist("Edited artist"); ui.view.songTitle("Edited title"); ui.click(80,215);
            assertEquals("Edited title",session.displayTrack(track).title());
            ui.click(100,129); ui.click(310,62); ui.view.dialogInput("Renamed"); ui.click(80,187);
            assertEquals("Renamed",session.playlistName(id));
            ui.click(380,84); ui.click(80,90); assertEquals(1,session.playlistTracks(id).size());
            ui.click(390,90); assertTrue(session.playlistTracks(id).isEmpty()); ui.click(350,232);
            ui.click(280,84); ui.click(80,187); assertTrue(session.playlists().isEmpty());
        }
    }
    @Test void playPausePlaylistPlayTrackPlayInputsAndSlidersAreSilent() throws Exception {
        try(MusicSession session=session()) {
            Track track=session.library().get(0); String id=session.createPlaylist("Mix"); session.addPlaylistTrack(id,track); Ui ui=new Ui(session);
            ui.click(240,221,0,0); assertEquals(track,session.player.current());
            ui.click(240,221,0,0); assertTrue(session.player.paused());
            ui.click(136,155,0,0); assertFalse(session.player.paused());
            ui.click(136,183,0,0); assertEquals("Folder",session.player.current().collection());
            ui.click(20,150); ui.click(200,106,0,0); assertEquals(track,session.player.current());
            ui.click(20,45); ui.click(200,80,0,0);
            ui.unhandled(200,17,0); ui.unhandled(200,190,0); ui.unhandled(200,190,1); ui.unhandled(18,17,2);
            int sliderSounds=ui.sounds.get(); ui.view.draw(ui.canvas,-1,-1);
            assertTrue(ui.view.click(200,251,0)); assertTrue(ui.view.drag(230,251,0)); ui.view.release();
            assertTrue(ui.view.click(410,251,0)); assertTrue(ui.view.drag(420,251,0)); ui.view.release(); assertEquals(sliderSounds,ui.sounds.get());
            int before=ui.sounds.get(); assertTrue(ui.view.key(32)); assertTrue(ui.view.key(83)); assertTrue(ui.view.scroll(200,100,-1));
            assertEquals(before,ui.sounds.get());
        }
    }
}
