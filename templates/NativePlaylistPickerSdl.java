package io.github.cloraj1.jukebox;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.lwjgl.sdl.SDL_DialogFileCallback;
import org.lwjgl.sdl.SDL_DialogFileFilter;
import static org.lwjgl.sdl.SDLDialog.*;
import static org.lwjgl.sdl.SDLProperties.*;
import static org.lwjgl.system.MemoryUtil.*;

/** 26.3 bundles SDL. Launch on the client thread and retain filters through its asynchronous callback. */
final class NativePlaylistPicker implements PlaylistFiles.Picker {
    private final Executor client;
    private static volatile Request active;
    // One callback has process lifetime: SDL may still invoke it after the client begins shutting down.
    private static final SDL_DialogFileCallback CALLBACK=SDL_DialogFileCallback.create((userdata,files,selectedFilter) -> {
        Request request=active; if(request==null) return;
        Path path=null; Throwable error=null;
        try {
            if(files==NULL) throw new IllegalStateException("Native file dialog failed");
            long first=memGetAddress(files); if(first!=NULL) path=Path.of(memUTF8(first));
        } catch(RuntimeException e) { error=e; }
        finally { request.release(); active=null; }
        if(error==null) request.result.complete(path); else request.result.completeExceptionally(error);
    });
    private static final class Request {
        final CompletableFuture<Path> result=new CompletableFuture<>();
        ByteBuffer name,pattern;
        SDL_DialogFileFilter.Buffer filters;
        int properties;
        void release() {
            if(properties!=0) SDL_DestroyProperties(properties);
            if(filters!=null) filters.free(); if(name!=null) memFree(name); if(pattern!=null) memFree(pattern);
        }
    }
    NativePlaylistPicker(Executor client) { this.client=client; }
    @Override public Path choose(boolean export,String title,String filter,Path suggested) {
        Request request=new Request();
        client.execute(() -> {
            if(active!=null) { request.result.completeExceptionally(new IllegalStateException("File dialog already open")); return; }
            try {
                request.name=memUTF8(filter); request.pattern=memUTF8("json");
                request.filters=SDL_DialogFileFilter.calloc(1); request.filters.get(0).name(request.name).pattern(request.pattern);
                request.properties=SDL_CreateProperties();
                if(request.properties==0 || !SDL_SetStringProperty(request.properties,SDL_PROP_FILE_DIALOG_TITLE_STRING,"JukeBox - "+title)
                    || !SDL_SetStringProperty(request.properties,SDL_PROP_FILE_DIALOG_LOCATION_STRING,suggested.toString())
                    || !SDL_SetPointerProperty(request.properties,SDL_PROP_FILE_DIALOG_FILTERS_POINTER,request.filters.address())
                    || !SDL_SetNumberProperty(request.properties,SDL_PROP_FILE_DIALOG_NFILTERS_NUMBER,1)) throw new IllegalStateException("File dialog properties failed");
                active=request;
                SDL_ShowFileDialogWithProperties(export?SDL_FILEDIALOG_SAVEFILE:SDL_FILEDIALOG_OPENFILE,CALLBACK,NULL,request.properties);
            } catch(RuntimeException | LinkageError e) { active=null; request.release(); request.result.completeExceptionally(e); }
        });
        try { return request.result.get(); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("File dialog interrupted",e); }
        catch(java.util.concurrent.ExecutionException e) { throw new IllegalStateException("File dialog failed",e.getCause()); }
    }
}
