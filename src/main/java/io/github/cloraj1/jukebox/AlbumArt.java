package io.github.cloraj1.jukebox;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/** Bounded, asynchronous ID3 APIC/PIC artwork reader for MP3 and RIFF WAV files. */
public final class AlbumArt implements AutoCloseable {
    private static final int MAX_TAG=16*1024*1024, MAX_IMAGE=8*1024*1024;
    public static final int SIDE=128;
    public record Picture(int[] pixels) {}
    private final LinkedHashMap<String,CompletableFuture<Picture>> cache=new LinkedHashMap<>(32,.75f,true);
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64),r -> {
        Thread t=new Thread(r,"JukeBox-AlbumArt"); t.setDaemon(true); return t;
    });
    private boolean closed;
    private final java.util.concurrent.locks.Lock fileRead;
    public AlbumArt() { this(new java.util.concurrent.locks.ReentrantReadWriteLock().readLock()); }
    AlbumArt(java.util.concurrent.locks.Lock fileRead) { this.fileRead=fileRead; }
    public synchronized Picture get(Track track) {
        if(closed || !Config.get().showAlbumArt || track==null) return null;
        CompletableFuture<Picture> value=cache.get(track.id());
        if(value==null) {
            value=new CompletableFuture<>(); cache.put(track.id(),value);
            CompletableFuture<Picture> pending=value;
            try { worker.execute(() -> {
                if(pending.isCancelled()) return;
                try {
                    fileRead.lockInterruptibly();
                    try { if(pending.isCancelled()) return; Picture picture=read(track.path()); pending.complete(picture==null?fallback():picture); }
                    finally { fileRead.unlock(); }
                }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); pending.cancel(false); }
                catch(Exception e) { pending.complete(fallback()); }
            }); } catch(java.util.concurrent.RejectedExecutionException e) { cache.remove(track.id()); return null; }
            while(cache.size()>32) { var first=cache.entrySet().iterator(); first.next().getValue().cancel(false); first.remove(); }
        }
        return value.isCancelled()?null:value.getNow(null);
    }
    public synchronized void clear() { cache.values().forEach(v -> v.cancel(false)); cache.clear(); }
    @Override public synchronized void close() { closed=true; clear(); worker.shutdownNow(); }

    static Picture read(Path path) throws Exception {
        try(RandomAccessFile file=new RandomAccessFile(path.toFile(),"r")) {
            if(file.length()<10) return null;
            byte[] header=new byte[12]; file.readFully(header,0,(int)Math.min(12,file.length()));
            if(ascii(header,0,3).equals("ID3")) { file.seek(0); return readTag(file,file.length()); }
            if(!ascii(header,0,4).equals("RIFF") || !ascii(header,8,4).equals("WAVE")) return null;
            long end=Math.min(file.length(),8+little(header,4));
            while(file.getFilePointer()+8<=end) {
                byte[] chunk=new byte[8]; file.readFully(chunk); long size=little(chunk,4),start=file.getFilePointer();
                if(size>end-start) return null;
                if(ascii(chunk,0,4).equalsIgnoreCase("id3 ")) return readTag(file,size);
                file.seek(start+size+(size&1));
            }
            return null;
        }
    }
    private static Picture readTag(RandomAccessFile file,long available) throws Exception {
        if(available<10) return null;
        byte[] header=new byte[10]; file.readFully(header);
        int version=header[3]&255,size=sync(header,6);
        if(!ascii(header,0,3).equals("ID3") || version<2 || version>4 || size<0 || size>MAX_TAG || size>available-10) return null;
        if(version==2 && (header[5]&0x40)!=0) return null;
        byte[] tag=new byte[size]; file.readFully(tag);
        boolean unsync=(header[5]&0x80)!=0;
        if(unsync && version<4) tag=unsync(tag);
        int pos=0;
        if(version>=3 && (header[5]&0x40)!=0) {
            if(tag.length<4) return null;
            long extra=version==4?sync(tag,0):big(tag,0,4)+4;
            if(extra<4 || extra>tag.length) return null;
            pos=(int)extra;
        }
        byte[] selected=null;
        int frameHeader=version==2?6:10;
        while(pos+frameHeader<=tag.length) {
            String name=ascii(tag,pos,version==2?3:4);
            if(tag[pos]==0) break;
            long length=version==2?big(tag,pos+3,3):version==4?sync(tag,pos+4):big(tag,pos+4,4);
            if(length<=0 || length>tag.length-pos-frameHeader) break;
            int flags=version==2?0:tag[pos+9]&255,start=pos+frameHeader;
            if(name.equals(version==2?"PIC":"APIC") && (flags&(version==3?0xC0:0x0C))==0) {
                byte[] data=Arrays.copyOfRange(tag,start,start+(int)length);
                if(version==4 && (unsync || (flags&2)!=0)) data=unsync(data);
                int prefix=(version==3 && (flags&0x20)!=0 || version==4 && (flags&0x40)!=0)?1:0;
                if(version==4 && (flags&1)!=0) prefix+=4;
                if(prefix>0 && prefix<data.length) data=Arrays.copyOfRange(data,prefix,data.length);
                ImageFrame frame=picture(data,version);
                if(frame!=null) { if(selected==null || frame.type==3) selected=frame.bytes; if(frame.type==3) break; }
            }
            pos=start+(int)length;
        }
        return selected==null?null:decode(selected);
    }
    private record ImageFrame(int type,byte[] bytes) {}
    private static ImageFrame picture(byte[] data,int version) {
        if(data.length<6) return null;
        int encoding=data[0]&255,pos=version==2?4:1;
        if(encoding>3) return null;
        if(version!=2) { while(pos<data.length && data[pos]!=0) pos++; pos++; }
        if(pos>=data.length) return null;
        int type=data[pos++]&255;
        if(encoding==1 || encoding==2) {
            while(pos+1<data.length && (data[pos]!=0 || data[pos+1]!=0)) pos+=2;
            pos+=2;
        } else { while(pos<data.length && data[pos]!=0) pos++; pos++; }
        if(pos>=data.length || data.length-pos>MAX_IMAGE) return null;
        return new ImageFrame(type,Arrays.copyOfRange(data,pos,data.length));
    }
    private static Picture decode(byte[] bytes) throws Exception {
        BufferedImage image=square(bytes,SIDE);
        return image==null?null:new Picture(image.getRGB(0,0,SIDE,SIDE,null,0,SIDE));
    }
    /** Normalize downloaded images before embedding; the same crop is used for existing tags. */
    static boolean writeSquare(Path source,Path destination) throws IOException {
        if(!Files.isRegularFile(source) || Files.size(source)>MAX_IMAGE) return false;
        BufferedImage image=square(Files.readAllBytes(source),512);
        return image!=null && ImageIO.write(image,"jpg",destination.toFile());
    }
    private static BufferedImage square(byte[] bytes,int side) throws IOException {
        try(var stream=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers=ImageIO.getImageReaders(stream); if(!readers.hasNext()) return null;
            var reader=readers.next();
            try {
                reader.setInput(stream,true,true);
                int w=reader.getWidth(0),h=reader.getHeight(0);
                if(w<=0 || h<=0 || w>8192 || h>8192 || (long)w*h>16_000_000) return null;
                int edge=Math.min(w,h);
                var param=reader.getDefaultReadParam(); int sample=Math.max(1,edge/side);
                param.setSourceRegion(new java.awt.Rectangle((w-edge)/2,(h-edge)/2,edge,edge));
                param.setSourceSubsampling(sample,sample,0,0);
                BufferedImage source=reader.read(0,param),image=new BufferedImage(side,side,BufferedImage.TYPE_INT_RGB);
                var g=image.createGraphics();
                try {
                    g.setColor(java.awt.Color.BLACK); g.fillRect(0,0,side,side);
                    g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g.drawImage(source,0,0,side,side,null);
                } finally { g.dispose(); }
                return image;
            } finally { reader.dispose(); }
        }
    }
    private static final class DefaultCover {
        static final Picture PICTURE=new Picture(fallbackImage(SIDE).getRGB(0,0,SIDE,SIDE,null,0,SIDE));
    }
    static Picture fallback() { return DefaultCover.PICTURE; }
    static void writeFallback(Path destination) throws IOException {
        if(!ImageIO.write(fallbackImage(512),"jpg",destination.toFile())) throw new IOException("JPEG writer unavailable");
    }
    /** Draw ♪ as a vector so the default cover does not depend on installed fonts. */
    static BufferedImage fallbackImage(int side) {
        BufferedImage image=new BufferedImage(side,side,BufferedImage.TYPE_INT_RGB); var g=image.createGraphics();
        try {
            g.setColor(java.awt.Color.BLACK); g.fillRect(0,0,side,side);
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g.scale(side/300.0,side/300.0); g.translate(16,0); g.setColor(java.awt.Color.WHITE);
            var note=new java.awt.geom.Path2D.Double();
            note.moveTo(121,73); note.curveTo(126,98,174,113,174,139);
            note.curveTo(175,157,158,178,146,184); note.curveTo(153,167,167,151,164,138);
            note.curveTo(160,124,140,117,129,106); note.lineTo(129,185);
            note.curveTo(139,207,110,221,94,210); note.curveTo(79,198,96,182,121,185);
            note.closePath(); g.fill(note);
        } finally { g.dispose(); }
        return image;
    }
    private static byte[] unsync(byte[] bytes) {
        byte[] result=new byte[bytes.length]; int end=0;
        for(int i=0;i<bytes.length;i++) { result[end++]=bytes[i]; if((bytes[i]&255)==255 && i+1<bytes.length && bytes[i+1]==0) i++; }
        return Arrays.copyOf(result,end);
    }
    private static int sync(byte[] b,int p) {
        for(int i=0;i<4;i++) if((b[p+i]&0x80)!=0) return -1;
        return (b[p]&127)<<21|(b[p+1]&127)<<14|(b[p+2]&127)<<7|b[p+3]&127;
    }
    private static long big(byte[] b,int p,int n) { long x=0; for(int i=0;i<n;i++) x=x<<8|(b[p+i]&255); return x; }
    private static long little(byte[] b,int p) { return (b[p]&255L)|(b[p+1]&255L)<<8|(b[p+2]&255L)<<16|(b[p+3]&255L)<<24; }
    private static String ascii(byte[] b,int p,int n) { return new String(b,p,n,StandardCharsets.US_ASCII); }
}
