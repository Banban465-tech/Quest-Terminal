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

    private synchronized String psStartNow() {
        if (psProc != null && psProc.isAlive()) return "already running";
        try {
            String cmd = ShellService.envExports() + "; cd " + Su.q(ShellService.PREFIX)
                    + "; exec sh " + ShellService.shq(ShellService.PWSH)
                    + " -NoLogo -NoProfile -NonInteractive -Command -";
            ProcessBuilder pb = new ProcessBuilder(su, "-c", cmd);
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
            return "ok";
        } catch (Throwable t) {
            return "pwsh start failed: " + t
                    + "\nis " + ShellService.PWSH + " still installed?";
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
                    return o.toString()
                            + "\ntimed out after " + (PS_TIMEOUT_MS / 1000)
                            + "s - the session was killed";
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
