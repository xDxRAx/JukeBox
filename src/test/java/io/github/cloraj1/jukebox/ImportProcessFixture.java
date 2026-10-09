package io.github.cloraj1.jukebox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Local subprocess fixture; no network requests or third-party recordings. */
public final class ImportProcessFixture {
    public static void main(String[] args) throws Exception {
        var options=Arrays.asList(args);
        if(args[0].equals("embed")) {
            Path song=Path.of(options.get(options.indexOf("-i")+1)),cover=Path.of(options.get(options.lastIndexOf("-i")+1)),output=Path.of(args[args.length-1]);
            if(args[1].equals("embedhang")) { Files.writeString(output.getParent().resolve("ready-cover"),"ready"); Thread.sleep(60_000); return; }
            if(args[1].equals("embedfail") || args[1].equals("embedfirstfail") && (javax.imageio.ImageIO.read(cover.toFile()).getRGB(0,0)&0xFFFFFF)!=0) {
                System.out.println("ERROR: Test image embedding rejected"); System.exit(2);
            }
            byte[] tag=ArtworkFixture.tag(3,false,ArtworkFixture.frame(3,3,Files.readAllBytes(cover),false,false));
            var bytes=new java.io.ByteArrayOutputStream(); bytes.write(tag); bytes.write(Files.readAllBytes(song)); Files.write(output,bytes.toByteArray()); return;
        }
        Path folder=Path.of(options.get(options.indexOf("--paths")+1));
        if(options.contains("--skip-download")) {
            if(args[0].equals("coverhang")) { Files.writeString(folder.resolve("ready-cover"),"ready"); Thread.sleep(60_000); return; }
            if(args[0].equals("coverfail")) { System.out.println("ERROR: Unable to download thumbnail: HTTP Error 403: Forbidden"); System.exit(2); }
            if(args[0].equals("covermissing")) return;
            if(args[0].equals("covercorrupt")) Files.writeString(folder.resolve("cover.jpg"),"bad image");
            else Files.write(folder.resolve("cover.jpg"),ArtworkFixture.image(0x61759F,"jpeg"));
            return;
        }
        if(args[0].equals("fail")) { Files.writeString(folder.resolve("incomplete.part"),"partial"); System.out.println("ERROR: Test download rejected"); System.exit(2); }
        if(args[0].equals("audio403")) { Files.writeString(folder.resolve("incomplete.part"),"partial"); System.out.println("ERROR: unable to download video data: HTTP Error 403: Forbidden"); System.exit(2); }
        if(args[0].equals("hang")) { Files.writeString(folder.resolve("ready"),"ready"); Thread.sleep(60_000); return; }
        System.out.println("[download] 100.0%"); System.out.println("[ExtractAudio] Destination: Fixture.mp3");
        if(args[0].equals("corrupt")) Files.writeString(folder.resolve("Fixture.mp3"),"not audio");
        else {
            String url=args[args.length-1],id=url.substring(url.lastIndexOf('=')+1);
            Files.copy(Path.of(args[1]),folder.resolve("Artist - Fixture ["+id+"].mp3"));
        }
    }
}
