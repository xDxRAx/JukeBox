package io.github.cloraj1.jukebox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Adapted from Afrikalify's MusicLibrary (MIT, BestOfAfrika). */
public final class MusicLibrary {
    private MusicLibrary() {}
    public static List<Track> scan(Path folder) throws IOException {
        Files.createDirectories(folder);
        try (var paths = Files.walk(folder, 8)) {
            return paths.filter(Files::isRegularFile).filter(MusicLibrary::supported)
                .map(p -> Track.fromPath(folder,p))
                .sorted(Comparator.comparing(Track::title,String.CASE_INSENSITIVE_ORDER).thenComparing(Track::id)).toList();
        }
    }
    public static List<Track> filter(List<Track> tracks,String query) {
        String q = query.strip().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return tracks;
        return tracks.stream().filter(t -> (t.title()+" "+t.artist()+" "+t.collection()).toLowerCase(Locale.ROOT).contains(q)).toList();
    }
    public static boolean supported(Path p) {
        String s = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return s.endsWith(".mp3") || s.endsWith(".wav");
    }
}
