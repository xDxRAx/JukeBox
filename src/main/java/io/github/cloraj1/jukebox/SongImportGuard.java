package io.github.cloraj1.jukebox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Locale;

/** Inspect existing references without renaming, overwriting or deleting their audio. */
final class SongImportGuard {
    private SongImportGuard() {}
    static void check(Path folder,String videoId,Track incoming) throws IOException {
        if(!Files.exists(folder)) return;
        try(var files=Files.walk(folder,8)) {
            for(Path file:files.filter(Files::isRegularFile).filter(MusicLibrary::supported).toList()) {
                boolean duplicate=videoId!=null && videoId.equals(Track.videoId(file));
                Track existing=Track.fromPath(folder,file);
                if(incoming!=null) {
                    duplicate|=same(incoming,existing.title(),existing.artist());
                    var info=Config.get().songInfo.get(existing.id());
                    if(info!=null) duplicate|=same(incoming,info.title,info.artist);
                }
                if(duplicate) throw new UrlSongImporter.ImportFailure("urlDuplicate",existing.title());
            }
        }
    }
    private static boolean same(Track incoming,String title,String artist) {
        return normalize(incoming.title()).equals(normalize(title)) && normalize(incoming.artist()).equals(normalize(artist));
    }
    private static String normalize(String text) {
        return Normalizer.normalize(text==null?"":text,Normalizer.Form.NFKC).strip().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);
    }
}
