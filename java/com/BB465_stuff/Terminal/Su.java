package com.BB465_stuff.Terminal;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs su from the app's own process, which is the whole point: su over Shizuku
 * runs as uid 2000, so Magisk/KernelSU record the grant against "shell" and the
 * app still has no root. From the app's own uid it is recorded against the
 * package name - the entry the user actually sees - so su is never invoked
 * through the shell transport.
 */
final class Su {

    static final int NONE = -1;
    static final int GRANTED = 0;
    static final int REFUSED = 1;
    static final int TIMEOUT = 2;

    /** KernelSU keeps su in the ramdisk, Magisk magic-mounts it, APatch hides it in /data */
    private static final String[] KSU = { "/debug_ramdisk/su", "/data/adb/ksu/bin/su" };
    private static final String[] MAGISK = { "/product/bin/su", "/system/bin/su", "/system/xbin/su" };
    private static final String[] APATCH = { "/data/adb/apd/bin/su", "/system/bin/su", "/system/xbin/su" };
    private static final String[] GENERIC = {
            "/system/bin/su", "/product/bin/su", "/system/xbin/su", "/sbin/su",
            "/debug_ramdisk/su", "/data/adb/ksu/bin/su", "/data/adb/apd/bin/su",
            "/su/bin/su", "/vendor/bin/su", "/system/bin/ksud", "/system/bin/.ext/.su"
    };

    private Su() {}

    // ---------------------------------------------------------------- discovery

    /** first su that is actually on disk, no execution, no prompt */
    static String find() {
        for (String p : GENERIC) {
            if (new File(p).exists()) return p;
        }
        return null;
    }

    /** the candidates worth probing for a given manager, remembered path first */
    static String[] candidates(String manager, String remembered) {
        List<String> out = new ArrayList<String>();
        if (remembered != null) out.add(remembered);
        String[] pref;
        if ("kernelsu".equals(manager)) pref = KSU;
        else if ("apatch".equals(manager)) pref = APATCH;
        else if ("magisk".equals(manager)) pref = MAGISK;
        else pref = GENERIC;
        for (String p : pref) {
            if (!out.contains(p)) out.add(p);
        }
        return out.toArray(new String[out.size()]);
    }

    /** name the manager from where su lives; null when the layout is unfamiliar */
    static String managerFor(String path) {
        if (path == null) return null;
        if (path.equals("/debug_ramdisk/su") || path.startsWith("/data/adb/ksu")) return "kernelsu";
        if (path.startsWith("/data/adb/apd")) return "apatch";
        if (path.equals("/product/bin/su") || path.equals("/system/bin/su")
                || path.equals("/system/xbin/su")) return "magisk";
        return null;
    }

    // ---------------------------------------------------------------- running

    static final class Result {
        String out = "";
        String err = "";
        int code = -1;
        boolean timedOut;
    }

    /** single-quote for /system/bin/sh */
    static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /**
     * The timeout is the only way to tell a su waiting for the superuser prompt
     * (which prints nothing) from a system with no root manager. Streams are
     * pumped on their own threads so a chatty command cannot fill the pipe
     * buffer and deadlock against that timeout.
     */
    static Result run(String su, String cmdline, String cwd, int timeoutMs) {
        Result r = new Result();
        if (su == null) { r.err = "no su binary"; return r; }
        String full = (cwd != null && cwd.length() > 0) ? ("cd " + q(cwd) + " && " + cmdline) : cmdline;
        try {
            ProcessBuilder pb = new ProcessBuilder(su, "-c", full);
            pb.redirectErrorStream(false);
            ShellService.applyEnv(pb.environment());
            Process p = pb.start();
            StringBuilder o = new StringBuilder(), e = new StringBuilder();
            Thread to = pump(p.getInputStream(), o);
            Thread te = pump(p.getErrorStream(), e);
            boolean done;
            try {
                done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException x) {
                done = false;
            }
            if (!done) {
                r.timedOut = true;
                r.code = -9;
                r.err = "no answer in " + (timeoutMs / 1000) + "s";
                p.destroy();
                try { if (!p.waitFor(300, TimeUnit.MILLISECONDS)) p.destroyForcibly(); }
                catch (InterruptedException ignored) { }
            } else {
                r.code = p.exitValue();
            }
            to.join(400);
            te.join(400);
            r.out = clip(o.toString());
            r.err = clip(e.toString());
        } catch (Throwable t) {
            r.err = su + ": " + t;
            r.code = -1;
        }
        return r;
    }

    private static Thread pump(final InputStream in, final StringBuilder into) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"), 8192);
                    char[] buf = new char[4096];
                    int n;
                    while ((n = br.read(buf)) > 0) into.append(buf, 0, n);
                } catch (Throwable ignored) { }
            }
        }, "su-pump");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** terminal windows are the point of this app, so nothing is truncated on the way out */
    private static String clip(String s) {
        return s == null ? "" : s;
    }

    // ---------------------------------------------------------------- the probe

    static final class Probe {
        String path;          // the binary that answered
        int status = NONE;   // GRANTED / REFUSED / TIMEOUT / NONE
        String note = "";    // what it actually said
        boolean hadBinary;   // is there a su on disk at all
    }

    /** ask one binary. Short timeout: this is called from the UI. */
    static int granted(String path, int timeoutMs) {
        Result r = run(path, "id -u", null, timeoutMs);
        String a = answer(r);
        if (r.timedOut) return TIMEOUT;
        if ("0".equals(a)) return GRANTED;
        if (a.length() == 0) return TIMEOUT;          // silent: waiting on a prompt
        if (a.startsWith("uid=")) return GRANTED;    // some builds answer with the whole line
        return REFUSED;
    }

    /**
     * A refusal costs nothing so the walk continues, but a timeout means the
     * manager is holding a prompt open for the binary the user is looking at,
     * so stop there rather than stacking several prompts.
     */
    static Probe probe(String[] paths, int timeoutMs, int maxTries) {
        Probe p = new Probe();
        p.hadBinary = false;
        int tries = 0;
        for (String path : paths) {
            if (path == null) continue;
            if (!new File(path).exists()) continue;
            p.hadBinary = true;
            if (tries++ >= maxTries) { p.note = "stopped after " + maxTries + " su binaries"; break; }
            Result r = run(path, "id -u", null, timeoutMs);
            String a = answer(r);
            p.path = path;
            p.note = a.length() == 0 ? (r.timedOut ? r.err : "no answer") : a;
            if (r.timedOut) { p.status = TIMEOUT; return p; }
            if ("0".equals(a) || a.startsWith("uid=0")) { p.status = GRANTED; return p; }
            if (a.length() == 0) { p.status = TIMEOUT; return p; }
            p.status = REFUSED;
        }
        if (p.status == NONE && !p.hadBinary) p.note = "no su binary on this device";
        return p;
    }

    /**
     * The most informative single line out of a probe: prefer uid 0, then any
     * line from stdout, then whatever the manager complained about.
     */
    static String answer(Result r) {
        String out = firstUseful(r.out);
        if (out.equals("0") || out.startsWith("uid=0")) return "0";
        if (out.length() > 0) return out;
        return firstUseful(r.err);
    }

    private static String firstUseful(String s) {
        if (s == null) return "";
        for (String line : s.split("\n")) {
            String t = line.trim();
            if (t.length() == 0) continue;
            if (t.startsWith("BB:") || t.startsWith("BBTERM:")) t = t.substring(t.indexOf(':') + 1).trim();
            if (t.length() == 0) continue;
            return t;
        }
        return "";
    }
}
