package io.github.cloraj1.jukebox;

import java.nio.file.Path;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** Dialogs and file reads run off the render thread; config changes return to the client thread. */
public final class PlaylistFiles implements AutoCloseable {
    public interface Picker { Path choose(boolean export,String title,String filter,Path suggested); }
    public record Status(String key,boolean error) {}
    private final MusicSession session;
    private final Executor client,worker;
    private final Picker picker;
    private final ExecutorService ownedWorker;
    private final AtomicBoolean busy=new AtomicBoolean();
    private volatile boolean closed;
    private volatile Status status=new Status("playlistTransferHint",false);
    private Path lastFolder;
    public final OperationErrors errors=new OperationErrors();
    public PlaylistFiles(MusicSession session,Executor client) {
        this(session,client,Executors.newSingleThreadExecutor(r -> { Thread thread=new Thread(r,"JukeBox-PlaylistFiles"); thread.setDaemon(true); return thread; }),new NativePlaylistPicker(client),true);
    }
    PlaylistFiles(MusicSession session,Executor client,Executor worker,Picker picker) { this(session,client,worker,picker,false); }
    private PlaylistFiles(MusicSession session,Executor client,Executor worker,Picker picker,boolean owned) {
        this.session=session; this.client=client; this.worker=worker; this.picker=picker; ownedWorker=owned?(ExecutorService)worker:null;
        lastFolder=session.folder().toAbsolutePath().normalize().getParent();
    }
    public boolean busy() { return busy.get(); }
    public Status status() { return status; }
    public void request(boolean export,Function<String,String> tr) {
        if(closed || !busy.compareAndSet(false,true)) return;
        status=new Status("playlistTransferBusy",false);
        final String json,title=tr.apply(export?"exportPlaylist":"importPlaylist"),filter=tr.apply("playlistFileType");
        try { json=export?PlaylistTransfer.snapshot(session.folder(),Config.get().playlists):null; }
        catch(IOException | RuntimeException e) { finish("playlistExportFailed",true); return; }
        Path suggested=export?lastFolder.resolve("JukeBox-playlists.json"):lastFolder;
        worker.execute(() -> {
            try {
                Path file=picker.choose(export,title,filter,suggested);
                if(closed) return;
                if(file==null) { finish("playlistTransferCancelled",false); return; }
                lastFolder=file.toAbsolutePath().normalize().getParent();
                if(export) { PlaylistTransfer.write(file,json); finish("playlistExportSuccess",false); }
                else {
                    var entries=PlaylistTransfer.read(file,session.folder());
                    client.execute(() -> {
                        if(closed) return;
                        try { PlaylistTransfer.apply(session.folder(),entries); finish("playlistImportSuccess",false); }
                        catch(PlaylistTransfer.InvalidFile e) { finish("playlistInvalid",true); }
                        catch(IOException | RuntimeException e) { finish("playlistImportFailed",true); }
                    });
                }
            } catch(PlaylistTransfer.InvalidFile e) { finish("playlistInvalid",true); }
            catch(Exception | LinkageError e) { finish(export?"playlistExportFailed":"playlistImportFailed",true); }
        });
    }
    private void finish(String key,boolean error) { if(error) errors.signal(); status=new Status(key,error); busy.set(false); }
    @Override public void close() { closed=true; if(ownedWorker!=null) ownedWorker.shutdownNow(); }
}
