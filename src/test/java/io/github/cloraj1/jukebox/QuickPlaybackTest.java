package io.github.cloraj1.jukebox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Random;
import com.mojang.blaze3d.platform.InputConstants;
import static org.junit.jupiter.api.Assertions.*;

class QuickPlaybackTest {
    @Test void nativeKeyboardConstantsKeepBindingsAndUiShortcutsCorrectAcrossVersions() {
        assertEquals(InputConstants.KEY_K,JukeBoxKeys.OPEN_UI); assertEquals(InputConstants.KEY_L,JukeBoxKeys.TOGGLE_PLAYBACK);
        assertEquals(InputConstants.KEY_UP,JukeBoxKeys.VOLUME_UP); assertEquals(InputConstants.KEY_DOWN,JukeBoxKeys.VOLUME_DOWN);
        assertEquals(32,JukeBoxKeys.uiKey(InputConstants.KEY_SPACE)); assertEquals(70,JukeBoxKeys.uiKey(InputConstants.KEY_F));
        assertEquals(78,JukeBoxKeys.uiKey(InputConstants.KEY_N)); assertEquals(66,JukeBoxKeys.uiKey(InputConstants.KEY_B));
        assertEquals(82,JukeBoxKeys.uiKey(InputConstants.KEY_R)); assertEquals(83,JukeBoxKeys.uiKey(InputConstants.KEY_S)); assertEquals(84,JukeBoxKeys.uiKey(InputConstants.KEY_T));
        assertEquals(256,JukeBoxKeys.uiKey(InputConstants.KEY_ESCAPE)); assertEquals(257,JukeBoxKeys.uiKey(InputConstants.KEY_RETURN)); assertEquals(335,JukeBoxKeys.uiKey(InputConstants.KEY_NUMPADENTER));
        assertEquals(263,JukeBoxKeys.uiKey(InputConstants.KEY_LEFT)); assertEquals(262,JukeBoxKeys.uiKey(InputConstants.KEY_RIGHT)); assertEquals(265,JukeBoxKeys.uiKey(InputConstants.KEY_UP)); assertEquals(264,JukeBoxKeys.uiKey(InputConstants.KEY_DOWN));
    }
    @TempDir Path temp;
    private void until(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+5_000_000_000L;
        while(!condition.getAsBoolean() && System.nanoTime()<deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean());
    }
    private MusicSession session(Random random) throws Exception {
        Config.load(temp.resolve("config"),temp); Path root=temp.resolve("music"); Files.createDirectories(root.resolve("Folder Mix"));
        for(String name:List.of("Artist - A","Artist - B","Folder Mix/Artist - C","Folder Mix/Artist - D")) {
            try(var input=getClass().getResourceAsStream("/tone.wav")) { Files.copy(input,root.resolve(name+".wav")); }
        }
        MusicSession session=new MusicSession(root,new MusicPlayer(format -> new MusicPlayer.Output() {
            volatile boolean closed;
            public void write(byte[] bytes,int offset,int length) { try { if(!closed) Thread.sleep(25); } catch(InterruptedException ignored) {} }
            public void pause(boolean value) {}
            public void drain() {}
            public double seconds() { return 0.25; }
            public void close() { closed=true; }
        }),random);
        until(() -> !session.loading() && session.library().size()==4 && session.library().stream().allMatch(t -> session.duration(t)>0)); return session;
    }
    @Test void directPlaylistPlayUsesSavedOrderAndKeepsNextTracksInThatPlaylist() throws Exception {
        try(MusicSession session=session(new Random(7))) {
            Track outside=session.library().get(0),first=session.library().get(3),second=session.library().get(1);
            String id=session.createPlaylist("Road Trip"); session.addPlaylistTrack(id,first); session.addPlaylistTrack(id,second);
            session.play(outside,session.library()); assertTrue(session.startPlaylist(id)); assertEquals(first,session.player.current());
            session.next(); assertEquals(second,session.player.current()); session.next(); assertEquals(first,session.player.current());
            session.removePlaylistTrack(id,second); session.next(); assertEquals(first,session.player.current());
        }
    }
    @Test void shuffleChoosesInitialSongRandomlyAndFolderPlayUsesOnlyFolderTracks() throws Exception {
        Random chooseLast=new Random() { @Override public int nextInt(int bound) { return bound-1; } };
        try(MusicSession session=session(chooseLast)) {
            Track first=session.library().get(0),last=session.library().get(2);
            String id=session.createPlaylist("Shuffle"); session.addPlaylistTrack(id,first); session.addPlaylistTrack(id,last);
            Config.get().shuffle=true; assertTrue(session.startPlaylist(id)); assertEquals(last,session.player.current()); session.next(); assertEquals(first,session.player.current());
            assertTrue(session.startFolder("Folder Mix")); assertEquals("D",session.player.current().title()); session.next(); assertEquals("C",session.player.current().title());
            Config.get().shuffle=false; assertTrue(session.startFolder("Folder Mix")); assertEquals("C",session.player.current().title());
        }
    }
    @Test void emptyOrUnavailablePlaylistDoesNotInterruptCurrentPlayback() throws Exception {
        try(MusicSession session=session(new Random(1))) {
            Track current=session.library().get(0); session.play(current,session.library()); until(() -> session.player.position()>0); session.player.pauseToggle();
            String empty=session.createPlaylist("Empty"); assertFalse(session.startPlaylist(empty)); assertFalse(session.startPlaylist("missing")); assertFalse(session.startFolder("missing"));
            assertEquals(current,session.player.current()); assertTrue(session.player.paused()); assertEquals(0.25,session.player.position(),0.001);
        }
    }
    @Test void toggleFeedbackReportsPauseResumeEditedNameAndAnEmptyLibrary() throws Exception {
        try(MusicSession session=session(new Random(2))) {
            var start=session.toggleWithFeedback(); assertEquals("actionPlaying",start.key()); until(() -> session.player.position()>0);
            Track current=session.player.current(); session.editSong(current,"Rose","Edited song");
            var paused=session.toggleWithFeedback(); assertEquals("actionPaused",paused.key()); assertEquals("Rose - Edited song",paused.song()); assertTrue(session.player.paused());
            var resumed=session.toggleWithFeedback(); assertEquals("actionPlaying",resumed.key()); assertFalse(session.player.paused()); assertEquals(paused.song(),resumed.song());
            session.player.stop(); Track intro=session.library().get(session.library().size()-1); session.editSong(intro,"","000 Intro");
            assertEquals("000 Intro",session.toggleWithFeedback().song()); assertEquals(intro,session.player.current());
        }
        try(MusicSession empty=new MusicSession(temp.resolve("empty"))) {
            until(() -> !empty.loading()); assertEquals("actionNoSong",empty.toggleWithFeedback().key()); assertNull(empty.player.current());
        }
    }
    private record Label(String text,int x,int y) {}
    @Test void sidebarPlayButtonDoesNotNavigateOrApplySearchAndFitsSmallScreens() throws Exception {
        try(MusicSession session=session(new Random(3))) {
            Track first=session.library().get(3); String id=session.createPlaylist("Quick Mix"); session.addPlaylistTrack(id,first);
            for(int[] size:new int[][]{{320,240},{480,270},{854,480}}) {
                int w=size[0],h=size[1],side=Maths.clamp(w/3,90,210); List<Label> labels=new ArrayList<>();
                MusicView view=new MusicView(session,key -> key); view.resize(w,h); view.search("unmatched query");
                MusicView.Canvas canvas=new MusicView.Canvas() {
                    public void fill(int x1,int y1,int x2,int y2,int color) { assertTrue(x1>=0 && y1>=0 && x2<=w && y2<=h,"Rectangle outside view"); }
                    public void text(String text,int x,int y,int color) { assertTrue(x>=0 && y>=0 && x+text.length()*5<=w && y+8<=h); labels.add(new Label(text,x,y)); }
                    public int width(String text) { return text.length()*5; }
                };
                view.draw(canvas,-1,-1); assertTrue(view.click(side-28,155,0)); assertEquals(first,session.player.current());
                labels.clear(); view.draw(canvas,-1,-1);
                assertEquals("unmatched query",view.query()); assertTrue(labels.stream().anyMatch(l -> l.text.equals("all") && l.x==side+12 && l.y==44)); assertFalse(view.dialogOpen());
                assertTrue(view.click(20,150,0)); labels.clear(); view.draw(canvas,-1,-1);
                assertTrue(labels.stream().anyMatch(l -> l.text.equals("Quick Mix") && l.x==side+12 && l.y==44));
            }
        }
    }
}
