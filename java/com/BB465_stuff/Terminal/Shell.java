package com.BB465_stuff.Terminal;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.util.Log;

import java.util.concurrent.atomic.AtomicInteger;

import rikka.shizuku.Shizuku;

/**
 * Shizuku transport. Its UserService runs as uid 2000, the same identity adb
 * shell gets, so no adb key is needed - which is why it is the default.
 *
 * The wire is a synchronous Parcel transact, not Messenger: Messenger drops
 * Message.obj, where the command line was being put. exec() therefore blocks on
 * its own thread and posts back to the UI, so a slow command cannot ANR.
 */
public class Shell implements Transport {

    public interface Callback {
        void onReady(Shell s);
        void onFail(String why);
        void onReply(int id, String out, String err, int code);
    }

    public interface Raw {
        void got(String out);
    }

    private static final Handler UI = new Handler(Looper.getMainLooper());

    private IBinder svc;
    private final Callback cb;
    private final AtomicInteger seq = new AtomicInteger(0);

    private Shell(Callback cb) { this.cb = cb; }

    public static final int REQUEST_CODE = 0xBB47;

    /** one-line summary of the live Shizuku/ByteZuku state, for the UI */
    public static String describeState() {
        boolean alive;
        try {
            alive = Shizuku.pingBinder();
        } catch (Throwable t) {
            alive = false;
        }
        int perm;
        try {
            perm = Shizuku.checkSelfPermission();
        } catch (Throwable t) {
            perm = -1;
        }
        boolean granted =
                perm == android.content.pm.PackageManager.PERMISSION_GRANTED;
        return (alive ? "server up" : "server DOWN")
                + " | access " + (granted ? "GRANTED" : "DENIED");
    }

    /** raw answer to "did shizuku say yes or no", for checkshizuku */
    public static String status() {
        boolean alive;
        try {
            alive = Shizuku.pingBinder();
        } catch (Throwable t) {
            alive = false;
        }
        int perm;
        try {
            perm = Shizuku.checkSelfPermission();
        } catch (Throwable t) {
            perm = -1;
        }
        boolean granted = perm == android.content.pm.PackageManager.PERMISSION_GRANTED;
        return "  manager      " + (alive ? "running" : "NOT RUNNING") + "\n"
             + "  access       " + (granted ? "YES - granted" : "NO - denied")
             + "   (selfPermission=" + perm + ")\n";
    }

    public static void requestPermission() {
        try {
            Log.i("BBterm", "requestPermission: calling Shizuku.requestPermission");
            Shizuku.requestPermission(REQUEST_CODE);
            Log.i("BBterm", "requestPermission: returned normally");
        } catch (Throwable t) {
            // Previously swallowed, which hid the real reason no dialog appeared.
            Log.w("BBterm", "requestPermission threw", t);
        }
    }

    public static void connect(Context ctx, final Callback cb) {
        boolean alive;
        try {
            alive = Shizuku.pingBinder();
        } catch (Throwable t) {
            Log.w("BBterm", "pingBinder threw", t);
            alive = false;
        }
        Log.i("BBterm", "connect: pingBinder=" + alive);
        if (!alive) {
            cb.onFail("Shizuku/ByteZuku is not running - start it first");
            return;
        }
        // The VR system recreates this activity on its own, and a second
        // connect() would stack a second watcher and bind on top of the first.
        if (connecting || binding || pendingWatch != null) {
            Log.i("BBterm", "connect: already connecting, ignoring this one");
            return;
        }
        connecting = true;
        final Context fctx = ctx;
        checkPermission(fctx, cb, 0);
    }

    // ByteZuku's checkSelfPermission flaps: the same install alternates between
    // 0 and -1 across launches. Trusting one sample made every other launch bail
    // out and pop the manager's prompt, which reset the grant and made it worse.
    private static final int PERM_TRIES = 5;
    private static final long PERM_DELAY = 350;

    // A grant often lands seconds after the prompt, so keep sampling in the
    // background for a few minutes and bind the moment it flips to granted.
    private static final long SLOW_DELAY = 2000;
    private static final int SLOW_TRIES = 90;          // ~3 minutes
    /** after a full miss, back off and try again rather than giving up for good */
    private static final long REARM_DELAY = 15000;
    private static boolean askedThisProcess = false;
    private static Runnable pendingWatch = null;      // at most one watcher
    private static boolean connecting = false;
    /** set while a bind is in flight, so the guard covers the bind window too */
    private static boolean binding = false;
    private static int retries = 0;
    private static final int MAX_RETRIES = 3;
    /** after the quick retries, keep trying at this interval */
    private static final long SLOW_REBIND = 20000;
    /** while access is missing, re-prompt the user every N watch ticks (30s) */
    private static final int REASK_EVERY = 15;

    private static void cancelWatch() {
        connecting = false;
        if (pendingWatch != null) {
            UI.removeCallbacks(pendingWatch);
            pendingWatch = null;
        }
    }

    /** manual re-check, used by checkshizuku after the user grants in the UI */
    public static void retry(final Context ctx, final Callback cb) {
        cancelWatch();
        askedThisProcess = false;      // asking again is fine, it is once per call
        connecting = true;
        checkPermission(ctx, cb, 0);
    }

    private static void startWatch(final Context ctx, final Callback cb) {
        if (pendingWatch != null) return;
        final int[] tick = { 0 };
        Log.i("BBterm", "watch: started, sampling every " + SLOW_DELAY
                + "ms up to " + SLOW_TRIES + " times");
        Runnable r = new Runnable() {
            public void run() {
                tick[0]++;
                int perm;
                try {
                    perm = Shizuku.checkSelfPermission();
                } catch (Throwable t) {
                    perm = -1;
                }
                Log.i("BBterm", "watch: selfPermission=" + perm + " tick=" + tick[0]);
                if (perm == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    pendingWatch = null;
                    Log.i("BBterm", "watch: granted, binding now");
                    binding = true;
                    settleThenBind(ctx, cb);
                    return;
                }
                // Re-ask on a slow cadence: a grant can be revoked by accident,
                // and asking only once leaves the user with no shell and no way
                // to see why. 30s is often enough and rare enough not to nag.
                if (tick[0] % REASK_EVERY == 0) {
                    Log.i("BBterm", "watch: still denied at tick " + tick[0]
                            + ", asking again");
                    askedThisProcess = false;
                    requestPermission();
                }
                if (tick[0] < SLOW_TRIES) {
                    UI.postDelayed(this, SLOW_DELAY);
                } else {
                    pendingWatch = null;
                    connecting = false;
                    Log.i("BBterm", "watch: gave up after " + tick[0]
                            + " ticks, re-arming in " + (REARM_DELAY / 1000) + "s");
                    // Never just stop: re-arm until the grant shows up, so
                    // granting in the manager always heals on its own.
                    UI.postDelayed(new Runnable() {
                        public void run() { startWatch(ctx, cb); }
                    }, REARM_DELAY);
                }
            }
        };
        pendingWatch = r;
        UI.postDelayed(r, SLOW_DELAY);
    }

    private static void checkPermission(final Context ctx, final Callback cb,
                                        final int attempt) {
        int perm;
        try {
            perm = Shizuku.checkSelfPermission();
        } catch (Throwable t) {
            Log.w("BBterm", "checkSelfPermission threw", t);
            perm = -1;
        }
        Log.i("BBterm", "connect: selfPermission=" + perm + " attempt=" + attempt);
        if (perm == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            cancelWatch();
            // cancelWatch clears 'connecting', which is right for the polling
            // phase but wrong here: the duplicate guard must keep covering us
            // until the binder actually lands.
            binding = true;
            settleThenBind(ctx, cb);
            return;
        }
        if (attempt < PERM_TRIES - 1) {
            UI.postDelayed(new Runnable() {
                public void run() { checkPermission(ctx, cb, attempt + 1); }
            }, PERM_DELAY);
            return;
        }
        // genuinely denied: ask once per process, not once per launch
        if (!askedThisProcess) {
            askedThisProcess = true;
            requestPermission();
        }
        // keep watching in the background so a late grant still lands
        startWatch(ctx, cb);
        cb.onFail("requesting access from Shizuku/ByteZuku...");
    }

    /**
     * The once-per-process rule avoids nagging on launch, but it left a hole:
     * dismiss the dialog once, or lose the grant, and the app never asked
     * again. This backs the ACCESS button and 'checkshizuku'.
     */
    public static void requestAgain(final Context ctx, final Callback cb) {
        askedThisProcess = false;
        cancelWatch();
        connecting = true;
        retries = 0;
        Log.i("BBterm", "requesting access again on demand");
        int perm;
        try {
            perm = Shizuku.checkSelfPermission();
        } catch (Throwable t) {
            perm = -1;
        }
        if (perm == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            binding = true;
            settleThenBind(ctx, cb);
            return;
        }
        requestPermission();
        // and keep watching, so granting in the manager still lands
        startWatch(ctx, cb);
        cb.onFail("asked again - answer the Shizuku/ByteZuku prompt.");
    }

    /**
     * Right after an install the provider is still coming up, and asking too
     * early is what makes the manager answer "unable to find token" and never
     * send a binder. Half a second is enough.
     */
    private static void settleThenBind(final Context ctx, final Callback cb) {
        UI.postDelayed(new Runnable() {
            public void run() { bind(ctx, cb); }
        }, 500);
    }

    private static void bind(Context ctx, final Callback cb) {
        final Shell shell = new Shell(cb);
        final boolean[] bound = { false };

        try {
            Shizuku.bindUserService(
                    new Shizuku.UserServiceArgs(
                            new ComponentName(ctx, ShellService.class))
                            .processNameSuffix("bbterm"),
                    new ServiceConnection() {
                        public void onServiceConnected(ComponentName n, IBinder b) {
                            bound[0] = true;
                            binding = false;
                            retries = 0;
                            shell.svc = b;
                            Log.i("BBterm", "service connected binder=" + b);
                            UI.post(new Runnable() {
                                public void run() { cb.onReady(shell); }
                            });
                            // One short command so a working shell is visible in
                            // logcat. 'pwshdemo' is the deliberate way to bring
                            // up a full PowerShell session.
                            shell.execRaw("id; echo BBTERM_OK; echo $((6*7))",
                                    new Raw() {
                                        public void got(String s) {
                                            Log.i("BBterm", "selftest -> " + s);
                                        }
                                    });
                            // A :bbterm from an older install survives
                            // 'am force-stop' and the Shizuku server hands it
                            // back, so check the build now or an unrelated
                            // command fails much later with no explanation.
                            shell.pingBuild(new Raw() {
                                public void got(String build) {
                                    // runAsync rewrites a rejected transact into
                                    // its own message, so a non-null reply is not
                                    // always a build string.
                                    if (build == null || !ShellService.BUILD.equals(build)) {
                                        Log.w("BBterm", "service is not the"
                                                + " expected build (got: " + build
                                                + ", want: " + ShellService.BUILD
                                                + ") - it is an older :bbterm");
                                        staleService();
                                    } else {
                                        Log.i("BBterm", "service build ok: " + build);
                                    }
                                }
                            });
                        }
                        public void onServiceDisconnected(ComponentName n) {
                            shell.svc = null;
                            binding = false;
                            connecting = false;
                            Log.w("BBterm", "service disconnected, rebinding");
                            UI.post(new Runnable() {
                                public void run() { cb.onFail("the shell service"
                                        + " died, reconnecting..."); }
                            });
                            // Authorised does not mean the service is alive, so
                            // put the connection back on its own.
                            UI.postDelayed(new Runnable() {
                                public void run() {
                                    if (shell.svc == null) connect(ctx, cb);
                                }
                            }, 900);
                        }
                    });
        } catch (Throwable t) {
            binding = false;
            connecting = false;
            cb.onFail("bindUserService: " + t);
            return;
        }

        // bindUserService is async and, when the manager is in a bad state,
        // never calls back at all - it logs "unable to find token" and the
        // binder is never delivered. Treat a silent bind as a failure.
        UI.postDelayed(new Runnable() {
            public void run() {
                if (bound[0] || shell.svc != null) return;
                Log.w("BBterm", "bind timed out, the manager never delivered a binder");
                // A shell left half-built is worse than none: drop it so the
                // retry starts clean.
                connecting = false;
                binding = false;
                shell.svc = null;
                if (retries < MAX_RETRIES) {
                    retries++;
                    // Space these out: hammering bindUserService faster than the
                    // manager can drain its queue makes it worse, because each
                    // request asks for a token it has already discarded.
                    long wait = 3000L * retries;
                    Log.i("BBterm", "rebind attempt " + retries + " of "
                            + MAX_RETRIES + " in " + (wait / 1000) + "s");
                    UI.postDelayed(new Runnable() {
                        public void run() { connect(ctx, cb); }
                    }, wait);
                } else {
                    // The Shizuku *server* still holds a binder for a dead
                    // :bbterm, and neither force-stop nor restarting the manager
                    // clears it. The message below is what actually works.
                    Log.w("BBterm", "no binder after " + MAX_RETRIES
                            + " attempts; the Shizuku server is holding a"
                            + " binder for a dead service.");
                    cb.onFail("authorised, but Shizuku will not hand over the"
                            + " binder. Its own log says it lost the request"
                            + " (\"unable to find token\").\n"
                            + "A stale com.BB465_stuff.Terminal:bbterm service"
                            + " from an earlier run is still registered with"
                            + " the Shizuku server, and force-stop does not"
                            + " remove it.\n"
                            + "What clears it:\n"
                            + "  adb shell pkill -f shizuku_server   (from a PC)\n"
                            + "  or reboot the headset\n"
                            + "Force-stopping the manager app alone does not"
                            + " help - the server keeps the stale binder.\n"
                            + "Still retrying every "
                            + (SLOW_REBIND / 1000) + "s in the meantime.");
                    UI.postDelayed(new Runnable() {
                        public void run() {
                            if (shell.svc == null) connect(ctx, cb);
                        }
                    }, SLOW_REBIND);
                }
            }
        }, 8000);
    }

    public boolean isReady() { return svc != null; }

    public int uid() { return 2000; }

    public String describe() {
        return "uid 2000 shell via Shizuku/ByteZuku";
    }

    private static final class Res {
        String out;
        String err;
        int code;
    }

    private Res call(String cmd) {
        return call(ShellService.MSG_EXEC, cmd);
    }

    /**
     * Serialized on purpose: the PowerShell session is a single pipe with a
     * sentinel line marking each reply, so two overlapping calls would read
     * each other's output. One at a time.
     */
    private synchronized Res call(int code, String payload) {
        IBinder b = svc;
        if (b == null) { Res x = new Res(); x.err = "no service"; x.code = -1; return x; }
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInterfaceToken(ShellService.DESCRIPTOR);
            d.writeInt(0);
            d.writeString(payload == null ? "" : payload);
            if (!b.transact(code, d, r, 0)) {
                Res x = new Res();
                x.err = "transact rejected (code " + code + ")";
                x.code = -1;
                return x;
            }
            r.readException();
            Res x = new Res();
            x.out = r.readString();
            x.err = r.readString();
            x.code = r.readInt();
            return x;
        } catch (Throwable t) {
            Res x = new Res();
            x.err = "transact: " + t;
            x.code = -1;
            return x;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    private void runAsync(final int code, final String payload, final Raw r) {
        if (svc == null) { r.got(null); return; }
        Thread t = new Thread("bbterm-call") {
            public void run() {
                final Res res = call(code, payload);
                final String s = (res.out != null && res.out.length() > 0)
                        ? res.out : res.err;
                UI.post(new Runnable() {
                    public void run() {
                        // A false transact() means the service does not know
                        // this message code at all - an older build still in the
                        // :bbterm process, which we cannot kill (uid 2000, owned
                        // by the manager).
                        if (res.err != null && res.err.contains("transact rejected")) {
                            Log.w("BBterm", "service rejected code " + code
                                    + " - stale :bbterm process");
                            cb.onFail("the running terminal service is an older build."
                                    + " restart the Shizuku/ByteZuku manager to reload"
                                    + " it, then reopen this app.");
                        }
                        r.got(s);
                    }
                });
            }
        };
        t.setDaemon(true);
        t.start();
    }

    /** bring up the persistent PowerShell session */
    public void psStart(Raw r) { runAsync(ShellService.MSG_PS_START, "", r); }

    /** run one line inside that session, keeping all its state */
    public void psLine(String cmd, Raw r) { runAsync(ShellService.MSG_PS_LINE, cmd, r); }

    public void psStop(Raw r) { runAsync(ShellService.MSG_PS_STOP, "", r); }

    /** fire and forget from the UI's point of view; output arrives via the callback */
    public void exec(String cmd) { exec(cmd, ""); }

    public void exec(String cmd, String cwd) {
        if (svc == null) return;
        final int id = seq.incrementAndGet();
        final String full = (cwd != null && cwd.length() > 0)
                ? ("cd '" + cwd + "' && " + cmd)
                : cmd;
        Thread t = new Thread("bbterm-exec") {
            public void run() {
                Res res = call(full);
                final String out = res.out, err = res.err;
                final int code = res.code;
                UI.post(new Runnable() {
                    public void run() { cb.onReply(id, out, err, code); }
                });
            }
        };
        t.setDaemon(true);
        t.start();
    }

    /**
     * Used by pkg, which needs its generated script on the device as a real
     * file to start it in the background: ash (toybox or the sh that ships on
     * the device) writes here-documents
     * to a temp file under /data/local, which uid 2000 cannot write to.
     */
    public String uploadTo(java.io.File f, String remote) {
        try {
            return String.valueOf(pushFile(f, remote));
        } catch (Throwable e) {
            return "error: " + e;
        }
    }

    /**
     * Needed because pm cannot read an apk off /storage/emulated/0: the sdcard
     * is FUSE and the package manager streams the file through a pipe, which
     * fails there. 'pm install -S <bytes> -' works, and needs the bytes on
     * stdin.
     *
     * The file is uploaded as its own transaction first, then the command runs
     * with 'cat <staged> |' in front of it: holding the stream open across the
     * transact would block a binder call for the whole upload.
     */
    public void execFromStream(final java.io.File f, String script) throws Exception {
        final int id = seq.incrementAndGet();
        final String remote = ShellService.STAGE_FILE;
        Thread t = new Thread("bbterm-stream") {
            public void run() {
                long sent;
                try {
                    sent = pushFile(f, remote);
                } catch (Throwable e) {
                    final String msg = "upload failed: " + e;
                    UI.post(new Runnable() {
                        public void run() { cb.onReply(id, msg, null, -1); }
                    });
                    return;
                }
                final long n = sent;
                // -S is the byte count, and it has to be the real one
                String cmd = "cat '" + remote + "' | " + script;
                cmd = cmd.replace("$N", String.valueOf(n));
                final Res res = call(cmd);
                final String out = res.out, err = res.err;
                final int code = res.code;
                UI.post(new Runnable() {
                    public void run() {
                        // tidy up the staged copy whatever happened
                        exec("rm -f '" + remote + "'");
                        cb.onReply(id, out, err, code);
                    }
                });
            }
        };
        t.setDaemon(true);
        t.start();
    }

    /** MSG_UPLOAD, returns the number of bytes the service wrote out */
    private long pushFile(java.io.File f, String remote) throws Exception {
        IBinder b = svc;
        if (b == null) throw new IllegalStateException("no service");
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInterfaceToken(ShellService.DESCRIPTOR);
            d.writeInt(0);
            d.writeString(remote);
            d.writeInt((int) f.length());
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                byte[] buf = new byte[32 * 1024];
                long total = 0;
                int n;
                while (total < f.length() && (n = in.read(buf)) > 0) {
                    d.writeByteArray(buf, 0, n);
                    total += n;
                }
                d.writeInt((int) total);
            } finally {
                in.close();
            }
            if (!b.transact(ShellService.MSG_UPLOAD, d, r, 0)) {
                // false only when the callee does not know the code - the bound
                // :bbterm predates the upload message.
                throw new IllegalStateException(
                        "the running shell service is an older build and does"
                        + " not support uploads. " + STALE_MSG);
            }
            r.readException();
            // replyOne writes out, err, code in that order
            String out = r.readString();
            String err = r.readString();
            int code = r.readInt();
            if (err != null && err.length() > 0) {
                throw new IllegalStateException(err);
            }
            if (code != 0) {
                throw new IllegalStateException("upload failed, code " + code);
            }
            long n;
            try {
                n = Long.parseLong(out == null ? "0" : out.trim());
            } catch (NumberFormatException e) {
                throw new IllegalStateException("unexpected upload reply: " + out);
            }
            return n;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    /** a null reply means the service does not know the message at all */
    public void pingBuild(Raw r) { runAsync(ShellService.MSG_PING, "", r); }

    /** what to tell the user when the bound service is not the one we shipped */
    public static final String STALE_MSG =
              "the running shell service is an older build.\n"
            + "\n"
            + "The :bbterm process is left over from a previous install.\n"
            + "am force-stop does not kill it, and the Shizuku server keeps\n"
            + "its binder, so a new app can end up talking to old code. That\n"
            + "is why a command can fail with no useful message.\n"
            + "\n"
            + "clear it with, from a PC:\n"
            + "  adb shell pkill -f bbterm\n"
            + "\n"
            + "or restart the Shizuku server, or reboot the headset. Then\n"
            + "reopen this app. Nothing installed is lost - the packages live\n"
            + "outside the app.";

    private static void staleService() {
        if (staleNotified) return;
        staleNotified = true;
        if (staleCb != null) staleCb.onFail(STALE_MSG);
    }

    private static boolean staleNotified = false;
    private static Callback staleCb = null;

    /** remember the callback so the stale warning has somewhere to go */
    public static void watchStale(Callback cb) { staleCb = cb; staleNotified = false; }

    /** blocking probe, for builtins that need the value back inline */
    public void execRaw(final String cmd, final Raw r) {
        if (svc == null) { r.got(null); return; }
        Thread t = new Thread("bbterm-raw") {
            public void run() {
                Res res = call(cmd);
                final String s = (res.out != null && res.out.length() > 0)
                        ? res.out : res.err;
                UI.post(new Runnable() {
                    public void run() { r.got(s); }
                });
            }
        };
        t.setDaemon(true);
        t.start();
    }
}
