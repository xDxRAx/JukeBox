package io.github.cloraj1.jukebox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RebrandMigrationTest {
    @TempDir Path temp;
    @Test void migratesLegacyConfigAndKeepsAudioPathsPlaylistsOverridesOrdersAndTools() throws Exception {
        Path config=Files.createDirectory(temp.resolve("config")),music=Files.createDirectory(temp.resolve("musiccraft-music")),song=music.resolve("Artist - Track.wav");
        Files.writeString(song,"unchanged audio"); Path tools=Files.createDirectories(config.resolve("musiccraft/tools")); Files.writeString(tools.resolve("sentinel"),"cached");
        Config.load(config,temp); Config.get().volume=23; Config.get().showAlbumArt=true;
        Config.Playlist list=new Config.Playlist("My playlist"); list.tracks.add(song.toString()); Config.get().playlists.add(list);
        Config.get().liked.add(song.toString()); Config.get().songInfo.put(song.toString(),new Config.SongInfo("Display title","Display artist"));
        Config.get().songOrder.put("all",List.of(song.toString())); Config.save();
        Files.move(config.resolve("jukebox.json"),config.resolve("musiccraft.json")); byte[] legacy=Files.readAllBytes(config.resolve("musiccraft.json"));
        Config.load(config,temp);
        assertEquals(music,Config.folder()); assertEquals(23,Config.get().volume); assertTrue(Config.get().showAlbumArt);
        assertEquals(list.id,Config.get().playlists.get(0).id); assertEquals(List.of(song.toString()),Config.get().playlists.get(0).tracks);
        assertTrue(Config.get().liked.contains(song.toString())); assertEquals("Display title",Config.get().songInfo.get(song.toString()).title);
        assertEquals(List.of(song.toString()),Config.get().songOrder.get("all")); assertEquals(tools,Config.toolsFolder());
        assertArrayEquals(legacy,Files.readAllBytes(config.resolve("musiccraft.json"))); assertEquals("unchanged audio",Files.readString(song));
        Config.get().volume=66; Config.save(); Config.load(config,temp); assertEquals(66,Config.get().volume);
    }
    @Test void freshInstallsUseJukeBoxPathsAndAcceptLegacyPlaylistExports() throws Exception {
        Config.load(temp.resolve("config"),temp);
        assertEquals(temp.resolve("jukebox-music"),Config.folder()); assertEquals(temp.resolve("config/jukebox/tools"),Config.toolsFolder());
        assertTrue(Files.isRegularFile(temp.resolve("config/jukebox.json"))); assertFalse(Files.exists(temp.resolve("config/musiccraft.json")));
        Path music=Files.createDirectory(temp.resolve("jukebox-music")); Files.writeString(music.resolve("Track.mp3"),"fixture");
        String legacy="{\"format\":\"musiccraft-playlists\",\"version\":1,\"playlists\":[{\"name\":\"Old playlist\",\"tracks\":[\"Track.mp3\"]}]}";
        var entries=PlaylistTransfer.parse(legacy,music); PlaylistTransfer.apply(music,entries);
        assertEquals("Old playlist",Config.get().playlists.get(0).name);
        assertTrue(PlaylistTransfer.snapshot(music,Config.get().playlists).contains("jukebox-playlists"));
        assertThrows(PlaylistTransfer.InvalidFile.class,() -> PlaylistTransfer.parse(legacy.replace("musiccraft-playlists","unknown-playlists"),music));
    }
}
