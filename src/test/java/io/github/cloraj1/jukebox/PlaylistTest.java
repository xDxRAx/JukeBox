package io.github.cloraj1.jukebox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class PlaylistTest {
    @TempDir Path temp;
    private void until(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+5_000_000_000L;
        while(!condition.getAsBoolean() && System.nanoTime()<deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean());
    }
    private MusicSession session() throws Exception {
        Config.load(temp.resolve("config"),temp);
        for(String name:List.of("Artist - First","Artist - Second","Artist - Third")) {
            try(var input=getClass().getResourceAsStream("/tone.wav")) { Files.copy(input,temp.resolve(name+".wav")); }
        }
        MusicSession session=new MusicSession(temp,new MusicPlayer(f -> new MusicPlayer.Output() {
            boolean closed;
            public void write(byte[] b,int o,int n) { try { if(!closed) Thread.sleep(15); } catch(InterruptedException ignored) {} }
            public void pause(boolean p) {}
            public void drain() {}
            public double seconds() { return 0; }
            public void close() { closed=true; }
        }));
        until(() -> !session.loading() && session.library().stream().allMatch(t -> session.duration(t)>0)); return session;
    }
    @Test void playlistsPersistOrderedUniqueSongsAndPreserveOldSettings() throws Exception {
        try(MusicSession session=session()) {
            List<Track> tracks=session.library();
            Config.get().volume=39; session.like(tracks.get(0));
            String id=session.createPlaylist("  여행 음악  ");
            session.addPlaylistTrack(id,tracks.get(1)); session.addPlaylistTrack(id,tracks.get(0)); session.addPlaylistTrack(id,tracks.get(1));
            assertEquals(List.of(tracks.get(1),tracks.get(0)),session.playlistTracks(id));
            Config.load(temp.resolve("config"),temp);
            assertEquals("여행 음악",session.playlistName(id)); assertEquals(39,Config.get().volume); assertTrue(session.liked(tracks.get(0)));
            assertEquals(List.of(tracks.get(1),tracks.get(0)),session.playlistTracks(id));
            session.renamePlaylist(id,"산책"); session.removePlaylistTrack(id,tracks.get(1));
            Config.load(temp.resolve("config"),temp);
            assertEquals("산책",session.playlistName(id)); assertEquals(List.of(tracks.get(0)),session.playlistTracks(id));
            session.deletePlaylist(id); Config.load(temp.resolve("config"),temp);
            assertTrue(session.playlists().isEmpty()); assertTrue(Files.exists(tracks.get(0).path())); assertTrue(Files.exists(tracks.get(1).path()));
        }
    }
    @Test void validatesNamesAndRetainsReferencesToTemporarilyMissingSongs() throws Exception {
        try(MusicSession session=session()) {
            assertThrows(IllegalArgumentException.class,() -> session.createPlaylist("  "));
            assertThrows(IllegalArgumentException.class,() -> session.createPlaylist("a".repeat(65)));
            String id=session.createPlaylist("Chill");
            assertThrows(IllegalArgumentException.class,() -> session.createPlaylist("chill"));
            Track song=session.library().get(0); session.addPlaylistTrack(id,song);
            Path moved=song.path().resolveSibling("hidden.tmp"); Files.move(song.path(),moved); session.reload(); until(() -> !session.loading());
            assertTrue(session.playlistTracks(id).isEmpty()); assertEquals(1,session.playlists().get(0).tracks());
            Files.move(moved,song.path()); session.reload(); until(() -> !session.loading());
            assertEquals(List.of(song),session.playlistTracks(id));
        }
    }
    @Test void oldConfigLoadsWithoutPlaylistsAndMalformedListsAreSanitized() throws Exception {
        Path dir=temp.resolve("config"); Files.createDirectories(dir);
        Files.writeString(dir.resolve("jukebox.json"),"{\"volume\":37,\"repeat\":\"ALL\",\"liked\":[\"song\"]}");
        Config.load(dir,temp); assertTrue(Config.get().playlists.isEmpty()); assertEquals(37,Config.get().volume); assertTrue(Config.get().liked.contains("song"));
        Files.writeString(dir.resolve("jukebox.json"),"{\"playlists\":[null,{\"name\":\"One\",\"tracks\":[null,\"song\",\"song\"]},{\"name\":\" \"}]}");
        Config.load(dir,temp); assertEquals(1,Config.get().playlists.size()); assertNotNull(Config.get().playlists.get(0).id); assertEquals(List.of("song"),Config.get().playlists.get(0).tracks);
    }
    private record Label(String value,int x,int y) {}
    private final List<Label> labels=new ArrayList<>();
    private void draw(MusicView view,int width,int height) {
        labels.clear();
        view.draw(new MusicView.Canvas() {
            public void fill(int x1,int y1,int x2,int y2,int color) { assertTrue(x1>=0 && y1>=0 && x2<=width && y2<=height,"Rectangle outside view"); }
            public void text(String s,int x,int y,int color) { assertTrue(x>=0 && y>=0 && x+s.length()*5<=width && y+8<=height,"Text outside view: "+s); labels.add(new Label(s,x,y)); }
            public int width(String s) { return s.length()*5; }
        },-1,-1);
    }
    private void click(MusicView view,String label) {
        Label l=labels.stream().filter(x -> x.value.equals(label)).reduce((a,b) -> b).orElseThrow(() -> new AssertionError("Button missing: "+label));
        assertTrue(view.click(l.x+1,l.y+1,0));
    }
    @Test void uiCanCreateAddRenameAndDeleteUsingVisibleButtons() throws Exception {
        try(MusicSession session=session()) {
            MusicView view=new MusicView(session,k -> k); view.resize(480,270);
            draw(view,480,270); click(view,"create"); assertTrue(view.dialogInputActive());
            view.dialogInput("Road Trip"); draw(view,480,270); click(view,"create");
            String id=session.playlists().get(0).id(); assertFalse(view.dialogOpen());
            draw(view,480,270); click(view,"addSongs"); draw(view,480,270); click(view,"+");
            assertEquals(1,session.playlistTracks(id).size());
            draw(view,480,270); click(view,"done");
            draw(view,480,270); click(view,"rename"); view.dialogInput("Walking"); draw(view,480,270); click(view,"save");
            assertEquals("Walking",session.playlistName(id));
            draw(view,480,270); click(view,"manage"); draw(view,480,270); click(view,"delete");
            draw(view,480,270); click(view,"cancel"); assertEquals(1,session.playlists().size());
            draw(view,480,270); click(view,"delete"); draw(view,480,270); click(view,"delete");
            assertTrue(session.playlists().isEmpty());
        }
    }
    @Test void modalFormsStayWithinSmallScreensAndBlockPlaybackShortcuts() throws Exception {
        try(MusicSession session=session()) {
            for(int[] size:new int[][]{{320,240},{480,270},{854,480}}) {
                MusicView view=new MusicView(session,k -> k); view.resize(size[0],size[1]);
                draw(view,size[0],size[1]); click(view,"create"); draw(view,size[0],size[1]);
                assertFalse(view.key(32)); assertNull(session.player.current());
                view.dialogInput("Test "+size[0]); view.confirmName();
                draw(view,size[0],size[1]); click(view,"addSongs"); draw(view,size[0],size[1]); view.key(256);
                draw(view,size[0],size[1]); click(view,"manage"); draw(view,size[0],size[1]); view.key(256);
                draw(view,size[0],size[1]); click(view,"delete"); draw(view,size[0],size[1]); view.key(256);
            }
        }
    }
    @Test void urlButtonOpensInputAndPlayTriangleIsOpticallyCentered() throws Exception {
        try(MusicSession session=session()) {
            for(int[] size:new int[][]{{320,240},{480,270},{854,480}}) {
                MusicView view=new MusicView(session,k -> k); view.resize(size[0],size[1]); draw(view,size[0],size[1]);
                assertEquals(12,view.searchY());
                assertTrue(view.click(size[0]-90,48,0)); draw(view,size[0],size[1]);
                assertTrue(view.dialogInputActive()); assertEquals("youtubeUrl",view.dialogInputHint());
                view.dialogInput("bad link"); view.key(257); draw(view,size[0],size[1]);
                assertEquals("urlInvalid",session.urlImporter.status().key()); assertTrue(labels.stream().anyMatch(l -> l.value.equals("urlInvalid")));
                view.dismissDialog();
            }
            MusicView view=new MusicView(session,k -> k); view.resize(480,270);
            List<int[]> pixels=new ArrayList<>();
            view.draw(new MusicView.Canvas() {
                public void fill(int x1,int y1,int x2,int y2,int color) { if(color==0xFF080808) for(int y=y1;y<y2;y++) for(int x=x1;x<x2;x++) pixels.add(new int[]{x,y}); }
                public void text(String s,int x,int y,int color) {}
                public int width(String s) { return s.length()*5; }
            },-1,-1);
            assertFalse(pixels.isEmpty()); double cx=pixels.stream().mapToInt(p -> p[0]).average().orElseThrow(),cy=pixels.stream().mapToInt(p -> p[1]).average().orElseThrow();
            assertEquals(242,cx,0.5); assertEquals(222,cy,0.01);
        }
    }
}
