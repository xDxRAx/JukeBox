package io.github.cloraj1.jukebox;

import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

/** Session-owned output lines prevent an old thread from clearing a newer track. */
public final class MusicPlayer implements AutoCloseable {
    public interface Output extends AutoCloseable {
        void write(byte[] bytes,int offset,int length);
        void pause(boolean paused);
        void drain();
        double seconds();
        void close();
    }
    @FunctionalInterface public interface OutputFactory { Output open(AudioFormat format) throws Exception; }
    private final OutputFactory factory;
    private volatile Playback active;
    private final java.util.Map<Thread,Track> workers=new java.util.concurrent.ConcurrentHashMap<>();
    private volatile String error = "";
    private volatile boolean finished;
    private volatile boolean ended;
    public MusicPlayer() { this(MusicPlayer::soundOutput); }
    public MusicPlayer(OutputFactory factory) { this.factory = factory; }
    private static final class Playback {
        final Track track;
        final double start;
        final AtomicBoolean cancelled = new AtomicBoolean();
        volatile boolean paused;
        volatile Output output;
        Thread thread;
        Playback(Track track,double start,boolean paused) { this.track=track; this.start=start; this.paused=paused; }
    }
    public synchronized void play(Track track) { start(track,0,false); }
    private synchronized void start(Track track,double seconds,boolean paused) {
        stop(); error=""; finished=false; ended=false;
        Playback p = new Playback(track,seconds,paused); active=p;
        p.thread = new Thread(() -> run(p),"JukeBox-Player"); p.thread.setDaemon(true); workers.put(p.thread,track); p.thread.start();
    }
    public synchronized void stop() {
        Playback p=active; active=null; finished=false; VoiceBuffer.clear();
        if (p != null) { p.cancelled.set(true); if(p.output != null) p.output.close(); p.thread.interrupt(); }
    }
    public synchronized void pauseToggle() {
        Playback p=active;
        if (p == null || ended || !error.isEmpty()) return;
        p.paused=!p.paused; if(p.output != null) p.output.pause(p.paused);
        VoiceBuffer.clear();
    }
    public synchronized void seek(double seconds) {
        Playback p=active;
        if (p != null) start(p.track,Math.max(0,seconds),p.paused);
    }
    public Track current() { Playback p=active; return p == null ? null : p.track; }
    void releaseFile(Track track) throws java.io.IOException {
        if(track.equals(current())) stop();
        long deadline=System.nanoTime()+2_000_000_000L;
        for(var entry:workers.entrySet()) if(entry.getValue().equals(track)) {
            Thread thread=entry.getKey();
            try { long left=deadline-System.nanoTime(); if(left>0) thread.join(Math.max(1,left/1_000_000)); }
            catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException("Interrupted while closing audio",e); }
            if(thread.isAlive()) throw new java.io.IOException("Audio file is still in use");
        }
    }
    void restore(Track track,double position,boolean paused) { start(track,position,paused); }
    public boolean paused() { Playback p=active; return p != null && p.paused; }
    public boolean playing() { return active != null && !paused() && !ended && error.isEmpty(); }
    public boolean ended() { return ended; }
    public double position() { Playback p=active; return p == null ? 0 : p.start + (p.output == null ? 0 : p.output.seconds()); }
    public String error() { return error; }
    public synchronized boolean consumeFinished() { if (!finished) return false; finished=false; return true; }
    private void run(Playback p) {
        try {
            if(p.cancelled.get()) return;
            AudioDecoder.decode(p.track,p.start,p.cancelled::get,(samples,count,channels,rate) -> {
                if(p.cancelled.get()) return;
                if(p.output == null) {
                    Output opened=factory.open(new AudioFormat(rate,16,channels,true,false));
                    // Publish the output under the same lock used by stop/seek.
                    synchronized(this) {
                        if(p.cancelled.get() || active != p) { opened.close(); return; }
                        p.output=opened; opened.pause(p.paused);
                    }
                }
                while(p.paused && !p.cancelled.get()) Thread.sleep(10);
                if(p.cancelled.get()) return;
                byte[] bytes = scalePcm(samples,count,Config.get().volume);
                p.output.write(bytes,0,bytes.length);
                if(!p.cancelled.get() && !p.paused) VoiceBuffer.feed(samples,count,channels,rate);
            });
            if(!p.cancelled.get() && p.output != null) p.output.drain();
            synchronized(this) { if(active==p && !p.cancelled.get()) { finished=true; ended=true; } }
        } catch (Exception e) {
            synchronized(this) {
                if(active==p && !p.cancelled.get()) { error=e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage()); System.err.println("JukeBox playback: "+error); }
            }
        } finally { if(p.output != null) p.output.close(); workers.remove(Thread.currentThread()); }
    }
    public static byte[] scalePcm(short[] samples,int count,int volume) {
        byte[] bytes=new byte[count*2]; float gain=Maths.clamp(volume,0,100)/100f;
        for(int i=0;i<count;i++) { short s=(short)(samples[i]*gain); bytes[2*i]=(byte)s; bytes[2*i+1]=(byte)(s>>8); }
        return bytes;
    }
    private static Output soundOutput(AudioFormat format) throws Exception {
        SourceDataLine line=AudioSystem.getSourceDataLine(format); line.open(format,format.getFrameSize()*(int)format.getSampleRate()/10); line.start();
        return new Output() {
            private volatile double lastPosition;
            private volatile boolean closed;
            public void write(byte[] bytes,int offset,int length) {
                int wrote=0;
                while(!closed && wrote<length) { int n=line.write(bytes,offset+wrote,length-wrote); if(n<=0) break; wrote+=n; }
            }
            public void pause(boolean paused) { if(!closed) { if(paused) line.stop(); else line.start(); } }
            public void drain() { if(!closed) line.drain(); }
            public double seconds() { if(!closed) lastPosition=line.getMicrosecondPosition()/1_000_000d; return lastPosition; }
            public void close() { if(!closed) { lastPosition=seconds(); closed=true; line.stop(); line.flush(); line.close(); } }
        };
    }
    @Override public void close() {
        stop();
        for(Thread worker:workers.keySet().toArray(Thread[]::new)) {
            try { worker.join(1000); } catch(InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
    }
}
