package io.github.cloraj1.jukebox.mixin;

import io.github.cloraj1.jukebox.JukeBoxToast;
import net.minecraft.client.gui.@GRAPHICS@;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Render the pinned notice even when vanilla Toasts are hidden with the HUD. */
@Mixin(@TOAST_MANAGER@.class)
public abstract class JukeBoxToastMixin {
    @Inject(method="@RENDER@",at=@At("RETURN"))
    private void jukebox$pinned(@GRAPHICS@ graphics,CallbackInfo ci) { JukeBoxToast.renderPinned(graphics); }
}
