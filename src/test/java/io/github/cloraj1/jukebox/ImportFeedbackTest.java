package io.github.cloraj1.jukebox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ImportFeedbackTest {
    @TempDir Path temp;
    @Test void errorCueUsesRequestedSoundAndHalfPitch() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        var sound=JukeBoxSounds.importError();
        java.lang.reflect.Method identifier;
        try { identifier=sound.getClass().getMethod("getIdentifier"); }
        catch(NoSuchMethodException e) { identifier=sound.getClass().getMethod("getLocation"); }
        assertEquals("minecraft:block.note_block.bit",identifier.invoke(sound).toString());
        // getPitch() also reads the sound asset selected by a running client.
        // Inspect the constructor's base pitch without opening an audio device.
        var pitch=net.minecraft.client.resources.sounds.AbstractSoundInstance.class.getDeclaredField("pitch"); pitch.setAccessible(true);
        assertEquals(0.5F,pitch.getFloat(sound));
    }
    @Test void queuedErrorsPlayExactlyOnceOnTheDrainingThread() throws Exception {
        var errors=new OperationErrors(); Thread background=new Thread(() -> { errors.signal(); errors.signal(); }); background.start(); background.join();
        AtomicInteger count=new AtomicInteger(); Thread client=Thread.currentThread();
        errors.drain(() -> { assertSame(client,Thread.currentThread()); count.incrementAndGet(); }); errors.drain(() -> fail("Already played")); assertEquals(2,count.get());
    }
    @Test void playlistImportExportAndValidationFailuresEachQueueOneCueAndCancellationNone() throws Exception {
        Config.load(temp.resolve("config"),temp); Path music=Files.createDirectory(temp.resolve("music")),bad=temp.resolve("bad.json"); Files.writeString(bad,"bad json");
        try(MusicSession session=new MusicSession(music)) {
            for(boolean export:new boolean[]{false,true}) try(PlaylistFiles files=new PlaylistFiles(session,Runnable::run,Runnable::run,(e,title,filter,suggested) -> export?temp.resolve("missing/export.json"):bad)) {
                files.request(export,k -> k); assertTrue(files.status().error()); AtomicInteger count=new AtomicInteger();
                files.errors.drain(count::incrementAndGet); files.errors.drain(count::incrementAndGet); assertEquals(1,count.get());
            }
            Config.Playlist invalid=new Config.Playlist(" invalid "); Config.get().playlists.add(invalid);
            try(PlaylistFiles files=new PlaylistFiles(session,Runnable::run,Runnable::run,(e,t,f,s) -> fail("Snapshot must fail before the dialog"))) {
                files.request(true,k -> k); assertEquals("playlistExportFailed",files.status().key()); AtomicInteger count=new AtomicInteger(); files.errors.drain(count::incrementAndGet); assertEquals(1,count.get());
            }
            Config.get().playlists.clear();
            try(PlaylistFiles files=new PlaylistFiles(session,Runnable::run,Runnable::run,(e,t,f,s) -> null)) {
                files.request(false,k -> k); files.request(true,k -> k); files.errors.drain(() -> fail("Cancellation is not an error"));
            }
        }
    }
}
