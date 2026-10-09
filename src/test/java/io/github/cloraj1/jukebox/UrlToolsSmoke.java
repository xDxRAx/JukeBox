package io.github.cloraj1.jukebox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Opt-in online tool provisioning and local conversion check; excluded from unit tests. */
public final class UrlToolsSmoke {
    public static void main(String[] args) throws Exception {
        Path folder=Path.of(args[0]).toAbsolutePath();
        try(var job=new UrlSongImporter(folder.resolve("music"),folder)) {
            var tools=UrlTools.prepare(folder,job);
            var version=new ArrayList<>(tools.downloader()); version.add("--version"); run(version);
            run(List.of(folder.resolve("deno.exe").toString(),"--version"));
            run(List.of(tools.ffmpegFolder().resolve("ffprobe.exe").toString(),"-version"));
            Path wav=folder.resolve("tone.wav"),mp3=folder.resolve("converted.mp3");
            try(var input=UrlToolsSmoke.class.getResourceAsStream("/tone.wav")) { Files.copy(input,wav,java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            run(List.of(tools.ffmpegFolder().resolve("ffmpeg.exe").toString(),"-hide_banner","-y","-i",wav.toString(),"-codec:a","libmp3lame","-q:a","0",mp3.toString()));
            double seconds=AudioDecoder.duration(Track.fromPath(folder,mp3));
            if(seconds<1.9 || seconds>2.2) throw new AssertionError("Bad converted MP3 duration: "+seconds);
            System.out.println("Official tools installed; WAV -> MP3 -> JLayer decode OK ("+seconds+" seconds).");
            Path cover=folder.resolve("default-cover.jpg"),covered=folder.resolve("covered.mp3"); AlbumArt.writeFallback(cover);
            run(UrlSongImporter.coverCommand(tools,mp3,cover,covered));
            if(AlbumArt.read(covered)==null || AudioDecoder.duration(Track.fromPath(folder,covered))<1.9) throw new AssertionError("Fallback cover embedding failed");
            System.out.println("FFmpeg stream-copy embeds the generated square music-note cover; JLayer/album-art decode OK.");
            // Exercise the actual downloader's parser/options without fetching any media.
            var check=new ArrayList<>(UrlSongImporter.command(tools,folder,"https://www.youtube.com/watch?v=abcdefghijk")); check.add(1,"--help"); run(check);
            if(args.length>1) {
                job.start(args[1]); long deadline=System.nanoTime()+180_000_000_000L;
                var previous=job.status();
                while(job.status().busy() && System.nanoTime()<deadline) {
                    Thread.sleep(200);
                    if(!job.status().equals(previous)) { previous=job.status(); System.out.println(previous); }
                }
                if(job.status().busy()) { job.cancel(); throw new AssertionError("Online import timed out"); }
                Path imported=job.consumeCompleted();
                if(imported==null) throw new AssertionError("Online import failed: "+job.status());
                System.out.println("YouTube -> MP3 -> library folder -> JLayer decode OK: "+imported.getFileName()+" ("+AudioDecoder.duration(Track.fromPath(imported.getParent(),imported))+" seconds)");
                if(AlbumArt.read(imported)==null) throw new AssertionError("Imported album cover missing");
            }
        }
    }
    private static void run(List<String> command) throws Exception {
        Path log=Files.createTempFile("jukebox-tool-check-",".log");
        try {
            Process process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if(!process.waitFor(60,java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("Tool check timed out"); }
            if(process.exitValue()!=0) throw new AssertionError(Files.readString(log));
            System.out.println(command.get(0)+": "+Files.readString(log).lines().findFirst().orElse("OK"));
        } finally { Files.deleteIfExists(log); }
    }
}
