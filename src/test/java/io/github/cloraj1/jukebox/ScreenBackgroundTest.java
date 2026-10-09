package io.github.cloraj1.jukebox;

import java.lang.reflect.Method;
import java.util.Arrays;
import net.minecraft.client.gui.screens.Screen;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScreenBackgroundTest {
    @Test void fullScreenPlayerDoesNotRunVanillaBackgroundEffects() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        // Newer Screen constructors require the running Minecraft client. Allocate
        // an uninitialized receiver to test only the background hook on every version.
        Class<?> allocator=Class.forName("sun.misc.Unsafe");
        var instance=allocator.getDeclaredField("theUnsafe");
        instance.setAccessible(true);
        Screen screen=(Screen)allocator.getMethod("allocateInstance",Class.class)
            .invoke(instance.get(null),JukeBoxScreen.class);
        Method background=Arrays.stream(Screen.class.getDeclaredMethods())
            .filter(method -> method.getName().equals("renderBackground") || method.getName().equals("extractBackground"))
            .findFirst().orElseThrow();
        Object[] arguments=new Object[background.getParameterCount()];
        Class<?>[] types=background.getParameterTypes();
        for(int i=0;i<types.length;i++) {
            if(types[i]==int.class) arguments[i]=0;
            else if(types[i]==float.class) arguments[i]=0F;
        }
        // Dispatch through the real Minecraft hook. No initialized client or graphics
        // context is supplied, so panorama, blur and menu drawing would fail here.
        assertDoesNotThrow(() -> background.invoke(screen,arguments));
    }
}
