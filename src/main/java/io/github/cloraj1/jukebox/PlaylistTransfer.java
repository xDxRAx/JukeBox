package io.github.cloraj1.jukebox;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Versioned, portable playlist references. Audio files are never included or modified. */
public final class PlaylistTransfer {
    static final int MAX_BYTES=4*1024*1024,MAX_PLAYLISTS=256,MAX_TRACKS=10000;
    public record Entry(String name,List<String> tracks) {
        public Entry { tracks=List.copyOf(tracks); }
    }
    public static final class InvalidFile extends IOException {
        InvalidFile() { super("Invalid JukeBox playlist format or song reference"); }
    }
    private static InvalidFile invalid() { return new InvalidFile(); }
    private static String name(String value) throws InvalidFile {
        if(value==null || value.isBlank() || !value.equals(value.strip()) || value.codePointCount(0,value.length())>64 || value.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        return value;
    }
    private static Path track(Path folder,String value,boolean requireAudio) throws IOException {
        if(value==null || value.isBlank() || value.length()>1024 || value.startsWith("/") || value.contains("\\") || value.contains(":") || value.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        for(String part:value.split("/",-1)) if(part.isEmpty() || part.equals(".") || part.equals("..")) throw invalid();
        String lower=value.toLowerCase(java.util.Locale.ROOT);
        if(!lower.endsWith(".mp3") && !lower.endsWith(".wav")) throw invalid();
        Path root=folder.toAbsolutePath().normalize(),path;
        try { path=root.resolve(value).normalize(); } catch(RuntimeException e) { throw invalid(); }
        if(!path.startsWith(root)) throw invalid();
        if(requireAudio && (!Files.isRegularFile(path) || !path.toRealPath().startsWith(root.toRealPath()))) throw invalid();
        return path;
    }
    public static String snapshot(Path folder,List<Config.Playlist> playlists) throws IOException {
        if(playlists.size()>MAX_PLAYLISTS) throw invalid();
        Path root=folder.toAbsolutePath().normalize(); JsonArray lists=new JsonArray();
        List<String> names=new ArrayList<>(); int total=0;
        for(Config.Playlist playlist:playlists) {
            String clean=name(playlist.name);
            if(names.stream().anyMatch(n -> n.equalsIgnoreCase(clean))) throw invalid(); names.add(clean);
            JsonObject object=new JsonObject(); object.addProperty("name",clean); JsonArray songs=new JsonArray(); Set<Path> seen=new HashSet<>();
            for(String id:playlist.tracks) {
                if(++total>MAX_TRACKS) throw invalid();
                Path absolute;
                try { absolute=Path.of(id).toAbsolutePath().normalize(); } catch(RuntimeException e) { throw invalid(); }
                if(!absolute.startsWith(root)) throw invalid();
                String relative=root.relativize(absolute).toString().replace('\\','/');
                Path key=track(root,relative,false); if(!seen.add(key)) throw invalid();
                songs.add(relative);
            }
            object.add("tracks",songs); lists.add(object);
        }
        JsonObject object=new JsonObject(); object.addProperty("format","jukebox-playlists"); object.addProperty("version",1); object.add("playlists",lists);
        String json=new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(object)+"\n";
        if(json.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES) throw invalid();
        return json;
    }
    public static List<Entry> read(Path file,Path folder) throws IOException {
        // Bound the actual read as well as the reported size (the file may grow after selection).
        byte[] bytes;
        try(var input=Files.newInputStream(file)) { bytes=input.readNBytes(MAX_BYTES+1); }
        if(bytes.length>MAX_BYTES) throw invalid();
        String json;
        try { json=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString(); }
        catch(java.nio.charset.CharacterCodingException e) { throw invalid(); }
        return parse(json,folder);
    }
    static List<Entry> parse(String json,Path folder) throws IOException {
        if(json.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES) throw invalid();
        List<Entry> entries=new ArrayList<>(); Set<String> fields=new HashSet<>(); int total=0;
        try(JsonReader reader=new JsonReader(new StringReader(json))) {
            reader.setLenient(false); require(reader,JsonToken.BEGIN_OBJECT); reader.beginObject();
            while(reader.hasNext()) {
                String field=reader.nextName(); if(!fields.add(field)) throw invalid();
                switch(field) {
                    case "format" -> { if(!Set.of("jukebox-playlists","musiccraft-playlists").contains(string(reader))) throw invalid(); }
                    case "version" -> { require(reader,JsonToken.NUMBER); if(!reader.nextString().equals("1")) throw invalid(); }
                    case "playlists" -> {
                        require(reader,JsonToken.BEGIN_ARRAY); reader.beginArray();
                        while(reader.hasNext()) {
                            if(entries.size()>=MAX_PLAYLISTS) throw invalid();
                            require(reader,JsonToken.BEGIN_OBJECT); reader.beginObject(); Set<String> itemFields=new HashSet<>(); String title=null; List<String> songs=new ArrayList<>(); Set<Path> seen=new HashSet<>();
                            while(reader.hasNext()) {
                                String item=reader.nextName(); if(!itemFields.add(item)) throw invalid();
                                switch(item) {
                                    case "name" -> title=name(string(reader));
                                    case "tracks" -> {
                                        require(reader,JsonToken.BEGIN_ARRAY); reader.beginArray();
                                        while(reader.hasNext()) {
                                            if(++total>MAX_TRACKS) throw invalid();
                                            Path song=track(folder,string(reader),true); if(!seen.add(song)) throw invalid(); songs.add(song.toString());
                                        }
                                        reader.endArray();
                                    }
                                    default -> throw invalid();
                                }
                            }
                            reader.endObject(); if(!itemFields.equals(Set.of("name","tracks"))) throw invalid();
                            String clean=title; if(entries.stream().anyMatch(e -> e.name().equalsIgnoreCase(clean))) throw invalid();
                            entries.add(new Entry(title,songs));
                        }
                        reader.endArray();
                    }
                    default -> throw invalid();
                }
            }
            reader.endObject(); if(!fields.equals(Set.of("format","version","playlists")) || reader.peek()!=JsonToken.END_DOCUMENT) throw invalid();
            return List.copyOf(entries);
        } catch(InvalidFile e) { throw e; }
        catch(IOException | RuntimeException e) { throw invalid(); }
    }
    private static void require(JsonReader reader,JsonToken token) throws IOException { if(reader.peek()!=token) throw invalid(); }
    private static String string(JsonReader reader) throws IOException { require(reader,JsonToken.STRING); return reader.nextString(); }
    public static void write(Path file,String json) throws IOException {
        Path destination=file.toAbsolutePath().normalize(),temporary=Files.createTempFile(destination.getParent(),".jukebox-playlists-",".tmp");
        try {
            Files.writeString(temporary,json,StandardCharsets.UTF_8);
            replace(temporary,destination);
        } finally { Files.deleteIfExists(temporary); }
    }
    static void replace(Path temporary,Path destination) throws IOException {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
        while(true) {
            try {
                try { Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
                catch(AtomicMoveNotSupportedException e) { Files.move(temporary,destination,StandardCopyOption.REPLACE_EXISTING); }
                return;
            } catch(java.nio.file.FileSystemException e) {
                // Windows can briefly hold a just-replaced config/export file.
                // Genuine invalid destinations still fail without changing the old data.
                if(!System.getProperty("os.name").startsWith("Windows") || !Files.isRegularFile(temporary) || !Files.isRegularFile(destination) || System.nanoTime()>=deadline) throw e;
                try { Thread.sleep(25); }
                catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw e; }
            }
        }
    }
    static int apply(Path folder,List<Entry> entries) throws IOException {
        // Recheck references on the client thread, in case a selected file disappeared during the dialog.
        List<Config.Playlist> incoming=new ArrayList<>();
        for(Entry entry:entries) { Config.Playlist playlist=new Config.Playlist(entry.name()); playlist.tracks.addAll(entry.tracks()); incoming.add(playlist); }
        parse(snapshot(folder,incoming),folder);
        List<Config.Playlist> old=Config.get().playlists,next=new ArrayList<>(old);
        if((long)old.size()+entries.size()>MAX_PLAYLISTS || (long)old.stream().mapToInt(p -> p.tracks.size()).sum()+entries.stream().mapToInt(e -> e.tracks().size()).sum()>MAX_TRACKS) throw invalid();
        for(Entry entry:entries) {
            String title=entry.name(); int number=2;
            while(containsName(next,title)) {
                String suffix=" ("+(number++)+")"; int available=64-suffix.length();
                String base=entry.name(); title=base.substring(0,base.offsetByCodePoints(0,Math.min(available,base.codePointCount(0,base.length()))))+suffix;
            }
            Config.Playlist playlist=new Config.Playlist(title); playlist.tracks.addAll(entry.tracks()); next.add(playlist);
        }
        Config.get().playlists=next;
        try { Config.saveChecked(); } catch(IOException | RuntimeException e) { Config.get().playlists=old; throw e; }
        return entries.size();
    }
    private static boolean containsName(List<Config.Playlist> lists,String name) { return lists.stream().anyMatch(p -> p.name.equalsIgnoreCase(name)); }
}
