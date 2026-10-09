package io.github.cloraj1.jukebox;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** One cancellable import at a time. External tools run directly, never through a shell. */
public final class UrlSongImporter implements AutoCloseable {
    public record Status(boolean busy,String key,String detail) {}
    public record Tools(List<String> downloader,Path ffmpegFolder,String jsRuntime,List<String> ffmpeg) {
        public Tools(List<String> downloader,Path ffmpegFolder,String jsRuntime) {
            this(downloader,ffmpegFolder,jsRuntime,List.of(ffmpegFolder.resolve(System.getProperty("os.name").startsWith("Windows")?"ffmpeg.exe":"ffmpeg").toString()));
        }
    }
    @FunctionalInterface interface ToolProvider { Tools prepare(UrlSongImporter job) throws Exception; }
    private static final Pattern VIDEO_ID=Pattern.compile("[A-Za-z0-9_-]{11}");
    private final Path music,temporary;
    private final ToolProvider tools;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r -> { Thread t=new Thread(r,"JukeBox-URL"); t.setDaemon(true); return t; });
    private volatile Status status=new Status(false,"","");
    private volatile boolean cancelled,closed;
    private volatile Process process;
    private volatile Thread jobThread;
    private volatile java.io.Closeable network;
    private Path completed;
    public final OperationErrors errors=new OperationErrors();
    public UrlSongImporter(Path music,Path toolsFolder) {
        this(music,toolsFolder.resolve("jobs"),job -> UrlTools.prepare(toolsFolder,job));
    }
    UrlSongImporter(Path music,Path temporary,ToolProvider tools) { this.music=music; this.temporary=temporary; this.tools=tools; }
    public Status status() { return status; }
    public synchronized Path consumeCompleted() { Path result=completed; completed=null; return result; }
    public synchronized boolean start(String url) {
        if(closed || status.busy()) return false;
        final String clean;
        try { clean=canonicalUrl(url); }
        catch(IllegalArgumentException e) { status=new Status(false,"urlInvalid",""); errors.signal(); return false; }
        cancelled=false; status=new Status(true,"urlPreparing","");
        worker.execute(() -> run(clean)); return true;
    }
    /** Only a single public YouTube video is accepted; playlist and tracking parameters are dropped. */
    static String canonicalUrl(String input) {
        try {
            if(input==null || input.length()>2048) throw new IllegalArgumentException();
            URI uri=URI.create(input.strip());
            String scheme=uri.getScheme(),host=uri.getHost(),id=null,path=uri.getPath();
            if(scheme==null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http")) || host==null || uri.getUserInfo()!=null || uri.getPort()!=-1) throw new IllegalArgumentException();
            host=host.toLowerCase(Locale.ROOT);
            if(host.equals("youtu.be")) id=path.startsWith("/")?path.substring(1):"";
            else if(List.of("youtube.com","www.youtube.com","m.youtube.com","music.youtube.com","youtube-nocookie.com","www.youtube-nocookie.com").contains(host)) {
                if(path.equals("/watch")) {
                    if(uri.getRawQuery()!=null) for(String part:uri.getRawQuery().split("&")) {
                        String[] pair=part.split("=",2);
                        if(pair[0].equals("v") && pair.length==2) id=URLDecoder.decode(pair[1],StandardCharsets.UTF_8);
                    }
                } else for(String prefix:List.of("/shorts/","/live/","/embed/")) if(path.startsWith(prefix)) id=path.substring(prefix.length());
            }
            if(id==null || !VIDEO_ID.matcher(id).matches()) throw new IllegalArgumentException();
            return "https://www.youtube.com/watch?v="+id;
        } catch(RuntimeException e) { throw new IllegalArgumentException("urlInvalid"); }
    }
    static List<String> command(Tools tools,Path destination,String url) {
        List<String> args=baseCommand(tools,destination);
        args.addAll(List.of("--extract-audio","--audio-format","mp3","--audio-quality","0",
            "--embed-metadata","--write-info-json","--no-embed-thumbnail","--no-write-thumbnail",
            "--format","bestaudio/best","--output","%(uploader).70B - %(title).120B [%(id)s].%(ext)s","--",url));
        return List.copyOf(args);
    }
    private static List<String> baseCommand(Tools tools,Path destination) {
        List<String> args=new ArrayList<>(tools.downloader());
        args.addAll(List.of("--ignore-config","--no-plugin-dirs","--no-playlist","--no-cache-dir","--no-simulate","--windows-filenames",
            "--no-mtime","--newline","--progress","--encoding","utf-8","--color","never",
            "--socket-timeout","15","--retries","3","--fragment-retries","3",
            "--ffmpeg-location",tools.ffmpegFolder().toString(),"--js-runtimes",tools.jsRuntime(),"--paths",destination.toString()));
        return args;
    }
    static List<String> thumbnailCommand(Tools tools,Path destination,String url,Path info) {
        List<String> args=baseCommand(tools,destination);
        args.addAll(List.of("--skip-download","--write-thumbnail","--convert-thumbnails","jpg","--no-embed-thumbnail",
            "--ignore-no-formats-error","--retries","1","--output","cover.%(ext)s"));
        if(info==null) args.addAll(List.of("--",url)); else args.addAll(List.of("--load-info-json",info.toString()));
        return List.copyOf(args);
    }
    static List<String> coverCommand(Tools tools,Path song,Path cover,Path output) {
        List<String> args=new ArrayList<>(tools.ffmpeg());
        args.addAll(List.of("-hide_banner","-loglevel","error","-nostdin","-y","-i",song.toString(),"-i",cover.toString(),
            "-map","0:a:0","-map","1:v:0","-map_metadata","0","-c","copy","-id3v2_version","3",
            "-metadata:s:v","title=Album cover","-metadata:s:v","comment=Cover (front)","-disposition:v:0","attached_pic",output.toString()));
        return List.copyOf(args);
    }
    void progress(String key,String detail) throws InterruptedException { checkCancelled(); status=new Status(true,key,detail); }
    void checkCancelled() throws InterruptedException { if(cancelled || closed || Thread.currentThread().isInterrupted()) throw new InterruptedException(); }
    void network(java.io.Closeable stream) throws InterruptedException { network=stream; checkCancelled(); }
    public void cancel() {
        cancelled=true;
        Process current=process; if(current!=null) stopProcess(current);
        java.io.Closeable currentStream=network; if(currentStream!=null) try { currentStream.close(); } catch(IOException ignored) {}
        Thread currentThread=jobThread; if(currentThread!=null) currentThread.interrupt();
    }
    private static void stopProcess(Process current) {
        current.descendants().forEach(handle -> { try { handle.destroyForcibly(); } catch(RuntimeException ignored) {} });
        current.destroyForcibly();
    }
    private void run(String url) {
        Path job=null; Status result;
        jobThread=Thread.currentThread();
        try {
            String videoId=url.substring(url.lastIndexOf('=')+1);
            checkCancelled(); SongImportGuard.check(music,videoId,null);
            checkCancelled(); Tools ready=tools.prepare(this); checkCancelled();
            Files.createDirectories(temporary); job=Files.createTempDirectory(temporary,"song-");
            Path log=job.resolve("import.log");
            progress("urlDownloading","");
            if(runProcess(command(ready,job,url),job,log,Duration.ofHours(1),true)!=0) throw new ImportFailure("urlDownloadFailed",errorLine(log));
            List<Path> songs;
            try(var files=Files.list(job)) { songs=files.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mp3")).toList(); }
            if(songs.size()!=1 || Files.size(songs.get(0))==0) throw new ImportFailure("urlNoAudio","");
            Path song=songs.get(0);
            if(AudioDecoder.duration(Track.fromPath(job,song))<=0) throw new ImportFailure("urlNoAudio","");
            Track incoming=Track.fromPath(job,song);
            SongImportGuard.check(music,videoId,incoming);
            boolean fallback=addArtwork(ready,job,song,url);
            checkCancelled(); Files.createDirectories(music);
            SongImportGuard.check(music,videoId,incoming);
            Path imported=moveNew(song,music);
            synchronized(this) { completed=imported; }
            result=new Status(false,fallback?"urlSuccessFallback":"urlSuccess",imported.getFileName().toString());
            if(fallback) errors.signal();
        } catch(InterruptedException e) { result=new Status(false,"urlCancelled",""); }
        catch(ImportFailure e) { result=new Status(false,cancelled || closed?"urlCancelled":e.key,safeDetail(e.getMessage())); }
        catch(Exception e) {
            System.err.println("JukeBox URL import: "+e);
            result=new Status(false,cancelled || closed?"urlCancelled":"urlImportFailed",safeDetail(e.getMessage()));
        } finally {
            Process current=process; if(current!=null && current.isAlive()) stopProcess(current);
            process=null; network=null;
            // Interruption must not prevent deletion of this job's partial downloads.
            Thread.interrupted();
            if(job!=null) try { cleanup(job); }
            catch(IOException e) { System.err.println("JukeBox: temporary import cleanup: "+e.getMessage()); }
            jobThread=null;
        }
        if(!result.key().equals("urlCancelled") && !result.key().startsWith("urlSuccess")) errors.signal();
        status=result;
    }
    private static void cleanup(Path job) throws IOException {
        List<Path> entries;
        try(var files=Files.walk(job)) { entries=files.sorted(Comparator.reverseOrder()).toList(); }
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        for(Path file:entries) {
            while(true) {
                try { Files.deleteIfExists(file); break; }
                catch(java.nio.file.FileSystemException e) {
                    // Termination can precede release of Windows file handles.
                    if(System.nanoTime()>=deadline) throw e;
                    try { Thread.sleep(25); } catch(InterruptedException ignored) {}
                }
            }
        }
    }
    private int runProcess(List<String> command,Path folder,Path log,Duration timeout,boolean audioProgress) throws Exception {
        checkCancelled(); Process current=new ProcessBuilder(command).directory(folder.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        process=current;
        try {
            long deadline=System.nanoTime()+timeout.toNanos();
            while(!current.waitFor(200,TimeUnit.MILLISECONDS)) {
                checkCancelled();
                if(System.nanoTime()>deadline) throw new IOException("Import step timed out");
                if(Files.size(log)>4*1024*1024) throw new IOException("Import log exceeded limit");
                if(audioProgress) updateProgress(log);
            }
            checkCancelled(); return current.exitValue();
        } finally {
            // Windows/Java 17 terminates asynchronously; wait on the worker before
            // deleting files still held by the converter's redirected output.
            boolean interrupted=Thread.interrupted();
            if(current.isAlive()) stopProcess(current);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(System.nanoTime()<deadline) {
                try { if(current.waitFor(100,TimeUnit.MILLISECONDS)) break; }
                catch(InterruptedException e) { interrupted=true; stopProcess(current); }
            }
            process=null;
            if(interrupted) Thread.currentThread().interrupt();
        }
    }
    /** Album art is optional: failures cannot discard successfully downloaded audio. */
    private boolean addArtwork(Tools ready,Path job,Path song,String url) throws InterruptedException {
        Path cover=job.resolve("cover.jpg"); boolean fallback=true;
        try {
            progress("urlArtwork",""); Path directory=Files.createDirectory(job.resolve("artwork")),info;
            try(var files=Files.list(job)) { info=files.filter(p -> p.getFileName().toString().endsWith(".info.json")).findFirst().orElse(null); }
            if(runProcess(thumbnailCommand(ready,directory,url,info),directory,job.resolve("artwork.log"),Duration.ofSeconds(45),false)==0) {
                List<Path> images;
                try(var files=Files.list(directory)) { images=files.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jpg")).toList(); }
                if(images.size()==1) fallback=!AlbumArt.writeSquare(images.get(0),cover);
            }
        } catch(InterruptedException e) { throw e; }
        catch(Exception e) { System.err.println("JukeBox: using default album cover: "+e.getMessage()); }
        checkCancelled();
        for(int attempt=0;attempt<2;attempt++) {
            try {
                if(fallback) AlbumArt.writeFallback(cover);
                Path output=job.resolve("covered.mp3");
                if(runProcess(coverCommand(ready,song,cover,output),job,job.resolve("embed-cover.log"),Duration.ofSeconds(30),false)==0 &&
                    Files.isRegularFile(output) && Files.size(output)>0 && AudioDecoder.duration(Track.fromPath(job,output))>0 && AlbumArt.read(output)!=null) {
                    checkCancelled(); Files.move(output,song,java.nio.file.StandardCopyOption.REPLACE_EXISTING); return fallback;
                }
            } catch(InterruptedException e) { throw e; }
            catch(Exception e) { System.err.println("JukeBox: cover embedding failed: "+e.getMessage()); }
            checkCancelled(); if(fallback) break; fallback=true;
        }
        // The reader supplies the same ♪ cover if even the fallback cannot be embedded.
        return true;
    }
    static Path moveNew(Path source,Path folder) throws IOException {
        Path target=folder.resolve(source.getFileName());
        try { return Files.move(source,target); }
        catch(java.nio.file.FileAlreadyExistsException e) { throw new ImportFailure("urlDuplicate",Track.fromPath(folder,target).title()); }
    }
    private void updateProgress(Path log) throws IOException,InterruptedException {
        String tail=tail(log);
        if(tail.contains("[ExtractAudio]") || tail.contains("[Metadata]")) progress("urlConverting","");
        else {
            var matcher=Pattern.compile("\\[download]\\s+([0-9.]+%)").matcher(tail); String percent="";
            while(matcher.find()) percent=matcher.group(1);
            if(!percent.isEmpty()) progress("urlDownloading",percent);
        }
    }
    private static String tail(Path log) throws IOException {
        try(var file=new java.io.RandomAccessFile(log.toFile(),"r")) {
            long length=file.length(); file.seek(Math.max(0,length-8192)); byte[] bytes=new byte[(int)Math.min(length,8192)]; file.readFully(bytes);
            return new String(bytes,StandardCharsets.UTF_8);
        }
    }
    private static String errorLine(Path log) throws IOException {
        String error=""; for(String line:tail(log).split("\\R")) if(line.startsWith("ERROR:")) error=line.substring(6).strip();
        return safeDetail(error);
    }
    private static String safeDetail(String message) {
        if(message==null) return "";
        String text=message.replaceAll("[\\p{Cntrl}]"," ").strip(); return text.substring(0,Math.min(text.length(),300));
    }
    static final class ImportFailure extends IOException {
        final String key;
        ImportFailure(String key,String message) { super(message); this.key=key; }
    }
    @Override public void close() { closed=true; cancel(); worker.shutdownNow(); }
}
