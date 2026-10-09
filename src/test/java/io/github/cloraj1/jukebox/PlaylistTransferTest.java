package io.github.cloraj1.jukebox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PlaylistTransferTest {
    @TempDir Path temp;
    private Path music() throws IOException {
        Config.load(temp.resolve("config"),temp);
        Path folder=temp.resolve("music"); Files.createDirectories(folder.resolve("한글"));
        try(var stream=getClass().getResourceAsStream("/tone.wav")) { Files.copy(stream,folder.resolve("한글/노래.wav")); }
        try(var stream=getClass().getResourceAsStream("/tone.wav")) { Files.copy(stream,folder.resolve("Other.wav")); }
        return folder;
    }
    private Config.Playlist playlist(Path folder,String name,String... tracks) {
        Config.Playlist list=new Config.Playlist(name);
        for(String track:tracks) list.tracks.add(folder.resolve(track).toAbsolutePath().normalize().toString());
        return list;
    }
    private String valid() { return "{\"format\":\"jukebox-playlists\",\"version\":1,\"playlists\":[{\"name\":\"Mix\",\"tracks\":[\"Other.wav\"]}]}"; }
    @Test void portableRoundTripPreservesOrderUnicodeSettingsAndAudio() throws Exception {
        Path folder=music(); byte[] audio=Files.readAllBytes(folder.resolve("한글/노래.wav"));
        Config.get().playlists.add(playlist(folder,"여행 🎵","한글/노래.wav","Other.wav"));
        Config.get().playlists.add(playlist(folder,"Empty"));
        String json=PlaylistTransfer.snapshot(folder,Config.get().playlists);
        assertTrue(json.contains("한글/노래.wav")); assertFalse(json.contains(folder.toString()));
        Path export=temp.resolve("My playlists.json"); PlaylistTransfer.write(export,json);
        Path destination=temp.resolve("different-music"); Files.createDirectories(destination.resolve("한글"));
        Files.copy(folder.resolve("한글/노래.wav"),destination.resolve("한글/노래.wav")); Files.copy(folder.resolve("Other.wav"),destination.resolve("Other.wav"));
        var imported=PlaylistTransfer.read(export,destination); assertEquals(2,imported.size());
        assertEquals(List.of(destination.resolve("한글/노래.wav").toString(),destination.resolve("Other.wav").toString()),imported.get(0).tracks());
        String oldId=Config.get().playlists.get(0).id; Config.get().volume=23; Config.get().showAlbumArt=true;
        Config.get().liked.add("preserved"); Config.get().songInfo.put("song",new Config.SongInfo("Edited","Artist"));
        assertEquals(2,PlaylistTransfer.apply(destination,imported));
        assertEquals(oldId,Config.get().playlists.get(0).id); assertNotEquals(oldId,Config.get().playlists.get(2).id);
        Config.load(temp.resolve("config"),temp); assertEquals(4,Config.get().playlists.size()); assertEquals(23,Config.get().volume);
        assertTrue(Config.get().showAlbumArt); assertTrue(Config.get().liked.contains("preserved")); assertEquals("Edited",Config.get().songInfo.get("song").title);
        assertArrayEquals(audio,Files.readAllBytes(folder.resolve("한글/노래.wav"))); assertArrayEquals(audio,Files.readAllBytes(destination.resolve("한글/노래.wav")));
    }
    @Test void rejectsWrongStructureTypesDuplicateFieldsAndNonJsonSyntax() throws Exception {
        Path folder=music();
        List<String> bad=List.of("", "[]", "null", "{}", valid().replace("jukebox-playlists","another-mod"),
            valid().replace("\"version\":1","\"version\":2"),valid().replace("\"version\":1","\"version\":\"1\""),
            valid().replace("\"version\":1","\"version\":1.0"),valid().replace("\"version\":1","\"version\":1,\"version\":1"),
            valid().replace("\"playlists\":","\"extra\":true,\"playlists\":"),valid().replace("\"tracks\":[\"Other.wav\"]","\"tracks\":null"),
            valid().replace("\"name\":\"Mix\"","\"name\":123"),valid().replace("\"name\":\"Mix\"","\"name\":null"),
            valid().replace("\"name\":\"Mix\"","\"name\":\"Mix\",\"name\":\"Other\""),
            valid().replace("\"tracks\":[\"Other.wav\"]","\"tracks\":[123]"),valid().replace("\"tracks\":[\"Other.wav\"]","\"tracks\":[true]"),
            valid().replace("\"name\":\"Mix\",",""),valid().replace("\"tracks\":[\"Other.wav\"]","\"extra\":0,\"tracks\":[\"Other.wav\"]"),
            valid()+"{}",valid().replace("{\"format\"","{/*comment*/\"format\""),valid().replace("\"Other.wav\"]","\"Other.wav\",]"));
        for(String json:bad) assertThrows(PlaylistTransfer.InvalidFile.class,() -> PlaylistTransfer.parse(json,folder),json);
    }
    @Test void rejectsInvalidNamesDuplicateListsAndInvalidSongReferences() throws Exception {
        Path folder=music(); Files.createDirectory(folder.resolve("directory.wav"));
        for(String path:List.of("missing.wav","../Other.wav","/Other.wav","C:/Other.wav","./Other.wav","dir//Other.wav","Other.txt","directory.wav","","Other.wav/","dir\\Other.wav")) {
            String json=valid().replace("Other.wav",path.replace("\\","\\\\"));
            assertThrows(PlaylistTransfer.InvalidFile.class,() -> PlaylistTransfer.parse(json,folder),path);
        }
        for(String title:List.of(""," "," Mix ","a".repeat(65),"bad\\nname")) assertThrows(PlaylistTransfer.InvalidFile.class,() -> PlaylistTransfer.parse(valid().replace("\"Mix\"","\""+title+"\""),folder));
        assertThrows(PlaylistTransfer.InvalidFile.class,() -> PlaylistTransfer.parse(valid().replace("[\"Other.wav\"]","[\"Other.wav\",\"Other.wav\"]"),folder));
        String duplicate=valid().replace("}]}" ,"},{\"name\":\"mix\",\"tracks\":[]}]}" );
        assertThrows(PlaylistTransfer.InvalidFile.class,() -> PlaylistTransfer.parse(duplicate,folder));
    }
    @Test void badLaterPlaylistCancelsEverythingWithoutTouchingSavedConfig() throws Exception {
        Path folder=music(); Config.get().playlists.add(playlist(folder,"Original","Other.wav")); Config.save();
        byte[] before=Files.readAllBytes(temp.resolve("config/jukebox.json")); String oldId=Config.get().playlists.get(0).id;
        Path file=temp.resolve("bad.json"); Files.writeString(file,valid().replace("}]}","},{\"name\":\"Bad\",\"tracks\":[\"missing.mp3\"]}]}"));
        try(MusicSession session=new MusicSession(folder); PlaylistFiles files=new PlaylistFiles(session,Runnable::run,Runnable::run,(export,title,filter,suggested) -> file)) {
            files.request(false,k -> k); assertEquals("playlistInvalid",files.status().key()); assertTrue(files.status().error()); assertFalse(files.busy());
            assertEquals(1,Config.get().playlists.size()); assertEquals(oldId,Config.get().playlists.get(0).id);
            assertArrayEquals(before,Files.readAllBytes(temp.resolve("config/jukebox.json")));
        }
    }
    @Test void rejectsOversizedFilesInvalidUtf8AndTooManyPlaylists() throws Exception {
        Path folder=music(); Path file=temp.resolve("large.json"); Files.write(file,new byte[PlaylistTransfer.MAX_BYTES+1]);
        assertThrows(PlaylistTransfer.InvalidFile.class,() -> PlaylistTransfer.read(file,folder)); Files.write(file,new byte[]{(byte)0xC3,(byte)0x28});
        assertThrows(PlaylistTransfer.InvalidFile.class,() -> PlaylistTransfer.read(file,folder));
        List<Config.Playlist> lists=new ArrayList<>(); for(int i=0;i<=PlaylistTransfer.MAX_PLAYLISTS;i++) lists.add(playlist(folder,"List "+i));
        assertThrows(PlaylistTransfer.InvalidFile.class,() -> PlaylistTransfer.snapshot(folder,lists));
        String items=java.util.stream.IntStream.rangeClosed(0,PlaylistTransfer.MAX_PLAYLISTS).mapToObj(i -> "{\"name\":\"List "+i+"\",\"tracks\":[]}").collect(java.util.stream.Collectors.joining(","));
        assertThrows(PlaylistTransfer.InvalidFile.class,() -> PlaylistTransfer.parse("{\"format\":\"jukebox-playlists\",\"version\":1,\"playlists\":["+items+"]}",folder));
    }
    @Test void collisionNamesUseSuffixWithinCharacterLimitAndFreshIds() throws Exception {
        Path folder=music(); String name="🎵".repeat(64);
        var first=playlist(folder,name); Config.get().playlists.add(first);
        var entries=List.of(new PlaylistTransfer.Entry(name,List.of(folder.resolve("Other.wav").toString())));
        PlaylistTransfer.apply(folder,entries); PlaylistTransfer.apply(folder,entries);
        assertTrue(Config.get().playlists.get(1).name.endsWith(" (2)")); assertTrue(Config.get().playlists.get(2).name.endsWith(" (3)"));
        assertEquals(64,Config.get().playlists.get(1).name.codePointCount(0,Config.get().playlists.get(1).name.length()));
        assertEquals(3,Config.get().playlists.stream().map(p -> p.id).distinct().count()); assertSame(first,Config.get().playlists.get(0));
    }
    @Test void failedConfigSaveRollsBackAllImportedLists() throws Exception {
        Path folder=music(); Config.get().playlists.add(playlist(folder,"Original")); Config.save();
        var old=Config.get().playlists; var entries=PlaylistTransfer.parse(valid(),folder);
        Path config=temp.resolve("config/jukebox.json"); Files.delete(config); Files.createDirectory(config); Files.writeString(config.resolve("sentinel"),"keep");
        assertThrows(IOException.class,() -> PlaylistTransfer.apply(folder,entries)); assertSame(old,Config.get().playlists);
        assertEquals("keep",Files.readString(config.resolve("sentinel"))); assertEquals(1,old.size());
    }
    @Test void vanishedAudioBeforeClientCommitCancelsImport() throws Exception {
        Path folder=music(); var old=Config.get().playlists; var entries=PlaylistTransfer.parse(valid(),folder);
        Files.delete(folder.resolve("Other.wav")); assertThrows(PlaylistTransfer.InvalidFile.class,() -> PlaylistTransfer.apply(folder,entries)); assertSame(old,Config.get().playlists);
    }
    @Test void exportUsesSelectedNameAndCancelLeavesExistingFileUntouched() throws Exception {
        Path folder=music(); Config.get().playlists.add(playlist(folder,"Mix","Other.wav")); Path file=temp.resolve("Chosen name.json"); Files.writeString(file,"old");
        try(MusicSession session=new MusicSession(folder); PlaylistFiles files=new PlaylistFiles(session,Runnable::run,Runnable::run,(export,title,filter,suggested) -> {
            assertTrue(export); assertEquals("exportPlaylist",title); assertEquals("playlistFileType",filter); assertEquals("JukeBox-playlists.json",suggested.getFileName().toString()); return file;
        })) {
            files.request(true,k -> k); assertEquals("playlistExportSuccess",files.status().key()); assertEquals("Mix",PlaylistTransfer.read(file,folder).get(0).name());
            String before=Files.readString(file);
            try(PlaylistFiles cancelled=new PlaylistFiles(session,Runnable::run,Runnable::run,(export,title,filter,suggested) -> null)) {
                cancelled.request(true,k -> k); assertEquals("playlistTransferCancelled",cancelled.status().key()); assertEquals(before,Files.readString(file));
                cancelled.request(false,k -> k); assertEquals("playlistTransferCancelled",cancelled.status().key()); assertEquals(1,Config.get().playlists.size());
            }
        }
        try(var files=Files.list(temp)) { assertFalse(files.anyMatch(p -> p.getFileName().toString().startsWith(".jukebox-playlists-"))); }
    }
    @Test void importReturnsToClientExecutorAndPreventsOverlappingDialogs() throws Exception {
        Path folder=music(),file=temp.resolve("import.json"); Files.writeString(file,valid());
        List<Runnable> background=new ArrayList<>(),client=new ArrayList<>(); AtomicInteger chosen=new AtomicInteger();
        try(MusicSession session=new MusicSession(folder); PlaylistFiles files=new PlaylistFiles(session,client::add,background::add,(export,title,filter,suggested) -> { chosen.incrementAndGet(); return file; })) {
            files.request(false,k -> k); files.request(true,k -> k); assertEquals(1,background.size()); assertTrue(files.busy());
            background.remove(0).run(); assertEquals(1,chosen.get()); assertTrue(Config.get().playlists.isEmpty()); assertTrue(files.busy());
            client.remove(0).run(); assertFalse(files.busy()); assertEquals("playlistImportSuccess",files.status().key()); assertEquals(1,Config.get().playlists.size());
            files.request(false,k -> k); background.remove(0).run(); files.close(); client.remove(0).run(); assertEquals(1,Config.get().playlists.size());
        }
    }
    @Test void translatedSettingsButtonsStayInsideSmallDialogsAndClickOnce() throws Exception {
        Path folder=music(); AtomicInteger picked=new AtomicInteger(),sounds=new AtomicInteger();
        try(MusicSession session=new MusicSession(folder); PlaylistFiles files=new PlaylistFiles(session,Runnable::run,Runnable::run,(export,title,filter,suggested) -> { picked.incrementAndGet(); return null; })) {
            for(String lang:List.of("en_us","ko_kr")) try(var language=new TestLanguage(lang)) {
                for(int[] size:new int[][]{{320,240},{480,270},{854,480}}) {
                    MusicView view=new MusicView(session,k -> net.minecraft.network.chat.Component.translatable("jukebox."+k).getString(),sounds::incrementAndGet,files); view.resize(size[0],size[1]);
                    List<String> labels=new ArrayList<>(); MusicView.Canvas canvas=new MusicView.Canvas() {
                        public void fill(int x1,int y1,int x2,int y2,int color) { assertTrue(x1>=0 && y1>=0 && x2<=size[0] && y2<=size[1]); }
                        public void text(String text,int x,int y,int color) { labels.add(text); }
                        public int width(String text) { return text.codePointCount(0,text.length())*5; }
                    };
                    view.draw(canvas,-1,-1); assertTrue(view.click(size[0]-17,17,0)); view.draw(canvas,-1,-1);
                    assertTrue(labels.contains(lang.equals("en_us")?"Import Playlist":"재생목록 가져오기")); assertTrue(labels.contains(lang.equals("en_us")?"Export Playlist":"재생목록 내보내기"));
                    int x=(size[0]-Math.min(360,size[0]-24))/2,y=(size[1]-Math.min(210,size[1]-36))/2;
                    int before=sounds.get(),count=picked.get(); assertTrue(view.click(x+30,y+140,0)); view.draw(canvas,-1,-1); assertTrue(view.click(x+Math.min(360,size[0]-24)-30,y+140,0));
                    assertEquals(before+2,sounds.get()); assertEquals(count+2,picked.get()); assertTrue(view.dialogOpen());
                }
            }
        }
    }
}
