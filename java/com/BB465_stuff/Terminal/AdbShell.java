package com.BB465_stuff.Terminal;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Transport over the on-device adb client. `adb shell` is uid 2000, a peer of
 * Shell; it needs neither Shizuku nor a root manager, and `adb root` on a
 * userdebug build restarts adbd as uid 0 with no superuser prompt.
 *
 * adb shell does not inherit this process's environment, so commands are
 * prefixed with the same exports the other transports use.
 */
final class AdbShell implements Transport {

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final String PS_SENTINEL = "__BB_PS_DONE_7f3a91c4__";
    private static final int PS_TIMEOUT_MS = 25000;
    private static final int EXEC_TIMEOUT_MS = 600000;
    /** same probe the Shizuku service runs, so the banner parses on any transport */
    private static final String PS_VERSION_CMD =
            "'BB|' + $PSVersionTable.PSVersion + '|' + $PSVersionTable.PSEdition"
          + " + '|' + $PSVersionTable.OS + '|' + $PSVersionTable.Platform";

    private final AdbBin adb;
    private final Context ctx;
    private final Shell.Callback cb;
    private final AtomicInteger seq = new AtomicInteger(0);
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "bbterm-adb");
                    t.setDaemon(true);
                    return t;
                }
            });

    private volatile int uid = 2000;

    // persistent powershell over the adb shell pipe
    private Process psProc;
    private PrintWriter psIn;
    private final LinkedBlockingQueue<String> psQ = new LinkedBlockingQueue<String>();
    private int psSeq = 0;

    AdbShell(Context ctx, String device, Shell.Callback cb) {
        this.ctx = ctx.getApplicationContext();
        this.adb = new AdbBin(this.ctx, device);
        this.cb = cb;
    }

    AdbBin bin() {
        return adb;
    }

    // ---------------------------------------------------------------- identity

    public int uid() {
        return uid;
    }

    /** re-read the far side's uid, which `adb root` can change under us */
    public void refreshUid() {
        submit(new Runnable() {
            public void run() {
                String u = adb.uid();
                if ("0".equals(u)) uid = 0;
                else if ("2000".equals(u)) uid = 2000;
            }
        });
    }

    public String describe() {
        return "uid " + uid + (uid == 0 ? " root" : " shell") + " via adb ("
                + adb.device() + ")";
    }

    public boolean isReady() {
        return adb.isOnline();
    }

    // ---------------------------------------------------------------- commands

    public void exec(String cmd) {
        exec(cmd, "");
    }

    public void exec(String cmd, String cwd) {
        final int id = seq.incrementAndGet();
        final String full = prefix(cwd) + cmd;
        submit(new Runnable() {
            public void run() {
                AdbBin.Result r = adb.shellResult(full, EXEC_TIMEOUT_MS);
                final String out = r.out, err = r.err;
                final int code = r.code;
                UI.post(new Runnable() {
                    public void run() { cb.onReply(id, out, err, code); }
                });
            }
        });
    }

    public void execRaw(final String cmd, final Shell.Raw r) {
        submit(new Runnable() {
            public void run() {
                AdbBin.Result res = adb.shellResult(prefix("") + cmd, EXEC_TIMEOUT_MS);
                final String s = res.out.length() > 0 ? res.out : res.err;
                UI.post(new Runnable() {
                    public void run() { r.got(s); }
                });
            }
        });
    }

    /** Java's String has no case-insensitive contains; this is the one meant */
    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(java.util.Locale.US);
    }

    public String uploadTo(File f, String remote) {
        String out = adb.push(f.getAbsolutePath(), remote);
        if (lower(out).contains("error") || lower(out).contains("failed")) return "error: " + out;
        for (String line : out.split("\n")) {
            if (line.contains("byte") && line.contains("/")) {
                return line.trim().split("\\s+")[0];
            }
        }
        return String.valueOf(f.length());
    }

    public void execFromStream(final File f, final String script) throws Exception {
        final int id = seq.incrementAndGet();
        final String remote = ShellService.STAGE_FILE;
        submit(new Runnable() {
            public void run() {
                String put = uploadTo(f, remote);
                if (put.startsWith("error")) {
                    final String msg = "upload failed: " + put;
                    UI.post(new Runnable() {
                        public void run() { cb.onReply(id, msg, null, -1); }
                    });
                    return;
                }
                AdbBin.Result r = adb.shellResult(
                        prefix("") + "cat " + Su.q(remote) + " | " + script, EXEC_TIMEOUT_MS);
                final String out = r.out, err = r.err;
                final int code = r.code;
                UI.post(new Runnable() {
                    public void run() { cb.onReply(id, out, err, code); }
                });
            }
        });
    }

    // ---------------------------------------------------------------- pwsh

    public void psStart(final Shell.Raw r) {
        submit(new Runnable() {
            public void run() {
                final String s = psStartNow();
                UI.post(new Runnable() {
                    public void run() { r.got(s); }
                });
            }
        });
    }

    private synchronized String psStartNow() {
        if (psProc != null && psProc.isAlive()) return "already running";
        try {
            String cmd = prefix(ShellService.PREFIX) + "exec sh "
                    + ShellService.shq(ShellService.PWSH)
                    + " -NoLogo -NoProfile -NonInteractive -Command -";
            ProcessBuilder pb = new ProcessBuilder(
                    AdbBin.binaryPath(ctx), "-s", adb.device(), "shell", cmd);
            pb.redirectErrorStream(true);
            psProc = pb.start();
            psIn = new PrintWriter(psProc.getOutputStream(), true);
            final BufferedReader out = new BufferedReader(
                    new InputStreamReader(psProc.getInputStream(), "UTF-8"));
            psQ.clear();
            Thread reader = new Thread("bbterm-adb-ps-reader") {
                public void run() {
                    try {
                        String l;
                        while ((l = out.readLine()) != null) {
                            psQ.offer(l);
                            if (psQ.size() > 2000) psQ.poll();
                        }
                    } catch (Throwable ignored) { }
                }
            };
            reader.setDaemon(true);
            reader.start();
            // The process is up, but the UI only calls the session live when it
            // sees a BB| version line. Returning the old bare "ok" meant
            // pwshstart printed "ok" and left the session dead, so run the same
            // probe the Shizuku service does and pass it through.
            String v = psLineNow(PS_VERSION_CMD);
            if (v != null && v.trim().startsWith("BB|")) return v.trim();
            if (psProc == null || !psProc.isAlive()) {
                return "pwsh start failed: the version probe took the session down";
            }
            return "BB|unknown|Unknown||";
        } catch (Throwable t) {
            return "pwsh start failed: " + t;
        }
    }

    public void psLine(final String cmd, final Shell.Raw r) {
        submit(new Runnable() {
            public void run() {
                final String s = psLineNow(cmd);
                UI.post(new Runnable() {
                    public void run() { r.got(s); }
                });
            }
        });
    }

    private synchronized String psLineNow(String cmd) {
        if (psProc == null || !psProc.isAlive() || psIn == null) {
            return "no powershell session - run pwshstart first";
        }
        final String sentinel = PS_SENTINEL + (++psSeq);
        StringBuilder o = new StringBuilder();
        try {
            psQ.clear();
            psIn.println(cmd);
            psIn.println("[Console]::Out.WriteLine('" + sentinel + "')");
            psIn.flush();
            long end = System.currentTimeMillis() + PS_TIMEOUT_MS;
            while (true) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) {
                    psKill();
                    return o.toString() + "\ntimed out after "
                            + (PS_TIMEOUT_MS / 1000) + "s - the session was killed";
                }
                String l = psQ.poll(left, TimeUnit.MILLISECONDS);
                if (l == null) continue;
                if (l.trim().equals(sentinel)) break;
                o.append(l).append('\n');
            }
        } catch (Throwable t) {
            psKill();
            return o.toString() + "\npowershell: " + t;
        }
        return o.toString();
    }

    public void psStop(final Shell.Raw r) {
        submit(new Runnable() {
            public void run() {
                psKill();
                UI.post(new Runnable() {
                    public void run() { r.got("stopped"); }
                });
            }
        });
    }

    private synchronized void psKill() {
        try { if (psIn != null) psIn.close(); } catch (Throwable ignored) { }
        try { if (psProc != null) psProc.destroy(); } catch (Throwable ignored) { }
        psIn = null;
        psProc = null;
        psQ.clear();
    }

    // ---------------------------------------------------------------- plumbing

    /** the app's environment, then a cd, in one string the far shell can run */
    private static String prefix(String cwd) {
        StringBuilder sb = new StringBuilder(ShellService.envExports());
        sb.append("; ");
        if (cwd != null && cwd.length() > 0) sb.append("cd ").append(Su.q(cwd)).append(" && ");
        return sb.toString();
    }

    private void submit(Runnable r) {
        try {
            worker.execute(r);
        } catch (Throwable t) {
            Log.i("BBterm", "adb worker refused a job", t);
        }
    }

    void shutdown() {
        psKill();
        worker.shutdownNow();
    }
}
