package io.github.cloraj1.jukebox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class SongManagementTest {
    @TempDir Path temp;
    final AtomicInteger outputs=new AtomicInteger();
    void until(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+5_000_000_000L;
        while(!condition.getAsBoolean() && System.nanoTime()<deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean());
    }
    MusicSession session(int count) throws Exception {
        Config.load(temp.resolve("config"),temp); Path folder=temp.resolve("music"),group=folder.resolve("Group"); Files.createDirectories(group);
        for(int i=0;i<count;i++) try(var input=getClass().getResourceAsStream("/tone.wav")) {
            Files.copy(input,group.resolve("Artist - "+String.format("%02d",i)+(i%2==0?" Keep":" Skip")+".wav"),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        MusicSession session=new MusicSession(folder,new MusicPlayer(format -> {
            outputs.incrementAndGet(); return new MusicPlayer.Output() {
                public void write(byte[] bytes,int offset,int length) { try { Thread.sleep(50); } catch(InterruptedException ignored) {} }
                public void pause(boolean paused) {} public void drain() {} public double seconds() { return .25; } public void close() {}
            };
        }));
        until(() -> !session.loading() && session.library().size()==count); return session;
    }
    @Test void deletingCurrentSongRemovesFileAndAllReferencesAndStopsPlayback() throws Exception {
        try(MusicSession session=session(3)) {
            var tracks=session.library(); Track deleted=tracks.get(0),other=tracks.get(1); byte[] original=Files.readAllBytes(other.path());
            String a=session.createPlaylist("A"),b=session.createPlaylist("B");
            session.addPlaylistTrack(a,deleted); session.addPlaylistTrack(a,other); session.addPlaylistTrack(b,deleted);
            session.like(deleted); session.editSong(deleted,"Edited","Title"); Config.get().songOrder.put("all",new ArrayList<>(tracks.stream().map(Track::id).toList()));
            session.play(deleted,tracks); until(() -> session.player.position()>0); session.play(other,tracks); until(() -> session.player.position()>0);
            session.play(deleted,tracks); until(() -> session.player.position()>0); session.player.pauseToggle();
            session.addQueue(deleted); session.addQueue(other); session.addQueue(deleted);
            session.deleteSong(deleted);
            assertFalse(Files.exists(deleted.path())); assertNull(session.currentTrack()); assertFalse(session.liked(deleted));
            assertFalse(Config.get().songInfo.containsKey(deleted.id())); assertFalse(Config.get().songOrder.get("all").contains(deleted.id()));
            assertEquals(List.of(other),session.playlistTracks(a)); assertTrue(session.playlistTracks(b).isEmpty()); assertEquals(List.of(other),session.queue());
            assertFalse(session.library().contains(deleted)); assertArrayEquals(original,Files.readAllBytes(other.path()));
            session.next(); assertEquals(other,session.currentTrack()); session.previous(); assertNotEquals(deleted,session.currentTrack());
            Config.load(temp.resolve("config"),temp); assertFalse(Config.get().liked.contains(deleted.id())); assertFalse(Config.get().playlists.stream().anyMatch(p -> p.tracks.contains(deleted.id())));
        }
    }
    @Test void deletingAnotherSongPreservesPlayingStateAndRejectsFilesOutsideLibrary() throws Exception {
        try(MusicSession session=session(3)) {
            var tracks=session.library(); Track playing=tracks.get(0); session.play(playing,tracks); until(() -> session.player.position()>0); session.player.pauseToggle();
            int before=outputs.get(); double position=session.player.position(); session.deleteSong(tracks.get(1));
            assertEquals(playing,session.currentTrack()); assertTrue(session.player.paused()); assertEquals(position,session.player.position()); assertEquals(before,outputs.get());
            Path outside=temp.resolve("outside.wav"); try(var input=getClass().getResourceAsStream("/tone.wav")) { Files.copy(input,outside); }
            byte[] bytes=Files.readAllBytes(outside); var config=Files.readAllBytes(temp.resolve("config/jukebox.json"));
            assertThrows(IOException.class,() -> session.deleteSong(new Track("Outside","Artist",outside,"")));
            assertArrayEquals(bytes,Files.readAllBytes(outside)); assertArrayEquals(config,Files.readAllBytes(temp.resolve("config/jukebox.json"))); assertEquals(before,outputs.get());
        }
    }
    @Test void failedDeletionKeepsPlaylistsFavoritesAndOtherFiles() throws Exception {
        try(MusicSession session=session(2)) {
            Track track=session.library().get(0),other=session.library().get(1); String id=session.createPlaylist("Keep"); session.addPlaylistTrack(id,track); session.like(track);
            byte[] config=Files.readAllBytes(temp.resolve("config/jukebox.json")),audio=Files.readAllBytes(other.path());
            Files.delete(track.path()); Files.createDirectory(track.path()); Files.writeString(track.path().resolve("keep"),"data");
            assertThrows(IOException.class,() -> session.deleteSong(track)); assertTrue(session.library().contains(track)); assertTrue(session.liked(track)); assertEquals(List.of(track),session.playlistTracks(id));
            assertArrayEquals(config,Files.readAllBytes(temp.resolve("config/jukebox.json"))); assertEquals("data",Files.readString(track.path().resolve("keep"))); assertArrayEquals(audio,Files.readAllBytes(other.path()));
        }
    }
    @Test void scanInFlightAndLaterReloadDoNotResurrectDeletedSongs() throws Exception {
        try(MusicSession session=session(3)) {
            Track track=session.library().get(0); Config.get().showAlbumArt=true; session.albumArt.get(track); session.reload(); session.albumArt.get(track);
            session.deleteSong(track); until(() -> !session.loading());
            assertFalse(session.library().contains(track)); session.reload(); until(() -> !session.loading()); assertFalse(session.library().contains(track));
        }
    }
    @Test void mp3PlaybackAndArtworkLookupReleaseTheFileBeforeDeletingDuringReload() throws Exception {
        Config.load(temp.resolve("config"),temp); Path folder=temp.resolve("mp3"); Files.createDirectories(folder); Path file=folder.resolve("Artist - Song.mp3");
        try(var input=getClass().getResourceAsStream("/tone.mp3")) { Files.copy(input,file); }
        Config.get().showAlbumArt=true;
        try(MusicSession session=new MusicSession(folder,new MusicPlayer(format -> new MusicPlayer.Output() {
            public void write(byte[] bytes,int offset,int count) { try { Thread.sleep(50); } catch(InterruptedException ignored) {} }
            public void pause(boolean paused) {} public void drain() {} public double seconds() { return .25; } public void close() {}
        }))) {
            until(() -> !session.loading() && session.library().size()==1); Track track=session.library().get(0);
            session.play(track,session.library()); until(() -> session.player.position()>0); session.albumArt.get(track); session.reload(); session.albumArt.get(track);
            session.deleteSong(track); until(() -> !session.loading()); assertFalse(Files.exists(file)); assertNull(session.currentTrack()); assertTrue(session.library().isEmpty());
        }
    }
    @Test void globalLikedAndFolderOrdersPersistIndependentlyWithUnchangedAudio() throws Exception {
        try(MusicSession session=session(4)) {
            var original=session.library(); for(Track track:original) session.like(track); byte[] audio=Files.readAllBytes(original.get(0).path());
            assertTrue(session.moveSong("all",0,4,original)); assertEquals(List.of(original.get(1),original.get(2),original.get(3),original.get(0)),session.library());
            var liked=session.tracksForPage("liked"); assertTrue(session.moveSong("liked",0,4,liked)); assertNotEquals(session.library(),session.tracksForPage("liked"));
            var folder=session.tracksForPage("folder:Group"); assertTrue(session.moveSong("folder:Group",3,0,folder)); assertNotEquals(session.library(),session.tracksForPage("folder:Group"));
            List<Track> allOrder=session.library(),likedOrder=session.tracksForPage("liked"),folderOrder=session.tracksForPage("folder:Group");
            Config.load(temp.resolve("config"),temp); session.reload(); until(() -> !session.loading());
            assertEquals(allOrder,session.library()); assertEquals(likedOrder,session.tracksForPage("liked")); assertEquals(folderOrder,session.tracksForPage("folder:Group"));
            assertArrayEquals(audio,Files.readAllBytes(original.get(0).path()));
            assertFalse(session.moveSong("all",0,4,original)); assertFalse(session.moveSong("all",-1,0,allOrder)); assertEquals(allOrder,session.library());
        }
    }
    @Test void playlistReorderPreservesMissingReferencesAndExportedSongOrder() throws Exception {
        try(MusicSession session=session(3)) {
            var tracks=session.library(); String id=session.createPlaylist("Ordered"); for(Track track:tracks) session.addPlaylistTrack(id,track);
            var playlist=Config.get().playlists.get(0); String missing=session.folder().resolve("Missing.wav").toString(); playlist.tracks.add(1,missing); Config.save();
            assertTrue(session.moveSong("list:"+id,2,0,session.playlistTracks(id)));
            assertEquals(List.of(tracks.get(2).id(),missing,tracks.get(0).id(),tracks.get(1).id()),playlist.tracks);
            Config.load(temp.resolve("config"),temp); assertEquals(List.of(tracks.get(2),tracks.get(0),tracks.get(1)),session.playlistTracks(id));
            Config.get().playlists.get(0).tracks.remove(missing);
            String exported=PlaylistTransfer.snapshot(session.folder(),Config.get().playlists); var entry=PlaylistTransfer.parse(exported,session.folder()).get(0);
            assertEquals(List.of(tracks.get(2).id(),tracks.get(0).id(),tracks.get(1).id()),entry.tracks());
        }
    }
    @Test void queueDuplicatesMoveByOccurrenceAndPlaybackUsesClickedOccurrence() throws Exception {
        try(MusicSession session=session(3)) {
            var tracks=session.library(); Track a=tracks.get(0),b=tracks.get(1),c=tracks.get(2);
            session.addQueue(a); session.addQueue(b); session.addQueue(a); session.addQueue(c);
            assertTrue(session.moveSong("queue",2,1,session.queue())); assertEquals(List.of(a,a,b,c),session.queue());
            session.playPage("queue",1,""); assertEquals(a,session.currentTrack()); assertEquals(List.of(a,b,c),session.queue());
            assertTrue(session.moveSong("queue",0,3,session.queue())); assertEquals(List.of(b,c,a),session.queue());
        }
    }
    @Test void reorderUpdatesNextSongWithoutRestartingOrUnpausingPlayback() throws Exception {
        try(MusicSession session=session(4)) {
            var tracks=session.library(); session.playPage("all",0,""); until(() -> session.player.position()>0); session.player.pauseToggle();
            int count=outputs.get(); assertTrue(session.moveSong("all",0,2,session.library()));
            assertEquals(tracks.get(0),session.currentTrack()); assertTrue(session.player.paused()); assertEquals(count,outputs.get()); assertEquals(.25,session.player.position());
            session.next(); assertEquals(tracks.get(2),session.currentTrack()); until(() -> session.player.position()>0);
            assertTrue(session.moveSong("all",3,2,session.library())); session.next(); assertEquals(tracks.get(1),session.currentTrack());
        }
    }
    record Label(String text,int x,int y) {}
    final class Ui {
        final AtomicLong time=new AtomicLong(); final AtomicInteger sounds=new AtomicInteger(); final List<Label> labels=new ArrayList<>();
        final MusicView view; final int w,h;
        final MusicView.Canvas canvas;
        Ui(MusicSession session,int w,int h,java.util.function.Function<String,String> tr) {
            this.w=w; this.h=h; view=new MusicView(session,tr,sounds::incrementAndGet,null,time::get); view.resize(w,h);
            canvas=new MusicView.Canvas() {
                public void fill(int x1,int y1,int x2,int y2,int color) { assertTrue(x1>=0 && y1>=0 && x2<=w && y2<=h,"Outside view"); }
                public void text(String text,int x,int y,int color) { labels.add(new Label(text,x,y)); }
                public int width(String text) { return text.codePointCount(0,text.length())*5; }
            };
        }
        void draw() { draw(-1,-1); }
        void draw(int x,int y) { labels.clear(); view.draw(canvas,x,y); }
        void clickLabel(String text) { Label label=labels.stream().filter(l -> l.text.equals(text)).reduce((a,b) -> b).orElseThrow(); assertTrue(view.click(label.x+1,label.y+1,0)); view.release(label.x+1,label.y+1,0); }
        void hold(int x,int y) { draw(x,y); assertTrue(view.click(x,y,0)); time.addAndGet(300_000_000L); }
    }
    @Test void shortClickPlaysOnReleaseButHeldDragEscAndOutsideDropsAreSilent() throws Exception {
        try(MusicSession session=session(4)) {
            Ui ui=new Ui(session,480,270,k -> k); ui.draw(); assertFalse(ui.view.click(18,17,0)); assertFalse(ui.labels.stream().anyMatch(l -> l.text.equals("JukeBox")));
            assertTrue(ui.view.click(200,80,0)); assertNull(session.currentTrack()); ui.time.addAndGet(100_000_000L); assertTrue(ui.view.release(200,80,0)); assertEquals(session.library().get(0),session.currentTrack());
            session.player.stop(); var before=session.library(); ui.hold(200,80); assertTrue(ui.view.drag(200,147,0)); assertNull(session.currentTrack()); assertTrue(ui.view.release(200,147,0));
            assertEquals(List.of(before.get(1),before.get(0),before.get(2),before.get(3)),session.library()); assertEquals(0,ui.sounds.get());
            var ordered=session.library(); ui.hold(200,80); ui.view.drag(200,150,0); assertTrue(ui.view.key(256)); ui.view.release(200,150,0); assertEquals(ordered,session.library());
            ui.hold(200,80); ui.view.drag(20,150,0); ui.view.release(20,150,0); assertEquals(ordered,session.library()); assertNull(session.currentTrack());
        }
    }
    @Test void filteredDragUsesFullSourcePositionsAndDoesNotLoseHiddenSongs() throws Exception {
        try(MusicSession session=session(5)) {
            var original=session.library(); Ui ui=new Ui(session,480,270,k -> k); ui.view.search("Keep"); ui.hold(200,80);
            ui.view.drag(200,129,0); ui.view.release(200,129,0);
            assertEquals(List.of(original.get(1),original.get(2),original.get(0),original.get(3),original.get(4)),session.library()); assertEquals(5,session.library().size()); assertNull(session.currentTrack());
            var ordered=session.library(); ui.hold(200,80); ui.view.search(""); ui.view.release(200,120,0); assertEquals(ordered,session.library());
        }
    }
    @Test void heldDragScrollsTowardHiddenRowsAndPersistsDropWithoutStartingPlayback() throws Exception {
        try(MusicSession session=session(10)) {
            var original=session.library(); Ui ui=new Ui(session,480,270,k -> k); ui.hold(200,80); ui.view.drag(200,199,0);
            for(int i=0;i<4;i++) { ui.time.addAndGet(150_000_000L); ui.draw(200,199); }
            ui.view.release(200,199,0); assertTrue(session.library().indexOf(original.get(0))>=4); assertNull(session.currentTrack()); assertEquals(0,ui.sounds.get());
            var ordered=session.library(); Config.load(temp.resolve("config"),temp); assertEquals(ordered,session.library());
        }
    }
    @Test void localizedPopupStaysInBoundsOffersModifyAndDeletesOnlySelectedSong() throws Exception {
        try(MusicSession session=session(8)) {
            int index=0;
            for(String lang:List.of("en_us","ko_kr")) try(var language=new TestLanguage(lang)) {
                for(int[] size:new int[][]{{320,240},{480,270},{854,480}}) {
                    Ui ui=new Ui(session,size[0],size[1],k -> net.minecraft.network.chat.Component.translatable("jukebox."+k).getString()); ui.draw(); Track target=session.library().get(0);
                    assertTrue(ui.view.click(size[0]-18,80,1)); assertTrue(ui.view.popupOpen()); assertFalse(ui.view.songEditOpen()); assertFalse(ui.view.textInputsActive()); assertEquals(1,ui.sounds.get());
                    ui.draw(); String modify=lang.equals("en_us")?"Modify Song Info":"곡 정보 수정",delete=lang.equals("en_us")?"Delete Song":"곡 삭제";
                    assertTrue(ui.labels.stream().anyMatch(l -> l.text.equals(modify))); assertTrue(ui.labels.stream().anyMatch(l -> l.text.equals(delete)));
                    ui.clickLabel(modify); assertTrue(ui.view.songEditOpen()); assertEquals(target.title(),ui.view.songTitle()); assertEquals(2,ui.sounds.get());
                    ui.view.key(256); ui.draw(); assertTrue(ui.view.click(size[0]-18,80,1)); ui.draw(); ui.clickLabel(delete);
                    assertFalse(Files.exists(target.path())); assertFalse(ui.view.dialogOpen()); assertEquals(8-++index,session.library().size()); assertNull(session.currentTrack());
                }
            }
        }
    }
    @Test void failedReorderSaveRollsBackAndOldConfigDefaultsToTitleOrder() throws Exception {
        try(MusicSession session=session(3)) {
            var original=session.library(); assertTrue(Config.get().songOrder.isEmpty());
            Path file=temp.resolve("config/jukebox.json"); Files.delete(file); Files.createDirectory(file); Files.writeString(file.resolve("keep"),"yes");
            assertThrows(IOException.class,() -> session.moveSong("all",0,3,original)); assertEquals(original,session.library()); assertTrue(Config.get().songOrder.isEmpty());
            assertEquals("yes",Files.readString(file.resolve("keep")));
        }
    }
}
