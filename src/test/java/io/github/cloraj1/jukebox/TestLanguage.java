package io.github.cloraj1.jukebox;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

/** Supply mod translations to Minecraft's real component formatter without starting a client. */
final class TestLanguage implements AutoCloseable {
    private final Language original=Language.getInstance();
    TestLanguage(String locale) throws Exception {
        Map<String,String> messages=new HashMap<>();
        try(var reader=new InputStreamReader(TestLanguage.class.getResourceAsStream("/assets/jukebox/lang/"+locale+".json"),StandardCharsets.UTF_8)) {
            new Gson().fromJson(reader,JsonObject.class).entrySet().forEach(entry -> messages.put(entry.getKey(),entry.getValue().getAsString()));
        }
        Language.inject(new Language() {
            public String getOrDefault(String key,String fallback) { return messages.getOrDefault(key,fallback); }
            public boolean has(String key) { return messages.containsKey(key); }
            public boolean isDefaultRightToLeft() { return false; }
            public FormattedCharSequence getVisualOrder(FormattedText text) { return FormattedCharSequence.EMPTY; }
        });
    }
    public void close() { Language.inject(original); }
}
