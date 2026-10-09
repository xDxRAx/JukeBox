package io.github.cloraj1.jukebox;

import java.awt.Desktop;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Player, queue and library survive closing the screen. Methods run on the client thread. */
public final class MusicSession implements AutoCloseable {
    public static final int VOLUME_KEY_STEP=1;
    public final MusicPlayer player;
    public final UrlSongImporter urlImporter;
    private final java.util.concurrent.locks.ReentrantReadWriteLock fileAccess=new java.util.concurrent.locks.ReentrantReadWriteLock(true);
    public final AlbumArt albumArt = new AlbumArt(fileAccess.readLock());
    private final Path root;
    private final ExecutorService scanner=Executors.newSingleThreadExecutor(r -> { Thread t=new Thread(r,"JukeBox-Library"); t.setDaemon(true); return t; });
    private final ExecutorService metadata=Executors.newSingleThreadExecutor(r -> { Thread t=new Thread(r,"JukeBox-Metadata"); t.setDaemon(true); return t; });
    private volatile List<Track> library=List.of();
    private final Object libraryLock=new Object();
    private List<Track> displaySource,displayLibrary=List.of();
    private List<String> cachedLibraryOrder;
    private Config.Data displayConfig;
    private int songInfoRevision,cachedInfoRevision=-1;
    private volatile boolean loading;
    private boolean importReloadPending;
    private volatile String libraryError="";
    private final Map<String,Double> durations=new ConcurrentHashMap<>();
    private final ArrayDeque<Track> queue=new ArrayDeque<>();
    private final ArrayDeque<Track> history=new ArrayDeque<>();
    private List<Track> context=List.of();
    private int contextIndex=-1;
    private String contextPlaylist;
    private String contextPage;
    private final Random random;
    public MusicSession(Path root) { this(root,new MusicPlayer()); }
    public MusicSession(Path root,MusicPlayer player) { this(root,player,new Random()); }
    MusicSession(Path root,MusicPlayer player,Random random) { this.root=root; this.player=player; this.random=random; urlImporter=new UrlSongImporter(root,Config.toolsFolder()); reload(); }
    public List<Track> library() {
        List<Track> source=library;
        List<String> order=Config.get().songOrder.get("all");
        if(source!=displaySource || displayConfig!=Config.get() || cachedInfoRevision!=songInfoRevision || order!=cachedLibraryOrder) {
            displayLibrary=source.stream().map(this::displayTrack).sorted(java.util.Comparator.comparing(Track::title,String.CASE_INSENSITIVE_ORDER).thenComparing(Track::id)).toList();
            displayLibrary=ordered(displayLibrary,order); cachedLibraryOrder=order;
            displaySource=source; displayConfig=Config.get(); cachedInfoRevision=songInfoRevision;
        }
        return displayLibrary;
    }
    private static List<Track> ordered(List<Track> tracks,List<String> order) {
        if(order==null || order.isEmpty()) return tracks;
        Map<String,Integer> ranks=new java.util.HashMap<>(); for(int i=0;i<order.size();i++) ranks.putIfAbsent(order.get(i),i);
        return tracks.stream().sorted(java.util.Comparator.comparingInt(t -> ranks.getOrDefault(t.id(),Integer.MAX_VALUE))).toList();
    }
    public List<Track> tracksForPage(String page) {
        if(page.startsWith("list:")) return playlistTracks(page.substring(5));
        if(page.equals("queue")) return queue();
        List<Track> tracks=library();
        if(page.equals("liked")) tracks=tracks.stream().filter(this::liked).toList();
        else if(page.startsWith("folder:")) tracks=tracks.stream().filter(t -> t.collection().equals(page.substring(7))).toList();
        return page.equals("all")?tracks:ordered(tracks,Config.get().songOrder.get(page));
    }
    public void playPage(String page,int index,String query) {
        List<Track> tracks=tracksForPage(page); if(index<0 || index>=tracks.size()) return;
        Track track=tracks.get(index);
        if(page.equals("queue")) playQueue(index);
        else if(page.startsWith("list:")) playPlaylist(track,page.substring(5));
        else { play(track,MusicLibrary.filter(tracks,query)); contextPage=page; }
    }
    /** before is an insertion boundary in the full, unfiltered source; expected prevents stale drops. */
    public boolean moveSong(String page,int from,int before,List<Track> expected) throws java.io.IOException {
        List<Track> source=tracksForPage(page);
        if(!source.equals(expected) || from<0 || from>=source.size() || before<0 || before>source.size()) return false;
        int to=before>from?before-1:before; if(to==from) return true;
        List<Track> next=new ArrayList<>(source); Track moved=next.remove(from); next.add(to,moved);
        if(page.equals("queue")) { queue.clear(); queue.addAll(next); return true; }
        if(page.startsWith("list:")) {
            Config.Playlist p=playlist(page.substring(5)); List<String> old=p.tracks;
            // Keep missing file references in their original slots rather than discarding them.
            var available=new java.util.HashSet<>(source.stream().map(Track::id).toList()); var replacement=next.iterator();
            p.tracks=new ArrayList<>(old.stream().map(id -> available.contains(id)?replacement.next().id():id).toList());
            try { Config.saveChecked(); } catch(java.io.IOException e) { p.tracks=old; throw e; }
            refreshPlaylistContext(p.id);
        } else {
            List<String> old=Config.get().songOrder.put(page,new ArrayList<>(next.stream().map(Track::id).toList()));
            try { Config.saveChecked(); } catch(java.io.IOException e) { if(old==null) Config.get().songOrder.remove(page); else Config.get().songOrder.put(page,old); throw e; }
            if(page.equals(contextPage)) refreshContext(next.stream().filter(context::contains).toList());
        }
        return true;
    }
    public void deleteSong(Track track) throws java.io.IOException {
        Path folder=root.toAbsolutePath().normalize(),path=track.path().toAbsolutePath().normalize();
        if(!path.startsWith(folder) || !path.getParent().toRealPath().startsWith(folder.toRealPath())
            || !library.contains(track) || !Files.isRegularFile(path,java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw new java.io.IOException("Song file is unavailable");
        Track current=player.current(); boolean stopped=track.equals(current),paused=player.paused(); double position=player.position();
        try {
            player.releaseFile(track);
            boolean locked;
            try { locked=fileAccess.writeLock().tryLock(2,java.util.concurrent.TimeUnit.SECONDS); }
            catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException("Interrupted while closing metadata",e); }
            if(!locked) throw new java.io.IOException("Song metadata is still in use");
            try { synchronized(libraryLock) { Files.delete(path); library=library.stream().filter(t -> !t.equals(track)).toList(); } }
            finally { fileAccess.writeLock().unlock(); }
        } catch(java.io.IOException e) { if(stopped && player.current()==null && Files.isRegularFile(path)) player.restore(track,position,paused); throw e; }
        String id=track.id(); Config.get().liked.remove(id); Config.get().songInfo.remove(id);
        Config.get().songOrder.replaceAll((key,order) -> new ArrayList<>(order.stream().filter(s -> !s.equals(id)).toList()));
        Config.get().playlists.forEach(p -> p.tracks.removeIf(id::equals));
        queue.removeIf(track::equals); history.removeIf(track::equals); refreshContext(context.stream().filter(t -> !t.equals(track)).toList());
        durations.remove(id); albumArt.clear(); songInfoRevision++; Config.save();
    }
    private void refreshContext(List<Track> next) {
        Track anchor=contextIndex>=0 && contextIndex<context.size()?context.get(contextIndex):null;
        int index=next.indexOf(anchor);
        if(index<0) { index=-1; for(int i=0;i<contextIndex && i<context.size();i++) if(next.contains(context.get(i))) index++; }
        context=List.copyOf(next); contextIndex=index;
    }
    public Track displayTrack(Track track) {
        if(track==null) return null;
        Config.SongInfo info=Config.get().songInfo.get(track.id());
        return info==null?track:new Track(info.title,info.artist,track.path(),track.collection());
    }
    public Track currentTrack() { return displayTrack(player.current()); }
    public List<Track> queue() { return queue.stream().map(this::displayTrack).toList(); }
    public void editSong(Track track,String artist,String title) {
        String cleanTitle=title==null?"":title.strip(),cleanArtist=artist==null?"":artist.strip();
        if(cleanTitle.isBlank()) throw new IllegalArgumentException("songTitleRequired");
        if(cleanTitle.codePointCount(0,cleanTitle.length())>128 || cleanArtist.codePointCount(0,cleanArtist.length())>128) throw new IllegalArgumentException("songInfoTooLong");
        if(cleanTitle.codePoints().anyMatch(Character::isISOControl) || cleanArtist.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("songInfoInvalid");
        Config.get().songInfo.put(track.id(),new Config.SongInfo(cleanTitle,cleanArtist));
        songInfoRevision++; Config.save();
    }
    public boolean loading() { return loading; }
    public String error() { return libraryError; }
    public Path folder() { return root; }
    public record PlaylistInfo(String id,String name,int tracks) {}
    public List<PlaylistInfo> playlists() {
        return Config.get().playlists.stream().map(p -> new PlaylistInfo(p.id,p.name,p.tracks.size())).toList();
    }
    private Config.Playlist playlist(String id) {
        return Config.get().playlists.stream().filter(p -> p.id.equals(id)).findFirst().orElse(null);
    }
    public String playlistName(String id) { Config.Playlist p=playlist(id); return p == null ? "" : p.name; }
    private String validateName(String name,String except) {
        String clean=name == null ? "" : name.strip();
        if(clean.isEmpty()) throw new IllegalArgumentException("nameRequired");
        if(clean.codePointCount(0,clean.length())>64) throw new IllegalArgumentException("nameTooLong");
        if(Config.get().playlists.stream().anyMatch(p -> !p.id.equals(except) && p.name.equalsIgnoreCase(clean))) throw new IllegalArgumentException("nameDuplicate");
        return clean;
    }
    public String createPlaylist(String name) {
        Config.Playlist p=new Config.Playlist(validateName(name,null));
        Config.get().playlists.add(p); Config.save(); return p.id;
    }
    public void renamePlaylist(String id,String name) {
        Config.Playlist p=playlist(id); if(p == null) return;
        p.name=validateName(name,id); Config.save();
    }
    public void deletePlaylist(String id) {
        Config.get().playlists.removeIf(p -> p.id.equals(id));
        if(id.equals(contextPlaylist)) contextPlaylist=null;
        Config.save();
    }
    public List<Track> playlistTracks(String id) {
        Config.Playlist p=playlist(id); if(p == null) return List.of();
        Map<String,Track> available=new java.util.HashMap<>();
        for(Track t:library) available.put(t.id(),t);
        return p.tracks.stream().map(available::get).filter(java.util.Objects::nonNull).map(this::displayTrack).toList();
    }
    public boolean playlistContains(String id,Track track) {
        Config.Playlist p=playlist(id); return p != null && p.tracks.contains(track.id());
    }
    public void addPlaylistTrack(String id,Track track) {
        Config.Playlist p=playlist(id); if(p == null || p.tracks.contains(track.id())) return;
        p.tracks.add(track.id()); refreshPlaylistContext(id); Config.save();
    }
    public void removePlaylistTrack(String id,Track track) {
        Config.Playlist p=playlist(id); if(p == null) return;
        p.tracks.remove(track.id()); refreshPlaylistContext(id); Config.save();
    }
    private void refreshPlaylistContext(String id) {
        if(!id.equals(contextPlaylist)) return;
        refreshContext(playlistTracks(id));
    }
    public void playPlaylist(Track track,String id) {
        play(track,playlistTracks(id)); contextPlaylist=id;
    }
    public boolean startPlaylist(String id) { return startCollection(playlistTracks(id),id); }
    public boolean startFolder(String folder) {
        boolean started=startCollection(tracksForPage("folder:"+folder),null); if(started) contextPage="folder:"+folder; return started;
    }
    private boolean startCollection(List<Track> tracks,String playlist) {
        if(tracks.isEmpty()) return false;
        Track first=tracks.get(Config.get().shuffle?random.nextInt(tracks.size()):0);
        play(first,tracks); contextPlaylist=playlist; return true;
    }
    public void reload() {
        if(loading) return; loading=true; libraryError="";
        albumArt.clear();
        scanner.execute(() -> {
            try {
                List<Track> found=MusicLibrary.scan(root);
                synchronized(libraryLock) { library=found.stream().filter(t -> Files.isRegularFile(t.path())).toList(); }
                metadata.execute(() -> {
                    for(Track track:found) {
                        if(Thread.currentThread().isInterrupted()) break;
                        try {
                            fileAccess.readLock().lockInterruptibly();
                            try { if(library.contains(track)) durations.put(track.id(),AudioDecoder.duration(track)); }
                            finally { fileAccess.readLock().unlock(); }
                        } catch(InterruptedException e) { Thread.currentThread().interrupt(); break; }
                        catch(Exception ignored) { if(library.contains(track)) durations.put(track.id(),0d); }
                    }
                });
            } catch(Exception e) { libraryError=e.getClass().getSimpleName()+": "+e.getMessage(); }
            finally { loading=false; }
        });
    }
    public double duration(Track t) { return t==null ? 0 : durations.getOrDefault(t.id(),0d); }
    public void play(Track track,List<Track> source) {
        context=List.copyOf(source); contextIndex=context.indexOf(track); contextPlaylist=null; contextPage=null; startTrack(track);
    }
    private void startTrack(Track track) {
        if(player.current()!=null) { if(history.size()>=100) history.removeFirst(); history.addLast(player.current()); }
        player.play(track);
    }
    public void toggle() {
        if(player.current()==null) { List<Track> tracks=library(); if(!tracks.isEmpty()) { play(tracks.get(0),tracks); contextPage="all"; } }
        else if(player.ended() || !player.error().isEmpty()) player.play(player.current());
        else player.pauseToggle();
    }
    public record PlaybackFeedback(String key,String song) {}
    public PlaybackFeedback toggleWithFeedback() {
        toggle(); Track current=currentTrack();
        if(current==null) return new PlaybackFeedback(loading?"actionLoading":"actionNoSong","");
        String name=current.artist().isBlank()?current.title():current.artist()+" - "+current.title();
        return new PlaybackFeedback(player.paused()?"actionPaused":"actionPlaying",name);
    }
    public void addQueue(Track t) { queue.addLast(t); }
    public void removeQueue(int i) { var copy=new ArrayList<>(queue); if(i>=0 && i<copy.size()) { copy.remove(i); queue.clear(); queue.addAll(copy); } }
    public void playQueue(int i) {
        var copy=new ArrayList<>(queue);
        if(i<0 || i>=copy.size()) return;
        Track t=copy.remove(i); queue.clear(); queue.addAll(copy);
        if(context.isEmpty()) { context=library; contextIndex=context.indexOf(t); }
        startTrack(t);
    }
    public boolean liked(Track t) { return Config.get().liked.contains(t.id()); }
    public void like(Track t) { if(!Config.get().liked.remove(t.id())) Config.get().liked.add(t.id()); Config.save(); }
    public void shuffle() { Config.get().shuffle=!Config.get().shuffle; Config.save(); }
    public void repeat() { Config.get().repeat=switch(Config.get().repeat) { case "OFF" -> "ALL"; case "ALL" -> "ONE"; default -> "OFF"; }; Config.save(); }
    public void volume(int volume) { Config.get().volume=Maths.clamp(volume,0,100); }
    public int setVolume(int value) { volume(value); Config.save(); return Config.get().volume; }
    public int adjustVolume(int delta) { return setVolume((int)Maths.clamp((long)Config.get().volume+delta,0,100)); }
    public void seek(double fraction) { double d=duration(player.current()); if(d>0) player.seek(Maths.clamp(fraction,0,1)*Math.max(0,d-0.01)); }
    public void previous() {
        if(player.current()!=null && player.position()>3) { player.seek(0); return; }
        if(!history.isEmpty()) { Track t=history.removeLast(); int i=context.indexOf(t); if(i>=0) contextIndex=i; player.play(t); }
        else if(player.current()!=null && !context.isEmpty()) {
            contextIndex=Math.max(0,contextIndex-1); player.play(context.get(contextIndex));
        }
    }
    public void next() { advance(false); }
    private void advance(boolean automatic) {
        Track current=player.current();
        if(automatic && Config.get().repeat.equals("ONE") && current!=null) { player.play(current); return; }
        if(!queue.isEmpty()) { startTrack(queue.removeFirst()); return; }
        List<Track> source=contextPlaylist!=null?context:context.isEmpty()?library:context;
        if(source.isEmpty()) return;
        if(Config.get().shuffle) {
            List<Track> choices=source.stream().filter(t -> !t.equals(current)).toList();
            if(!choices.isEmpty()) advanceContext(choices.get(random.nextInt(choices.size())),source);
            else if(!automatic || !Config.get().repeat.equals("OFF")) advanceContext(source.get(0),source);
            return;
        }
        int i=context.isEmpty() && contextPlaylist==null?source.indexOf(current)+1:contextIndex+1;
        if(i<source.size()) advanceContext(source.get(i),source);
        else if(!automatic || Config.get().repeat.equals("ALL")) advanceContext(source.get(0),source);
    }
    private void advanceContext(Track track,List<Track> source) {
        String playlist=contextPlaylist,page=contextPage; play(track,source); contextPlaylist=playlist; contextPage=page;
    }
    public void tick() {
        if(urlImporter.consumeCompleted()!=null) importReloadPending=true;
        if(importReloadPending && !loading) { importReloadPending=false; reload(); }
        if(player.consumeFinished()) advance(true);
    }
    public void openFolder() {
        scanner.execute(() -> {
            try { Files.createDirectories(root); Desktop.getDesktop().open(root.toFile()); }
            catch(Exception e) { libraryError=e.getClass().getSimpleName()+": "+e.getMessage(); }
        });
    }
    public void broadcast() { Config.get().broadcastToVoiceChat=!Config.get().broadcastToVoiceChat; VoiceBuffer.clear(); Config.save(); }
    @Override public void close() {
        albumArt.close(); urlImporter.close(); player.close(); scanner.shutdownNow(); metadata.shutdownNow();
        try {
            scanner.awaitTermination(1,java.util.concurrent.TimeUnit.SECONDS);
            metadata.awaitTermination(1,java.util.concurrent.TimeUnit.SECONDS);
        } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
