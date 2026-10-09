package io.github.cloraj1.jukebox;

/** Menu events include OS repeats; only the first press should perform an action. */
public final class MenuShortcut {
    private boolean held;
    public boolean press() { if(held) return false; held=true; return true; }
    public void release() { held=false; }
}
