package io.github.cloraj1.jukebox;

import com.mojang.blaze3d.platform.InputConstants;

/** Translate native keyboard codes into MusicView's stable shortcut IDs (26.3 uses SDL codes). */
public final class JukeBoxKeys {
    public static final int OPEN_UI=InputConstants.KEY_K;
    public static final int TOGGLE_PLAYBACK=InputConstants.KEY_L;
    public static final int VOLUME_UP=InputConstants.KEY_UP;
    public static final int VOLUME_DOWN=InputConstants.KEY_DOWN;
    private JukeBoxKeys() {}
    public static int uiKey(int nativeKey) {
        return switch(nativeKey) {
            case InputConstants.KEY_SPACE -> 32;
            case InputConstants.KEY_B -> 66;
            case InputConstants.KEY_F -> 70;
            case InputConstants.KEY_N -> 78;
            case InputConstants.KEY_R -> 82;
            case InputConstants.KEY_S -> 83;
            case InputConstants.KEY_T -> 84;
            case InputConstants.KEY_ESCAPE -> 256;
            case InputConstants.KEY_RETURN -> 257;
            case InputConstants.KEY_NUMPADENTER -> 335;
            case InputConstants.KEY_RIGHT -> 262;
            case InputConstants.KEY_LEFT -> 263;
            case InputConstants.KEY_DOWN -> 264;
            case InputConstants.KEY_UP -> 265;
            default -> -1;
        };
    }
}
