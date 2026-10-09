package io.github.cloraj1.jukebox;

import java.nio.file.Path;
import java.util.concurrent.Executor;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

/** Minecraft 1.21–26.2 bundle these native file dialogs. Called from the file worker. */
final class NativePlaylistPicker implements PlaylistFiles.Picker {
    NativePlaylistPicker(Executor client) {}
    @Override public Path choose(boolean export,String title,String filter,Path suggested) {
        try(MemoryStack stack=MemoryStack.stackPush()) {
            var patterns=stack.mallocPointer(1); patterns.put(stack.UTF8("*.json")).flip();
            String selected=export?
                TinyFileDialogs.tinyfd_saveFileDialog("JukeBox - "+title,suggested.toString(),patterns,filter):
                TinyFileDialogs.tinyfd_openFileDialog("JukeBox - "+title,suggested.toString()+java.io.File.separator,patterns,filter,false);
            return selected==null?null:Path.of(selected);
        }
    }
}
