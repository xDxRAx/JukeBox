package io.github.cloraj1.jukebox;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipInputStream;

/** Uses installed tools; Windows x64 can provision official release assets on first use. */
final class UrlTools {
    private record Asset(String url,long size,String sha256) {}
    static UrlSongImporter.Tools prepare(Path folder,UrlSongImporter job) throws Exception {
        boolean windows=System.getProperty("os.name","").toLowerCase(Locale.ROOT).startsWith("windows");
        String suffix=windows?".exe":"";
        Path downloader=find(folder,"yt-dlp"+suffix),ffmpeg=find(folder,"ffmpeg"+suffix),probe=find(folder,"ffprobe"+suffix);
        Path deno=find(folder,"deno"+suffix),node=find(folder,"node"+suffix);
        boolean auto=windows && List.of("amd64","x86_64").contains(System.getProperty("os.arch",""));
        if(auto) {
            Files.createDirectories(folder);
            if(downloader==null) {
                install(folder,"yt-dlp/yt-dlp","latest","yt-dlp.exe",List.of("yt-dlp.exe"),job); downloader=folder.resolve("yt-dlp.exe");
            }
            if(ffmpeg==null || probe==null || !ffmpeg.getParent().equals(probe.getParent())) {
                install(folder,"yt-dlp/FFmpeg-Builds","tags/latest","ffmpeg-master-latest-win64-gpl.zip",List.of("ffmpeg.exe","ffprobe.exe"),job);
                ffmpeg=folder.resolve("ffmpeg.exe"); probe=folder.resolve("ffprobe.exe");
            }
            // Deno is the recommended runtime for YouTube extraction. It needs no installer.
            if(deno==null) {
                install(folder,"denoland/deno","latest","deno-x86_64-pc-windows-msvc.zip",List.of("deno.exe"),job); deno=folder.resolve("deno.exe");
            }
        }
        if(downloader==null || ffmpeg==null || probe==null || !ffmpeg.getParent().equals(probe.getParent()) || (deno==null && node==null))
            throw new UrlSongImporter.ImportFailure("urlToolsMissing","");
        return new UrlSongImporter.Tools(List.of(downloader.toString()),ffmpeg.getParent(),deno!=null?"deno:"+deno:"node:"+node);
    }
    private static Path find(Path folder,String name) {
        Path managed=folder.resolve(name);
        if(Files.isRegularFile(managed) && Files.isExecutable(managed)) return managed.toAbsolutePath();
        String path=System.getenv("PATH");
        if(path!=null) for(String directory:path.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if(directory.isBlank()) continue;
            try { Path file=Path.of(directory.replace("\"","")).resolve(name).toAbsolutePath(); if(Files.isRegularFile(file) && Files.isExecutable(file)) return file; }
            catch(RuntimeException ignored) {}
        }
        return null;
    }
    private static HttpURLConnection connect(String url) throws IOException {
        HttpURLConnection connection=(HttpURLConnection)URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(20_000); connection.setReadTimeout(20_000);
        connection.setRequestProperty("User-Agent","JukeBox/1.2 (cloraj1)");
        connection.setRequestProperty("Accept","application/vnd.github+json");
        if(connection.getResponseCode()!=200) { int code=connection.getResponseCode(); connection.disconnect(); throw new IOException("GitHub HTTP "+code); }
        if(!connection.getURL().getProtocol().equals("https")) { connection.disconnect(); throw new IOException("HTTPS required"); }
        return connection;
    }
    private static Asset asset(String repo,String release,String filename,UrlSongImporter job) throws Exception {
        HttpURLConnection connection=connect("https://api.github.com/repos/"+repo+"/releases/"+release);
        try(InputStream input=connection.getInputStream()) {
            job.network(input);
            byte[] json=input.readNBytes(2*1024*1024);
            var assets=JsonParser.parseString(new String(json,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("assets");
            for(var element:assets) {
                var item=element.getAsJsonObject(); if(!item.get("name").getAsString().equals(filename)) continue;
                String url=item.get("browser_download_url").getAsString(),digest=item.has("digest") && !item.get("digest").isJsonNull()?item.get("digest").getAsString():"";
                if(!url.startsWith("https://github.com/"+repo+"/releases/download/") || !digest.matches("sha256:[0-9a-f]{64}")) throw new IOException("Release asset checksum unavailable");
                long size=item.get("size").getAsLong(); if(size<=0 || size>300L*1024*1024) throw new IOException("Unexpected release asset size");
                return new Asset(url,size,digest.substring(7));
            }
            throw new IOException("Official release asset missing: "+filename);
        } finally { connection.disconnect(); }
    }
    private static void install(Path folder,String repo,String release,String filename,List<String> executables,UrlSongImporter job) throws Exception {
        Path scratch=Files.createTempDirectory(folder,"setup-");
        try {
            job.progress("urlTools",repo.substring(repo.indexOf('/')+1));
            Asset asset=asset(repo,release,filename,job);
            Path download=scratch.resolve("download"); MessageDigest sha=MessageDigest.getInstance("SHA-256");
            HttpURLConnection connection=connect(asset.url());
            try(InputStream input=connection.getInputStream(); var output=Files.newOutputStream(download)) {
                job.network(input); byte[] buffer=new byte[64*1024]; long count=0; int read;
                while((read=input.read(buffer))!=-1) {
                    job.checkCancelled(); count+=read; if(count>asset.size()) throw new IOException("Release asset exceeded declared size");
                    sha.update(buffer,0,read); output.write(buffer,0,read);
                    job.progress("urlTools",repo.substring(repo.indexOf('/')+1)+" "+count/1048576+" / "+asset.size()/1048576+" MB");
                }
                if(count!=asset.size() || !HexFormat.of().formatHex(sha.digest()).equals(asset.sha256())) throw new IOException("Release asset checksum mismatch");
            } finally { connection.disconnect(); }
            job.checkCancelled();
            if(filename.endsWith(".zip")) extractExecutables(download,scratch,executables,job);
            else Files.move(download,scratch.resolve(executables.get(0)));
            for(String name:executables) {
                job.checkCancelled(); Path source=scratch.resolve(name); if(!Files.isRegularFile(source)) throw new IOException("Executable missing: "+name);
                Files.move(source,folder.resolve(name),StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(folder.resolve(filename+".origin.txt"),"Downloaded by JukeBox from "+asset.url()+"\nSHA-256: "+asset.sha256()+"\nProject source and license: https://github.com/"+repo+"\n");
        } catch(InterruptedException e) { throw e; }
        catch(Exception e) { throw new UrlSongImporter.ImportFailure("urlToolDownloadFailed",e.getMessage()); }
        finally {
            try(var files=Files.walk(scratch)) { for(Path file:files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(file); }
            catch(IOException ignored) {}
        }
    }
    private static void extractExecutables(Path archive,Path destination,List<String> names,UrlSongImporter job) throws Exception {
        try(var zip=new ZipInputStream(Files.newInputStream(archive))) {
            java.util.zip.ZipEntry entry; byte[] buffer=new byte[64*1024];
            while((entry=zip.getNextEntry())!=null) {
                job.checkCancelled(); if(entry.isDirectory()) continue;
                String name=entry.getName().replace('\\','/'); name=name.substring(name.lastIndexOf('/')+1);
                if(!names.contains(name)) continue;
                // Flatten only the exact expected names, so archive paths cannot escape the tool folder.
                try(var output=Files.newOutputStream(destination.resolve(name))) {
                    int read; long count=0; while((read=zip.read(buffer))!=-1) {
                        job.checkCancelled(); count+=read; if(count>400L*1024*1024) throw new IOException("Executable exceeded size limit"); output.write(buffer,0,read);
                    }
                }
            }
        }
    }
}
