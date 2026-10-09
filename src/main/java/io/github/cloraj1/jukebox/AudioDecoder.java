package io.github.cloraj1.jukebox;

import java.io.BufferedInputStream;
import java.nio.file.Files;
import java.util.function.BooleanSupplier;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

/** Streaming MP3/PCM decoding adapted from Afrikalify MusicPlayer. */
public final class AudioDecoder {
    @FunctionalInterface public interface Sink { void write(short[] samples,int count,int channels,int sampleRate) throws Exception; }
    private AudioDecoder() {}
    public static void decode(Track track,double start,BooleanSupplier cancelled,Sink sink) throws Exception {
        if (track.extension().equals("mp3")) mp3(track,start,cancelled,sink);
        else wav(track,start,cancelled,sink);
    }
    private static void mp3(Track track,double start,BooleanSupplier cancelled,Sink sink) throws Exception {
        try (var input = new BufferedInputStream(Files.newInputStream(track.path()))) {
            Bitstream bitstream = new Bitstream(input); Decoder decoder = new Decoder(); Header header;
            double seconds = 0;
            try {
                while (!cancelled.getAsBoolean() && (header = bitstream.readFrame()) != null) {
                    SampleBuffer buffer = (SampleBuffer)decoder.decodeFrame(header,bitstream);
                    int channels = buffer.getChannelCount(), rate = buffer.getSampleFrequency();
                    double duration = buffer.getBufferLength() / (double)(channels * rate);
                    if (seconds + duration > start) {
                        int skip = (int)Math.max(0,Math.floor((start - seconds) * rate)) * channels;
                        int count = buffer.getBufferLength() - skip;
                        if (skip == 0) sink.write(buffer.getBuffer(),count,channels,rate);
                        else {
                            short[] tail = new short[count]; System.arraycopy(buffer.getBuffer(),skip,tail,0,count);
                            sink.write(tail,count,channels,rate);
                        }
                    }
                    seconds += duration; bitstream.closeFrame();
                }
            } finally { bitstream.close(); }
        }
    }
    private static void wav(Track track,double start,BooleanSupplier cancelled,Sink sink) throws Exception {
        try (var input = AudioSystem.getAudioInputStream(track.path().toFile())) {
            AudioFormat base = input.getFormat();
            AudioFormat format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,base.getSampleRate(),16,base.getChannels(),base.getChannels()*2,base.getSampleRate(),false);
            try (var pcm = AudioSystem.getAudioInputStream(format,input)) {
                long skip = (long)(start * format.getFrameRate()) * format.getFrameSize();
                byte[] bytes = new byte[4096 * format.getFrameSize()];
                while (skip > 0 && !cancelled.getAsBoolean()) {
                    long n = pcm.skip(skip);
                    if (n == 0) { int read = pcm.read(bytes,0,(int)Math.min(skip,bytes.length)); if (read < 0) return; n = read; }
                    skip -= n;
                }
                int read;
                while (!cancelled.getAsBoolean() && (read = pcm.read(bytes)) >= 0) {
                    short[] samples = new short[read/2];
                    for (int i=0;i<samples.length;i++) samples[i] = (short)((bytes[2*i]&255) | (bytes[2*i+1]<<8));
                    if (read > 0) sink.write(samples,samples.length,format.getChannels(),(int)format.getSampleRate());
                }
            }
        }
    }
    public static double duration(Track track) throws Exception {
        if (!track.extension().equals("mp3")) {
            try (var audio = AudioSystem.getAudioInputStream(track.path().toFile())) {
                return audio.getFrameLength() / (double)audio.getFormat().getFrameRate();
            }
        }
        try (var input = new BufferedInputStream(Files.newInputStream(track.path()))) {
            Bitstream stream = new Bitstream(input); Header header; double milliseconds = 0;
            try {
                while ((header=stream.readFrame()) != null) {
                    if(Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Metadata scan cancelled");
                    milliseconds += header.ms_per_frame(); stream.closeFrame();
                }
            }
            finally { stream.close(); }
            return milliseconds / 1000;
        }
    }
}
