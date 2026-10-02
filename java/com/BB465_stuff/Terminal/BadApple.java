package com.BB465_stuff.Terminal;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.PorterDuff;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.io.File;

/**
 * Bad Apple as a frame overlay instead of terminal output.
 *
 * Drawing the frames into the scrollback is the obvious way and it is a trap:
 * every tick appends a full screen of characters to the session's
 * SpannableStringBuilder, so a three minute video at 12fps is about 2300 full
 * screen appends, tens of megabytes of buffer, and a full re-measure and
 * re-layout of the text view on every one of them. So the pictures get their
 * own view inside the terminal pane and the scrollback keeps what it had.
 *
 * Frames are pulled by timestamp, straight from the audio clock, never by
 * walking a frame index. Two earlier attempts got the speed wrong in opposite
 * directions and both were wrong for the same underlying reason, which is that
 * a frame index and a playing sound are different clocks:
 *
 *   - one frame per tick plays a 30fps source at 2.5x with the music stuck in
 *     real time, because 30 source frames per second go past 12 times;
 *   - deriving a stride from the container's frame count is only as good as
 *     that metadata, and it guessed wrong the other way, slow.
 *
 * Asking for the frame that belongs to the audio that is playing right now
 * cannot be wrong about speed at all. What it costs is exactness: the decoder
 * returns the nearest keyframe rather than decoding forward to land exactly on
 * the timestamp, which is the difference between a fast plain seek and the
 * version that played at a third of speed because every call walked and decoded
 * a whole group of pictures. Keyframes in this source are frequent enough that
 * the quantisation is invisible.
 *
 * Bitmaps belong to the UI thread. The worker never frees one, because the
 * render thread can still be drawing a frame that setImageBitmap has already
 * replaced, and recycling that is a hard crash. Retired frames go back through
 * a delayed post so they outlive the draw that was using them.
 */
class BadApple {

    /** reports back into the terminal */
    interface Say { void say(String s); }

    /**
     * Frames are held this long after being replaced before being freed. One
     * frame is 83ms at 12fps, so this comfortably covers a draw pass that
     * started before the swap.
     */
    private static final int RETIRE_GRACE_MS = 400;

    private final Context ctx;
    /**
     * The terminal pane's parent, not the window. The overlay takes the scroll
     * view's slot so the input bar and status line stay on screen: a
     * full-window overlay hid the very command that stops it, along with the
     * message saying what is playing.
     */
    private final ViewGroup host;
    private final int index;
    private final Say say;
    private final String path;
    private final int fps;
    private final boolean loop;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private ImageView view;
    private MediaPlayer player;
    private MediaMetadataRetriever retriever;
    private Thread worker;
    private volatile boolean dead;
    private volatile boolean ended;

    BadApple(Context ctx, ViewGroup host, int index, Say say,
             String path, int fps, boolean loop) {
        this.ctx = ctx;
        this.host = host;
        this.index = index;
        this.say = say;
        this.path = path;
        this.fps = fps < 1 ? 12 : fps;
        this.loop = loop;
    }

    // ------------------------------------------------------------- lifecycle

    /** @return null on success, otherwise the reason it would not start */
    String start() {
        File f = new File(path);
        if (!f.isFile() || f.length() == 0) {
            return "badapple: no file at " + path + "\n";
        }

        retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(f.getAbsolutePath());
        } catch (Exception e) {
            releaseQuietly();
            return "badapple: " + f.getName() + " is not a video this can read\n"
                    + "  " + e.getClass().getSimpleName() + ": " + e.getMessage() + "\n";
        }

        long durationUs = longMeta(MediaMetadataRetriever.METADATA_KEY_DURATION, 0L);
        int rot = intMeta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION, 0);

        // one decode to learn the real frame size, which is where rotation
        // metadata usually disagrees with the container header
        Bitmap probe = null;
        try {
            probe = retriever.getFrameAtTime(0,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        } catch (Throwable ignored) {
        }
        if (probe == null) {
            releaseQuietly();
            return "badapple: " + f.getName() + " has no decodable video track\n";
        }
        int fw = probe.getWidth();
        int fh = probe.getHeight();
        probe.recycle();
        if (fw <= 0 || fh <= 0) {
            releaseQuietly();
            return "badapple: " + f.getName() + " reported a "
                    + fw + "x" + fh + " frame\n";
        }

        final boolean rotate = rot != 0;
        final Matrix rotM = new Matrix();
        if (rotate) rotM.postRotate(rot);

        // report before attaching. Attaching first puts the overlay over the
        // scroll area before this lands, so the message saying what is playing
        // prints underneath the video and nobody sees it.
        say.say("badapple: " + f.getName() + "\n"
                + "  " + fw + "x" + fh + (rotate ? " rot " + rot : "")
                + ", " + fps + "fps off the audio clock, "
                + hhmmss(durationUs / 1000000L)
                + (loop ? ", looping" : "") + "\n"
                + "  'badapple stop', tap it, or press any key\n");

        view = new ImageView(ctx);
        view.setBackgroundColor(0xFF000000);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setClickable(true);
        view.setFocusable(false);
        view.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { stop("stopped"); }
        });
        // same slot the scroll view has: width full, height 0, weight 1. That
        // fills the pane between the tab bar and the input bar and leaves both
        // of them alone.
        host.addView(view, index, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // audio is the clock, but a silent file still has to play, so failing
        // here is not fatal, it just means falling back to the wall clock
        final long wall0 = SystemClock.elapsedRealtime();
        try {
            player = new MediaPlayer();
            player.setDataSource(f.getAbsolutePath());
            player.setLooping(loop);
            player.prepare();
            player.start();
        } catch (Throwable e) {
            if (player != null) {
                try { player.release(); } catch (Throwable ignored) { }
                player = null;
            }
        }

        worker = new Thread(new Runnable() {
            public void run() { pump(rotate, rotM, wall0, durationUs); }
        }, "badapple");
        worker.setDaemon(true);
        worker.start();
        return null;
    }

    /**
     * Show the frame that belongs to the audio playing right now.
     */
    private void pump(final boolean rotate, final Matrix rotM,
                      final long wall0, final long durationUs) {
        final long frameUs = 1000000L / fps;
        long lastSlot = -1;

        while (!dead) {
            long nowUs = clockUs(wall0);
            long slot = (nowUs / frameUs) * frameUs;

            if (slot != lastSlot) {
                lastSlot = slot;
                Bitmap got = null;
                try {
                    got = retriever.getFrameAtTime(
                            slot, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                } catch (Throwable ignored) {
                }

                if (got == null) {
                    if (!loop) { ended = true; break; }
                    lastSlot = -1;
                    try {
                        Thread.sleep(60L);
                    } catch (InterruptedException e) {
                        return;
                    }
                    continue;
                }

                if (rotate) {
                    Bitmap t = Bitmap.createBitmap(
                            got.getHeight(), got.getWidth(), Bitmap.Config.ARGB_8888);
                    Canvas c = new Canvas(t);
                    // clear first. the other order wipes the frame just drawn,
                    // because SRC replaces the destination rather than blending
                    c.drawColor(0xFF000000, PorterDuff.Mode.SRC);
                    c.drawBitmap(got, rotM, null);
                    got.recycle();
                    postShow(t);
                } else {
                    postShow(got);
                }
            }

            if (!loop && nowUs >= durationUs) { ended = true; break; }

            try {
                Thread.sleep(6L);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private long clockUs(long wall0) {
        if (player != null) {
            try {
                return (long) player.getCurrentPosition() * 1000L;
            } catch (Throwable ignored) {
            }
        }
        return (SystemClock.elapsedRealtime() - wall0) * 1000L;
    }

    // ------------------------------------------------------------- ui thread

    private void postShow(final Bitmap bmp) {
        ui.post(new Runnable() {
            public void run() {
                if (dead || view == null) {
                    postFree(bmp);
                    return;
                }
                view.setImageBitmap(bmp);
                // the frame this one replaced may still be mid-draw, so it goes
                // out on a delay rather than being freed under the render thread
                Bitmap old = view.getTag() instanceof Bitmap ? (Bitmap) view.getTag() : null;
                if (old != null && old != bmp) postFree(old);
                view.setTag(bmp);
            }
        });
    }

    private void postFree(final Bitmap bmp) {
        if (bmp == null) return;
        ui.postDelayed(new Runnable() {
            public void run() {
                if (!bmp.isRecycled()) bmp.recycle();
            }
        }, RETIRE_GRACE_MS);
    }

    void stop(String why) {
        if (dead) return;
        dead = true;

        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        if (player != null) {
            try { player.stop(); } catch (Throwable ignored) { }
            try { player.release(); } catch (Throwable ignored) { }
            player = null;
        }
        releaseQuietly();

        ui.post(new Runnable() {
            public void run() {
                if (view != null) {
                    host.removeView(view);
                    Bitmap old = view.getTag() instanceof Bitmap ? (Bitmap) view.getTag() : null;
                    if (old != null && !old.isRecycled()) old.recycle();
                    view.setTag(null);
                    view = null;
                }
                if (why != null || ended) {
                    say.say("badapple: " + (why == null ? "done" : why) + "\n");
                }
            }
        });
    }

    private void releaseQuietly() {
        if (retriever != null) {
            try { retriever.release(); } catch (Throwable ignored) { }
            retriever = null;
        }
    }

    private int intMeta(int key, int dflt) {
        try {
            String s = retriever.extractMetadata(key);
            return s == null ? dflt : Integer.parseInt(s);
        } catch (Throwable e) {
            return dflt;
        }
    }

    private long longMeta(int key, long dflt) {
        try {
            String s = retriever.extractMetadata(key);
            return s == null ? dflt : Long.parseLong(s);
        } catch (Throwable e) {
            return dflt;
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