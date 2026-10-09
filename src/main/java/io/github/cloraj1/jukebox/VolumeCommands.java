package io.github.cloraj1.jukebox;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import java.util.function.ObjIntConsumer;

/** Register the same bounded command tree for Fabric and parser regression tests. */
public final class VolumeCommands {
    private VolumeCommands() {}
    public static <S> void register(CommandDispatcher<S> dispatcher,ObjIntConsumer<S> setVolume) {
        dispatcher.register(LiteralArgumentBuilder.<S>literal("volume")
            .then(RequiredArgumentBuilder.<S,Integer>argument("value",IntegerArgumentType.integer(1,100))
                .executes(context -> { setVolume.accept(context.getSource(),IntegerArgumentType.getInteger(context,"value")); return 1; })));
    }
}
