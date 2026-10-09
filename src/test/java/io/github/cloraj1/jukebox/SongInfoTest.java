package io.github.cloraj1.jukebox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SongInfoTest {
    @TempDir Path temp;
    private final AtomicInteger outputs=new AtomicInteger();
    private MusicPlayer player() {
        return new MusicPlayer(format -> {
            outputs.incrementAndGet();
            return new MusicPlayer.Output() {
                volatile boolean closed;
                public void write(byte[] bytes,int offset,int length) { try { if(!closed) Thread.sleep(50); } catch(InterruptedException ignored) {} }
                public void pause(boolean value) {}
                public void drain() {}
                public double seconds() { return 0.25; }
                public void close() { closed=true; }
            };
        });
    }
    private void until(java.util.function.BooleanSupplier condition) throws Exception {
        long limit=System.nanoTime()+5_000_000_000L;
        while(!condition.getAsBoolean() && System.nanoTime()<limit) Thread.sleep(5);
        assertTrue(condition.getAsBoolean());
    }
    private MusicSession session() throws Exception {
        Config.load(temp.resolve("config"),temp); Path music=temp.resolve("music"); Files.createDirectories(music);
        for(String name:List.of("Original - Download [abcdefghijk]","Other - Second")) {
            try(var input=getClass().getResourceAsStream("/tone.wav")) { Files.copy(input,music.resolve(name+".wav"),java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        }
        MusicSession session=new MusicSession(music,player());
        until(() -> !session.loading() && session.library().size()==2 && session.library().stream().allMatch(t -> session.duration(t)>0)); return session;
    }
    @Test void editsPersistAcrossScanAndRestartAndKeepFileFavoritesAndPlaylistOrder() throws Exception {
        String id,playlist; Path path; byte[] audio;
        try(MusicSession session=session()) {
            Track original=session.library().get(0),other=session.library().get(1); id=original.id(); path=original.path(); audio=Files.readAllBytes(path);
            playlist=session.createPlaylist("Favorites"); session.addPlaylistTrack(playlist,original); session.addPlaylistTrack(playlist,other); session.like(original);
            session.editSong(original,"  Rose  ","  toxic till the end  ");
            Track edited=session.library().stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
            assertEquals("Rose",edited.artist()); assertEquals("toxic till the end",edited.title()); assertEquals(original,edited); assertEquals(original.hashCode(),edited.hashCode());
            assertTrue(session.liked(edited)); assertEquals(List.of(id,other.id()),session.playlistTracks(playlist).stream().map(Track::id).toList());
            assertEquals("Rose",session.playlistTracks(playlist).get(0).artist());
            assertEquals(List.of(edited),MusicLibrary.filter(session.library(),"Rose"));
            assertEquals(List.of(edited),MusicLibrary.filter(session.library(),"toxic"));
            session.reload(); until(() -> !session.loading());
            assertEquals("toxic till the end",session.displayTrack(original).title()); assertArrayEquals(audio,Files.readAllBytes(path));
        }
        try(MusicSession restarted=session()) {
            Track edited=restarted.library().stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
            assertEquals("Rose",edited.artist()); assertEquals("toxic till the end",edited.title()); assertTrue(restarted.liked(edited)); assertEquals(id,restarted.playlistTracks(playlist).get(0).id()); assertArrayEquals(audio,Files.readAllBytes(path));
        }
    }
    @Test void editingCurrentAndQueuedTracksDoesNotRestartPlaybackOrBreakNavigation() throws Exception {
        try(MusicSession session=session()) {
            Track first=session.library().get(0),second=session.library().get(1);
            session.play(first,session.library()); until(() -> outputs.get()==1 && session.player.position()>0); session.player.pauseToggle();
            session.addQueue(first); session.addQueue(second); int opened=outputs.get(); double position=session.player.position();
            session.editSong(first,"새 가수","새 제목");
            assertEquals("새 제목",session.currentTrack().title()); assertTrue(session.player.paused()); assertEquals(position,session.player.position(),0.001); assertEquals(opened,outputs.get());
            assertEquals("새 가수",session.queue().get(0).artist()); assertEquals(first.id(),session.queue().get(0).id());
            session.playQueue(session.queue().indexOf(session.displayTrack(first))); assertEquals(first.id(),session.player.current().id());
            session.next(); assertEquals(second.id(),session.player.current().id());
            session.previous(); assertEquals(first.id(),session.player.current().id()); assertEquals("새 제목",session.currentTrack().title());
        }
    }
    @Test void blankTitleAndExcessiveOrMultilineTextAreRejectedWithoutChangingSettings() throws Exception {
        try(MusicSession session=session()) {
            Track original=session.library().get(0);
            assertThrows(IllegalArgumentException.class,() -> session.editSong(original,"Artist"," "));
            assertThrows(IllegalArgumentException.class,() -> session.editSong(original,"a".repeat(129),"Title"));
            assertThrows(IllegalArgumentException.class,() -> session.editSong(original,"Artist","a".repeat(129)));
            assertThrows(IllegalArgumentException.class,() -> session.editSong(original,"Artist","One\nTwo"));
            assertTrue(Config.get().songInfo.isEmpty());
            session.editSong(original,"","제목 🎵"); assertEquals("",session.displayTrack(original).artist()); assertEquals("제목 🎵",session.displayTrack(original).title());
        }
    }
    @Test void oldConfigAndMissingOverrideValuesLoadWithoutLosingExistingSettings() throws Exception {
        Path config=temp.resolve("config"); Files.createDirectories(config);
        Files.writeString(config.resolve("jukebox.json"),"{\"volume\":39,\"liked\":[\"old\"],\"songInfo\":null}");
        Config.load(config,temp); assertTrue(Config.get().songInfo.isEmpty()); assertEquals(39,Config.get().volume); assertTrue(Config.get().liked.contains("old"));
        Files.writeString(config.resolve("jukebox.json"),"{\"songInfo\":{\"one\":null,\"two\":{\"title\":\" \"},\"three\":{\"title\":\"Song\"}}}");
        Config.load(config,temp); assertEquals(1,Config.get().songInfo.size()); assertEquals("",Config.get().songInfo.get("three").artist);
    }
    private record Label(String value,int x,int y) {}
    private final List<Label> labels=new ArrayList<>();
    private void draw(MusicView view,int w,int h) {
        labels.clear(); view.draw(new MusicView.Canvas() {
            public void fill(int x1,int y1,int x2,int y2,int color) { assertTrue(x1>=0 && y1>=0 && x2<=w && y2<=h,"Rectangle outside view"); }
            public void text(String value,int x,int y,int color) { assertTrue(x>=0 && y>=0 && x+value.length()*5<=w && y+8<=h,"Text outside view: "+value); labels.add(new Label(value,x,y)); }
            public int width(String value) { return value.length()*5; }
        },-1,-1);
    }
    private void click(MusicView view,String button) {
        Label label=labels.stream().filter(l -> l.value.equals(button)).reduce((a,b) -> b).orElseThrow(); assertTrue(view.click(label.x+1,label.y+1,0));
    }
    @Test void rightClickOptionsOpenTwoPrefilledFieldsAndSaveCancelEnterAndPlayerBarWork() throws Exception {
        try(MusicSession session=session()) {
            for(int[] size:new int[][]{{320,240},{480,270},{854,480}}) {
                MusicView view=new MusicView(session,key -> key); view.resize(size[0],size[1]); draw(view,size[0],size[1]);
                int side=Maths.clamp(size[0]/3,90,210); assertTrue(view.click(side+40,80,1)); assertTrue(view.popupOpen()); draw(view,size[0],size[1]); click(view,"modifySongInfo");
                assertTrue(view.songEditOpen()); assertTrue(view.textInputsActive()); assertFalse(view.dialogInputActive()); assertEquals(session.library().get(0).artist(),view.songArtist()); assertEquals(session.library().get(0).title(),view.songTitle()); assertNull(session.player.current());
                draw(view,size[0],size[1]); view.songArtist("Cancelled artist"); view.songTitle("Cancelled title"); click(view,"cancel"); assertTrue(Config.get().songInfo.isEmpty());
                draw(view,size[0],size[1]); assertTrue(view.click(side+40,80,1)); assertTrue(view.popupOpen()); draw(view,size[0],size[1]); click(view,"modifySongInfo"); view.songArtist("Rose"); view.songTitle("toxic till the end"); draw(view,size[0],size[1]); click(view,"save");
                assertFalse(view.dialogOpen()); Track edited=session.library().stream().filter(t -> t.title().equals("toxic till the end")).findFirst().orElseThrow(); assertEquals("Rose",edited.artist());
                session.play(edited,session.library()); until(() -> session.player.position()>0); session.player.pauseToggle();
                draw(view,size[0],size[1]); assertTrue(view.click(20,size[1]-40,1)); draw(view,size[0],size[1]); click(view,"modifySongInfo"); assertTrue(view.songEditOpen()); assertEquals("Rose",view.songArtist());
                view.songTitle(" "); view.key(257); assertTrue(view.songEditOpen()); draw(view,size[0],size[1]); assertTrue(labels.stream().anyMatch(l -> l.value.equals("songTitleRequired")));
                view.songTitle("Changed"); view.key(257); assertFalse(view.dialogOpen()); assertEquals("Changed",session.currentTrack().title()); assertTrue(session.player.paused());
                session.player.stop(); Config.get().songInfo.clear(); Config.save(); Config.load(temp.resolve("config"),temp);
                session.reload(); until(() -> !session.loading());
            }
        }
    }
}
