package io.github.cloraj1.jukebox;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.@KEY_HELPER_PACKAGE@.v1.@KEY_HELPER@;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.@IDENTIFIER@;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.components.toasts.Toast;

public final class JukeBoxClient implements ClientModInitializer {
    public static MusicSession session;
    public static PlaylistFiles playlistFiles;
    public static KeyMapping toggleKey;
    public static KeyMapping volumeUpKey,volumeDownKey;
    public static final PlaybackNotice notice=new PlaybackNotice();
    public static boolean musicUiOpen(Minecraft client) { return client.@SCREEN_GET@ instanceof JukeBoxScreen; }
    public static boolean noticeVisible() { return notice.visible(musicUiOpen(Minecraft.getInstance())); }
    public static void togglePlayback(Minecraft client) {
        MusicSession.PlaybackFeedback result=session.toggleWithFeedback();
        if(musicUiOpen(client)) return;
        if(client.player!=null && notice.actionBarEnabled(false)) {
            client.player.@ACTIONBAR@(JukeBoxMessages.playback(result)@ACTIONBAR_EXTRA@);
        }
        notice.show(); updateToast(client);
    }
    public static void setVolume(Minecraft client,int value) { showVolume(client,session.setVolume(value)); }
    public static void adjustVolume(Minecraft client,int delta) { showVolume(client,session.adjustVolume(delta)); }
    private static void showVolume(Minecraft client,int value) {
        if(client.player!=null) client.player.@ACTIONBAR@(JukeBoxMessages.volume(value)@ACTIONBAR_EXTRA@);
    }
    private static void updateToast(Minecraft client) {
        var manager=client.@TOAST_GET@;
        if(noticeVisible() && !Config.get().alwaysPlaybackNotification && manager.getToast(JukeBoxToast.class,Toast.NO_TOKEN)==null) manager.addToast(new JukeBoxToast());
    }
    @Override public void onInitializeClient() {
        Config.load(FabricLoader.getInstance().getConfigDir(), FabricLoader.getInstance().getGameDir());
        session = new MusicSession(Config.folder());
        playlistFiles=new PlaylistFiles(session,Minecraft.getInstance()::execute);
        var category=@CATEGORY@;
        KeyMapping key = @KEY_HELPER@.@KEY_REGISTER@(new KeyMapping(
            "key.musiccraft.open_ui", InputConstants.Type.@KEY_TYPE@, JukeBoxKeys.OPEN_UI, category)); // K
        toggleKey=@KEY_HELPER@.@KEY_REGISTER@(new KeyMapping(
            "key.musiccraft.toggle_playback", InputConstants.Type.@KEY_TYPE@, JukeBoxKeys.TOGGLE_PLAYBACK, category)); // L
        volumeUpKey=@KEY_HELPER@.@KEY_REGISTER@(new KeyMapping(
            "key.musiccraft.volume_up", InputConstants.Type.@KEY_TYPE@, JukeBoxKeys.VOLUME_UP, category));
        volumeDownKey=@KEY_HELPER@.@KEY_REGISTER@(new KeyMapping(
            "key.musiccraft.volume_down", InputConstants.Type.@KEY_TYPE@, JukeBoxKeys.VOLUME_DOWN, category));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher,registryAccess) ->
            VolumeCommands.register(dispatcher,(source,value) -> setVolume(source.getClient(),value)));
        ScreenEvents.AFTER_INIT.register((client,screen,width,height) -> {
            if(!(screen instanceof TitleScreen)) return;
            MenuShortcut open=new MenuShortcut(),toggle=new MenuShortcut(),up=new MenuShortcut(),down=new MenuShortcut();
            ScreenKeyboardEvents.allowKeyPress(screen).register((@MENU_PARAMETERS@) -> {
                if(key.matches(@MENU_MATCH_ARGS@)) { if(open.press()) client.@SCREEN_SET@(new JukeBoxScreen(screen)); return false; }
                if(toggleKey.matches(@MENU_MATCH_ARGS@)) { if(toggle.press()) togglePlayback(client); return false; }
                if(volumeUpKey.matches(@MENU_MATCH_ARGS@)) { if(up.press()) adjustVolume(client,MusicSession.VOLUME_KEY_STEP); return false; }
                if(volumeDownKey.matches(@MENU_MATCH_ARGS@)) { if(down.press()) adjustVolume(client,-MusicSession.VOLUME_KEY_STEP); return false; }
                return true;
            });
            ScreenKeyboardEvents.allowKeyRelease(screen).register((@MENU_PARAMETERS@) -> {
                if(key.matches(@MENU_MATCH_ARGS@)) open.release();
                if(toggleKey.matches(@MENU_MATCH_ARGS@)) toggle.release();
                if(volumeUpKey.matches(@MENU_MATCH_ARGS@)) up.release();
                if(volumeDownKey.matches(@MENU_MATCH_ARGS@)) down.release();
                return true;
            });
        });
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            int clicks=0; while(toggleKey.consumeClick()) clicks++;
            if(toggleKey.same(client.options.keyAdvancements)) {
                // Older key maps dispatch a shared key to one binding; newer maps dispatch to both.
                int advancementClicks=0; while(client.options.keyAdvancements.consumeClick()) advancementClicks++;
                clicks=Math.max(clicks,advancementClicks);
            }
            while(clicks-->0) {
                // JukeBoxScreen handles the configured key and protects its text inputs.
                if(!(client.@SCREEN_GET@ instanceof JukeBoxScreen)) togglePlayback(client);
            }
            while(volumeUpKey.consumeClick()) if(client.@SCREEN_GET@==null) adjustVolume(client,MusicSession.VOLUME_KEY_STEP);
            while(volumeDownKey.consumeClick()) if(client.@SCREEN_GET@==null) adjustVolume(client,-MusicSession.VOLUME_KEY_STEP);
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            session.tick();
            Runnable errorSound=() -> client.getSoundManager().play(JukeBoxSounds.importError());
            session.urlImporter.errors.drain(errorSound);
            playlistFiles.errors.drain(errorSound);
            updateToast(client);
            if(!Config.get().showAlbumArt) JukeBoxArtwork.clear();
            while (key.consumeClick()) {
                if (!(client.@SCREEN_GET@ instanceof JukeBoxScreen)) client.@SCREEN_SET@(new JukeBoxScreen(client.@SCREEN_GET@));
            }
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> { playlistFiles.close(); session.close(); JukeBoxArtwork.clear(); Config.save(); });
    }
}
