package io.github.cloraj1.jukebox;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.UUID;
import java.util.Map;
import java.util.LinkedHashMap;

/** Adapted from AfrikalifyConfig (MIT, BestOfAfrika). */
public final class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path file;
    private static Data data = new Data();
    public static final class Data {
        public String musicFolder;
        public int volume = 80;
        public boolean shuffle = false;
        public String repeat = "OFF";
        public Set<String> liked = new LinkedHashSet<>();
        public List<Playlist> playlists = new ArrayList<>();
        public Map<String,SongInfo> songInfo = new LinkedHashMap<>();
        public Map<String,List<String>> songOrder = new LinkedHashMap<>();
        public boolean playbackNotifications = true;
        public boolean alwaysPlaybackNotification = false;
        public boolean showAlbumArt = false;
        public boolean broadcastToVoiceChat = false;
        public int voiceMusicVolume = 45;
        public int softness = 55;
    }
    public static final class Playlist {
        public String id;
        public String name;
        public List<String> tracks = new ArrayList<>();
        public Playlist() {}
        public Playlist(String name) { this.id=UUID.randomUUID().toString(); this.name=name; }
    }
    /** Display overrides keyed by the unchanged audio path, alongside playlist/favorite IDs. */
    public static final class SongInfo {
        public String title;
        public String artist;
        public SongInfo() {}
        public SongInfo(String title,String artist) { this.title=title; this.artist=artist; }
    }
    public static void load(Path configDir,Path gameDir) {
        data = new Data();
        file = configDir.resolve("jukebox.json");
        Path legacy=configDir.resolve("musiccraft.json");
        Path readFile=Files.isRegularFile(file)?file:legacy;
        if (Files.isRegularFile(readFile)) {
            try (var reader = Files.newBufferedReader(readFile)) {
                Data loaded = GSON.fromJson(reader,Data.class);
                if (loaded != null) data = loaded;
            } catch (Exception e) { System.err.println("JukeBox: invalid config, using defaults: " + e.getMessage()); }
        }
        Path defaultMusic=gameDir.resolve("jukebox-music");
        if(!Files.isDirectory(defaultMusic) && Files.isDirectory(gameDir.resolve("musiccraft-music"))) defaultMusic=gameDir.resolve("musiccraft-music");
        if (data.musicFolder == null || data.musicFolder.isBlank()) data.musicFolder = defaultMusic.toAbsolutePath().toString();
        try { Path.of(data.musicFolder); } catch (RuntimeException e) { data.musicFolder = defaultMusic.toAbsolutePath().toString(); }
        data.volume = Maths.clamp(data.volume,0,100);
        data.voiceMusicVolume = Maths.clamp(data.voiceMusicVolume,0,100);
        data.softness = Maths.clamp(data.softness,0,100);
        if (data.liked == null) data.liked = new LinkedHashSet<>();
        if (data.songInfo == null) data.songInfo = new LinkedHashMap<>();
        if (data.songOrder == null) data.songOrder = new LinkedHashMap<>();
        data.songOrder.entrySet().removeIf(e -> e.getKey()==null || e.getValue()==null);
        data.songOrder.replaceAll((key,order) -> new ArrayList<>(order.stream().filter(s -> s!=null && !s.isBlank()).distinct().toList()));
        data.songInfo.entrySet().removeIf(e -> e.getKey()==null || e.getKey().isBlank() || e.getValue()==null || e.getValue().title==null || e.getValue().title.isBlank());
        for (SongInfo info:data.songInfo.values()) {
            info.title=info.title.strip();
            info.artist=info.artist==null?"":info.artist.strip();
        }
        if (data.playlists == null) data.playlists = new ArrayList<>();
        data.playlists.removeIf(p -> p == null || p.name == null || p.name.isBlank());
        Set<String> ids=new HashSet<>();
        for (Playlist playlist:data.playlists) {
            if (playlist.id == null || playlist.id.isBlank() || !ids.add(playlist.id)) {
                playlist.id=UUID.randomUUID().toString(); ids.add(playlist.id);
            }
            playlist.name=playlist.name.strip();
            if (playlist.tracks == null) playlist.tracks=new ArrayList<>();
            playlist.tracks=new ArrayList<>(playlist.tracks.stream().filter(s -> s != null && !s.isBlank()).distinct().toList());
        }
        if (!Set.of("OFF","ALL","ONE").contains(data.repeat == null ? "" : data.repeat)) data.repeat = "OFF";
        save();
    }
    public static Data get() { return data; }
    public static Path folder() { return Path.of(data.musicFolder).toAbsolutePath().normalize(); }
    public static Path toolsFolder() {
        Path current=file.getParent().resolve("jukebox/tools"),legacy=file.getParent().resolve("musiccraft/tools");
        return (Files.isDirectory(current) || !Files.isDirectory(legacy)?current:legacy).toAbsolutePath().normalize();
    }
    public static void save() {
        try { saveChecked(); }
        catch (Exception e) { System.err.println("JukeBox: could not save config: " + e); }
    }
    static void saveChecked() throws java.io.IOException {
        if (file == null) return;
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(),".jukebox-config-",".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temporary)) { GSON.toJson(data,writer); }
            PlaylistTransfer.replace(temporary,file);
        } finally { Files.deleteIfExists(temporary); }
    }
}
