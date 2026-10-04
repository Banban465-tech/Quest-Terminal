package com.BB465_stuff.Terminal;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import android.os.Handler;
import android.os.Looper;

/**
 * Root transport, through the app's own su. Same surface and same reply contract
 * as Shell, so a session can switch transports mid-life and nothing above
 * notices.
 */
final class SuShell implements Transport {

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final String PS_SENTINEL = "__BB_PS_DONE_7f3a91c4__";
    private static final int PS_TIMEOUT_MS = 25000;
    private static final int EXEC_TIMEOUT_MS = 600000;   // 10 min: root can legitimately take a while
    private static final int PROBE_TIMEOUT_MS = 60000;  // the su prompt may be sitting there unanswered
    /** same probe the Shizuku service runs, so the banner parses on any transport */
    private static final String PS_VERSION_CMD =
            "'BB|' + $PSVersionTable.PSVersion + '|' + $PSVersionTable.PSEdition"
          + " + '|' + $PSVersionTable.OS + '|' + $PSVersionTable.Platform";

    private final String su;
    private final Shell.Callback cb;
    private final AtomicInteger seq = new AtomicInteger(0);
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "bbterm-su");
                    t.setDaemon(true);
                    return t;
                }
            });

    // persistent powershell, as su -c "sh pwsh.sh ... -Command -"
    private Process psProc;
    private PrintWriter psIn;
    private final LinkedBlockingQueue<String> psQ = new LinkedBlockingQueue<String>();
    private int psSeq = 0;

    SuShell(String su, Shell.Callback cb) {
        this.su = su;
        this.cb = cb;
    }

    // ---------------------------------------------------------------- identity

    public int uid() { return 0; }

    public String describe() {
        String mgr = Su.managerFor(su);
        return "uid 0 root via su" + (mgr == null ? "" : " (" + mgr + ")")
                + " " + su;
    }

    public boolean isReady() { return su != null; }

    /** the su this transport runs through, so rootcheck and status can name it */
    String suPath() { return su; }

    // ---------------------------------------------------------------- commands

    public void exec(String cmd) { exec(cmd, ""); }

    public void exec(String cmd, String cwd) {
        final int id = seq.incrementAndGet();
        final String full = (cwd != null && cwd.length() > 0)
                ? ("cd " + Su.q(cwd) + " && " + cmd) : cmd;
        submit(new Runnable() {
            public void run() {
                Su.Result r = Su.run(su, full, null, EXEC_TIMEOUT_MS);
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
                Su.Result res = Su.run(su, cmd, null, EXEC_TIMEOUT_MS);
                final String s = merge(res);
                UI.post(new Runnable() {
                    public void run() { r.got(s); }
                });
            }
        });
    }

    /**
     * Root means the staging file can be written directly, but the file still
     * has to travel over a pipe: the app process is not root, so it cannot open
     * a root-owned path for writing. su opens it, the app writes into su.
     */
    public String uploadTo(File f, String remote) {
        final long[] sent = new long[1];
        final String[] err = new String[1];
        try {
            ProcessBuilder pb = new ProcessBuilder(su, "-c", "cat > " + Su.q(remote));
            ShellService.applyEnv(pb.environment());
            Process p = pb.start();
            OutputStream os = p.getOutputStream();
            InputStream in = new FileInputStream(f);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                sent[0] += n;
            }
            in.close();
            os.close();
            p.waitFor();
        } catch (Throwable e) {
            err[0] = "error: " + e;
        }
        if (err[0] != null) return err[0];
        return String.valueOf(sent[0]);
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
                Su.Result r = Su.run(su, "cat " + Su.q(remote) + " | " + script, null, EXEC_TIMEOUT_MS);
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
                // the UI needs the BB| version line; a bare "ok" leaves the
                // session reported as dead right after it started
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
     * The wrapper is a hand-made stub putting a glibc loader in front of the
     * real pwsh binary. Nothing here creates it, so ask the device before
     * blaming pwshstart - the old code threw the message away and told the
     * user to run a command they had just run.
     */
    private String psPreflightNow() {
        try {
            String out = execNow("ls -l " + ShellService.shq(ShellService.PWSH) + " 2>&1", 10000);
            if (out.contains("No such file")) {
                return "pwsh wrapper is missing: " + ShellService.PWSH
                     + "\n  it is a hand-made stub, nothing creates it."
                     + "\n  the device said: " + out.trim();
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /**
     * One synchronous su command, for the preflight that has to answer before
     * anything else happens. Java's own File API cannot be used here: this
     * transport runs in the app's uid, and /data/local/tmp belongs to shell, so
     * only root can say whether the wrapper is really there.
     *
     * Returns "" on any failure. The watchdog keeps an unanswered su prompt from
     * pinning the single worker thread forever.
     */
    private String execNow(String cmd, int timeoutMs) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(su, "-c", cmd);
            pb.redirectErrorStream(true);
            ShellService.applyEnv(pb.environment());
            final Process proc = pb.start();
            p = proc;
            Thread wd = new Thread("bbterm-su-preflight") {
                public void run() {
                    try { Thread.sleep(timeoutMs); } catch (Throwable ignored) { }
                    try { proc.destroy(); } catch (Throwable ignored) { }
                }
            };
            wd.setDaemon(true);
            wd.start();
            StringBuilder b = new StringBuilder();
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), "UTF-8"));
            String l;
            while ((l = r.readLine()) != null) b.append(l).append('\n');
            return b.toString();
        } catch (Throwable t) {
            android.util.Log.w("BBterm", "su preflight failed", t);
            return "";
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Throwable ignored) { }
            }
        }
    }

    /** start pwsh with these args. null if the process is up, else why it is not */
    private String psSpawnNow(String[] args) {
        try {
            StringBuilder cmd = new StringBuilder();
            // same reason as the adb transport: this directory is normally made
            // by the Shizuku service. Here it is only a `;` away from the exec
            // so a missing directory left the cwd at / instead of breaking the
            // launch, but the session still deserves to start where it claims.
            cmd.append(ShellService.envExports());
            cmd.append("; mkdir -p ").append(ShellService.shq(ShellService.PREFIX))
               .append(" 2>/dev/null");
            cmd.append("; cd ").append(Su.q(ShellService.PREFIX)).append(" || exit 3");
            cmd.append("; exec sh ").append(ShellService.shq(ShellService.PWSH));
            for (int i = 0; i < args.length; i++) cmd.append(' ').append(args[i]);
            ProcessBuilder pb = new ProcessBuilder(su, "-c", cmd.toString());
            pb.redirectErrorStream(true);
            ShellService.applyEnv(pb.environment());
            psProc = pb.start();
            psIn = new PrintWriter(psProc.getOutputStream(), true);
            final BufferedReader out = new BufferedReader(
                    new InputStreamReader(psProc.getInputStream(), "UTF-8"));
            psQ.clear();
            Thread reader = new Thread("bbterm-su-ps-reader") {
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
                    return o.toString()
                            + "\ntimed out after " + (PS_TIMEOUT_MS / 1000)
                            + "s - the session was killed";
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

    private void submit(Runnable r) {
        try {
            worker.execute(r);
        } catch (Throwable t) {
            // the single worker is shut down only when the activity is going away
            android.util.Log.i("BBterm", "su worker refused a job", t);
        }
    }

    /** stdout, then stderr: a command's error text is as interesting as its output */
    private static String merge(Su.Result r) {
        StringBuilder sb = new StringBuilder(r.out == null ? "" : r.out);
        if (r.err != null && r.err.length() > 0) {
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
            sb.append(r.err);
        }
        return sb.toString();
    }

    void shutdown() {
        psKill();
        worker.shutdownNow();
    }
}
