package io.github.cloraj1.jukebox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SongImportGuardTest {
    @TempDir Path temp;
    @Test void hidesOnlyTrailingVideoIdsWithoutChangingPathsOrOrdinaryBrackets() {
        Path path=temp.resolve("NMIXX - LOUD [_M2gRyX_6ec].mp3");
        Track track=Track.fromPath(temp,path);
        assertEquals("LOUD",track.title()); assertEquals("NMIXX",track.artist()); assertEquals(path.toString(),track.id());
        assertEquals("_M2gRyX_6ec",Track.videoId(path));
        assertEquals("LOUD",Track.fromPath(temp,temp.resolve("NMIXX - LOUD [_M2gRyX_6ec] (1).mp3")).title());
        for(String title:java.util.List.of("LOUD [Live]","LOUD [2026]","LOUD [abcdefghijk] Remix","[abcdefghijk]"))
            assertEquals(title,Track.fromPath(temp,temp.resolve("NMIXX - "+title+".wav")).title());
    }
    @Test void findsSameVideoInSubfoldersBeforePreparingOrDownloadingTools() throws Exception {
        Path music=temp.resolve("music"),file=music.resolve("Nested/Renamed - Different title [abcdefghijk] (2).mp3");
        Files.createDirectories(file.getParent()); Files.writeString(file,"existing bytes"); AtomicInteger prepared=new AtomicInteger();
        try(var importer=new UrlSongImporter(music,temp.resolve("jobs"),job -> { prepared.incrementAndGet(); throw new AssertionError("Must not download a duplicate"); })) {
            assertTrue(importer.start("https://youtu.be/abcdefghijk"));
            long deadline=System.nanoTime()+5_000_000_000L;
            while(importer.status().busy() && System.nanoTime()<deadline) Thread.sleep(5);
            assertFalse(importer.status().busy()); assertEquals("urlDuplicate",importer.status().key()); assertEquals(0,prepared.get());
            assertNull(importer.consumeCompleted()); assertEquals("existing bytes",Files.readString(file)); assertFalse(Files.exists(temp.resolve("jobs")));
        }
    }
    @Test void matchesNormalizedArtistTitleAndDisplayOverridesButAllowsOtherArtists() throws Exception {
        Config.load(temp.resolve("config"),temp); Path music=Files.createDirectory(temp.resolve("music"));
        Path file=music.resolve("Artist - Different [abcdefghijk].wav"); Files.writeString(file,"existing");
        Config.get().songInfo.put(file.toAbsolutePath().toString(),new Config.SongInfo("Ｆｉｘｔｕｒｅ","  ARTIST  "));
        Track incoming=new Track("Fixture","artist",temp.resolve("input.mp3"),"");
        assertEquals("urlDuplicate",assertThrows(UrlSongImporter.ImportFailure.class,() -> SongImportGuard.check(music,"lmnopqrstuv",incoming)).key);
        SongImportGuard.check(music,"lmnopqrstuv",new Track("Fixture","Another artist",incoming.path(),""));
        assertEquals("existing",Files.readString(file));
    }
    @Test void destinationCollisionDoesNotOverwriteOrInventNumberedFiles() throws Exception {
        Path music=Files.createDirectory(temp.resolve("music")),source=temp.resolve("Song.mp3"),target=music.resolve("Song.mp3");
        Files.writeString(source,"new"); Files.writeString(target,"original");
        assertEquals("urlDuplicate",assertThrows(UrlSongImporter.ImportFailure.class,() -> UrlSongImporter.moveNew(source,music)).key);
        assertEquals("original",Files.readString(target)); assertEquals("new",Files.readString(source));
        try(var files=Files.list(music)) { assertEquals(1,files.count()); }
    }
}
