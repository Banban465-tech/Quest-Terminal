package com.BB465_stuff.Terminal;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.io.File;

/**
 * Bad Apple as a video overlay instead of terminal output.
 *
 * Drawing the frames into the scrollback is the obvious way and it is a trap:
 * every tick appends a full screen of characters to the session's
 * SpannableStringBuilder, so a three minute video at 12fps is about 2300 full
 * screen appends, tens of megabytes of buffer, and a full re-measure and
 * re-layout of the text view on every one of them. So the pictures get their
 * own view inside the terminal pane and the scrollback keeps what it had.
 *
 * The overlay hands the file to MediaPlayer and gets out of the way. That is
 * several implementations late, and the reasons are worth keeping, because
 * each one looked reasonable at the time:
 *
 *   - getFrameAtTime per frame, OPTION_CLOSEST_SYNC, returns the nearest
 *     keyframe, so with sparse keyframes it shows the same image for every
 *     tick in between. Not slow playback, a freeze.
 *   - OPTION_CLOSEST decodes forward from the previous keyframe on every call,
 *     redoing most of every group of pictures, twelve times a second.
 *   - one decoded frame per tick against the audio clock plays a 30fps source
 *     2.5x.
 *   - keeping one in every srcFps/fps fixes the speed and passes every memory
 *     check, then dies about a minute in, because getFramesAtIndex is O(index):
 *     it re-seeks to the start of the file and counts forward to the frame you
 *     asked for, so each pass costs more than the time it covers.
 *
 * MediaMetadataRetriever is a metadata tool, not a player. It keeps no state
 * between calls, so every frame costs a seek. Android's hardware decoder has
 * all the state, is clocked by the audio track, and drops and repeats frames on
 * its own. SurfaceView plus MediaPlayer is the answer, with one caveat learned
 * the hard way: a SurfaceView is a separate compositor layer, and on this
 * headset it came out as a small black rectangle in the corner. A TextureView
 * is an ordinary view, so it composites and lays out like one.
 */
class BadApple {

    private static final String TAG = "BBTerminal";

    /** reports back into the terminal */
    interface Say { void say(String s); }

    /**
     * An ordinary view that happens to be a video target. Black, tap-to-stop,
     * and sized by the parent to the video's aspect ratio.
     */
    private static class Pane extends TextureView
            implements TextureView.SurfaceTextureListener {
        final BadApple owner;

        Pane(Context c, BadApple owner) {
            super(c);
            this.owner = owner;
            setOpaque(true);
            setClickable(true);
            setFocusable(false);
            setOnClickListener(new OnClickListener() {
                public void onClick(View v) { owner.stop("stopped"); }
            });
            setSurfaceTextureListener(this);
        }

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
            owner.attachSurface(new Surface(st));
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
            owner.detachSurface();
            // we dropped our own reference in detachSurface, so the framework
            // is free to release the texture
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture st) { }
    }

    /**
     * The terminal pane's parent, not the window. The overlay takes the scroll
     * view's slot so the input bar and status line stay on screen: a
     * full-window overlay hid the very command that stops it, along with the
     * message saying what is playing.
     */
    private final Context ctx;
    private final ViewGroup host;
    private final int index;
    private final Say say;
    private final String path;
    private final boolean loop;

    private final Handler ui = new Handler(Looper.getMainLooper());

    /** fills the pane, and centres the video inside itself */
    private FrameLayout wrap;
    private Pane pane;
    private MediaPlayer player;
    private boolean dead;

    private Surface surface;
    private boolean surfaceLive;
    private boolean displaySet;
    private boolean wantPlay;
    private volatile boolean renderedStart;
    private boolean warnedNoPicture;

    BadApple(Context ctx, ViewGroup host, int index, Say say,
             String path, int fps, boolean loop) {
        this.ctx = ctx;
        this.host = host;
        this.index = index;
        this.say = say;
        this.path = path;
        this.loop = loop;
        // fps is accepted and ignored. it only meant something while the
        // frames were being pulled by hand, and it was a way to get the speed
        // wrong rather than a way to set it. the decoder runs at the rate the
        // file and the music agree on.
    }

    // ------------------------------------------------------------- lifecycle

    /** @return null on success, otherwise the reason it would not start */
    String start() {
        File f = new File(path);
        if (!f.isFile() || f.length() == 0) {
            return "badapple: no file at " + path + "\n";
        }

        long durationUs = probeDuration(f);
        String dims = probeDims(f);
        if (dims == null) {
            return "badapple: " + f.getName() + " has no video track\n";
        }

        // report before attaching. Attaching first puts the overlay over the
        // scroll area before this lands, so the message saying what is playing
        // prints underneath the video and nobody sees it.
        say.say("badapple: " + f.getName() + "\n"
                + "  " + dims + ", " + hhmmss(durationUs / 1000000L)
                + (loop ? ", looping" : "") + "\n"
                + "  'badapple stop', tap it, or press any key\n");

        // a FrameLayout holds the pane, so the pane can be centred and letterboxed
        // inside the slot instead of being stretched or pinned to a corner
        wrap = new FrameLayout(ctx);
        wrap.setBackgroundColor(0xFF000000);
        pane = new Pane(ctx, this);
        wrap.addView(pane, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        // same slot the scroll view has: width full, height 0, weight 1. That
        // fills the pane between the tab bar and the input bar and leaves both
        // of them alone.
        host.addView(wrap, index, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        try {
            player = new MediaPlayer();
            player.setDataSource(f.getAbsolutePath());
            player.setLooping(loop);
            player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                public void onPrepared(MediaPlayer mp) {
                    if (dead) { releaseQuietly(); return; }
                    wantPlay = true;
                    Log.i(TAG, "badapple prepared " + mp.getVideoWidth()
                            + "x" + mp.getVideoHeight()
                            + " surfaceLive=" + surfaceLive
                            + " displaySet=" + displaySet);
                    sizeToVideo(mp.getVideoWidth(), mp.getVideoHeight());
                    playIfReady();
                }
            });
            player.setOnVideoSizeChangedListener(
                    new MediaPlayer.OnVideoSizeChangedListener() {
                public void onVideoSizeChanged(MediaPlayer mp, int w, int h) {
                    if (dead) return;
                    sizeToVideo(w, h);
                }
            });
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                public void onCompletion(MediaPlayer mp) {
                    // only reachable when not looping
                    stop(null);
                }
            });
            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    Log.w(TAG, "badapple player error what=" + what + " extra=" + extra);
                    stop("playback error " + what + "/" + extra);
                    return true;
                }
            });
            player.setOnInfoListener(new MediaPlayer.OnInfoListener() {
                public boolean onInfo(MediaPlayer mp, int what, int extra) {
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        renderedStart = true;
                    }
                    return false;
                }
            });
            // async, because prepare() on the UI thread of a headset app is an
            // ANR waiting to happen
            player.prepareAsync();
        } catch (Throwable e) {
            Log.w(TAG, "badapple could not open " + f.getName(), e);
            stop(null);
            return "badapple: " + f.getName() + " would not open\n"
                    + "  " + e.getClass().getSimpleName() + ": " + e.getMessage() + "\n";
        }
        return null;
    }

    /**
     * Size the pane to the video's own proportions, centred in the slot, with
     * black bars where they do not match. Done here rather than by stretching
     * the pane, because a 4:3 video in a tall terminal pane is otherwise
     * squashed out of shape.
     */
    private void sizeToVideo(final int vw, final int vh) {
        if (vw <= 0 || vh <= 0) return;
        ui.post(new Runnable() {
            public void run() {
                if (dead || wrap == null || pane == null) return;
                int availW = wrap.getWidth();
                int availH = wrap.getHeight();
                if (availW <= 0 || availH <= 0) {
                    // not laid out yet; try once the layout has happened
                    wrap.post(new Runnable() {
                        public void run() { sizeToVideo(vw, vh); }
                    });
                    return;
                }
                double scale = Math.min((double) availW / (double) vw,
                                        (double) availH / (double) vh);
                int w = Math.max(1, (int) Math.round(vw * scale));
                int h = Math.max(1, (int) Math.round(vh * scale));
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) pane.getLayoutParams();
                if (lp.width != w || lp.height != h) {
                    lp.width = w;
                    lp.height = h;
                    lp.gravity = Gravity.CENTER;
                    pane.setLayoutParams(lp);
                }
            }
        });
    }

    // ------------------------------------------------------ surface lifetime

    /**
     * A TextureView's SurfaceTexture is neither permanent nor available at a
     * known moment. Binding a player to a surface that has already been
     * released is what produces "IllegalArgumentException: the surface has been
     * released" from inside the renderer, so the display is bound only to a
     * surface that is valid right then.
     */
    void attachSurface(Surface s) {
        surface = s;
        surfaceLive = true;
        displaySet = false;
        bindSurface();
        playIfReady();
    }

    void detachSurface() {
        surfaceLive = false;
        displaySet = false;
        surface = null;
        if (player != null) {
            try { player.setSurface(null); } catch (Throwable ignored) { }
        }
    }

    /**
     * Hand the player a surface, once, for the current one.
     *
     * Split out from attachSurface because the two arrive in either order. The
     * surface appears when the view is added, often before the player object
     * exists; the player then takes a moment to prepare. Whichever arrives
     * second has to finish the job, and doing it in attachSurface alone left the
     * player with no surface at all, which starts and then draws nothing.
     */
    private void bindSurface() {
        if (displaySet || player == null || !surfaceLive || surface == null) return;
        try {
            if (!surface.isValid()) return;
            player.setSurface(surface);
            displaySet = true;
        } catch (Throwable t) {
            Log.w(TAG, "badapple setSurface refused", t);
        }
    }

    /** start, but only when there is both something prepared and somewhere to draw it */
    private void playIfReady() {
        bindSurface();
        if (dead || player == null || !wantPlay || !displaySet) return;
        try {
            if (!player.isPlaying()) player.start();
            Log.i(TAG, "badapple start issued, isPlaying="
                    + player.isPlaying() + " displaySet=" + displaySet);
            // a decoder that cannot allocate output buffers plays the audio and
            // shows nothing, and reports no error of any kind. not hypothetical:
            // it is what the Qualcomm VP9 path did here, and it looked like a
            // freeze.
            ui.postDelayed(new Runnable() {
                public void run() {
                    if (dead || renderedStart || warnedNoPicture) return;
                    warnedNoPicture = true;
                    say.say("badapple: sound yes, picture no. this decoder is\n"
                            + "  refusing the video track (VP9 is the usual\n"
                            + "  culprit on this chip). an H.264 file plays.\n");
                }
            }, 4000L);
        } catch (Throwable t) {
            Log.w(TAG, "badapple start failed", t);
            stop("would not start: " + t.getClass().getSimpleName());
        }
    }

    // ---------------------------------------------------------- probe / meta

    /**
     * Duration and dimensions, for the message only. Deliberately the cheap
     * metadata calls and nothing else: every extra frame decoded here is a
     * frame decoded for nothing.
     */
    private long probeDuration(File f) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            String s = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return s == null ? 0L : Long.parseLong(s);
        } catch (Throwable t) {
            return 0L;
        } finally {
            try { r.release(); } catch (Throwable ignored) { }
        }
    }

    /** @return "WxH" or null if there is no readable video track */
    private String probeDims(File f) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            String w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            if (w == null || h == null) return null;
            int iw = Integer.parseInt(w), ih = Integer.parseInt(h);
            int rot = 0;
            String rs = r.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (rs != null) rot = Integer.parseInt(rs);
            // report the shape as it will actually be shown
            if (rot == 90 || rot == 270) {
                int t = iw; iw = ih; ih = t;
            }
            if (iw <= 0 || ih <= 0) return null;
            return iw + "x" + ih + (rot == 0 ? "" : " rot " + rot);
        } catch (Throwable t) {
            return null;
        } finally {
            try { r.release(); } catch (Throwable ignored) { }
        }
    }

    // ------------------------------------------------------------- teardown

    void stop(String why) {
        if (dead) return;
        dead = true;

        // tear down the view first, then the player. removing the view destroys
        // the texture, and a player still bound to it throws on release.
        ui.post(new Runnable() {
            public void run() {
                if (wrap != null) {
                    host.removeView(wrap);
                    wrap = null;
                    pane = null;
                }
                releaseQuietly();
                surfaceLive = false;
                displaySet = false;
                surface = null;
                wantPlay = false;
                if (why != null) {
                    say.say("badapple: " + why + "\n");
                }
            }
        });
    }

    private void releaseQuietly() {
        if (player != null) {
            MediaPlayer p = player;
            player = null;
            try { p.setSurface(null); } catch (Throwable ignored) { }
            try { p.setOnPreparedListener(null); } catch (Throwable ignored) { }
            try { p.setOnCompletionListener(null); } catch (Throwable ignored) { }
            try { p.setOnErrorListener(null); } catch (Throwable ignored) { }
            try { p.setOnInfoListener(null); } catch (Throwable ignored) { }
            try { p.setOnVideoSizeChangedListener(null); } catch (Throwable ignored) { }
            try { p.reset(); } catch (Throwable ignored) { }
            try { p.release(); } catch (Throwable ignored) { }
        }
    }

    static String hhmmss(long sec) {
        if (sec <= 0) return "?";
        long h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        if (h > 0) return h + ":" + pad(m) + ":" + pad(s);
        return m + ":" + pad(s);
    }

    private static String pad(long v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }
}