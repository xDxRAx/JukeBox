package io.github.cloraj1.jukebox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class AlbumArtTest {
    @TempDir Path temp;
    private Path file(byte[] bytes) throws Exception { Path path=temp.resolve("test.mp3"); Files.write(path,bytes); return path; }
    private AlbumArt.Picture await(AlbumArt reader,Track track) throws Exception {
        long deadline=System.nanoTime()+5_000_000_000L; AlbumArt.Picture art;
        while((art=reader.get(track))==null && System.nanoTime()<deadline) Thread.sleep(5);
        assertNotNull(art); return art;
    }
    @Test void picAndApicVersionsUnicodeDescriptionsAndFrontCoverPreference() throws Exception {
        byte[] red=ArtworkFixture.image(0xCC2233,"png"),green=ArtworkFixture.image(0x22CC33,"png");
        for(int version:new int[]{2,3,4}) {
            byte[] tag=ArtworkFixture.tag(version,false,ArtworkFixture.frame(version,0,red,false,false),ArtworkFixture.frame(version,3,green,true,false));
            AlbumArt.Picture picture=AlbumArt.read(file(tag)); assertNotNull(picture);
            assertEquals(128*128,picture.pixels().length); assertEquals(0xFF22CC33,picture.pixels()[64*128+64]);
            assertEquals(0xFF22CC33,picture.pixels()[0]);
        }
    }
    @Test void jpegGlobalAndFrameUnsynchronizationDecodeWithoutChangingAudio() throws Exception {
        byte[] jpeg=ArtworkFixture.image(0xCC2233,"jpeg");
        for(int version:new int[]{3,4}) {
            byte[] tag=ArtworkFixture.tag(version,version==3,ArtworkFixture.frame(version,3,jpeg,false,true));
            Path path=file(tag); assertNotNull(AlbumArt.read(path)); assertArrayEquals(tag,Files.readAllBytes(path));
        }
    }
    @Test void riffWavArtSkipsOddPaddedChunksAndKeepsPlayableAudio() throws Exception {
        byte[] wave; try(var stream=getClass().getResourceAsStream("/tone.wav")) { wave=stream.readAllBytes(); }
        byte[] tag=ArtworkFixture.tag(3,false,ArtworkFixture.frame(3,3,ArtworkFixture.image(0xCC2233,"png"),false,false));
        Path path=temp.resolve("embedded.wav"); Files.write(path,ArtworkFixture.wav(wave,tag));
        assertNotNull(AlbumArt.read(path)); Track track=Track.fromPath(temp,path);
        assertTrue(AudioDecoder.duration(track)>1.5);
    }
    @Test void absentTruncatedMalformedAndOversizedTagsAreIgnored() throws Exception {
        assertNull(AlbumArt.read(file(new byte[24])));
        byte[] tag=ArtworkFixture.tag(4,false,ArtworkFixture.frame(4,3,ArtworkFixture.image(0xCC2233,"png"),false,false));
        assertNull(AlbumArt.read(file(Arrays.copyOf(tag,tag.length-4))));
        byte[] bad=tag.clone(); bad[6]=(byte)0x80; assertNull(AlbumArt.read(file(bad)));
        byte[] huge=tag.clone(); System.arraycopy(ArtworkFixture.sync(32*1024*1024),0,huge,6,4); assertNull(AlbumArt.read(file(huge)));
        byte[] frame=tag.clone(); Arrays.fill(frame,14,18,(byte)0x7F); assertNull(AlbumArt.read(file(frame)));
    }
    @Test void asynchronousCacheIsOptInReloadableAndClosesCleanly() throws Exception {
        Config.load(temp.resolve("config"),temp);
        byte[] tag=ArtworkFixture.tag(3,false,ArtworkFixture.frame(3,3,ArtworkFixture.image(0xCC2233,"png"),false,false));
        Path path=file(tag); Track track=Track.fromPath(temp,path); AlbumArt reader=new AlbumArt();
        try {
            assertNull(reader.get(track)); Config.get().showAlbumArt=true;
            AlbumArt.Picture first=await(reader,track); assertSame(first,reader.get(track));
            Files.write(path,ArtworkFixture.tag(3,false,ArtworkFixture.frame(3,3,ArtworkFixture.image(0x22CC33,"png"),false,false)));
            assertSame(first,reader.get(track)); reader.clear();
            AlbumArt.Picture second=await(reader,track); assertNotSame(first,second); assertEquals(0xFF22CC33,second.pixels()[64*128+64]);
            Config.get().showAlbumArt=false; assertNull(reader.get(track));
        } finally { reader.close(); }
        Config.get().showAlbumArt=true; assertNull(reader.get(track));
    }
    @Test void landscapeAndPortraitCoversCropTheCenterAndFillTheWholeSquare() throws Exception {
        for(int[] size:new int[][]{{96,32},{32,96}}) {
            var image=new java.awt.image.BufferedImage(size[0],size[1],java.awt.image.BufferedImage.TYPE_INT_RGB); var g=image.createGraphics();
            g.setColor(java.awt.Color.RED); g.fillRect(0,0,size[0],size[1]);
            g.setColor(java.awt.Color.GREEN); g.fillRect((size[0]-32)/2,(size[1]-32)/2,32,32); g.dispose();
            var bytes=new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(image,"png",bytes);
            var art=AlbumArt.read(file(ArtworkFixture.tag(4,false,ArtworkFixture.frame(4,3,bytes.toByteArray(),false,false))));
            assertNotNull(art); for(int pixel:art.pixels()) assertEquals(0xFF00FF00,pixel);
        }
    }
    @Test void importedCoversAreSquareJpegAndDefaultIsBlackWithAWhiteMusicNote() throws Exception {
        Path input=temp.resolve("wide.png"),cover=temp.resolve("square.jpg"),placeholder=temp.resolve("default.jpg");
        Files.write(input,ArtworkFixture.image(0x22CC33,"png")); assertTrue(AlbumArt.writeSquare(input,cover));
        var image=javax.imageio.ImageIO.read(cover.toFile()); assertEquals(512,image.getWidth()); assertEquals(512,image.getHeight());
        assertTrue((image.getRGB(0,0)>>8&255)>190); assertTrue((image.getRGB(511,511)>>8&255)>190);
        AlbumArt.writeFallback(placeholder); image=javax.imageio.ImageIO.read(placeholder.toFile());
        assertEquals(512,image.getWidth()); assertEquals(512,image.getHeight()); assertEquals(0xFF000000,image.getRGB(0,0));
        long white=Arrays.stream(AlbumArt.fallback().pixels()).filter(p -> p==0xFFFFFFFF).count(); assertTrue(white>200 && white<2000);
        assertTrue((AlbumArt.fallback().pixels()[60*128+61]&255)>180);
        assertEquals(0xFFFFFFFF,AlbumArt.fallback().pixels()[85*128+54]);
    }
    @Test void optInMissingAndInvalidArtUsesTheSameDefaultWithoutChangingTheFile() throws Exception {
        Config.load(temp.resolve("config"),temp); Config.get().showAlbumArt=true;
        for(byte[] bytes:new byte[][]{new byte[24],ArtworkFixture.tag(4,false,ArtworkFixture.frame(4,3,new byte[24],false,false))}) {
            Path path=file(bytes); Track track=Track.fromPath(temp,path);
            try(AlbumArt reader=new AlbumArt()) { assertSame(AlbumArt.fallback(),await(reader,track)); assertArrayEquals(bytes,Files.readAllBytes(path)); }
        }
    }
}
