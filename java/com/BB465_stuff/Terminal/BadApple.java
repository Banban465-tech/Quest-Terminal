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
 * Frames are decoded sequentially and converted to the display rate, never
 * seeked to by timestamp. Both of the obvious alternatives are wrong here, in
 * opposite directions:
 *
 *   - one seek per displayed frame, asking for the nearest sync frame, is
 *     fast but returns the SAME frame for every tick between keyframes, which
 *     is not slow playback, it is a freeze. This file has sparse keyframes.
 *   - asking for the exact frame with OPTION_CLOSEST decodes forward from the
 *     previous keyframe on every call, so twelve requests a second repeat most
 *     of every group of pictures and it plays at a third of speed.
 *
 * So: walk the frames in order, decode a short run at a time, and keep one out
 * of every srcFps/fps. Because the walk is sequential the decoder never has to
 * seek, and because the keeps are counted against the source rate rather than
 * assumed, the speed is right whether the source is 24, 25, 30 or 60fps.
 *
 * Frames are picked by an accumulator rather than a fixed stride, because the
 * ratio is usually not a whole number. 30fps shown at 12fps is two and a half
 * source frames per displayed frame, and a stride of 3 would drift 20% by the
 * end of the video.
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

    /**
     * Source frames decoded per pass. Sequential, so the decoder never seeks,
     * but each one is a real Bitmap, so this is not free. Six at 640x480 is
     * about 7MB held briefly, which is what it takes to be able to keep one in
     * three and still not stall the worker.
     */
    private static final int RUN = 6;

    /** how many ready-to-show frames may sit ahead of the clock */
    private static final int QUEUE_MAX = 6;

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

    /** ready-to-show frames waiting for their moment. worker thread only. */
    private final java.util.ArrayDeque<Bitmap> queue =
            new java.util.ArrayDeque<Bitmap>();

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

        final long durationUs = longMeta(MediaMetadataRetriever.METADATA_KEY_DURATION, 0L);
        int rot = intMeta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION, 0);
        // how many source frames there are, which is what gives the real source
        // rate. without it the rate conversion has to assume, and assuming is
        // how the speed ended up wrong twice already.
        int frameCount = intMeta(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT, 0);
        int rateCalc = fps;
        String srcNote = "assumed " + fps + "fps";
        if (frameCount > 0 && durationUs > 0) {
            rateCalc = (int) Math.max(1,
                    Math.round(((double) frameCount) * 1000000.0 / (double) durationUs));
            srcNote = rateCalc + "fps";
        }
        final int srcFps = rateCalc;

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
                + ", " + fps + "fps shown off a " + srcNote + " source"
                + ", " + hhmmss(durationUs / 1000000L)
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
            public void run() { pump(rotate, rotM, wall0, durationUs, srcFps); }
        }, "badapple");
        worker.setDaemon(true);
        worker.start();
        return null;
    }

    /**
     * Decode the source in order, keep the frames that belong on screen at the
     * display rate, and let the audio clock decide when each is shown.
     */
    private void pump(final boolean rotate, final Matrix rotM,
                      final long wall0, final long durationUs, final int srcFps) {
        final long frameUs = 1000000L / fps;
        int nextSrc = 0;
        // rate conversion, in source frames. Adding the display rate once per
        // source frame and emitting whenever it reaches the source rate keeps
        // exactly fps frames per second no matter what srcFps is.
        int acc = 0;
        long shownUpTo = 0;

        while (!dead) {
            // 1. decode a short run, keep the ones we will actually show
            if (queue.size() < QUEUE_MAX) {
                java.util.List<Bitmap> batch = null;
                try {
                    batch = retriever.getFramesAtIndex(nextSrc, RUN);
                } catch (Throwable ignored) {
                }

                if (batch == null || batch.isEmpty()) {
                    if (!loop) { ended = true; break; }
                    queue.clear();
                    nextSrc = 0;
                    acc = 0;
                    continue;
                }
                nextSrc += batch.size();

                for (Bitmap b : batch) {
                    if (b == null) continue;
                    acc += fps;
                    if (acc < srcFps) {
                        // skipped: free it right away rather than parking 30 of
                        // these in a queue, which is how this leaked a gigabyte
                        b.recycle();
                        continue;
                    }
                    acc -= srcFps;
                    if (rotate) {
                        // rotate here, into the orientation the view wants, so
                        // the UI thread has nothing to think about
                        Bitmap t = Bitmap.createBitmap(
                                b.getHeight(), b.getWidth(), Bitmap.Config.ARGB_8888);
                        Canvas c = new Canvas(t);
                        // clear first. the other order wipes the frame just
                        // drawn, because SRC replaces rather than blends
                        c.drawColor(0xFF000000, PorterDuff.Mode.SRC);
                        c.drawBitmap(b, rotM, null);
                        b.recycle();
                        queue.addLast(t);
                    } else {
                        queue.addLast(b);
                    }
                }
            }

            // 2. hand over whatever is due
            long nowUs = clockUs(wall0);
            if (!queue.isEmpty() && shownUpTo + frameUs <= nowUs) {
                Bitmap due = queue.pollFirst();
                shownUpTo += frameUs;
                postShow(due);
            } else if (queue.size() >= QUEUE_MAX) {
                // the decoder is ahead of the clock. give the surplus back, but
                // do NOT advance the clock for it, or the pacing runs at double
                // rate and everything after the first drop is early
                postFree(queue.pollLast());
            }

            if (!loop && nowUs >= durationUs && queue.isEmpty()) {
                ended = true;
                break;
            }

            try {
                Thread.sleep(4L);
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

        Bitmap b;
        while ((b = queue.pollFirst()) != null) {
            try { b.recycle(); } catch (Throwable ignored) { }
        }

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