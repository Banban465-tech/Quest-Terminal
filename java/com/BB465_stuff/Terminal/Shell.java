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
 * Client side of the Shizuku transport.
 *
 * Shizuku is the only viable transport here and that is not a preference:
 * adbd requires an RSA key we do not hold (ro.adb.secure=1) and, as it turns
 * out, refuses the app's key outright because the PC's key is only authorized
 * over the WIFI TLS transport, not plain TCP. Shizuku already runs as uid 2000,
 * so binding its UserService is what buys a real shell.
 *
 * The wire is a plain synchronous Parcel transact, not Messenger: Messenger
 * drops Message.obj, which is where the command line was being put. exec()
 * therefore blocks on its own thread and posts the result back to the UI, so
 * a slow command cannot ANR the activity.
 */
public class Shell {

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
        // The VR system recreates this activity now and then, and every onCreate
        // called connect() again. That stacked a second polling chain and a
        // second bind on top of the first, which double-ran the startup
        // self-test and would have forked a second PowerShell session.
        if (connecting || binding || pendingWatch != null) {
            Log.i("BBterm", "connect: already connecting, ignoring this one");
            return;
        }
        connecting = true;
        final Context fctx = ctx;
        checkPermission(fctx, cb, 0);
    }

    /**
     * ByteZuku's checkSelfPermission flaps: the same install read 0 on one
     * launch and -1 on the next, alternating. Trusting a single sample made
     * every other launch bail out and pop the manager's prompt, which reset
     * the grant again and made it worse. So poll it briefly before deciding.
     */
    private static final int PERM_TRIES = 5;
    private static final long PERM_DELAY = 350;

    /**
     * The fast burst is not enough. The manager's grant often lands seconds
     * after our prompt, and before this watcher existed that meant closing and
     * reopening the app by hand. Now we keep sampling in the background for a
     * couple of minutes and bind the instant it flips to granted.
     */
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
                // Re-ask on a slow cadence while access is missing. Granting
                // can be revoked by accident - a stray tap in the manager, an
                // install, a manager restart - and a terminal that only ever
                // asks once leaves the user with no shell and no explanation.
                // Every 30s is often enough to be useful and rare enough not to
                // be a nuisance.
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
                    // Do not just stop. A terminal that quietly stops trying is
                    // worse than one that never tries, because it looks like
                    // the app is broken. Re-arm until the grant shows up, so
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
            // phase but wrong here: we are about to bind, and the duplicate
            // guard has to keep covering us until the binder actually lands.
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
     * Ask for access again, on demand.
     *
     * The once-per-process rule is right for not nagging on launch, but it left
     * a real hole: dismiss the dialog once, or revoke the grant by accident,
     * and the app never asked again - it just sat there looking authorised-ish
     * with no shell. This is what the ACCESS button and 'checkshizuku' call, so
     * one tap always produces a fresh prompt.
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
     * Give the Shizuku provider a moment before asking it for a user service.
     * Right after an install it is still coming up, and asking too early is
     * what makes the manager answer with "unable to find token" and never send
     * a binder. Half a second is enough and costs nothing when it works.
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
                            // Prove the transport the moment it binds, so a
                            // working shell is visible in logcat without anyone
                            // having to type into a VR panel. One short command:
                            // this used to also run a full PowerShell session on
                            // every connect, which was only ever a development
                            // check and left a pwsh process behind on every app
                            // launch. 'pwshdemo' is the deliberate way to do that.
                            shell.execRaw("id; echo BBTERM_OK; echo $((6*7))",
                                    new Raw() {
                                        public void got(String s) {
                                            Log.i("BBterm", "selftest -> " + s);
                                        }
                                    });
                            // Is this the build the app expects? A :bbterm from
                            // an older install survives 'am force-stop' and the
                            // Shizuku server keeps handing it back, so without
                            // this the first sign of trouble is some unrelated
                            // command failing much later with no explanation.
                            shell.pingBuild(new Raw() {
                                public void got(String build) {
                                    // runAsync rewrites a "transact rejected"
                                    // into its own message, so the reply here
                                    // is not always a build string. Anything
                                    // that is not our build exactly means the
                                    // service is stale.
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
                            // Being authorised is not the same as having a live
                            // service. If the :bbterm process went away, a
                            // terminal that only complains is useless, so put
                            // the connection back on its own.
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
        // never calls back at all. The manager logs
        //   IllegalArgumentException: unable to find token ...
        // in that case and the service process just sits there with its binder
        // undelivered, so the app would otherwise wait forever with no error
        // and no shell. Treat a silent bind as a failure and try again.
        UI.postDelayed(new Runnable() {
            public void run() {
                if (bound[0] || shell.svc != null) return;
                Log.w("BBterm", "bind timed out, the manager never delivered a binder");
                // A shell left half-built is worse than none: drop it so the
                // retry starts from a clean state.
                connecting = false;
                binding = false;
                shell.svc = null;
                if (retries < MAX_RETRIES) {
                    retries++;
                    // Spread these out. Hammering bindUserService faster than
                    // the manager can drain its queue makes it worse: its own
                    // log fills with "unable to find token", because it is
                    // being handed a request for a token it has already
                    // discarded. A few seconds of patience is what works.
                    long wait = 3000L * retries;
                    Log.i("BBterm", "rebind attempt " + retries + " of "
                            + MAX_RETRIES + " in " + (wait / 1000) + "s");
                    UI.postDelayed(new Runnable() {
                        public void run() { connect(ctx, cb); }
                    }, wait);
                } else {
                    // The manager has genuinely lost the request. Verified on
                    // the device: its own log says "unable to find token", the
                    // stale :bbterm service survives 'am force-stop', and
                    // force-stopping and reopening the manager app does NOT
                    // clear it, because the Shizuku *server* is what still
                    // holds the binder. So say the thing that actually works
                    // instead of a remedy we have seen fail.
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
                        // transact() returning false means the service process
                        // does not know this message code at all, i.e. it is an
                        // older build still running in the :bbterm process.
                        // The app cannot kill it -- that process is uid 2000 and
                        // owned by the manager, not by us -- so say what to do.
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
     * Push a file to the shell and return the byte count, or an error string.
     *
     * Used by pkg, which needs its generated script on the device as a real
     * file so it can be started in the background. It cannot be pasted inline
     * because this busybox's ash writes here-documents to a temp file under
     * /data/local, which uid 2000 cannot write to - so a here-doc based
     * install would fail for a reason that has nothing to do with installing.
     */
    public String uploadTo(java.io.File f, String remote) {
        try {
            return String.valueOf(pushFile(f, remote));
        } catch (Throwable e) {
            return "error: " + e;
        }
    }

    /**
     * Run a command with a file on its stdin.
     *
     * Needed because pm cannot read an apk off /storage/emulated/0 - the sdcard
     * is FUSE and the package manager streams the file through a pipe, which
     * fails there. 'pm install -S <bytes> -' is the way through, and that
     * requires the bytes to actually arrive on stdin.
     *
     * The file is uploaded first as its own transaction, then the command runs
     * with 'cat <staged> |' in front of it. Doing it that way rather than
     * holding the stream open across the transact avoids a binder call that
     * blocks for the whole upload, and if the upload fails nothing is run.
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
                // transact() returns false only when the callee does not know
                // the code, which means the bound :bbterm predates the upload
                // message. Saying "rejected" on its own sent me looking in the
                // wrong place entirely.
                throw new IllegalStateException(
                        "the running shell service is an older build and does"
                        + " not support uploads. " + STALE_MSG);
            }
            r.readException();
            // replyOne writes out, err, code - in that order. Reading one
            // string and calling it the error made every successful upload
            // look like a failure, and the "error" it printed was just the
            // byte count. Then readLong() on an int field for good measure.
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

    /**
     * Ask the service which build it is. A null reply means it does not know
     * the message at all, i.e. it is an older process.
     */
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
