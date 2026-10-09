package io.github.cloraj1.jukebox;

import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

/** Audio-path identity survives display edits and optional embedded-art display. */
public record Track(String title, String artist, Path path, String collection) {
    private static final Pattern VIDEO_SUFFIX=Pattern.compile("\\s+\\[([A-Za-z0-9_-]{11})\\](?: \\(\\d+\\))?$");
    public Track { path=path.toAbsolutePath().normalize(); }
    @Override public boolean equals(Object value) { return value instanceof Track other && path.equals(other.path); }
    @Override public int hashCode() { return path.hashCode(); }
    public static Track fromPath(Path root, Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String title = dot > 0 ? name.substring(0, dot) : name;
        String artist = "";
        int separator = title.indexOf(" - ");
        if (separator > 0) { artist = title.substring(0, separator); title = title.substring(separator + 3); }
        String clean=VIDEO_SUFFIX.matcher(title).replaceFirst("").stripTrailing();
        if(!clean.isBlank()) title=clean;
        Path relative = root.relativize(path);
        String collection = relative.getNameCount() > 1 ? relative.getName(0).toString() : "";
        return new Track(title, artist, path.toAbsolutePath().normalize(), collection);
    }
    public String id() { return path.toString(); }
    static String videoId(Path path) {
        String name=path.getFileName().toString(); int dot=name.lastIndexOf('.');
        var match=VIDEO_SUFFIX.matcher(dot>0?name.substring(0,dot):name);
        return match.find()?match.group(1):null;
    }
    public String extension() {
        String name = path.getFileName().toString();
        return name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }
}
