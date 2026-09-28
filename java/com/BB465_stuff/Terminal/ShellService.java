package com.BB465_stuff.Terminal;

import android.os.Binder;
import android.os.Parcel;
import android.os.RemoteException;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shell service for the terminal.
 *
 * Shizuku starts this in its own process running as uid 2000 (shell), which is
 * what gives a real command environment with no root: logcat, dumpsys, pm, am,
 * settings, and the busybox already sitting in /data/local/tmp.
 *
 * What uid 2000 can NOT do, and this does not pretend otherwise:
 *  - ptrace / inject        EPERM under SELinux Enforcing, so no frida, no mic
 *  - su                     needs real root
 *  - adb pair / wireless    ro.adb.secure=1, needs an RSA key we do not hold
 *
 * Why this extends Binder rather than Service. Stock Shizuku instantiates a
 * Service subclass and calls onBind(null). ByteZuku (com.byteus.bytezuku) does
 * not: its starter is moe.shizuku.starter.ServiceStarter ->
 * rikka.shizuku.N10.a, which calls Class.newInstance() and then casts the
 * *instance itself* to IBinder:
 *
 *   InstantiationException: ... has no zero argument constructor   (needs ctor)
 *   ClassCastException: ShellService cannot be cast to IBinder      (needs Binder)
 *
 * Extending Binder satisfies the cast for free and asBinder() still returns
 * this, so the fork can hand it straight back to its server.
 *
 * Why raw Parcel instead of Messenger. Messenger.writeToParcel only carries
 * what/arg1/arg2/replyTo -- it silently drops Message.obj, so the command
 * string never arrived. We own both ends, so the wire format is a plain
 * synchronous transact: write token, id, command; read back out, err, code.
 */
public class ShellService extends Binder {

    public static final String DESCRIPTOR = "com.BB465_stuff.Terminal.IShell";

    public static final int MSG_EXEC     = 1;
    public static final int MSG_DESCRIBE = 2;

    /**
     * PowerShell session control. The session is a real long-lived pwsh process
     * on the far side, so $variables, imported modules and the working
     * directory all survive from one line to the next. Lines are delimited by
     * writing a sentinel line after each command and reading stdout until it
     * comes back, which is how a REPL can be driven over a one-shot RPC.
     */
    public static final int MSG_PS_START = 3;
    public static final int MSG_PS_LINE  = 4;
    public static final int MSG_PS_STOP  = 5;
    /** the app pushing a file to the shell, used by apkinstall */
    public static final int MSG_UPLOAD   = 6;
    /**
     * Handshake: the app asks which build the service is.
     *
     * A :bbterm process from an older install can outlive the app, because
     * 'am force-stop' does not kill it and the Shizuku server keeps its binder.
     * The app then talks to a service that has never heard of the newer
     * message codes, and the only symptom is a confusing mid-command failure
     * like "service rejected the upload" with no hint why. Asking on connect
     * turns that into one clear line at startup, while it is still cheap.
     */
    public static final int MSG_PING     = 7;
    /** bump whenever the message codes or their payloads change */
    public static final String BUILD = "2026-09-27.7";

    /** wrapper that puts the glibc loader in front of the real pwsh binary */
    public static final String PWSH = "/data/local/tmp/pwsh/pwsh.sh";
    /** where apkinstall stages an apk before handing it to pm */
    public static final String STAGE_FILE = "/data/local/tmp/bbapkinstall.apk";

    private static final String PS_SENTINEL = "__BB_PS_DONE_7f3a91c4__";
    private static final int PS_TIMEOUT_MS = 25000;
    /** bumped per call so a stale sentinel can never end the wrong reply */
    private static int psSeq = 0;

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

    /** already on the device, and a decade newer than anything we would ship */
    public static final String BUSYBOX = "/data/local/tmp/busybox";

    /**
     * Where 'pkg install' puts packages.
     *
     * Deliberately NOT PREFIX. PREFIX is on /storage/emulated/0, which is a
     * FUSE mount that refuses both symlink() and link() with EACCES - checked
     * on the device:
     *
     *   ln -s somefile <PREFIX>/linktest   -> Permission denied
     *   ln         <PREFIX>/somefile ...  -> Permission denied
     *   ln -s somefile /data/local/tmp/... -> ok
     *
     * Nearly every Termux package ships symlinks (bin/python is a link to
     * bin/python3.14), so untarring into PREFIX fails outright:
     *   tar: can't create symlink './data/.../share/doc/hello/copyright'
     * and the install dies partway through. /data/local/tmp is ext4, uid 2000
     * owned and survives a reboot.
     *
     * The trade-offs, stated rather than glossed: /data/local/tmp is outside
     * the app so the packages outlive an uninstall, it is wiped by a factory
     * reset, and it is not private to this app. It is not on the sdcard, so
     * it will not appear in a file manager.
     */
    public static final String PKGROOT = "/data/local/tmp/bbpkg";

    /**
     * Package prefix. Lives in the app's own external files dir rather than
     * /data/local/tmp so an uninstall cleans it up and a stray install cannot
     * scribble somewhere shared. uid 2000 can read it once the app has chmod'd
     * it, which was verified on device.
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
     * Write a file the app sent over to somewhere the shell can read.
     *
     * Only used by apkinstall. pm cannot read an apk straight off the sdcard
     * because the package manager streams it through a pipe and /storage is a
     * FUSE mount, so the bytes are staged on a real filesystem first.
     * Returns the byte count so the caller can pass a truthful -S to pm;
     * a short write is an error rather than a silently truncated install.
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
     * The client writes [interfaceToken][int id][string payload] for every
     * code. A Parcel is a sequential cursor, so a handler that skips the int
     * lands its readString() on the int and the command arrives as "0" -
     * PowerShell then prints nothing and every reply comes back empty.
     * Always drain all three fields.
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
     * Point TLS clients at the CA bundle the ca-certificates package installed.
     *
     * Without this, python reports
     *   CERTIFICATE_VERIFY_FAILED: unable to get local issuer certificate
     * for every https request, which reads like "the headset has no internet"
     * when in fact the network is fine and python is simply looking in the
     * wrong place. The bundle lands at PKGROOT/etc/tls/cert.pem, which no
     * default search path covers, so it has to be named explicitly.
     *
     * Set only when the file is actually there. Pointing a client at a missing
     * file would turn a working default into a failure, which is worse than
     * leaving it alone.
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
     * Start the persistent pwsh. -Command - makes it read statements from
     * stdin, which is what lets one process serve a whole session. No TTY means
     * no prompt of its own, so the app draws the PS C:\...> line itself.
     */
    private synchronized Result psStart() {
        Result r = new Result();
        if (psProc != null && psProc.isAlive()) {
            r.out = "already running";
            return r;
        }
        try {
            File cwd = new File(PREFIX);
            if (!cwd.isDirectory()) cwd = new File("/");
            ProcessBuilder pb = new ProcessBuilder(
                    "sh", PWSH, "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", "-");
            pb.directory(cwd);
            pb.redirectErrorStream(true);
            pb.environment().put("PATH", PKGROOT + "/bin:" + PREFIX + "/bin:"
                    + "/system/bin:/system/xbin:/vendor/bin");
            pb.environment().put("HOME", PREFIX);
            // Termux binaries are linked against /data/data/com.termux/files/usr/lib,
            // which cannot exist on a device without Termux installed, and their
            // RPATH is absolute so it cannot be patched. This is the only way they
            // find their shared libraries. See Pkg for the details.
            pb.environment().put("LD_LIBRARY_PATH",
                    PKGROOT + "/lib:" + PREFIX + "/lib:/system/lib64:/system/lib");
            pb.environment().put("TMPDIR", PKGROOT + "/tmp");
        exportCaPaths(pb.environment());
            psProc = pb.start();
            psIn = new PrintWriter(psProc.getOutputStream(), true);
            psOutReader = new BufferedReader(
                    new InputStreamReader(psProc.getInputStream()));
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
                        // EOF: pwsh quit on its own. Say why, it is otherwise
                        // invisible and just looks like every reply came back
                        // empty.
                        android.util.Log.i("BBterm", "pwsh stdout closed, exit="
                                + proc.exitValue());
                    } catch (Throwable t) {
                        android.util.Log.i("BBterm", "pwsh reader ended", t);
                    }
                }
            };
            psReader.setDaemon(true);
            psReader.start();
            // Process.pid() is not in Android's boot classpath, it throws
            // NoSuchMethodError at runtime. We only wanted a breadcrumb.
            android.util.Log.i("BBterm", "pwsh started, service pid="
                    + android.os.Process.myPid());
            r.out = "ok";
        } catch (Throwable t) {
            r.err = "pwsh start failed: " + t
                    + "\nis " + PWSH + " still installed?";
            r.code = -1;
        }
        return r;
    }

    private BufferedReader psOutReader;

    /**
     * Send one line to the live session and collect everything it printed up to
     * the sentinel. Bounded by PS_TIMEOUT_MS so a command that never returns
     * (Read-Host, an interactive prompt) cannot wedge the service forever; the
     * session is killed and reported dead so the next pwshstart is clean.
     */
    private synchronized Result psLine(String cmd) {
        Result r = new Result();
        if (psProc == null || !psProc.isAlive() || psIn == null) {
            r.err = "no powershell session - run pwshstart first";
            r.code = -1;
            return r;
        }
        // A unique sentinel per call. With one shared sentinel, a line left in
        // the queue by an earlier call (or by a reader thread that outlived its
        // process) would terminate this call instantly and report no output,
        // which is exactly the empty reply this used to give.
        final String sentinel = PS_SENTINEL + (++psSeq);
        StringBuilder o = new StringBuilder();
        try {
            // drop anything stale before we send, so this call only ever sees
            // output that its own command produced
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
                String l = psQ.poll(left, TimeUnit.MILLISECONDS);
                if (l == null) continue;
                if (l.trim().equals(sentinel)) break;
                o.append(l).append('\n');
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
        pb.environment().put("PATH", PKGROOT + "/bin:PREFIX/bin:"
                + "/system/bin:/system/xbin:/vendor/bin");
        pb.environment().put("BB", BUSYBOX);
        pb.environment().put("HOME", PREFIX);
        // Termux binaries are linked against /data/data/com.termux/files/usr/lib,
        // which cannot exist on a device without Termux installed, and their
        // RPATH is absolute so it cannot be patched. This is the only way they
        // find their shared libraries. See Pkg for the details.
        pb.environment().put("LD_LIBRARY_PATH",
                PKGROOT + "/lib:" + PREFIX + "/lib:/system/lib64:/system/lib");
        pb.environment().put("TMPDIR", PKGROOT + "/tmp");
        exportCaPaths(pb.environment());
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
