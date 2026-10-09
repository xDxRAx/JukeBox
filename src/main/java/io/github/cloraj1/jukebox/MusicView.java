package io.github.cloraj1.jukebox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Shared layout with optional embedded-art draws supplied by the Minecraft adapter. */
public final class MusicView {
    public interface Canvas {
        void fill(int x1,int y1,int x2,int y2,int color); void text(String s,int x,int y,int color); int width(String s);
        default boolean cover(Track track,int x,int y,int size) { return false; }
    }
    private record Hit(int x,int y,int w,int h,String hint,Runnable action,boolean audible) {
        Hit(int x,int y,int w,int h,String hint,Runnable action) { this(x,y,w,h,hint,action,true); }
        boolean contains(double mx,double my) { return mx>=x && mx<x+w && my>=y && my<y+h; }
    }
    private record SongTarget(Hit bounds,Track track) {}
    private record SongRow(Hit hit,Track track,int index) {}
    private static final int WHITE=0xFFF4F4F4, MUTED=0xFF888888, ACCENT=0xFFBFC8FF;
    private final MusicSession session;
    private final Function<String,String> tr;
    private final Runnable buttonClick;
    private final PlaylistFiles playlistFiles;
    private final List<Hit> hits=new ArrayList<>();
    private final List<SongTarget> songHits=new ArrayList<>();
    private final List<SongRow> songRows=new ArrayList<>();
    private final java.util.function.LongSupplier clock;
    private Track popupTrack;
    private int popupX,popupY;
    private String popupError="";
    private SongRow pressedRow;
    private List<Track> pressedSource=List.of(),source=List.of();
    private List<Integer> visibleIndices=List.of();
    private String pressedPage;
    private long pressedAt,scrollAt;
    private double pressedX,pressedY,dragX,dragY;
    private boolean songDragging;
    private int insertBefore=-1,insertSlot;
    private int width,height,side,bottom,listTop=70,offset,sideOffset;
    private String page="all",query="";
    private List<String> collections=List.of();
    private List<Track> visible=List.of();
    private int seekX,seekW,seekY,volumeX,volumeW,volumeY;
    private int dragging;
    private double seekFraction;
    private long noticeUntil;
    private String notice="";
    private final List<String> navigation=new ArrayList<>(List.of("all"));
    private int navIndex;
    private enum Dialog { NONE, CREATE, RENAME, MANAGE, DELETE, ADD_TRACK, ADD_SONGS, URL, EDIT_SONG, SETTINGS }
    private Dialog dialog=Dialog.NONE;
    private String dialogPlaylist,dialogText="",dialogError="";
    private String songArtist="",songTitle="";
    private Track dialogTrack;
    private int dialogOffset,inputRevision;
    public MusicView(MusicSession session,Function<String,String> tr) { this(session,tr,() -> {}); }
    public MusicView(MusicSession session,Function<String,String> tr,Runnable buttonClick) {
        this(session,tr,buttonClick,null);
    }
    public MusicView(MusicSession session,Function<String,String> tr,Runnable buttonClick,PlaylistFiles playlistFiles) {
        this(session,tr,buttonClick,playlistFiles,System::nanoTime);
    }
    public MusicView(MusicSession session,Function<String,String> tr,Runnable buttonClick,PlaylistFiles playlistFiles,java.util.function.LongSupplier clock) {
        this.session=session; this.tr=tr; this.buttonClick=buttonClick; this.playlistFiles=playlistFiles; this.clock=clock;
    }
    public void resize(int width,int height) { cancelSongGesture(); this.width=width; this.height=height; side=Maths.clamp(width/3,90,210); bottom=height-66; }
    public int searchX() { return Math.max(60,width/2-Math.min(180,width-130)/2); }
    public int searchWidth() { return Math.max(50,Math.min(180,width-130)); }
    public int searchY() { return 12; }
    public String query() { return query; }
    public void search(String query) { cancelSongGesture(); this.query=query; offset=0; }
    private String t(String key) { return tr.apply(key); }
    private boolean customPlaylist() { return page.startsWith("list:"); }
    private String playlistId() { return customPlaylist()?page.substring(5):""; }
    private String pageTitle() { return customPlaylist()?session.playlistName(playlistId()):page.startsWith("folder:") ? page.substring(7) : t(page); }
    private void page(String name) {
        cancelSongGesture();
        if(!page.equals(name)) {
            while(navigation.size()>navIndex+1) navigation.remove(navigation.size()-1);
            navigation.add(name); navIndex=navigation.size()-1;
        }
        page=name; offset=0;
    }
    private void back(int direction) { cancelSongGesture(); navIndex=Maths.clamp(navIndex+direction,0,navigation.size()-1); page=navigation.get(navIndex); offset=0; }
    private void startCollection(String name) {
        boolean started=name.startsWith("list:")?session.startPlaylist(name.substring(5)):session.startFolder(name.substring(7));
        if(!started) { notice=t("playlistNoSongs"); noticeUntil=System.nanoTime()+4_000_000_000L; }
        else noticeUntil=0;
    }
    public boolean dialogOpen() { return dialog!=Dialog.NONE || popupOpen(); }
    public boolean popupOpen() { return popupTrack!=null; }
    public boolean dialogInputActive() { return dialog==Dialog.CREATE || dialog==Dialog.RENAME || dialog==Dialog.ADD_SONGS || dialog==Dialog.URL; }
    public boolean songEditOpen() { return dialog==Dialog.EDIT_SONG; }
    public boolean textInputsActive() { return dialogInputActive() || songEditOpen(); }
    public String songArtist() { return songArtist; }
    public String songTitle() { return songTitle; }
    public void songArtist(String value) { songArtist=value; dialogError=""; }
    public void songTitle(String value) { songTitle=value; dialogError=""; }
    public int songArtistY() { return dialogY()+54; }
    public int songTitleY() { return dialogY()+100; }
    public boolean dialogInputEditable() { return dialog!=Dialog.URL || !session.urlImporter.status().busy(); }
    public int inputRevision() { return inputRevision; }
    public String dialogInput() { return dialogText; }
    public String dialogInputHint() { return t(dialog==Dialog.URL?"youtubeUrl":dialog==Dialog.ADD_SONGS?"search":"playlistName"); }
    public void dialogInput(String value) { dialogText=value; dialogError=""; if(dialog==Dialog.ADD_SONGS) dialogOffset=0; }
    private int dialogW() { return Math.min(360,width-24); }
    private int dialogH() { return Math.min(dialog==Dialog.SETTINGS?210:dialog==Dialog.EDIT_SONG?200:dialog==Dialog.URL?190:dialog==Dialog.CREATE || dialog==Dialog.RENAME || dialog==Dialog.DELETE?144:250,height-36); }
    private int dialogX() { return (width-dialogW())/2; }
    private int dialogY() { return (height-dialogH())/2; }
    public int dialogInputX() { return dialogX()+16; }
    public int dialogInputY() { return dialogY()+40; }
    public int dialogInputWidth() { return dialogW()-32; }
    private void openDialog(Dialog mode,String id,Track track) {
        popupTrack=null; cancelSongGesture(); hits.clear();
        dialog=mode; dialogPlaylist=id; dialogTrack=track; dialogOffset=0; dialogError="";
        dialogText=mode==Dialog.RENAME?session.playlistName(id):""; inputRevision++; dragging=0;
        if(mode==Dialog.EDIT_SONG) {
            Track display=session.displayTrack(track); songArtist=display.artist(); songTitle=display.title();
        }
    }
    public void dismissDialog() { dialog=Dialog.NONE; dialogError=""; dialogTrack=null; popupTrack=null; popupError=""; }
    private void confirmUrl() { if(dialog==Dialog.URL) session.urlImporter.start(dialogText); }
    public void confirmSongInfo() {
        if(!songEditOpen()) return;
        try { session.editSong(dialogTrack,songArtist,songTitle); dismissDialog(); }
        catch(IllegalArgumentException e) { dialogError=t(e.getMessage()); }
    }
    private void songHit(int x,int y,int w,int h,Track track) {
        songHits.add(new SongTarget(new Hit(x,y,w,h,"",() -> {}),track));
    }
    public void confirmName() {
        if(dialog!=Dialog.CREATE && dialog!=Dialog.RENAME) return;
        try {
            String id=dialogPlaylist;
            if(dialog==Dialog.CREATE) { id=session.createPlaylist(dialogText); if(dialogTrack!=null) session.addPlaylistTrack(id,dialogTrack); }
            else session.renamePlaylist(id,dialogText);
            dismissDialog(); page("list:"+id); search(""); sideOffset=0;
        } catch(IllegalArgumentException e) { dialogError=t(e.getMessage()); }
    }
    public void draw(Canvas c,int mx,int my) {
        hits.clear(); songHits.clear(); songRows.clear();
        if(customPlaylist() && session.playlistName(playlistId()).isEmpty()) page("all");
        listTop=customPlaylist()?98:70;
        collections=session.library().stream().map(Track::collection).filter(s -> !s.isEmpty()).distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
        source=session.tracksForPage(page);
        visible=MusicLibrary.filter(source,query);
        List<Integer> indices=new ArrayList<>(); int match=0;
        for(int i=0;i<source.size() && match<visible.size();i++) if(source.get(i).equals(visible.get(match))) { indices.add(i); match++; }
        visibleIndices=indices;
        c.fill(0,0,width,height,0xFF090707);
        c.fill(0,32,side-6,bottom,0xFF101010); c.fill(side,32,width,bottom,0xFF101010);
        iconButton(c,searchX()-22,7,18,"back",t("back"),() -> back(-1),false,mx,my);
        iconButton(c,searchX()+searchWidth()+8,7,18,"forward",t("forward"),() -> back(1),false,mx,my);
        border(c,searchX()-4,5,searchWidth()+8,23,0xFFA0A0A0);
        iconButton(c,width-27,7,20,"settings",t("settings"),() -> openDialog(Dialog.SETTINGS,null,null),false,mx,my);
        // Keep playlist actions visible even when only a few list rows fit.
        int sy=42;
        navRowCompact(c,t("all"),"all",sy); sy+=20;
        navRowCompact(c,t("liked"),"liked",sy); sy+=20;
        navRowCompact(c,t("queue"),"queue",sy); sy+=22;
        fit(c,t("playlists"),12,sy,side-24,MUTED); sy+=14;
        int half=(side-27)/2;
        textButton(c,10,sy,half,18,t("create"),() -> openDialog(Dialog.CREATE,null,null),mx,my);
        textButton(c,15+half,sy,half,18,t("manage"),() -> openDialog(Dialog.MANAGE,null,null),mx,my); sy+=26;
        List<String> collectionPages=new ArrayList<>();
        for(MusicSession.PlaylistInfo p:session.playlists()) collectionPages.add("list:"+p.id());
        for(String folder:collections) collectionPages.add("folder:"+folder);
        int folderCapacity=Math.max(0,(bottom-sy-2)/28);
        sideOffset=Maths.clamp(sideOffset,0,Math.max(0,collectionPages.size()-folderCapacity));
        for(int i=0;i<folderCapacity && i+sideOffset<collectionPages.size();i++) {
            String name=collectionPages.get(i+sideOffset);
            String label=name.startsWith("list:")?session.playlistName(name.substring(5)):name.substring(7);
            navRow(c,label,t(name.startsWith("list:")?"savedPlaylist":"playlist"),name,sy+i*28,mx,my);
        }
        scrollbar(c,side-5,sy,Math.max(0,bottom-sy),sideOffset,collectionPages.size(),folderCapacity);
        fit(c,pageTitle(),side+12,44,Math.max(20,width-side-127),WHITE);
        var importStatus=session.urlImporter.status();
        fit(c,visible.size()+" "+t("tracks")+(session.loading()?" · "+t("loading"):importStatus.busy()?" · "+t(importStatus.key())+" "+importStatus.detail():""),side+12,58,width-side-24,MUTED);
        iconButton(c,width-98,40,18,"clip",t("addSongUrl"),() -> openDialog(Dialog.URL,null,null),importStatus.busy(),mx,my);
        iconButton(c,width-75,40,18,"reload",t("reload"),session::reload,false,mx,my);
        iconButton(c,width-52,40,18,"folder",t("openFolder"),session::openFolder,false,mx,my);
        if(Integrations.voiceChat) iconButton(c,width-29,40,18,"voice",t("voice"),session::broadcast,Config.get().broadcastToVoiceChat,mx,my);
        if(customPlaylist()) {
            int group=(width-side-24)/3; String id=playlistId();
            textButton(c,side+10,74,group-4,18,t("rename"),() -> openDialog(Dialog.RENAME,id,null),mx,my);
            textButton(c,side+10+group,74,group-4,18,t("delete"),() -> openDialog(Dialog.DELETE,id,null),mx,my);
            textButton(c,side+10+group*2,74,group-4,18,t("addSongs"),() -> openDialog(Dialog.ADD_SONGS,id,null),mx,my);
        }
        int capacity=Math.max(0,(bottom-listTop-4)/32);
        offset=Maths.clamp(offset,0,Math.max(0,visible.size()-capacity));
        if(pressedRow!=null) updateSongDrag(mx,my);
        if(songDragging && capacity>0 && mx>=side+5 && mx<width-7 && clock.getAsLong()-scrollAt>=120_000_000L) {
            if(my<listTop+12) offset=Math.max(0,offset-1);
            else if(my>=bottom-12) offset=Math.min(Math.max(0,visible.size()-capacity),offset+1);
            scrollAt=clock.getAsLong(); updateInsertion(dragY);
        }
        if(visible.isEmpty()) {
            fit(c,t(session.loading()?"loading":"empty"),side+14,listTop+10,width-side-28,MUTED);
            fit(c,t(customPlaylist()?"playlistEmptyHint":"emptyHint"),side+14,listTop+24,width-side-28,MUTED);
        }
        for(int i=0;i<capacity && i+offset<visible.size();i++) {
            int index=i+offset,y=listTop+i*32; Track track=visible.get(index);
            boolean selected=track.equals(session.player.current());
            songHit(side+5,y,width-side-12,30,track);
            if(songDragging && visibleIndices.get(index)==pressedRow.index()) c.fill(side+5,y,width-7,y+30,0xFF34384C);
            else if(selected || (mx>=side+5 && mx<width-6 && my>=y && my<y+30)) c.fill(side+5,y,width-7,y+30,selected?0xFF272727:0xFF1C1C1C);
            String number=selected && session.player.playing()?">":Integer.toString(index+1);
            c.text(number,side+12,y+10,selected?ACCENT:MUTED);
            int titleWidth=width-side-158;
            int artWidth=Config.get().showAlbumArt && c.cover(track,side+35,y+3,24)?30:0;
            fit(c,track.title(),side+35+artWidth,y+5,titleWidth-artWidth,selected?ACCENT:WHITE);
            String subtitle=track.artist().isBlank()?track.extension().toUpperCase():track.artist();
            fit(c,subtitle,side+35+artWidth,y+18,titleWidth-artWidth,MUTED);
            double duration=session.duration(track);
            c.text(duration>0?time(duration):"--:--",width-115,y+11,MUTED);
            int sourceIndex=visibleIndices.get(index); String rowPage=page,rowQuery=query;
            Hit rowHit=new Hit(side+5,y,Math.max(1,width-side-84),30,track.title()+" · "+t("editSongHint"),() -> session.playPage(rowPage,sourceIndex,rowQuery),false);
            hits.add(rowHit); songRows.add(new SongRow(rowHit,track,sourceIndex));
            String currentList=playlistId();
            iconButton(c,width-78,y+6,18,customPlaylist()?"remove":"playlist",t(customPlaylist()?"removeFromPlaylist":"addToPlaylist"),() -> {
                if(customPlaylist()) session.removePlaylistTrack(currentList,track);
                else openDialog(Dialog.ADD_TRACK,null,track);
            },false,mx,my);
            iconButton(c,width-54,y+6,18,"heart",t("like"),() -> session.like(track),session.liked(track),mx,my);
            iconButton(c,width-30,y+6,18,page.equals("queue")?"remove":"plus",t(page.equals("queue")?"removeQueue":"addQueue"),() -> {
                if(page.equals("queue")) session.removeQueue(sourceIndex); else session.addQueue(track);
            },false,mx,my);
        }
        scrollbar(c,width-4,listTop,bottom-listTop,offset,visible.size(),capacity);
        if(songDragging && dragX>=side+5 && dragX<width-7 && dragY>=listTop-12 && dragY<bottom) {
            int line=Maths.clamp(listTop+insertSlot*32-1,listTop,bottom-2); c.fill(side+5,line,width-7,line+2,ACCENT);
        }
        player(c,mx,my);
        String error=!session.player.error().isEmpty()?session.player.error():session.error();
        if(!error.isEmpty()) { c.fill(side,bottom-18,width,bottom,0xFF351B1B); fit(c,t("error")+": "+error,side+8,bottom-13,width-side-16,0xFFFFAAAA); }
        else if(noticeUntil!=0 && System.nanoTime()-noticeUntil<0) { c.fill(side,bottom-18,width,bottom,0xFF272727); fit(c,notice,side+8,bottom-13,width-side-16,MUTED); }
        if(popupOpen()) { drawSongPopup(c,mx,my); return; }
        if(dialogOpen()) { drawDialog(c,mx,my); return; }
        if(songDragging) return;
        for(Hit hit:hits) if(hit.contains(mx,my)) {
            int tw=Math.min(width-12,c.width(hit.hint)+12), x=Maths.clamp(mx-tw/2,4,Math.max(4,width-tw-4)), y=Math.max(34,my-21);
            c.fill(x,y,x+tw,y+17,0xFF303030); fit(c,hit.hint,x+6,y+4,tw-12,WHITE); break;
        }
    }
    private void navRowCompact(Canvas c,String label,String name,int y) {
        if(page.equals(name)) c.fill(7,y-3,side-13,y+17,0xFF242424);
        fit(c,label,12,y+3,side-32,page.equals(name)?WHITE:0xFFD8D8D8);
        hits.add(new Hit(7,y-3,side-20,20,label,() -> page(name)));
    }
    private void textButton(Canvas c,int x,int y,int w,int h,String label,Runnable action,int mx,int my) {
        Hit hit=new Hit(x,y,w,h,label,action); hits.add(hit);
        c.fill(x,y,x+w,y+h,hit.contains(mx,my)?0xFF444444:0xFF292929); border(c,x,y,w,h,0xFF656565);
        int labelW=Math.min(c.width(label),w-8); fit(c,label,x+(w-labelW)/2,y+(h-8)/2,w-8,WHITE);
    }
    private void drawSongPopup(Canvas c,int mx,int my) {
        hits.clear(); songHits.clear(); songRows.clear();
        int w=Math.min(190,width-8),h=popupError.isEmpty()?68:86;
        int x=Maths.clamp(popupX,4,width-w-4),y=Maths.clamp(popupY,4,height-h-4);
        c.fill(x,y,x+w,y+h,0xFF171717); border(c,x,y,w,h,0xFF8B8B8B);
        Track track=popupTrack;
        fit(c,session.displayTrack(track).title(),x+8,y+6,w-16,MUTED);
        textButton(c,x+5,y+21,w-10,20,t("modifySongInfo"),() -> openDialog(Dialog.EDIT_SONG,null,track),mx,my);
        textButton(c,x+5,y+44,w-10,20,t("deleteSong"),() -> {
            try { session.deleteSong(track); dismissDialog(); notice=t("songDeleted"); noticeUntil=System.nanoTime()+4_000_000_000L; }
            catch(java.io.IOException e) { popupError=t("songDeleteFailed"); }
        },mx,my);
        if(!popupError.isEmpty()) fit(c,popupError,x+8,y+71,w-16,0xFFFFAAAA);
    }
    private void drawDialog(Canvas c,int mx,int my) {
        hits.clear(); songHits.clear();
        c.fill(0,0,width,height,0xCC000000);
        int x=dialogX(),y=dialogY(),w=dialogW(),h=dialogH();
        c.fill(x,y,x+w,y+h,0xFF171717); border(c,x,y,w,h,0xFF8B8B8B);
        String title=switch(dialog) {
            case CREATE -> "createPlaylist"; case RENAME -> "renamePlaylist"; case MANAGE -> "managePlaylists";
            case DELETE -> "deletePlaylist"; case ADD_TRACK -> "choosePlaylist"; case URL -> "addSongUrl"; case EDIT_SONG -> "editSong"; case SETTINGS -> "settings"; default -> "addSongs";
        };
        fit(c,t(title),x+12,y+12,w-46,WHITE);
        textButton(c,x+w-29,y+7,20,18,"X",this::dismissDialog,mx,my);
        if(dialog==Dialog.SETTINGS) {
            setting(c,x,y+38,w,"playbackNotifications",Config.get().playbackNotifications,() -> { Config.get().playbackNotifications=!Config.get().playbackNotifications; Config.save(); },mx,my);
            setting(c,x,y+68,w,"alwaysPlaybackNotification",Config.get().alwaysPlaybackNotification,() -> { Config.get().alwaysPlaybackNotification=!Config.get().alwaysPlaybackNotification; Config.save(); },mx,my);
            setting(c,x,y+98,w,"showAlbumArt",Config.get().showAlbumArt,() -> { Config.get().showAlbumArt=!Config.get().showAlbumArt; if(!Config.get().showAlbumArt) session.albumArt.clear(); Config.save(); },mx,my);
            int transferW=(w-32)/2;
            if(playlistFiles==null || !playlistFiles.busy()) {
                textButton(c,x+12,y+130,transferW,20,t("importPlaylist"),() -> { if(playlistFiles!=null) playlistFiles.request(false,tr); },mx,my);
                textButton(c,x+20+transferW,y+130,transferW,20,t("exportPlaylist"),() -> { if(playlistFiles!=null) playlistFiles.request(true,tr); },mx,my);
            } else fit(c,t("playlistTransferBusy"),x+16,y+136,w-32,MUTED);
            var transferStatus=playlistFiles==null?new PlaylistFiles.Status("playlistTransferHint",false):playlistFiles.status();
            fit(c,t(transferStatus.key()),x+16,y+157,w-32,transferStatus.error()?0xFFFFAAAA:MUTED);
            textButton(c,x+12,y+h-30,88,20,t("close"),this::dismissDialog,mx,my);
            return;
        }
        if(songEditOpen()) {
            fit(c,t("songArtist"),x+16,y+33,w-32,MUTED);
            border(c,dialogInputX()-3,y+47,dialogInputWidth()+6,22,0xFFAAAAAA);
            fit(c,t("songTitle"),x+16,y+79,w-32,MUTED);
            border(c,dialogInputX()-3,y+93,dialogInputWidth()+6,22,0xFFAAAAAA);
            fit(c,t("songEditNote"),x+16,y+126,w-32,MUTED);
            if(!dialogError.isEmpty()) fit(c,dialogError,x+16,y+143,w-32,0xFFFFAAAA);
            textButton(c,x+12,y+h-30,88,20,t("save"),this::confirmSongInfo,mx,my);
            textButton(c,x+108,y+h-30,88,20,t("cancel"),this::dismissDialog,mx,my);
            return;
        }
        if(dialog==Dialog.URL) {
            border(c,dialogInputX()-3,y+33,dialogInputWidth()+6,22,0xFFAAAAAA);
            fit(c,t("urlHint"),x+16,y+65,w-32,MUTED);
            fit(c,t("urlSetupHint"),x+16,y+80,w-32,MUTED);
            var status=session.urlImporter.status();
            if(!status.key().isEmpty()) {
                int color=status.key().endsWith("Failed") || status.key().equals("urlInvalid") || status.key().equals("urlDuplicate") || status.key().equals("urlNoAudio") || status.key().equals("urlToolsMissing")?0xFFFFAAAA:ACCENT;
                fit(c,t(status.key()),x+16,y+104,w-32,color);
                fit(c,status.detail(),x+16,y+119,w-32,MUTED);
            }
            if(status.busy()) textButton(c,x+12,y+h-30,88,20,t("cancel"),session.urlImporter::cancel,mx,my);
            else textButton(c,x+12,y+h-30,88,20,t("add"),this::confirmUrl,mx,my);
            textButton(c,x+108,y+h-30,88,20,t("close"),this::dismissDialog,mx,my);
            return;
        }
        if(dialog==Dialog.CREATE || dialog==Dialog.RENAME) {
            border(c,dialogInputX()-3,y+33,dialogInputWidth()+6,22,0xFFAAAAAA);
            fit(c,t("playlistNameHint"),x+16,y+65,w-32,MUTED);
            if(!dialogError.isEmpty()) fit(c,dialogError,x+16,y+83,w-32,0xFFFFAAAA);
            textButton(c,x+12,y+h-30,Math.min(88,(w-30)/2),20,t(dialog==Dialog.CREATE?"create":"save"),this::confirmName,mx,my);
            textButton(c,x+108,y+h-30,Math.min(88,w-120),20,t("cancel"),this::dismissDialog,mx,my);
            return;
        }
        if(dialog==Dialog.DELETE) {
            fit(c,session.playlistName(dialogPlaylist),x+16,y+38,w-32,WHITE);
            fit(c,t("deleteHint"),x+16,y+58,w-32,MUTED);
            textButton(c,x+12,y+h-30,88,20,t("delete"),() -> {
                session.deletePlaylist(dialogPlaylist); if(page.equals("list:"+dialogPlaylist)) page("all"); dismissDialog();
            },mx,my);
            textButton(c,x+108,y+h-30,88,20,t("cancel"),this::dismissDialog,mx,my);
            return;
        }
        boolean songs=dialog==Dialog.ADD_SONGS;
        if(songs) border(c,dialogInputX()-3,y+33,dialogInputWidth()+6,22,0xFFAAAAAA);
        int top=y+(songs?64:34),capacity=Math.max(1,(y+h-38-top)/26);
        List<Track> tracks=songs?MusicLibrary.filter(session.library(),dialogText):List.of();
        List<MusicSession.PlaylistInfo> playlists=session.playlists();
        int count=songs?tracks.size():playlists.size();
        dialogOffset=Maths.clamp(dialogOffset,0,Math.max(0,count-capacity));
        if(count==0) fit(c,t(songs?"empty":"noPlaylists"),x+16,top+8,w-32,MUTED);
        for(int i=0;i<capacity && i+dialogOffset<count;i++) {
            int row=top+i*26,index=i+dialogOffset;
            c.fill(x+9,row,x+w-9,row+23,0xFF222222);
            if(songs) {
                Track track=tracks.get(index); String id=dialogPlaylist;
                songHit(x+9,row,w-18,23,track);
                boolean present=session.playlistContains(id,track);
                fit(c,track.title(),x+15,row+3,w-63,present?ACCENT:WHITE);
                fit(c,track.artist(),x+15,row+14,w-63,MUTED);
                textButton(c,x+w-40,row+2,24,18,present?"-":"+",() -> {
                    if(session.playlistContains(id,track)) session.removePlaylistTrack(id,track); else session.addPlaylistTrack(id,track);
                },mx,my);
                hits.add(new Hit(x+9,row,w-55,23,track.title(),() -> {
                    if(session.playlistContains(id,track)) session.removePlaylistTrack(id,track); else session.addPlaylistTrack(id,track);
                }));
            } else {
                MusicSession.PlaylistInfo playlist=playlists.get(index);
                int reserved=dialog==Dialog.MANAGE?120:24;
                fit(c,playlist.name(),x+15,row+3,w-reserved-24,WHITE);
                fit(c,playlist.tracks()+" "+t("tracks"),x+15,row+14,w-reserved-24,MUTED);
                hits.add(new Hit(x+9,row,w-reserved-9,23,playlist.name(),() -> {
                    if(dialog==Dialog.ADD_TRACK && dialogTrack!=null) session.addPlaylistTrack(playlist.id(),dialogTrack);
                    dismissDialog(); page("list:"+playlist.id()); search("");
                }));
                if(dialog==Dialog.MANAGE) {
                    textButton(c,x+w-122,row+2,54,18,t("rename"),() -> openDialog(Dialog.RENAME,playlist.id(),null),mx,my);
                    textButton(c,x+w-62,row+2,48,18,t("delete"),() -> openDialog(Dialog.DELETE,playlist.id(),null),mx,my);
                }
            }
        }
        scrollbar(c,x+w-5,top,y+h-38-top,dialogOffset,count,capacity);
        if(!songs) textButton(c,x+12,y+h-29,Math.min(120,w-114),20,t("createPlaylist"),() -> openDialog(Dialog.CREATE,null,dialogTrack),mx,my);
        textButton(c,x+w-95,y+h-29,83,20,t("done"),this::dismissDialog,mx,my);
    }
    private void navRow(Canvas c,String label,String subtitle,String name,int y,int mx,int my) {
        if(page.equals(name)) c.fill(7,y-3,side-13,y+25,0xFF242424);
        fit(c,label,12,y+2,side-56,page.equals(name)?WHITE:0xFFD8D8D8);
        fit(c,subtitle,12,y+15,side-56,MUTED);
        // The button consumes the click before the surrounding navigation row.
        iconButton(c,side-32,y+3,16,"play",t("playPlaylist")+": "+label,() -> startCollection(name),false,mx,my);
        hits.add(new Hit(7,y-3,side-20,28,label,() -> page(name)));
    }
    private void setting(Canvas c,int x,int y,int w,String name,boolean enabled,Runnable action,int mx,int my) {
        fit(c,t(name),x+16,y+6,w-100,WHITE);
        textButton(c,x+w-76,y,60,20,t(enabled?"enabled":"disabled"),action,mx,my);
    }
    private void player(Canvas c,int mx,int my) {
        c.fill(0,bottom,width,height,0xFF090707);
        Track current=session.currentTrack();
        int titleW=Math.max(40,width*28/100);
        int artWidth=current!=null && Config.get().showAlbumArt && c.cover(current,12,bottom+16,24)?30:0;
        fit(c,current==null?t("noTrack"):current.title(),12+artWidth,bottom+19,titleW-22-artWidth,WHITE);
        fit(c,current==null?t("chooseTrack"):current.artist().isBlank()?current.extension().toUpperCase():current.artist(),12+artWidth,bottom+34,titleW-22-artWidth,MUTED);
        if(current!=null) songHit(7,bottom+12,titleW-7,32,current);
        int controlsX=width/2-57;
        iconButton(c,controlsX,bottom+9,18,"shuffle",t("shuffle"),session::shuffle,Config.get().shuffle,mx,my);
        iconButton(c,controlsX+23,bottom+9,18,"previous",t("previous"),session::previous,false,mx,my);
        iconButton(c,controlsX+46,bottom+5,26,session.player.playing()?"pause":"play",t("playPause"),session::toggle,false,mx,my);
        iconButton(c,controlsX+77,bottom+9,18,"next",t("next"),session::next,false,mx,my);
        iconButton(c,controlsX+100,bottom+9,18,"repeat",t("repeat")+": "+Config.get().repeat,session::repeat,!Config.get().repeat.equals("OFF"),mx,my);
        if(Config.get().repeat.equals("ONE")) c.text("1",controlsX+114,bottom+24,ACCENT);
        seekX=titleW+30; seekW=Math.max(25,width*73/100-seekX-28); seekY=bottom+47;
        double duration=session.duration(current),position=dragging==1?seekFraction*duration:session.player.position();
        c.text(time(position),seekX-30,seekY-2,MUTED);
        c.fill(seekX,seekY,seekX+seekW,seekY+4,0xFF747474);
        int filled=(int)(seekW*(duration<=0?0:Maths.clamp(position/duration,0,1)));
        c.fill(seekX,seekY,seekX+filled,seekY+4,WHITE);
        c.text(duration>0?time(duration):"--:--",seekX+seekW+6,seekY-2,MUTED);
        volumeX=Math.max(seekX+seekW+43,width*82/100); volumeW=Math.max(10,width-volumeX-36); volumeY=bottom+47;
        icon(c,volumeX-19,volumeY-4,"volume",MUTED);
        c.fill(volumeX,volumeY,volumeX+volumeW,volumeY+4,0xFF747474);
        c.fill(volumeX,volumeY,volumeX+volumeW*Config.get().volume/100,volumeY+4,WHITE);
        c.text(Config.get().volume+"%",volumeX+volumeW+5,volumeY-2,MUTED);
    }
    private void iconButton(Canvas c,int x,int y,int size,String name,String hint,Runnable action,boolean active,int mx,int my) {
        Hit hit=new Hit(x,y,size,size,hint,action,!name.equals("play") && !name.equals("pause")); hits.add(hit);
        if(name.equals("play") || name.equals("pause")) {
            int r=size/2; for(int dy=-r;dy<=r;dy++) { int dx=(int)Math.sqrt(r*r-dy*dy); c.fill(x+r-dx,y+r+dy,x+r+dx+1,y+r+dy+1,hit.contains(mx,my)?0xFFD2D2D2:WHITE); }
            // Anchor to the actual disk center; the triangle is optically centered by its pixel mass.
            if(name.equals("play") && size<=16) {
                int[] rows={1,3,5,6,5,3,1};
                for(int row=0;row<rows.length;row++) c.fill(x+r-2,y+r-3+row,x+r-2+rows[row],y+r-2+row,0xFF080808);
            } else icon(c,x+r-5,y+r-5,name,0xFF080808);
        } else { if(hit.contains(mx,my)) c.fill(x,y,x+size,y+size,0xFF303030); icon(c,x+(size-11)/2,y+(size-11)/2,name,active?ACCENT:WHITE); }
    }
    static void icon(Canvas c,int x,int y,String name,int color) {
        String[] pixels=switch(name) {
            case "play" -> new String[]{"..#........","..##.......","..####.....","..######...","..########.","..#########","..########.","..######...","..####.....","..##.......","..#........"};
            case "pause" -> new String[]{".###...###.",".###...###.",".###...###.",".###...###.",".###...###.",".###...###.",".###...###.",".###...###.",".###...###.",".###...###.",".###...###."};
            case "next" -> new String[]{".#.......##",".##......##",".###.....##",".####....##",".#####...##",".######..##",".#####...##",".####....##",".###.....##",".##......##",".#.......##"};
            case "previous" -> new String[]{"##.......#.","##......##.","##.....###.","##....####.","##...#####.","##..######.","##...#####.","##....####.","##.....###.","##......##.","##.......#."};
            case "heart" -> new String[]{".###...###.","##.##.##.##","#...###...#","#.........#","##.......##",".##.....##.","..##...##..","...##.##...","....###....",".....#.....","..........."};
            case "home" -> new String[]{".....#.....","....###....","...#####...","..#######..",".#########.","###########","..#######..","..##...##..","..##...##..","..##...##..","..##...##.."};
            case "back" -> new String[]{"......##...",".....##....","....##.....","...##......","..##.......",".##........","..##.......","...##......","....##.....",".....##....","......##..."};
            case "forward" -> new String[]{"...##......","....##.....",".....##....","......##...",".......##..","........##.",".......##..","......##...",".....##....","....##.....","...##......"};
            case "shuffle" -> new String[]{"........#..",".......##..","###...#####","..##.##.##.","...###..#..","....#......","...###..#..","..##.##.##.","###...#####",".......##..","........#.."};
            case "repeat", "reload" -> new String[]{"...######..","..##....##.",".##......##",".#.......#.",".###.......","..#.....#..",".......###.",".#.......#.","##......##.",".##....##..","..######..."};
            case "folder" -> new String[]{"...........",".####......",".#..######.",".#########.",".#.......#.",".#.......#.",".#.......#.",".#.......#.",".#.......#.",".#########.","..........."};
            case "clip" -> new String[]{"....####...","...##..##..","..##....#..",".##..##.#..",".#..##..#..",".#.##..##..",".#.#..##...",".#...##....",".##.##.....","..###......","..........."};
            case "settings" -> new String[]{"....###....",".#..###..#.",".#########.","..##...##..","###..#..###","###.###.###","###..#..###","..##...##..",".#########.",".#..###..#.","....###...."};
            case "playlist" -> new String[]{"...........",".######....","...........",".######....","...........",".####...#..",".......###.",".####...#..","...........",".####......","..........."};
            case "volume", "voice" -> new String[]{".....#.....","....##..#..","...###...#.",".#####.#..#",".#####..#.#",".#####..#.#",".#####..#.#",".#####.#..#","...###...#.","....##..#..",".....#....."};
            case "remove" -> new String[]{"...........","...........","...........","...........",".#########.",".#########.","...........","...........","...........","...........","..........."};
            default -> new String[]{"...........","....##.....","....##.....","....##.....",".#########.",".#########.","....##.....","....##.....","....##.....","...........","..........."};
        };
        for(int row=0;row<pixels.length;row++) for(int col=0;col<pixels[row].length();col++) if(pixels[row].charAt(col)=='#') c.fill(x+col,y+row,x+col+1,y+row+1,color);
    }
    static void fit(Canvas c,String s,int x,int y,int max,int color) {
        if(max<=0) return;
        if(c.width(s)>max) { while(!s.isEmpty() && c.width(s+"...")>max) s=s.substring(0,s.offsetByCodePoints(s.length(),-1)); s+="..."; }
        if(c.width(s)<=max) c.text(s,x,y,color);
    }
    private static void border(Canvas c,int x,int y,int w,int h,int color) { c.fill(x,y,x+w,y+1,color); c.fill(x,y+h-1,x+w,y+h,color); c.fill(x,y,x+1,y+h,color); c.fill(x+w-1,y,x+w,y+h,color); }
    private static void scrollbar(Canvas c,int x,int y,int h,int offset,int count,int capacity) {
        if(count<=capacity || capacity<=0 || h<=0) return;
        c.fill(x,y,x+3,y+h,0xFF282828); int thumb=Math.max(12,h*capacity/count),top=y+(h-thumb)*offset/Math.max(1,count-capacity);
        c.fill(x,top,x+3,top+thumb,0xFFBBBBBB);
    }
    public static String time(double seconds) { int value=(int)Math.max(0,seconds); return (value/60)+":"+String.format(java.util.Locale.ROOT,"%02d",value%60); }
    public boolean click(double x,double y,int button) {
        if(popupOpen()) {
            if(button==0) for(Hit hit:hits) if(hit.contains(x,y)) { activate(hit); return true; }
            dismissDialog(); return true;
        }
        if(button==1) {
            cancelSongGesture();
            for(SongTarget target:songHits) if(target.bounds().contains(x,y)) {
                popupTrack=target.track(); popupX=(int)x; popupY=(int)y; popupError=""; hits.clear(); buttonClick.run(); return true;
            }
            return false;
        }
        if(button!=0) return false;
        if(dialogOpen()) { for(Hit hit:hits) if(hit.contains(x,y)) { activate(hit); return true; } return false; }
        if(y>=seekY-5 && y<seekY+10 && x>=seekX && x<seekX+seekW) { dragging=1; seekFraction=(x-seekX)/seekW; return true; }
        if(y>=volumeY-5 && y<volumeY+10 && x>=volumeX && x<volumeX+volumeW) { dragging=2; setVolume(x); return true; }
        cancelSongGesture();
        for(Hit hit:hits) if(hit.contains(x,y)) {
            SongRow row=songRows.stream().filter(r -> r.hit()==hit).findFirst().orElse(null);
            if(row!=null) {
                pressedRow=row; pressedSource=List.copyOf(source); pressedPage=page; pressedAt=clock.getAsLong(); scrollAt=pressedAt;
                pressedX=dragX=x; pressedY=dragY=y;
            } else activate(hit);
            return true;
        }
        return false;
    }
    private void activate(Hit hit) {
        if(hit.audible) buttonClick.run();
        hit.action.run();
    }
    public boolean drag(double x,double y,int button) {
        if(dialogOpen()) return false;
        if(button==0 && pressedRow!=null) { updateSongDrag(x,y); return true; }
        if(button!=0 || dragging==0) return false;
        if(dragging==2) setVolume(x);
        else seekFraction=Maths.clamp((x-seekX)/seekW,0,1);
        return true;
    }
    private void updateSongDrag(double x,double y) {
        dragX=x; dragY=y;
        if(!songDragging && clock.getAsLong()-pressedAt>=250_000_000L && (x-pressedX)*(x-pressedX)+(y-pressedY)*(y-pressedY)>=16) songDragging=true;
        if(songDragging) updateInsertion(y);
    }
    private void updateInsertion(double y) {
        int count=Math.min(visible.size()-offset,Math.max(0,(bottom-listTop-4)/32));
        if(count<=0) { insertSlot=0; insertBefore=0; return; }
        int row=Maths.clamp((int)Math.floor((y-listTop)/32),0,count-1);
        boolean after=y>=listTop+row*32+16;
        insertSlot=row+(after?1:0);
        insertBefore=visibleIndices.get(offset+row)+(after?1:0);
    }
    private void cancelSongGesture() { pressedRow=null; pressedSource=List.of(); songDragging=false; insertBefore=-1; }
    public void cancelPointer() { cancelSongGesture(); dragging=0; }
    public void release() { release(dragX,dragY,0); }
    public boolean release(double x,double y,int button) {
        if(button!=0) return false;
        if(pressedRow!=null) {
            if(songDragging) {
                updateSongDrag(x,y);
                if(page.equals(pressedPage) && x>=side+5 && x<width-7 && y>=listTop-12 && y<bottom) {
                    try { session.moveSong(page,pressedRow.index(),insertBefore,pressedSource); }
                    catch(java.io.IOException e) { notice=t("songOrderFailed"); noticeUntil=System.nanoTime()+4_000_000_000L; }
                }
            } else if(pressedRow.hit().contains(x,y) && pressedSource.equals(session.tracksForPage(pressedPage))) activate(pressedRow.hit());
            cancelSongGesture(); return true;
        }
        boolean handled=dragging!=0;
        if(dragging==2) Config.save(); else if(dragging==1) session.seek(seekFraction); dragging=0; return handled;
    }
    private void setVolume(double x) { session.volume((int)Math.round((x-volumeX)*100/volumeW)); }
    public boolean scroll(double x,double y,double delta) {
        if(dialogOpen()) { dialogOffset=Math.max(0,dialogOffset-(int)Math.signum(delta)*2); return true; }
        if(y<32 || y>=bottom) return false;
        if(x<side) sideOffset=Math.max(0,sideOffset-(int)Math.signum(delta));
        else offset=Math.max(0,offset-(int)Math.signum(delta)*3);
        return true;
    }
    public boolean key(int key) {
        if(key==256 && pressedRow!=null) { cancelSongGesture(); return true; }
        if(dialogOpen()) {
            if(key==256) { dismissDialog(); return true; }
            if(key==257 || key==335) { if(songEditOpen()) confirmSongInfo(); else if(dialog==Dialog.URL) confirmUrl(); else if(dialog==Dialog.CREATE || dialog==Dialog.RENAME) confirmName(); return true; }
            return false;
        }
        switch(key) {
            case 32 -> session.toggle(); case 78 -> session.next(); case 66 -> session.previous();
            case 82 -> session.reload(); case 83 -> session.shuffle(); case 84 -> session.repeat();
            case 263 -> session.player.seek(Math.max(0,session.player.position()-5));
            case 262 -> { double d=session.duration(session.player.current()); if(d>0) session.player.seek(Math.min(Math.max(0,d-0.01),session.player.position()+5)); }
            default -> { return false; }
        }
        return true;
    }
}
