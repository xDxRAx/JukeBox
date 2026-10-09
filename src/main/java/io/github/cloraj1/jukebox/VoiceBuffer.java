package io.github.cloraj1.jukebox;

import java.util.ArrayDeque;

/** Afrikalify's 48 kHz voice resampler, with bounded latency and an explicit off switch. */
public final class VoiceBuffer {
    private static final ArrayDeque<Short> samples = new ArrayDeque<>();
    public static synchronized void clear() { samples.clear(); }
    public static synchronized void feed(short[] pcm,int length,int channels,int rate) {
        if (!Config.get().broadcastToVoiceChat || length<=0 || channels<=0) return;
        float gain=Config.get().voiceMusicVolume/100f * (1-Config.get().softness/100f*0.55f);
        int frames=length/channels;
        for(int out=0;out<frames*48000/rate;out++) {
            int base=Math.min(frames-1,out*rate/48000)*channels, mixed=0;
            for(int ch=0;ch<channels;ch++) mixed+=pcm[base+ch];
            if(samples.size()>=24000) samples.removeFirst();
            samples.addLast(softClip(mixed/(float)channels*gain));
        }
    }
    public static synchronized short[] next(int count) {
        if(!Config.get().broadcastToVoiceChat || count<=0 || samples.isEmpty()) return new short[0];
        short[] frame=new short[count];
        for(int i=0;i<count && !samples.isEmpty();i++) frame[i]=samples.removeFirst();
        return frame;
    }
    public static short softClip(float value) {
        float n=Maths.clamp(value/32768f,-1,1);
        return (short)Maths.clamp(Math.round((float)Math.tanh(n*1.35)/(float)Math.tanh(1.35)*32767),Short.MIN_VALUE,Short.MAX_VALUE);
    }
}
