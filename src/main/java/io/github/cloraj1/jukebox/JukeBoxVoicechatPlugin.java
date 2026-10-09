package io.github.cloraj1.jukebox;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
/** Adapted from AfrikalifyVoicechatPlugin (MIT, BestOfAfrika). */
public final class JukeBoxVoicechatPlugin implements VoicechatPlugin {
    public String getPluginId() { return "jukebox"; }
    public void registerEvents(EventRegistration registration) {
        Integrations.voiceChat=true;
        registration.registerEvent(ClientSoundEvent.class,event -> {
            short[] raw=event.getRawAudio(), music=VoiceBuffer.next(raw.length);
            if(music.length==0) return;
            short[] mixed=raw.clone();
            for(int i=0;i<Math.min(raw.length,music.length);i++) mixed[i]=VoiceBuffer.softClip(raw[i]+music[i]);
            event.setRawAudio(mixed);
        });
    }
}
