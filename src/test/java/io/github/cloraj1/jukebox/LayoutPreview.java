package io.github.cloraj1.jukebox;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFormat;
import com.google.gson.Gson;

/** Renders the same MusicView using Minecraft's ASCII bitmap font for layout inspection. */
public final class LayoutPreview {
    public static void main(String[] args) throws Exception {
        Path output=Path.of(args[0]),fontPath=Path.of(args[1]),scratch=Path.of(args[2]);
        Files.createDirectories(scratch);
        Config.load(scratch.resolve("config"),scratch);
        Config.get().playbackNotifications=true; Config.get().alwaysPlaybackNotification=false; Config.get().showAlbumArt=false;
        Path music=scratch.resolve("music"); Files.createDirectories(music.resolve("Evening Walk")); Files.createDirectories(music.resolve("Quiet Hours"));
        List<String> names=List.of("Juniper - After the Rain","Seoul Sessions - Blue Hour","Juniper - City Lights","Analog Days - First Light","Nara - Floating","Nara - Home Again","Analog Days - Into the Woods","Seoul Sessions - Night Drive");
        for(int i=0;i<names.size();i++) {
            Path folder=i%3==0?music.resolve("Evening Walk"):i%3==1?music.resolve("Quiet Hours"):music;
            try(var input=LayoutPreview.class.getResourceAsStream("/tone.wav")) {
                int color=new int[]{0x61759F,0xC7A16A,0x70876A,0x98787A}[i%4];
                byte[] art=ArtworkFixture.tag(3,false,ArtworkFixture.frame(3,3,ArtworkFixture.image(color,"png"),false,false));
                Files.write(folder.resolve(names.get(i)+".wav"),ArtworkFixture.wav(input.readAllBytes(),art));
            }
        }
        MusicPlayer player=new MusicPlayer(f -> new MusicPlayer.Output() {
            volatile boolean closed;
            public void write(byte[] b,int o,int n) { try { if(!closed) Thread.sleep(100); } catch(InterruptedException ignored) {} }
            public void pause(boolean p) {}
            public void drain() {}
            public double seconds() { return 1.2; }
            public void close() { closed=true; }
        });
        try(MusicSession session=new MusicSession(music,player)) {
            while(session.loading() || session.duration(session.library().isEmpty()?null:session.library().get(0))==0) Thread.sleep(10);
            Track selected=session.library().get(0); session.play(selected,session.library());
            Config.get().liked.add(selected.id()); Config.get().volume=39;
            while(player.position()==0) Thread.sleep(10);
            player.pauseToggle();
            var labels=new Gson().fromJson(Files.readString(Path.of("src/main/resources/assets/jukebox/lang/en_us.json")),java.util.Map.class);
            var uiTime=new java.util.concurrent.atomic.AtomicLong();
            MusicView view=new MusicView(session,k -> String.valueOf(labels.get("jukebox."+k)),() -> {},null,uiTime::get);
            String playlist=session.playlists().stream().filter(p -> p.name().equals("Evening Mix")).map(MusicSession.PlaylistInfo::id).findFirst().orElseGet(() -> session.createPlaylist("Evening Mix"));
            for(Track track:session.library().subList(0,4)) session.addPlaylistTrack(playlist,track);
            int w=480,h=276; view.resize(w,h);
            BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB),font=ImageIO.read(fontPath.toFile());
            MusicView.Canvas canvas=new MusicView.Canvas() {
                private int glyphWidth(char ch) {
                    if(ch==' ') return 3; if(ch>255) return 5;
                    int sx=(ch%16)*8,sy=(ch/16)*8,last=0;
                    for(int x=0;x<8;x++) for(int y=0;y<8;y++) if((font.getRGB(sx+x,sy+y)>>>24)>0) last=Math.max(last,x+1);
                    return last;
                }
                public void fill(int x1,int y1,int x2,int y2,int color) {
                    int a=color>>>24;
                    for(int y=Math.max(0,y1);y<Math.min(h,y2);y++) for(int x=Math.max(0,x1);x<Math.min(w,x2);x++) {
                        int paint=color;
                        if(a<255) { int old=image.getRGB(x,y); paint=0xFF000000; for(int shift:new int[]{0,8,16}) paint|=((((color>>shift)&255)*a+((old>>shift)&255)*(255-a))/255)<<shift; }
                        image.setRGB(x,y,paint);
                    }
                }
                public int width(String s) { int size=0; for(char ch:s.toCharArray()) size+=glyphWidth(ch)+1; return size; }
                public boolean cover(Track track,int x,int y,int size) {
                    AlbumArt.Picture art=session.albumArt.get(track); if(art==null) return false;
                    for(int dy=0;dy<size;dy++) for(int dx=0;dx<size;dx++) fill(x+dx,y+dy,x+dx+1,y+dy+1,art.pixels()[(dy*128/size)*128+dx*128/size]);
                    return true;
                }
                public void text(String s,int x,int y,int color) {
                    for(char ch:s.toCharArray()) {
                        if(ch<=255 && ch!=' ') { int sx=ch%16*8,sy=ch/16*8;
                            for(int dx=0;dx<8;dx++) for(int dy=0;dy<8;dy++) if((font.getRGB(sx+dx,sy+dy)>>>24)>0) fill(x+dx,y+dy,x+dx+1,y+dy+1,color);
                        }
                        x+=glyphWidth(ch)+1;
                    }
                }
            };
            view.draw(canvas,-1,-1); view.click(20,150,0);
            view.draw(canvas,-1,-1); canvas.text("Search songs or artists",view.searchX(),view.searchY(),0xFF888888);
            BufferedImage scaled=new BufferedImage(w*3,h*3,BufferedImage.TYPE_INT_ARGB);
            var graphics=scaled.createGraphics(); graphics.drawImage(image,0,0,w*3,h*3,null); graphics.dispose();
            ImageIO.write(scaled,"png",output.toFile());
            view.draw(canvas,200,108); view.click(200,108,0); uiTime.addAndGet(300_000_000L);
            view.drag(200,167,0); view.draw(canvas,200,167);
            var dragGraphics=scaled.createGraphics(); dragGraphics.drawImage(image,0,0,w*3,h*3,null); dragGraphics.dispose();
            ImageIO.write(scaled,"png",output.resolveSibling("JukeBox-song-reorder.png").toFile());
            view.cancelPointer(); view.draw(canvas,-1,-1);
            view.click(100,125,0); view.draw(canvas,-1,-1);
            var modalGraphics=scaled.createGraphics(); modalGraphics.drawImage(image,0,0,w*3,h*3,null); modalGraphics.dispose();
            ImageIO.write(scaled,"png",output.resolveSibling("JukeBox-playlist-management.png").toFile());
            view.dismissDialog(); view.draw(canvas,-1,-1); view.click(w-90,48,0);
            view.dialogInput("https://youtu.be/abcdefghijk"); view.draw(canvas,-1,-1);
            canvas.text(view.dialogInput(),view.dialogInputX(),view.dialogInputY(),0xFFF4F4F4);
            var urlGraphics=scaled.createGraphics(); urlGraphics.drawImage(image,0,0,w*3,h*3,null); urlGraphics.dispose();
            ImageIO.write(scaled,"png",output.resolveSibling("JukeBox-add-song-url.png").toFile());
            view.dismissDialog(); view.draw(canvas,-1,-1); view.click(30,125,0);
            view.dialogInput("Evening Walk"); view.draw(canvas,-1,-1);
            canvas.text(view.dialogInput(),view.dialogInputX(),view.dialogInputY(),0xFFF4F4F4);
            var inputGraphics=scaled.createGraphics(); inputGraphics.drawImage(image,0,0,w*3,h*3,null); inputGraphics.dispose();
            ImageIO.write(scaled,"png",output.resolveSibling("JukeBox-input-alignment.png").toFile());
            view.dismissDialog(); view.draw(canvas,-1,-1); view.click(200,108,1); view.draw(canvas,-1,-1);
            var popupGraphics=scaled.createGraphics(); popupGraphics.drawImage(image,0,0,w*3,h*3,null); popupGraphics.dispose();
            ImageIO.write(scaled,"png",output.resolveSibling("JukeBox-song-options.png").toFile());
            view.click(220,136,0);
            view.songArtist("Rose"); view.songTitle("toxic till the end"); view.draw(canvas,-1,-1);
            canvas.text(view.songArtist(),view.dialogInputX(),view.songArtistY(),0xFFF4F4F4);
            canvas.text(view.songTitle(),view.dialogInputX(),view.songTitleY(),0xFFF4F4F4);
            var songGraphics=scaled.createGraphics(); songGraphics.drawImage(image,0,0,w*3,h*3,null); songGraphics.dispose();
            ImageIO.write(scaled,"png",output.resolveSibling("JukeBox-edit-song.png").toFile());
            view.dismissDialog(); view.draw(canvas,-1,-1); view.click(w-17,17,0); view.draw(canvas,-1,-1);
            var settingsGraphics=scaled.createGraphics(); settingsGraphics.drawImage(image,0,0,w*3,h*3,null); settingsGraphics.dispose();
            ImageIO.write(scaled,"png",output.resolveSibling("JukeBox-settings.png").toFile());
            view.dismissDialog(); Config.get().showAlbumArt=true;
            long deadline=System.nanoTime()+5_000_000_000L;
            for(Track track:session.library()) while(session.albumArt.get(track)==null && System.nanoTime()<deadline) Thread.sleep(10);
            view.draw(canvas,-1,-1);
            var artGraphics=scaled.createGraphics(); artGraphics.drawImage(image,0,0,w*3,h*3,null); artGraphics.dispose();
            ImageIO.write(scaled,"png",output.resolveSibling("JukeBox-album-covers.png").toFile());
            MusicToastView.draw(canvas,session,k -> String.valueOf(labels.get("jukebox."+k)),MusicToastView.WIDTH);
            BufferedImage toast=new BufferedImage(MusicToastView.WIDTH*3,MusicToastView.HEIGHT*3,BufferedImage.TYPE_INT_ARGB);
            var toastGraphics=toast.createGraphics(); toastGraphics.drawImage(image.getSubimage(0,0,MusicToastView.WIDTH,MusicToastView.HEIGHT),0,0,toast.getWidth(),toast.getHeight(),null); toastGraphics.dispose();
            ImageIO.write(toast,"png",output.resolveSibling("JukeBox-playback-toast.png").toFile());
        }
    }
}
