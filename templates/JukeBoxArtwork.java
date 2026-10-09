package io.github.cloraj1.jukebox;

import java.util.LinkedHashMap;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.@GRAPHICS@;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.@IDENTIFIER@;

/** Client-thread-only GPU cache; decoded art comes from the bounded background cache. */
public final class JukeBoxArtwork {
    private record Texture(AlbumArt.Picture picture,@IDENTIFIER@ id) {}
    private static final LinkedHashMap<String,Texture> textures=new LinkedHashMap<>(32,.75f,true);
    private static long serial;
    public static boolean draw(@GRAPHICS@ g,Track track,int x,int y,int size) {
        AlbumArt.Picture picture=JukeBoxClient.session.albumArt.get(track);
        if(picture==null) return false;
        var manager=Minecraft.getInstance().getTextureManager();
        Texture texture=textures.get(track.id());
        if(texture==null || texture.picture()!=picture) {
            if(texture!=null) { manager.release(texture.id()); textures.remove(track.id()); }
            NativeImage image=new NativeImage(AlbumArt.SIDE,AlbumArt.SIDE,false);
            for(int py=0;py<AlbumArt.SIDE;py++) for(int px=0;px<AlbumArt.SIDE;px++) {
                int color=picture.pixels()[py*AlbumArt.SIDE+px];
                image.@ART_PIXEL@(px,py,@ART_COLOR@);
            }
            @IDENTIFIER@ id=@ART_ID@;
            DynamicTexture dynamic=new DynamicTexture(@ART_TEXTURE_EXTRA@image);
            manager.register(id,dynamic); texture=new Texture(picture,id); textures.put(track.id(),texture);
            while(textures.size()>32) { var first=textures.entrySet().iterator(); manager.release(first.next().getValue().id()); first.remove(); }
        }
        @ART_BLIT@;
        return true;
    }
    public static void clear() {
        var manager=Minecraft.getInstance().getTextureManager();
        textures.values().forEach(texture -> manager.release(texture.id())); textures.clear();
    }
}
