package io.github.cloraj1.jukebox;

import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;

/** Original test images embedded in synthetic ID3 tags; no downloaded artwork. */
final class ArtworkFixture {
    static byte[] image(int color,String format) throws Exception {
        BufferedImage image=new BufferedImage(64,32,BufferedImage.TYPE_INT_RGB);
        var g=image.createGraphics(); g.setColor(new java.awt.Color(color)); g.fillRect(0,0,64,32); g.dispose();
        var bytes=new ByteArrayOutputStream(); ImageIO.write(image,format,bytes); return bytes.toByteArray();
    }
    static byte[] frame(int version,int type,byte[] image,boolean utf16,boolean unsync) throws Exception {
        var body=new ByteArrayOutputStream(); body.write(utf16?1:0);
        body.write((version==2?"PNG":"image/png\0").getBytes(StandardCharsets.ISO_8859_1)); body.write(type);
        body.write(utf16?"표지".getBytes(StandardCharsets.UTF_16):"cover".getBytes(StandardCharsets.UTF_8));
        body.write(0); if(utf16) body.write(0); body.write(image);
        byte[] payload=body.toByteArray(); if(unsync && version==4) payload=stuff(payload);
        var frame=new ByteArrayOutputStream(); frame.write((version==2?"PIC":"APIC").getBytes(StandardCharsets.US_ASCII));
        if(version==4) frame.write(sync(payload.length)); else number(frame,payload.length,version==2?3:4,false);
        if(version!=2) { frame.write(0); frame.write(unsync && version==4?2:0); }
        frame.write(payload); return frame.toByteArray();
    }
    static byte[] tag(int version,boolean unsync,byte[]... frames) throws Exception {
        var body=new ByteArrayOutputStream(); for(byte[] frame:frames) body.write(frame);
        byte[] payload=body.toByteArray(); if(unsync && version<4) payload=stuff(payload);
        var tag=new ByteArrayOutputStream(); tag.write(new byte[]{'I','D','3',(byte)version,0,(byte)(unsync?0x80:0)});
        tag.write(sync(payload.length)); tag.write(payload); return tag.toByteArray();
    }
    static byte[] wav(byte[] original,byte[] tag) throws Exception {
        var result=new ByteArrayOutputStream(); result.write(original); result.write("JUNK".getBytes(StandardCharsets.US_ASCII));
        number(result,1,4,true); result.write(42); result.write(0);
        result.write("id3 ".getBytes(StandardCharsets.US_ASCII)); number(result,tag.length,4,true); result.write(tag);
        if((tag.length&1)!=0) result.write(0);
        byte[] bytes=result.toByteArray(); int size=bytes.length-8;
        for(int i=0;i<4;i++) bytes[4+i]=(byte)(size>>>(i*8)); return bytes;
    }
    static byte[] sync(int value) { return new byte[]{(byte)(value>>>21&127),(byte)(value>>>14&127),(byte)(value>>>7&127),(byte)(value&127)}; }
    private static void number(ByteArrayOutputStream stream,int n,int count,boolean little) {
        for(int i=0;i<count;i++) stream.write(n>>>(8*(little?i:count-1-i))&255);
    }
    private static byte[] stuff(byte[] bytes) {
        var result=new ByteArrayOutputStream(); for(byte b:bytes) { result.write(b&255); if((b&255)==255) result.write(0); }
        return result.toByteArray();
    }
}
