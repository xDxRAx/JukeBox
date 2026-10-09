package io.github.cloraj1.jukebox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class UrlImportTest {
    @TempDir Path temp;
    private static final String ID="abcdefghijk",URL="https://www.youtube.com/watch?v="+ID;
    private void until(java.util.function.BooleanSupplier condition) throws Exception {
        long limit=System.nanoTime()+8_000_000_000L;
        while(!condition.getAsBoolean() && System.nanoTime()<limit) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(),"Background import did not finish");
    }
    private UrlSongImporter importer(String mode) throws Exception {
        Path fixture=temp.resolve(mode+".fixture");
        try(var input=getClass().getResourceAsStream("/tone.mp3")) {
            Files.write(fixture,input.readAllBytes());
        }
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        String classes=Path.of(ImportProcessFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        return new UrlSongImporter(temp.resolve("music-"+mode),temp.resolve("jobs"),job -> new UrlSongImporter.Tools(
            List.of(java,"-cp",classes,ImportProcessFixture.class.getName(),mode,fixture.toString()),temp,"deno",
            List.of(java,"-cp",classes,ImportProcessFixture.class.getName(),"embed",mode)));
    }
    private void assertClean() throws Exception { try(var files=Files.list(temp.resolve("jobs"))) { assertEquals(0,files.count()); } }
    @Test void canonicalizesSingleVideoLinksAndRejectsOtherHostsAndFlagInjection() {
        for(String input:List.of(URL+"&list=PLabc", "https://youtu.be/"+ID+"?si=abc", "https://m.youtube.com/shorts/"+ID,"https://music.youtube.com/watch?v="+ID,"https://www.youtube.com/live/"+ID)) assertEquals(URL,UrlSongImporter.canonicalUrl(input));
        for(String input:List.of("--exec bad","file:///tmp/song","https://youtube.com.evil.test/watch?v="+ID,"https://www.youtube.com/playlist?list=PLabc","https://www.youtube.com@evil.test/watch?v="+ID,"https://youtu.be/../bad","https://youtube.com:443/watch?v="+ID,"https://youtu.be/abc")) assertThrows(IllegalArgumentException.class,() -> UrlSongImporter.canonicalUrl(input),input);
    }
    @Test void separatesAudioAndArtworkWithLiteralArgumentsAndCopiesAudioWhenEmbedding() {
        var tools=new UrlSongImporter.Tools(List.of("C:\\Tools with spaces\\yt-dlp.exe"),temp.resolve("Tools with spaces"),"deno:C:\\Tools with spaces\\deno.exe");
        List<String> args=UrlSongImporter.command(tools,temp.resolve("Songs with spaces"),URL);
        assertEquals(tools.downloader().get(0),args.get(0)); assertEquals(URL,args.get(args.size()-1)); assertEquals("--",args.get(args.size()-2));
        for(String flag:List.of("--ignore-config","--no-plugin-dirs","--no-playlist","--no-embed-thumbnail","--no-write-thumbnail","--extract-audio","--write-info-json")) assertTrue(args.contains(flag));
        assertFalse(args.contains("--embed-thumbnail")); assertFalse(args.contains("--write-thumbnail"));
        assertEquals("mp3",args.get(args.indexOf("--audio-format")+1));
        assertEquals(temp.resolve("Songs with spaces").toString(),args.get(args.indexOf("--paths")+1));
        var thumbnail=UrlSongImporter.thumbnailCommand(tools,temp,URL,temp.resolve("video.info.json"));
        assertTrue(thumbnail.contains("--skip-download")); assertTrue(thumbnail.contains("--write-thumbnail"));
        assertFalse(thumbnail.contains("--extract-audio")); assertFalse(thumbnail.contains(URL));
        assertEquals("jpg",thumbnail.get(thumbnail.indexOf("--convert-thumbnails")+1));
        assertEquals(temp.resolve("video.info.json").toString(),thumbnail.get(thumbnail.indexOf("--load-info-json")+1));
        var cover=UrlSongImporter.coverCommand(tools,temp.resolve("input.mp3"),temp.resolve("cover.jpg"),temp.resolve("output.mp3"));
        assertEquals("copy",cover.get(cover.indexOf("-c")+1)); assertEquals("0",cover.get(cover.indexOf("-map_metadata")+1));
        assertTrue(cover.contains("attached_pic")); assertEquals(temp.resolve("output.mp3").toString(),cover.get(cover.size()-1));
    }
    @Test void importsRealMp3AndKeepsExistingFileOnRepeatedImports() throws Exception {
        try(var importer=importer("success")) {
            assertTrue(importer.start(URL)); until(() -> !importer.status().busy());
            assertEquals("urlSuccess",importer.status().key()); Path first=importer.consumeCompleted(); assertNotNull(first); assertClean();
            byte[] original=Files.readAllBytes(first); assertTrue(AudioDecoder.duration(Track.fromPath(first.getParent(),first))>1.9);
            assertNotNull(AlbumArt.read(first));
            assertTrue(importer.start(URL)); until(() -> !importer.status().busy());
            assertEquals("urlDuplicate",importer.status().key()); assertNull(importer.consumeCompleted());
            assertArrayEquals(original,Files.readAllBytes(first));
            try(var files=Files.list(first.getParent())) { assertEquals(1,files.count()); }
            var cues=new java.util.concurrent.atomic.AtomicInteger(); importer.errors.drain(cues::incrementAndGet); importer.errors.drain(cues::incrementAndGet); assertEquals(1,cues.get()); assertClean();
        }
    }
    @Test void failedDownloadCleansPartialsAndReportsErrorWithoutImporting() throws Exception {
        try(var importer=importer("fail")) {
            importer.start(URL); until(() -> !importer.status().busy());
            assertEquals("urlDownloadFailed",importer.status().key()); assertEquals("Test download rejected",importer.status().detail()); assertNull(importer.consumeCompleted()); assertFalse(Files.exists(temp.resolve("music-fail"))); assertClean();
            var cues=new java.util.concurrent.atomic.AtomicInteger(); importer.errors.drain(cues::incrementAndGet); assertEquals(1,cues.get());
        }
    }
    @Test void cancellationStopsTheSubprocessAndRejectsConcurrentImports() throws Exception {
        try(var importer=importer("hang")) {
            importer.start(URL);
            until(() -> { try(var dirs=Files.list(temp.resolve("jobs"))) { return dirs.anyMatch(p -> Files.exists(p.resolve("ready"))); } catch(Exception e) { return false; } });
            assertFalse(importer.start(URL)); importer.cancel(); until(() -> !importer.status().busy());
            assertEquals("urlCancelled",importer.status().key()); assertNull(importer.consumeCompleted()); assertClean();
            importer.errors.drain(() -> fail("Cancellation is not an error"));
        }
    }
    @Test void rejectsCorruptConverterOutputAndAllowsRetry() throws Exception {
        try(var importer=importer("corrupt")) {
            assertFalse(importer.start("invalid")); assertEquals("urlInvalid",importer.status().key());
            assertTrue(importer.start(URL)); until(() -> !importer.status().busy());
            assertEquals("urlNoAudio",importer.status().key()); assertNull(importer.consumeCompleted()); assertClean();
            var cues=new java.util.concurrent.atomic.AtomicInteger(); importer.errors.drain(cues::incrementAndGet); assertEquals(2,cues.get());
        }
    }
    @Test void thumbnailFailureMissingImageCorruptImageAndEmbeddingFailureUseDefaultCover() throws Exception {
        for(String mode:List.of("coverfail","covermissing","covercorrupt","embedfirstfail")) {
            try(var importer=importer(mode)) {
                assertTrue(importer.start(URL)); until(() -> !importer.status().busy());
                assertEquals("urlSuccessFallback",importer.status().key(),mode); Path song=importer.consumeCompleted(); assertNotNull(song,mode);
                assertTrue(AudioDecoder.duration(Track.fromPath(song.getParent(),song))>1.9);
                AlbumArt.Picture art=AlbumArt.read(song); assertNotNull(art); assertEquals(0xFF000000,art.pixels()[0]);
                assertTrue(java.util.Arrays.stream(art.pixels()).filter(p -> (p&0xFFFFFF)>0xEEEEEE).count()>200,mode);
                assertTrue((art.pixels()[60*128+61]&0xFF)>230,mode);
                assertClean();
                var cues=new java.util.concurrent.atomic.AtomicInteger(); importer.errors.drain(cues::incrementAndGet); importer.errors.drain(cues::incrementAndGet); assertEquals(1,cues.get(),mode);
            }
        }
    }
    @Test void evenDefaultEmbeddingFailureKeepsAudioAndUsesTheUiDefaultCover() throws Exception {
        try(var importer=importer("embedfail")) {
            importer.start(URL); until(() -> !importer.status().busy()); Path song=importer.consumeCompleted(); assertNotNull(song);
            assertEquals("urlSuccessFallback",importer.status().key()); assertNull(AlbumArt.read(song));
            assertTrue(AudioDecoder.duration(Track.fromPath(song.getParent(),song))>1.9);
            Config.load(temp.resolve("config"),temp); Config.get().showAlbumArt=true;
            try(AlbumArt reader=new AlbumArt()) { Track track=Track.fromPath(song.getParent(),song); until(() -> reader.get(track)!=null); assertSame(AlbumArt.fallback(),reader.get(track)); }
            assertClean();
        }
    }
    @Test void cancellationDuringThumbnailAndEmbeddingStepsNeverImportsTheAudio() throws Exception {
        for(String mode:List.of("coverhang","embedhang")) {
            try(var importer=importer(mode)) {
                importer.start(URL);
                until(() -> { try(var files=Files.walk(temp.resolve("jobs"))) { return files.anyMatch(p -> p.getFileName().toString().equals("ready-cover")); } catch(Exception e) { return false; } });
                importer.cancel(); until(() -> !importer.status().busy());
                assertEquals("urlCancelled",importer.status().key()); assertNull(importer.consumeCompleted()); assertClean();
            }
        }
    }
    @Test void media403RemainsAnAudioDownloadErrorRatherThanAFalseArtworkSuccess() throws Exception {
        try(var importer=importer("audio403")) {
            importer.start(URL); until(() -> !importer.status().busy());
            assertEquals("urlDownloadFailed",importer.status().key()); assertTrue(importer.status().detail().contains("unable to download video data: HTTP Error 403"));
            assertNull(importer.consumeCompleted()); assertFalse(Files.exists(temp.resolve("music-audio403"))); assertClean();
        }
    }
    @Test void differentVideoWithSameArtistAndTitleIsRejectedAfterDownload() throws Exception {
        try(var importer=importer("success")) {
            importer.start(URL); until(() -> !importer.status().busy()); Path first=importer.consumeCompleted(); byte[] before=Files.readAllBytes(first);
            assertTrue(importer.start("https://youtu.be/lmnopqrstuv")); until(() -> !importer.status().busy());
            assertEquals("urlDuplicate",importer.status().key()); assertNull(importer.consumeCompleted());
            assertArrayEquals(before,Files.readAllBytes(first));
            try(var files=Files.list(first.getParent())) { assertEquals(1,files.count()); }
            assertClean();
        }
    }
}
