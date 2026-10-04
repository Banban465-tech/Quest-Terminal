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

    /** same two stdin forms the Shizuku service tries; see the note there */
    private static final String[] PS_MODE_COMMAND = {
        "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", "-" };
    private static final String[] PS_MODE_PLAIN = {
        "-NoLogo", "-NoProfile", "-NonInteractive" };

    private synchronized String psStartNow() {
        if (psProc != null && psProc.isAlive()) return "already running";

        String pre = psPreflightNow();
        if (pre != null) return pre;

        String why = "no output";
        String[][] modes = { PS_MODE_COMMAND, PS_MODE_PLAIN };
        for (String[] mode : modes) {
            String spawnErr = psSpawnNow(mode);
            if (spawnErr == null) {
                String v = psLineNow(PS_VERSION_CMD);
                String t = v == null ? "" : v.trim();
                if (t.startsWith("BB|")) return t;
                why = (psProc == null || !psProc.isAlive())
                        ? psWhyNow()
                        : t + " - no version line came back";
            } else {
                why = spawnErr;
            }
            // this mode is not going to work, so do not leave it holding the slot
            psKill();
        }
        return "pwsh start failed: " + why
             + "\nis " + ShellService.PWSH + " still installed?";
    }

    /**
     * The wrapper is a hand-made stub - a shell script putting a glibc loader in
     * front of the real pwsh binary. Nothing here creates it and
     * /data/local/tmp does not survive every reboot, so ask the device whether
     * it is there. The old code let sh fail, discarded the message and told the
     * user to run pwshstart, which they had just done.
     */
    private String psPreflightNow() {
        try {
            AdbBin.Result r = adb.shellResult(
                    prefix("") + "ls -l " + ShellService.shq(ShellService.PWSH)
                    + " 2>&1", 15000);
            String out = (r.out == null ? "" : r.out)
                       + (r.err == null ? "" : r.err);
            if (r.code != 0 || out.contains("No such file")) {
                return "pwsh wrapper is missing: " + ShellService.PWSH
                     + "\n  it is a hand-made stub, nothing creates it."
                     + "\n  the device said: " + out.trim();
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** start pwsh with these args. null if the process is up, else why it is not */
    private String psSpawnNow(String[] args) {
        try {
            StringBuilder cmd = new StringBuilder();
            cmd.append(prefix(ShellService.PREFIX)).append("exec sh ")
               .append(ShellService.shq(ShellService.PWSH));
            for (int i = 0; i < args.length; i++) cmd.append(' ').append(args[i]);
            ProcessBuilder pb = new ProcessBuilder(
                    AdbBin.binaryPath(ctx), "-s", adb.device(), "shell", cmd.toString());
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
            return null;
        } catch (Throwable t) {
            return "" + t;
        }
    }

    /** whatever the dead process said on its way out, which is the whole answer */
    private String psWhyNow() {
        long until = System.currentTimeMillis() + 800;
        while (psQ.isEmpty() && System.currentTimeMillis() < until) {
            try { Thread.sleep(50); } catch (Throwable ignored) { }
        }
        StringBuilder b = new StringBuilder();
        String l;
        int n = 0;
        while ((l = psQ.poll()) != null && n < 12) {
            b.append(l).append('\n');
            n++;
        }
        if (b.length() == 0) b.append("the process exited with no output at all");
        return b.toString().trim();
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
                String l = psQ.poll(Math.min(left, 200), TimeUnit.MILLISECONDS);
                if (l != null) {
                    if (l.trim().equals(sentinel)) break;
                    o.append(l).append('\n');
                    continue;
                }
                // see ShellService.psLine: wait in slices so a shell that dies
                // mid-command reports its own last words instead of a timeout
                if (psProc == null || !psProc.isAlive()) {
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
                    if (finished) break;
                    String said = o.toString().trim();
                    psKill();
                    return said.length() > 0 ? said
                            : "the powershell session exited while running that";
                }
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
