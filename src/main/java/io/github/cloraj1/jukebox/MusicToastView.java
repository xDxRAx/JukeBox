package io.github.cloraj1.jukebox;

import java.util.function.Function;

/** Live title, artist, playback glyph and timeline in a vanilla Toast-sized panel. */
public final class MusicToastView {
    public static final int WIDTH=200,HEIGHT=64;
    public static void draw(MusicView.Canvas c,MusicSession session,Function<String,String> tr,int width) {
        c.fill(0,0,width,HEIGHT,0xFF777777); c.fill(2,2,width-2,HEIGHT-2,0xFF171717);
        Track track=session.currentTrack(); int left=8;
        if(track!=null && Config.get().showAlbumArt && c.cover(track,8,7,24)) left=38;
        MusicView.fit(c,track==null?tr.apply(session.loading()?"loading":"noTrack"):track.title(),left,7,width-left-8,0xFFF4F4F4);
        MusicView.fit(c,track==null?"JukeBox":track.artist().isBlank()?track.extension().toUpperCase():track.artist(),left,20,width-left-8,0xFFBBBBBB);
        int center=width/2;
        MusicView.icon(c,center-28,33,"previous",0xFFEAEAEA);
        MusicView.icon(c,center-5,33,session.player.playing()?"pause":"play",0xFFF4F4F4);
        MusicView.icon(c,center+18,33,"next",0xFFEAEAEA);
        double total=session.duration(track),position=session.player.position();
        String elapsed=MusicView.time(position),duration=total>0?MusicView.time(total):"--:--";
        int start=8+c.width(elapsed)+5,end=width-8-c.width(duration)-5;
        c.text(elapsed,8,50,0xFFBBBBBB); c.text(duration,width-8-c.width(duration),50,0xFFBBBBBB);
        if(end>start) {
            c.fill(start,52,end,56,0xFF555555);
            int filled=(int)((end-start)*(total<=0?0:Maths.clamp(position/total,0,1)));
            if(filled>0) c.fill(start,52,start+filled,56,0xFFBFC8FF);
        }
    }
}
