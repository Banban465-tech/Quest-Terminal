package com.BB465_stuff.Terminal;

import android.os.Binder;
import android.os.Parcel;
import android.os.RemoteException;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shizuku starts this in its own process as uid 2000 (shell), which is what
 * gives a real command environment with no root: logcat, dumpsys, pm, am,
 * settings. It cannot ptrace/inject (EPERM
 * under SELinux Enforcing), run su, or pair adb (ro.adb.secure=1).
 *
 * Extends Binder, not Service: ByteZuku's starter calls Class.newInstance() and
 * casts the instance itself to IBinder, so a Service subclass dies on
 * ClassCastException and a missing zero-arg ctor dies on InstantiationException.
 * Binder satisfies both and asBinder() returns this.
 *
 * Raw Parcel, not Messenger: Messenger.writeToParcel drops Message.obj, so the
 * command string never arrived. We own both ends, so the wire is a plain
 * synchronous transact - token, id, command; read back out, err, code.
 */
public class ShellService extends Binder {

    public static final String DESCRIPTOR = "com.BB465_stuff.Terminal.IShell";

    public static final int MSG_EXEC     = 1;
    public static final int MSG_DESCRIBE = 2;

    /**
     * PowerShell session control. A real long-lived pwsh process, so variables,
     * modules and the working directory survive between lines. Each command is
     * followed by a sentinel line and the reader collects stdout until it comes
     * back, which is how a REPL is driven over a one-shot RPC.
     */
    public static final int MSG_PS_START = 3;
    public static final int MSG_PS_LINE  = 4;
    public static final int MSG_PS_STOP  = 5;
    /** the app pushing a file to the shell, used by apkinstall */
    public static final int MSG_UPLOAD   = 6;
    /**
     * Handshake. A :bbterm from an older install can outlive the app -
     * force-stop does not kill it and the Shizuku server keeps its binder - and
     * the only symptom is a confusing mid-command failure. Asking on connect
     * turns that into one clear line at startup.
     */
    public static final int MSG_PING     = 7;
    /** bump whenever the message codes or their payloads change */
    public static final String BUILD = "2026-09-29.1";

    /** wrapper that puts the glibc loader in front of the real pwsh binary */
    public static final String PWSH = "/data/local/tmp/pwsh/pwsh.sh";
    /** where apkinstall stages an apk before handing it to pm */
    public static final String STAGE_FILE = "/data/local/tmp/bbapkinstall.apk";

    private static final String PS_SENTINEL = "__BB_PS_DONE_7f3a91c4__";
    private static final int PS_TIMEOUT_MS = 25000;
    /** bumped per call so a stale sentinel can never end the wrong reply */
    private static int psSeq = 0;

    /**
     * One probe at startup so the UI can name the build it actually got. The
     * leading BB tag is the point: an echoed prompt or a stray line cannot pass
     * off as a version, so the UI only renders a banner it can parse.
     */
    private static final String PS_VERSION_CMD =
            "'BB|' + $PSVersionTable.PSVersion + '|' + $PSVersionTable.PSEdition"
          + " + '|' + $PSVersionTable.OS + '|' + $PSVersionTable.Platform";


    private Process psProc;
    private PrintWriter psIn;
    private final BlockingQueue<String> psQ = new LinkedBlockingQueue<String>();
    private Thread psReader;

    /** kept so older callers still compile; the reply is a parcel now */
    public static final int MSG_REPLY = 2;

    public static final String KEY_OUT  = "out";
    public static final String KEY_ERR  = "err";
    public static final String KEY_CODE = "code";
    public static final String KEY_ID   = "id";

    /*
     * No bundled busybox, and none needed: Meta Quest ships toybox in
     * /system/bin, which answers every applet the generated scripts and the
     * environment used to reach through $BB.
     */

    /**
     * Where 'pkg install' puts packages. Deliberately NOT PREFIX: /storage is a
     * FUSE mount that refuses symlink() and link() with EACCES, and nearly
     * every Termux package ships symlinks (bin/python -> bin/python3.14), so
     * untarring there dies partway through. /data/local/tmp is ext4, uid 2000
     * owned, and survives a reboot - but it is outside the app, so packages
     * outlive an uninstall, a factory reset wipes it, and it is not private.
     */
    public static final String PKGROOT = "/data/local/tmp/bbpkg";

    /**
     * In the app's own external files dir rather than /data/local/tmp, so an
     * uninstall cleans it up. uid 2000 can read it once chmod'd.
     */
    public static final String PREFIX =
            "/storage/emulated/0/Android/data/com.BB465_stuff.Terminal/files/bbterm";

    private static final int MAX_OUT = 60_000;
    private static final int QUEUE   = 256;

    /**
     * Stock Shizuku constructs this reflectively passing its UserServiceArgs;
     * ByteZuku calls Class.newInstance() and needs a public zero-arg one.
     * Provide both and let each starter pick whichever it can reach.
     */
    public ShellService(rikka.shizuku.Shizuku.UserServiceArgs args) {
        log("constructed with UserServiceArgs");
    }

    public ShellService() {
        log("constructed no-arg (ByteZuku path)");
    }

    private void log(String how) {
        android.util.Log.i("BBterm", "ShellService " + how
                + " pid=" + android.os.Process.myPid()
                + " uid=" + android.os.Process.myUid());
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        if (code == MSG_EXEC) {
            Result r = new Result();
            int id = 0;
            try {
                data.enforceInterface(DESCRIPTOR);
                id = data.readInt();
                String cmd = data.readString();
                if (cmd == null) cmd = "";
                android.util.Log.i("BBterm", "exec id=" + id + " uid="
                        + android.os.Process.myUid() + " cmd=" + cmd);
                r = run(cmd);
            } catch (Throwable t) {
                android.util.Log.w("BBterm", "exec failed", t);
                r.err = "internal: " + t;
                r.code = -1;
            }
            try {
                reply.writeNoException();
                reply.writeString(r.out);
                reply.writeString(r.err);
                reply.writeInt(r.code);
            } catch (Throwable ignored) {
            }
            return true;
        }
        if (code == MSG_DESCRIBE) {
            try {
                reply.writeNoException();
                reply.writeString(describe());
            } catch (Throwable ignored) {
            }
            return true;
        }
        if (code == MSG_PS_START) {
            readPayload(data);
            Result r = psStart();
            replyOne(reply, r);
            return true;
        }
        if (code == MSG_PS_LINE) {
            Result r = psLine(readPayload(data));
            replyOne(reply, r);
            return true;
        }
        if (code == MSG_PS_STOP) {
            readPayload(data);
            Result r = psStop();
            replyOne(reply, r);
            return true;
        }
        if (code == MSG_UPLOAD) {
            replyOne(reply, upload(data));
            return true;
        }
        if (code == MSG_PING) {
            Result r = new Result();
            r.out = BUILD;
            replyOne(reply, r);
            return true;
        }
        return super.onTransact(code, data, reply, flags);
    }

    /**
     * Used by apkinstall: pm streams an apk through a pipe and /storage is a
     * FUSE mount, so the bytes are staged on a real filesystem first. Returns
     * the byte count so the caller can pass a truthful -S to pm; a short write
     * is an error rather than a silently truncated install.
     */
    private Result upload(Parcel data) {
        Result r = new Result();
        try {
            String path = readPayload(data);
            long expect = data.readInt();
            long total = 0;
            java.io.File out = new java.io.File(path);
            File parent = out.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                r.err = "cannot create " + parent;
                r.code = -1;
                return r;
            }
            java.io.FileOutputStream fo = new java.io.FileOutputStream(out);
            try {
                while (total < expect) {
                    byte[] chunk = data.createByteArray();
                    if (chunk == null || chunk.length == 0) break;
                    fo.write(chunk);
                    total += chunk.length;
                }
                fo.flush();
                fo.getFD().sync();
            } finally {
                fo.close();
            }
            if (total != expect) {
                r.err = "short write: got " + total + " of " + expect + " bytes";
                r.code = -1;
                return r;
            }
            r.out = String.valueOf(total);
        } catch (Throwable t) {
            r.err = "upload: " + t;
            r.code = -1;
        }
        return r;
    }

    /**
     * Every client call starts [interfaceToken][int id][string payload]. A
     * Parcel is a sequential cursor, so skipping the int lands readString() on
     * the int and the command arrives as "0". Drain all three fields.
     */
    private static String readPayload(Parcel data) {
        try {
            data.enforceInterface(DESCRIPTOR);
        } catch (Throwable ignored) {
        }
        try {
            data.readInt();
        } catch (Throwable ignored) {
        }
        try {
            String s = data.readString();
            return s == null ? "" : s;
        } catch (Throwable t) {
            return "";
        }
    }

    private void replyOne(Parcel reply, Result r) {
        try {
            reply.writeNoException();
            reply.writeString(r.out);
            reply.writeString(r.err);
            reply.writeInt(r.code);
        } catch (Throwable ignored) {
        }
    }

    /**
     * The one environment every command gets, whether it runs through the
     * Shizuku service or the local su path, so both behave the same. The
     * exports are also repeated inside the command string for the PowerShell
     * session, because su sanitises parts of the environment on some managers.
     */
    public static void applyEnv(java.util.Map<String, String> env) {
        env.put("PATH", PKGROOT + "/bin:" + PREFIX + "/bin:"
                + "/system/bin:/system/xbin:/vendor/bin:/debug_ramdisk");
        env.put("HOME", PREFIX);
        // Termux binaries carry an absolute RPATH into Termux's own lib dir, so
        // they only load here because LD_LIBRARY_PATH names ours first.
        env.put("LD_LIBRARY_PATH",
                PKGROOT + "/lib:" + PREFIX + "/lib:/system/lib64:/system/lib");
        env.put("TMPDIR", PKGROOT + "/tmp");
        exportCaPaths(env);
    }

    /** the same environment as sh statements, for command strings that must
     *  survive a su which rewrites the environment */
    public static String envExports() {
        StringBuilder sb = new StringBuilder();
        sb.append("export PATH=").append(shq(PKGROOT + "/bin:" + PREFIX + "/bin:"
                + "/system/bin:/system/xbin:/vendor/bin:/debug_ramdisk"));
        sb.append("; export HOME=").append(shq(PREFIX));
        sb.append("; export LD_LIBRARY_PATH=")
          .append(shq(PKGROOT + "/lib:" + PREFIX + "/lib:/system/lib64:/system/lib"));
        sb.append("; export TMPDIR=").append(shq(PKGROOT + "/tmp"));
        return sb.toString();
    }

    /** single-quote a string for /system/bin/sh */
    public static String shq(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /**
     * Without this python reports CERTIFICATE_VERIFY_FAILED for every https
     * request, which reads like "no internet" when the network is fine. Only
     * set when the bundle exists: pointing a client at a missing file turns a
     * working default into a failure.
     */
    private static void exportCaPaths(java.util.Map<String, String> env) {
        String pem = PKGROOT + "/etc/tls/cert.pem";
        if (!new File(pem).isFile()) return;
        env.put("SSL_CERT_FILE", pem);
        env.put("CURL_CA_BUNDLE", pem);
        env.put("REQUESTS_CA_BUNDLE", pem);
        env.put("NODE_EXTRA_CA_CERTS", pem);
        env.put("GIT_SSL_CAINFO", pem);
        String dir = PKGROOT + "/etc/tls/certs";
        if (new File(dir).isDirectory()) env.put("SSL_CERT_DIR", dir);
    }

    // ------------------------------------------------------------ powershell

    /**
     * -Command - makes pwsh read statements from stdin, so one process serves
     * the whole session. No TTY means no prompt of its own, so the app draws the
     * PS C:\...> line itself.
     */
    /** why the last start attempt failed, carried into the reply */
    private String psFail = "";

    /**
     * Two ways to hand pwsh a stdin. "-Command -" is the documented "read the
     * script from standard input" form, but how much it buffers before running
     * a line varies between versions, and a form that waits for EOF can never
     * answer a sentinel written into the middle of the stream - which is
     * exactly this design. So the plain form is kept as a fallback and
     * whichever one actually produces the version probe is the one left up.
     */
    private static final String[] PS_MODE_COMMAND = {
        "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", "-" };
    private static final String[] PS_MODE_PLAIN = {
        "-NoLogo", "-NoProfile", "-NonInteractive" };

    private synchronized Result psStart() {
        Result r = new Result();
        if (psProc != null && psProc.isAlive()) {
            r.out = "already running";
            return r;
        }
        String pre = psPreflight();
        if (pre != null) {
            r.err = pre;
            r.code = -1;
            return r;
        }

        String why = "no output";
        String[][] modes = { PS_MODE_COMMAND, PS_MODE_PLAIN };
        for (String[] mode : modes) {
            String spawnErr = psSpawn(mode);
            if (spawnErr == null) {
                String probe = psProbe();
                if (probe != null) {
                    r.out = probe;
                    return r;
                }
                why = psFail;
            } else {
                why = spawnErr;
            }
            // this mode is not going to work, so do not leave it holding the slot
            psKill();
            android.util.Log.i("BBterm", "pwsh mode " + java.util.Arrays.toString(mode)
                    + " did not come up: " + why);
        }
        r.err = "pwsh start failed: " + why
              + "\nis " + PWSH + " still installed?";
        r.code = -1;
        return r;
    }

    /**
     * The wrapper is a hand-made stub - a shell script that puts a glibc loader
     * in front of the real pwsh binary. Nothing in this app creates it, and
     * /data/local/tmp does not survive every reboot or factory reset, so its
     * absence is checked up front. Saying so plainly beats letting sh fail and
     * then reporting "run pwshstart first" to someone who just did.
     */
    private static String psPreflight() {
        File w = new File(PWSH);
        if (!w.exists()) {
            String have;
            File dir = w.getParentFile();
            String[] kids = (dir == null) ? null : dir.list();
            if (kids == null) {
                have = "\n  " + dir + " could not be listed";
            } else if (kids.length == 0) {
                have = "\n  " + dir + " is empty";
            } else {
                java.util.Arrays.sort(kids);
                StringBuilder b = new StringBuilder();
                for (int i = 0; i < kids.length && i < 12; i++) {
                    if (b.length() > 0) b.append(' ');
                    b.append(kids[i]);
                }
                have = "\n  what is in " + dir + ": " + b;
            }
            return "pwsh wrapper is missing: " + PWSH
                 + "\n  it is a hand-made stub, nothing creates it." + have;
        }
        if (!w.canRead()) return "pwsh wrapper is not readable: " + PWSH;
        return null;
    }

    /** start pwsh with these args. null if the process is up, else why it is not */
    private String psSpawn(String[] args) {
        try {
            File cwd = new File(PREFIX);
            if (!cwd.isDirectory()) cwd = new File("/");
            List<String> cmd = new ArrayList<String>();
            cmd.add("sh");
            cmd.add(PWSH);
            for (int i = 0; i < args.length; i++) cmd.add(args[i]);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(cwd);
            pb.redirectErrorStream(true);
            applyEnv(pb.environment());
            psProc = pb.start();
            psIn = new PrintWriter(psProc.getOutputStream(), true);
            psOutReader = new BufferedReader(
                    new InputStreamReader(psProc.getInputStream(), "UTF-8"));
            psQ.clear();
            final Process proc = psProc;
            psReader = new Thread("bbterm-ps-reader") {
                public void run() {
                    try {
                        String l;
                        while ((l = psOutReader.readLine()) != null) {
                            psQ.offer(l);
                            if (psQ.size() > 2000) psQ.poll();
                        }
                        // EOF: pwsh quit on its own, which otherwise just looks
                        // like every reply came back empty
                        android.util.Log.i("BBterm", "pwsh stdout closed, exit="
                                + proc.exitValue());
                    } catch (Throwable t) {
                        android.util.Log.i("BBterm", "pwsh reader ended", t);
                    }
                }
            };
            psReader.setDaemon(true);
            psReader.start();
            android.util.Log.i("BBterm", "pwsh started, service pid="
                    + android.os.Process.myPid());
            return null;
        } catch (Throwable t) {
            return "" + t;
        }
    }

    /**
     * Ask for the version and wait for the sentinel. Returns the BB| line, or
     * null with the reason in psFail.
     *
     * A process that dies during the probe is the case worth reading: sh's
     * "no such file" or the loader's own error is the entire answer, and it
     * used to be discarded here and reported as "run pwshstart first".
     */
    private String psProbe() {
        psFail = "";
        Result v = psLine(PS_VERSION_CMD);
        String s = (v.out == null) ? "" : v.out.trim();
        if (v.code == 0 && s.startsWith("BB|")) return s;

        if (psProc == null || !psProc.isAlive()) {
            // let the reader thread finish delivering the last lines
            long until = System.currentTimeMillis() + 800;
            while (psQ.isEmpty() && System.currentTimeMillis() < until) {
                try { Thread.sleep(50); } catch (Throwable ignored) { }
            }
            psFail = psWhy(v);
            return null;
        }
        psFail = (s.length() > 0 ? s : v.err) + " - no version line came back";
        return null;
    }

    /** whatever the dead process said on its way out, for the error message */
    private String psWhy(Result v) {
        StringBuilder b = new StringBuilder();
        if (v.err != null && v.err.trim().length() > 0) b.append(v.err.trim());
        if (b.length() == 0) {
            String l;
            int n = 0;
            while ((l = psQ.poll()) != null && n < 12) {
                b.append(l).append('\n');
                n++;
            }
        }
        if (b.length() == 0) b.append("the process exited with no output at all");
        return b.toString().trim();
    }

    private BufferedReader psOutReader;

    /**
     * Collects everything the command printed up to the sentinel. Bounded by
     * PS_TIMEOUT_MS so a command that never returns (Read-Host, an interactive
     * prompt) cannot wedge the service; the session is killed so the next
     * pwshstart is clean.
     */
    private synchronized Result psLine(String cmd) {
        Result r = new Result();
        if (psProc == null || !psProc.isAlive() || psIn == null) {
            r.err = "no powershell session - run pwshstart first";
            r.code = -1;
            return r;
        }
        // Unique per call: a shared sentinel could be terminated by a line left
        // in the queue by an earlier call, reporting no output.
        final String sentinel = PS_SENTINEL + (++psSeq);
        StringBuilder o = new StringBuilder();
        try {
            // drop anything stale, so this call only sees its own output
            psQ.clear();
            psIn.println(cmd);
            psIn.println("[Console]::Out.WriteLine('" + sentinel + "')");
            psIn.flush();
            long end = System.currentTimeMillis() + PS_TIMEOUT_MS;
            while (true) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) {
                    r.err = "timed out after " + (PS_TIMEOUT_MS / 1000)
                            + "s - the session was killed";
                    r.out = o.toString();
                    r.code = -1;
                    android.util.Log.i("BBterm", "ps: timeout on '" + cmd
                            + "' collected=" + o.length());
                    psKill();
                    return r;
                }
                // Wait in short slices and re-check liveness on every one. A pwsh
                // that dies during startup (stale wrapper, missing loader) used to
                // sit here for the whole timeout, and the message it printed was
                // swallowed - so pwshstart only ever came back with "run pwshstart
                // first" to someone who had just run it.
                String l = psQ.poll(Math.min(left, 200), TimeUnit.MILLISECONDS);
                if (l != null) {
                    if (l.trim().equals(sentinel)) break;
                    o.append(l).append('\n');
                    continue;
                }
                if (psProc == null || !psProc.isAlive()) {
                    // give the reader a moment to hand over the last lines
                    boolean finished = false;
                    long until = System.currentTimeMillis() + 500;
                    while (System.currentTimeMillis() < until) {
                        String d = psQ.poll();
                        if (d == null) {
                            try { Thread.sleep(50); } catch (Throwable ignored) { }
                            continue;
                        }
                        if (d.trim().equals(sentinel)) { finished = true; break; }
                        o.append(d).append('\n');
                    }
                    // the command finished and the shell died straight after:
                    // that is still a successful reply
                    if (finished) break;
                    android.util.Log.i("BBterm", "ps: session died during '" + cmd
                            + "': " + o.toString().trim());
                    r.err = (o.length() > 0 ? o.toString().trim()
                            : "the powershell session exited while running that");
                    r.code = -1;
                    psKill();
                    return r;
                }
            }
        } catch (Throwable t) {
            r.err = "powershell: " + t;
            r.code = -1;
            psKill();
            return r;
        }
        android.util.Log.i("BBterm", "ps: '" + cmd + "' -> " + o.length()
                + " chars, alive=" + psProc.isAlive());
        r.out = clip(o.toString());
        return r;
    }

    private synchronized Result psStop() {
        psKill();
        Result r = new Result();
        r.out = "stopped";
        return r;
    }

    private void psKill() {
        try { if (psIn != null) psIn.close(); } catch (Throwable ignored) { }
        try { if (psProc != null) psProc.destroy(); } catch (Throwable ignored) { }
        psIn = null;
        psProc = null;
        psQ.clear();
    }

    static class Result {
        String out = "";
        String err = "";
        int code = 0;
    }

    private static String clip(String s) {
        if (s == null) return "";
        if (s.length() > MAX_OUT) {
            s = s.substring(0, MAX_OUT) + "\n... [output truncated]\n";
        }
        return s;
    }

    private Result run(String cmdline) throws Exception {
        File cwd = new File(PREFIX);
        if (!cwd.isDirectory()) cwd = new File("/");
        Result r = new Result();

        ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmdline);
        pb.directory(cwd);
        pb.redirectErrorStream(false);
        applyEnv(pb.environment());
        Process p = pb.start();

        // both pipes must be drained at once or a chatty command deadlocks
        final BlockingQueue<String> oq = new ArrayBlockingQueue<String>(QUEUE);
        final BlockingQueue<String> eq = new ArrayBlockingQueue<String>(QUEUE);
        final AtomicInteger done = new AtomicInteger(0);
        pump(p.getInputStream(), oq, done);
        pump(p.getErrorStream(), eq, done);

        StringBuilder o = new StringBuilder();
        StringBuilder e = new StringBuilder();
        while (done.get() < 2) {
            String so = oq.poll();
            if (so != null) o.append(so);
            String se = eq.poll();
            if (se != null) e.append(se);
            if (so == null && se == null) {
                try { Thread.sleep(4); } catch (InterruptedException x) { break; }
            }
        }
        // final drain
        String so;
        while ((so = oq.poll()) != null) o.append(so);
        String se;
        while ((se = eq.poll()) != null) e.append(se);

        r.code = p.waitFor();
        r.out = clip(o.toString());
        r.err = clip(e.toString());
        return r;
    }

    private static void pump(final InputStream in, final BlockingQueue<String> q,
                             final AtomicInteger done) {
        Thread t = new Thread("bbterm-pump") {
            public void run() {
                StringBuilder sb = new StringBuilder();
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(in));
                    String l;
                    while ((l = r.readLine()) != null) {
                        sb.append(l).append('\n');
                        if (sb.length() >= 4096) {
                            q.offer(sb.toString());
                            sb.setLength(0);
                        }
                    }
                } catch (Throwable ignored) {
                }
                if (sb.length() > 0) q.offer(sb.toString());
                // no EOF sentinel: ArrayBlockingQueue rejects null elements
                // (NPE in offer) and the done counter already says when both
                // pipes are finished
                done.incrementAndGet();
            }
        };
        t.setDaemon(true);
        t.start();
    }

    /** used by the activity to check the environment before claiming anything */
    public static String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("uid=").append(android.os.Process.myUid())
          .append(" shell=").append(isShell()).append('\n');
        return sb.toString();
    }

    static boolean isShell() { return "0".equals(uidOf()); }

    static String uidOf() {
        try {
            Process p = new ProcessBuilder("id", "-u").start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String s = r.readLine();
            p.waitFor();
            return s == null ? "" : s.trim();
        } catch (Throwable t) {
            return "";
        }
    }
}
