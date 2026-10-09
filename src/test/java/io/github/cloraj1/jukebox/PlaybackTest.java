package io.github.cloraj1.jukebox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sound.sampled.AudioFormat;
import static org.junit.jupiter.api.Assertions.*;

class PlaybackTest {
    @TempDir Path temp;
    private Track fixture(String extension,String filename) throws Exception {
        Path file=temp.resolve(filename+"."+extension);
        try(var in=getClass().getResourceAsStream("/tone."+extension)) { Files.copy(in,file); }
        return Track.fromPath(temp,file);
    }
    private static void until(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+5_000_000_000L;
        while(!condition.getAsBoolean() && System.nanoTime()<deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(),"Timed out waiting for playback");
    }
    private static final class FakeOutput implements MusicPlayer.Output {
        volatile boolean closed,paused;
        final AtomicInteger bytes=new AtomicInteger();
        final AudioFormat format;
        FakeOutput(AudioFormat f) { format=f; }
        public void write(byte[] b,int o,int n) {
            try { while(paused && !closed) Thread.sleep(2); if(!closed) { bytes.addAndGet(n); Thread.sleep(4); } } catch(InterruptedException ignored) {}
        }
        public void pause(boolean p) { paused=p; }
        public void drain() {}
        public double seconds() { return bytes.get()/(double)(format.getFrameSize()*format.getFrameRate()); }
        public void close() { closed=true; }
    }
    @Test void wavAndMp3DecodeAndSeek() throws Exception {
        for(String extension:List.of("wav","mp3")) {
            Track track=fixture(extension,"tone-"+extension);
            double duration=AudioDecoder.duration(track);
            assertTrue(duration>=1.9 && duration<2.2,"Duration of "+extension);
            AtomicInteger full=new AtomicInteger(),tail=new AtomicInteger(),nonzero=new AtomicInteger();
            AudioDecoder.decode(track,0,() -> false,(pcm,count,ch,rate) -> { assertEquals(44100,rate); full.addAndGet(count); for(int i=0;i<count;i++) if(pcm[i]!=0) nonzero.incrementAndGet(); });
            AudioDecoder.decode(track,1,() -> false,(pcm,count,ch,rate) -> tail.addAndGet(count));
            assertTrue(nonzero.get()>1000);
            assertTrue(tail.get()<full.get()*0.6 && tail.get()>full.get()*0.4);
        }
    }
    @Test void pauseSeekAndRapidSwitchDoNotClearNewPlayback() throws Exception {
        Config.load(temp.resolve("config"),temp);
        Track a=fixture("wav","Artist - First"),b=fixture("mp3","Artist - Second");
        List<FakeOutput> lines=new CopyOnWriteArrayList<>();
        try(MusicPlayer player=new MusicPlayer(f -> { var output=new FakeOutput(f); lines.add(output); return output; })) {
            player.play(a); until(() -> !lines.isEmpty() && lines.get(0).bytes.get()>0);
            player.pauseToggle(); assertTrue(player.paused());
            Thread.sleep(25); int before=lines.get(0).bytes.get(); Thread.sleep(30); assertEquals(before,lines.get(0).bytes.get());
            player.seek(1); until(() -> lines.size()==2); assertTrue(lines.get(1).paused); assertTrue(lines.get(0).closed);
            assertEquals(1,player.position(),0.02);
            player.pauseToggle(); until(() -> lines.get(1).bytes.get()>0);
            player.play(b); until(() -> lines.size()>=3); Thread.sleep(15);
            assertEquals(b,player.current()); assertTrue(player.error().isEmpty());
            for(int i=0;i<20;i++) player.play(i%2==0?a:b);
            until(() -> player.current().equals(b)); assertEquals(b,player.current());
            player.stop(); assertNull(player.current());
        }
    }
    @Test void volumeIncludesRealZeroEvenWithoutMixerGainControl() {
        short[] pcm={32767,-32768,12000};
        assertArrayEquals(new byte[6],MusicPlayer.scalePcm(pcm,pcm.length,0));
        assertArrayEquals(new byte[]{-1,127,0,-128,-32,46},MusicPlayer.scalePcm(pcm,pcm.length,100));
    }
    @Test void queueContinuesWithoutAnOpenScreenAndRepeatOneWins() throws Exception {
        Config.load(temp.resolve("config"),temp);
        Track a=fixture("wav","A"),b=fixture("mp3","B");
        try(MusicSession session=new MusicSession(temp,new MusicPlayer(FakeOutput::new))) {
            until(() -> !session.loading());
            session.play(a,List.of(a)); session.addQueue(b);
            until(session.player::ended); session.tick(); assertEquals(b,session.player.current());
            assertTrue(session.queue().isEmpty());
            until(session.player::ended); session.tick();
            assertFalse(session.player.playing(),"At end of context with repeat OFF");
            Config.get().repeat="ONE"; session.play(a,List.of(a,b)); session.addQueue(b);
            until(session.player::ended); session.tick(); assertEquals(a,session.player.current()); assertEquals(1,session.queue().size());
            session.next(); assertEquals(b,session.player.current());
        }
    }
    @Test void scanFiltersUnsupportedFormatsAndSearchesArtists() throws Exception {
        Path folder=temp.resolve("Chill"); Files.createDirectories(folder);
        Files.copy(fixture("wav","Composer - Rain").path(),folder.resolve("Composer - Rain.WAV"));
        Files.writeString(temp.resolve("cover.png"),"fake image"); Files.writeString(temp.resolve("unsupported.flac"),"unsupported");
        List<Track> tracks=MusicLibrary.scan(temp);
        assertEquals(2,tracks.size()); assertEquals(2,MusicLibrary.filter(tracks,"composer").size());
        assertTrue(tracks.stream().anyMatch(t -> t.collection().equals("Chill")));
    }
    @Test void corruptAudioReportsAnErrorAndNeverAutoAdvances() throws Exception {
        Config.load(temp.resolve("config"),temp); Path broken=temp.resolve("broken.wav"); Files.writeString(broken,"invalid");
        try(MusicPlayer player=new MusicPlayer(FakeOutput::new)) {
            player.play(Track.fromPath(temp,broken)); until(() -> !player.error().isEmpty());
            assertFalse(player.playing()); assertFalse(player.consumeFinished());
        }
    }
    @Test void layoutAtTypicalGuiScalesHasNoDrawingOutsideTheScreen() throws Exception {
        Config.load(temp.resolve("config"),temp); fixture("wav","Artist - A long song name");
        try(MusicSession session=new MusicSession(temp,new MusicPlayer(FakeOutput::new))) {
            until(() -> !session.loading());
            MusicView view=new MusicView(session,k -> k);
            for(int[] dimensions:new int[][]{{320,240},{480,270},{854,480}}) {
                int w=dimensions[0],h=dimensions[1]; view.resize(w,h);
                view.draw(new MusicView.Canvas() {
                    public void fill(int x1,int y1,int x2,int y2,int color) { assertTrue(x1>=0 && y1>=0 && x2<=w && y2<=h,"Out of bounds: "+x1+","+y1+","+x2+","+y2); }
                    public void text(String s,int x,int y,int color) { assertTrue(x>=0 && y>=0 && x+s.length()*5<=w && y+9<=h,"Text out of bounds: "+s); }
                    public int width(String s) { return s.length()*5; }
                },-1,-1);
            }
        }
    }
}
