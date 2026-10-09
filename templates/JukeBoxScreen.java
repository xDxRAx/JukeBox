package io.github.cloraj1.jukebox;

import net.minecraft.client.gui.@GRAPHICS@;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

public final class JukeBoxScreen extends Screen {
    private final Screen parent;
    private final MusicView view;
    private EditBox search;
    private EditBox dialogInput;
    private EditBox artistInput,titleInput;
    private int inputRevision=-1;
    private int songInputRevision=-1;
    private final MenuShortcut toggleShortcut=new MenuShortcut();
    private final MenuShortcut volumeUpShortcut=new MenuShortcut(),volumeDownShortcut=new MenuShortcut();
    public JukeBoxScreen(Screen parent) {
        super(Component.literal("JukeBox")); this.parent = parent;
        view = new MusicView(JukeBoxClient.session, k -> I18n.get("jukebox." + k),
            () -> minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK,1.0F)),JukeBoxClient.playlistFiles);
    }
    @Override protected void init() {
        view.resize(width, height);
        search = new EditBox(font, view.searchX(), view.searchY(), view.searchWidth(), 12, Component.translatable("jukebox.search"));
        search.setMaxLength(160); search.setBordered(false);
        search.setHint(Component.translatable("jukebox.search")); search.setValue(view.query());
        search.setResponder(view::search); addRenderableWidget(search);
        dialogInput=new EditBox(font,view.dialogInputX(),view.dialogInputY(),view.dialogInputWidth(),12,Component.translatable("jukebox.playlistName"));
        dialogInput.setMaxLength(2048); dialogInput.setBordered(false); dialogInput.setResponder(view::dialogInput);
        dialogInput.setVisible(false); addRenderableWidget(dialogInput); inputRevision=-1;
        artistInput=new EditBox(font,view.dialogInputX(),view.songArtistY(),view.dialogInputWidth(),12,Component.translatable("jukebox.songArtist"));
        titleInput=new EditBox(font,view.dialogInputX(),view.songTitleY(),view.dialogInputWidth(),12,Component.translatable("jukebox.songTitle"));
        artistInput.setMaxLength(512); titleInput.setMaxLength(512);
        artistInput.setBordered(false); titleInput.setBordered(false);
        artistInput.setHint(Component.translatable("jukebox.songArtist")); titleInput.setHint(Component.translatable("jukebox.songTitle"));
        artistInput.setResponder(view::songArtist); titleInput.setResponder(view::songTitle);
        artistInput.setVisible(false); titleInput.setVisible(false);
        addRenderableWidget(artistInput); addRenderableWidget(titleInput); songInputRevision=-1;
    }
    private void syncInputs() {
        search.setVisible(!view.dialogOpen());
        if(!view.dialogOpen()) {
            if(!search.getValue().equals(view.query())) search.setValue(view.query());
            dialogInput.setFocused(false);
        }
        dialogInput.setVisible(view.dialogInputActive());
        artistInput.setVisible(view.songEditOpen()); titleInput.setVisible(view.songEditOpen());
        if(!view.songEditOpen()) { artistInput.setFocused(false); titleInput.setFocused(false); }
        else {
            dialogInput.setFocused(false);
            artistInput.setX(view.dialogInputX()); artistInput.setY(view.songArtistY());
            titleInput.setX(view.dialogInputX()); titleInput.setY(view.songTitleY());
            if(songInputRevision!=view.inputRevision()) {
                artistInput.setValue(view.songArtist()); titleInput.setValue(view.songTitle()); songInputRevision=view.inputRevision();
                search.setFocused(false); setFocused(artistInput); artistInput.setFocused(true);
            }
        }
        if(view.dialogInputActive()) {
            dialogInput.setEditable(view.dialogInputEditable());
            dialogInput.setX(view.dialogInputX()); dialogInput.setY(view.dialogInputY());
            dialogInput.setHint(Component.literal(view.dialogInputHint()));
            if(inputRevision!=view.inputRevision()) {
                dialogInput.setValue(view.dialogInput()); inputRevision=view.inputRevision();
                search.setFocused(false); setFocused(dialogInput); dialogInput.setFocused(true);
            }
        }
    }
    @Override public void @BACKGROUND_RENDER@(@BACKGROUND_PARAMETERS@) {
        // MusicView paints the full screen. Older Screen.render calls this after
        // our view has drawn, which would blur the player UI itself.
    }
    @Override public void @RENDER@(@GRAPHICS@ g, int x, int y, float delta) {
        syncInputs();
        view.draw(new JukeBoxCanvas(g,font), x, y);
        super.@RENDER@(g,x,y,delta);
    }
    @Override public boolean mouseClicked(@CLICK@) {
        if (view.click(@CLICK_ARGS@)) { search.setFocused(false); syncInputs(); return true; }
        if(view.dialogOpen() && !view.textInputsActive()) return true;
        return super.mouseClicked(@SUPER_CLICK@);
    }
    @Override public boolean mouseDragged(@DRAG@) {
        return view.drag(@DRAG_ARGS@) || super.mouseDragged(@SUPER_DRAG@);
    }
    @Override public boolean mouseReleased(@RELEASE@) { return view.release(@RELEASE_ARGS@) || super.mouseReleased(@SUPER_CLICK_RELEASE@); }
    @Override public boolean mouseScrolled(@SCROLL@) {
        return view.scroll(x,y,vertical) || super.mouseScrolled(@SUPER_SCROLL@);
    }
    @Override public boolean keyPressed(@KEY@) {
        if(view.dialogOpen()) {
            if(view.key(@KEY_ARG@)) { syncInputs(); return true; }
            if(view.textInputsActive()) return super.keyPressed(@SUPER_KEY@);
            return true;
        }
        if (!search.isFocused() && JukeBoxClient.toggleKey.matches(@KEY_MATCH_ARGS@)) { if(toggleShortcut.press()) JukeBoxClient.togglePlayback(minecraft); return true; }
        if (!search.isFocused() && JukeBoxClient.volumeUpKey.matches(@KEY_MATCH_ARGS@)) { if(volumeUpShortcut.press()) JukeBoxClient.adjustVolume(minecraft,MusicSession.VOLUME_KEY_STEP); return true; }
        if (!search.isFocused() && JukeBoxClient.volumeDownKey.matches(@KEY_MATCH_ARGS@)) { if(volumeDownShortcut.press()) JukeBoxClient.adjustVolume(minecraft,-MusicSession.VOLUME_KEY_STEP); return true; }
        if (!search.isFocused() && @KEY_ARG@ == 70) { setFocused(search); search.setFocused(true); return true; }
        if (!search.isFocused() && view.key(@KEY_ARG@)) return true;
        return super.keyPressed(@SUPER_KEY@);
    }
    @Override public boolean keyReleased(@KEY@) {
        if(JukeBoxClient.toggleKey.matches(@KEY_MATCH_ARGS@)) toggleShortcut.release();
        if(JukeBoxClient.volumeUpKey.matches(@KEY_MATCH_ARGS@)) volumeUpShortcut.release();
        if(JukeBoxClient.volumeDownKey.matches(@KEY_MATCH_ARGS@)) volumeDownShortcut.release();
        return super.keyReleased(@SUPER_KEY@);
    }
    @Override public void onClose() { Config.save(); minecraft.@SCREEN_SET@(parent); }
    @Override public void removed() { view.cancelPointer(); Config.save(); }
    @Override public boolean isPauseScreen() { return false; }
}
