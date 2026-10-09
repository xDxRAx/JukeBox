package io.github.cloraj1.jukebox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class NotificationSettingsTest {
    @TempDir Path temp;
    @Test void legacyDefaultsAndNewSettingsPersistTogether() throws Exception {
        Path config=temp.resolve("config"); Files.createDirectories(config);
        Files.writeString(config.resolve("jukebox.json"),"{\"volume\":39,\"shuffle\":true}"); Config.load(config,temp);
        assertTrue(Config.get().playbackNotifications); assertFalse(Config.get().alwaysPlaybackNotification); assertFalse(Config.get().showAlbumArt);
        Config.get().playbackNotifications=false; Config.get().alwaysPlaybackNotification=true; Config.get().showAlbumArt=true; Config.save();
        Config.load(config,temp); assertFalse(Config.get().playbackNotifications); assertTrue(Config.get().alwaysPlaybackNotification); assertTrue(Config.get().showAlbumArt);
        assertEquals(39,Config.get().volume); assertTrue(Config.get().shuffle);
    }
    @Test void transientNoticeExpiresRenewsAndHonorsOffSetting() {
        Config.load(temp.resolve("config"),temp); AtomicLong time=new AtomicLong(123); PlaybackNotice notice=new PlaybackNotice(time::get);
        assertFalse(notice.visible()); notice.show(); assertTrue(notice.visible()); time.addAndGet(4_000_000_000L); assertTrue(notice.visible());
        notice.show(); time.addAndGet(4_999_999_999L); assertTrue(notice.visible()); time.incrementAndGet(); assertFalse(notice.visible());
        notice.show(); Config.get().playbackNotifications=false; assertFalse(notice.visible());
    }
    @Test void alwaysNoticeNeedsNoPlaybackEventOrWorldAndOverridesTransientOff() {
        Config.load(temp.resolve("config"),temp); Config.get().playbackNotifications=false;
        PlaybackNotice notice=new PlaybackNotice(() -> Long.MAX_VALUE); assertFalse(notice.visible());
        Config.get().alwaysPlaybackNotification=true; assertTrue(notice.visible());
        Config.get().alwaysPlaybackNotification=false; assertFalse(notice.visible());
    }
    @Test void heldMenuKeyDoesNotToggleRepeatedlyAndCanBePressedAgain() {
        MenuShortcut key=new MenuShortcut(); assertTrue(key.press()); for(int i=0;i<10;i++) assertFalse(key.press());
        key.release(); assertTrue(key.press()); key.release(); key.release(); assertTrue(key.press());
    }
    @Test void transientAndPinnedNoticesAreSuppressedWhileMusicUiIsOpen() {
        Config.load(temp.resolve("config"),temp); AtomicLong time=new AtomicLong(1); PlaybackNotice notice=new PlaybackNotice(time::get);
        notice.show(); assertTrue(notice.visible(false)); assertFalse(notice.visible(true));
        Config.get().alwaysPlaybackNotification=true; assertFalse(notice.visible(true)); assertTrue(notice.visible(false));
        Config.get().alwaysPlaybackNotification=false; time.addAndGet(5_000_000_000L); assertFalse(notice.visible(false));
    }
    @Test void actionBarFollowsNotificationSettingWithoutDependingOnExpiryOrPinnedMode() {
        Config.load(temp.resolve("config"),temp); AtomicLong time=new AtomicLong(1); PlaybackNotice notice=new PlaybackNotice(time::get);
        for(boolean pinned:new boolean[]{false,true}) {
            Config.get().alwaysPlaybackNotification=pinned;
            Config.get().playbackNotifications=true; notice.show();
            assertFalse(notice.actionBarEnabled(false)); assertFalse(notice.actionBarEnabled(true)); assertTrue(notice.visible(false));
            time.addAndGet(5_000_000_000L); assertFalse(notice.actionBarEnabled(false)); assertEquals(pinned,notice.visible(false));
            Config.get().playbackNotifications=false; notice.show();
            assertTrue(notice.actionBarEnabled(false)); assertFalse(notice.actionBarEnabled(true)); assertEquals(pinned,notice.visible(false));
            assertFalse(notice.visible(true));
        }
    }
    @Test void requestedKoreanMessagesUseEditedArtistAndTitle() throws Exception {
        try(MusicSession session=session(); var language=new TestLanguage("ko_kr")) {
            Track track=session.library().get(0); session.editSong(track,"Zedd","Clarity");
            assertEquals("♬ 재생 중: Zedd - Clarity ♬",JukeBoxMessages.playback(session.toggleWithFeedback()).getString());
            assertEquals("♪ 정지 됨: Zedd - Clarity ♪",JukeBoxMessages.playback(session.toggleWithFeedback()).getString());
            assertEquals("♬ 재생 중: Zedd - Clarity ♬",JukeBoxMessages.playback(session.toggleWithFeedback()).getString());
        }
    }
    private MusicSession session() throws Exception {
        Config.load(temp.resolve("config"),temp); Path folder=temp.resolve("music"); Files.createDirectories(folder);
        try(var stream=getClass().getResourceAsStream("/tone.wav")) { Files.copy(stream,folder.resolve("Original - Sample.wav")); }
        MusicSession session=new MusicSession(folder,new MusicPlayer(format -> new MusicPlayer.Output() {
            volatile boolean closed;
            public void write(byte[] b,int o,int n) { try { if(!closed) Thread.sleep(100); } catch(InterruptedException ignored) {} }
            public void pause(boolean p) {} public void drain() {} public double seconds() { return .25; } public void close() { closed=true; }
        }));
        long until=System.nanoTime()+5_000_000_000L;
        while((session.loading() || session.library().isEmpty() || session.duration(session.library().get(0))==0) && System.nanoTime()<until) Thread.sleep(5);
        assertFalse(session.library().isEmpty()); return session;
    }
    private record Label(String text,int x,int y) {}
    private MusicView.Canvas canvas(int width,int height,List<Label> labels,boolean covers,List<int[]> art) {
        return new MusicView.Canvas() {
            public void fill(int x1,int y1,int x2,int y2,int color) { assertTrue(x1>=0 && y1>=0 && x2<=width && y2<=height); }
            public void text(String text,int x,int y,int color) { assertTrue(x>=0 && y>=0 && x+text.length()*5<=width && y+8<=height); labels.add(new Label(text,x,y)); }
            public int width(String text) { return text.length()*5; }
            public boolean cover(Track track,int x,int y,int size) { art.add(new int[]{x,y,size}); assertTrue(x>=0 && y>=0 && x+size<=width && y+size<=height); return covers; }
        };
    }
    @Test void gearOpensThreePersistedTogglesAndModalBlocksPlaybackAtSmallSizes() throws Exception {
        try(MusicSession session=session()) {
            for(int[] size:new int[][]{{320,240},{480,270},{854,480}}) {
                int w=size[0],h=size[1]; MusicView view=new MusicView(session,k -> k); view.resize(w,h);
                List<Label> labels=new ArrayList<>(); var c=canvas(w,h,labels,false,new ArrayList<>());
                view.draw(c,-1,-1); assertTrue(view.click(w-17,17,0)); labels.clear(); view.draw(c,-1,-1);
                assertTrue(view.dialogOpen()); assertFalse(view.textInputsActive());
                for(String key:List.of("playbackNotifications","alwaysPlaybackNotification","showAlbumArt")) assertTrue(labels.stream().anyMatch(l -> l.text.equals(key) || l.text.startsWith(key.substring(0,10))));
                int x=(w-Math.min(360,w-24))/2,y=(h-Math.min(210,h-36))/2,button=x+Math.min(360,w-24)-46;
                boolean first=Config.get().playbackNotifications,second=Config.get().alwaysPlaybackNotification,third=Config.get().showAlbumArt;
                assertTrue(view.click(button,y+48,0)); view.draw(c,-1,-1); assertTrue(view.click(button,y+78,0)); view.draw(c,-1,-1); assertTrue(view.click(button,y+108,0));
                assertEquals(!first,Config.get().playbackNotifications); assertEquals(!second,Config.get().alwaysPlaybackNotification); assertEquals(!third,Config.get().showAlbumArt);
                assertFalse(view.key(32)); assertNull(session.player.current()); assertTrue(view.key(256)); assertFalse(view.dialogOpen());
                Config.load(temp.resolve("config"),temp); assertEquals(!third,Config.get().showAlbumArt);
            }
        }
    }
    @Test void optionalArtSitsLeftOfEditedNamesAndToastUsesLivePausedMetadata() throws Exception {
        try(MusicSession session=session()) {
            Track track=session.library().get(0); session.play(track,session.library()); session.editSong(track,"Rose","Edited song");
            session.player.pauseToggle(); List<Label> labels=new ArrayList<>(); List<int[]> art=new ArrayList<>();
            MusicView view=new MusicView(session,k -> k); view.resize(480,270); var c=canvas(480,270,labels,true,art);
            view.draw(c,-1,-1); assertTrue(art.isEmpty());
            Config.get().showAlbumArt=true; labels.clear(); view.draw(c,-1,-1);
            assertTrue(art.stream().anyMatch(a -> a[0]==195 && a[1]==73 && a[2]==24));
            assertTrue(labels.stream().anyMatch(l -> l.text.equals("Edited song") && l.x==225));
            labels.clear(); art.clear(); var toast=canvas(MusicToastView.WIDTH,MusicToastView.HEIGHT,labels,true,art);
            MusicToastView.draw(toast,session,k -> k,MusicToastView.WIDTH);
            assertTrue(labels.stream().anyMatch(l -> l.text.equals("Edited song") && l.x==38));
            assertTrue(labels.stream().anyMatch(l -> l.text.equals("Rose") && l.y==20)); assertEquals(24,art.get(0)[2]);
            Config.get().showAlbumArt=false; art.clear(); MusicToastView.draw(toast,session,k -> k,MusicToastView.WIDTH); assertTrue(art.isEmpty());
        }
    }
}
