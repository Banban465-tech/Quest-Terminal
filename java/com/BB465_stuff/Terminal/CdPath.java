package com.BB465_stuff.Terminal;

/**
 * Path tidying for the cd builtin.
 *
 * Own class, no Android imports, so it can be unit tested off-device. The
 * terminal class cannot be loaded without a real framework because its static
 * Handler field trips over the android.jar stub, and this logic is exactly the
 * kind that quietly gets a boundary condition wrong.
 */
public final class CdPath {

    private CdPath() { }

    /**
     * The shared volume has several names and they are all the same place.
     * Verified on the device:
     *
     *   /sdcard               -> /storage/self/primary
     *   /storage/self/primary -> /storage/emulated/0
     *
     * The shell accepts any of them and pwd reports back whichever one you
     * typed. That matters because the prompt is derived from the session
     * directory, so leaving it as /sdcard/Download would draw a nonsense
     * C:\sdcard\Download\ instead of the D:\ it really is. So the alias is
     * rewritten to the real path before anything else looks at it.
     *
     * Longest first, so /storage/self/primary cannot be mistaken for the
     * start of some longer path.
     */
    private static final String[][] ALIASES = {
        { "/storage/self/primary", "/storage/emulated/0" },
        { "/mnt/sdcard",           "/storage/emulated/0" },
        { "/mnt/user",             "/storage/emulated/0" },
        { "/sdcard",               "/storage/emulated/0" },
    };

    public static final String SHARED = "/storage/emulated/0";

    /** rewrite a leading /sdcard-style alias, leaving everything else alone */
    public static String canonicalize(String p) {
        if (p == null || p.length() < 2) return p;
        for (String[] a : ALIASES) {
            if (p.equals(a[0])) return a[1];
            if (p.startsWith(a[0] + "/")) return a[1] + p.substring(a[0].length());
        }
        return p;
    }

    /** collapse //, resolve . and .., without touching the filesystem */
    public static String normalize(String p) {
        if (p == null) return "/";
        String[] parts = p.split("/");
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        for (String seg : parts) {
            if (seg.length() == 0 || seg.equals(".")) continue;
            if (seg.equals("..")) {
                if (!out.isEmpty()) out.remove(out.size() - 1);
                continue;
            }
            out.add(seg);
        }
        StringBuilder sb = new StringBuilder();
        for (String seg : out) sb.append('/').append(seg);
        return sb.length() == 0 ? "/" : sb.toString();
    }

    /**
     * What the cd builtin actually ends up using.
     *
     * Order matters, and both orders are wrong on their own:
     *
     *  - canonicalize first, then normalize: '//sdcard//Download//' misses the
     *    alias, because the leading slashes mean the path does not start with
     *    '/sdcard', and you are left somewhere the user did not ask for.
     *  - normalize first, then canonicalize: '..' gets resolved against the
     *    alias, so '/sdcard/Download/../..' climbs out of /sdcard and lands at
     *    '/' when the real answer is '/storage/emulated'.
     *
     * So: collapse only the leading slashes, rewrite the alias while it is
     * unambiguously at the front, then do the full tidy. That gets both right.
     */
    public static String resolve(String p) {
        return normalize(canonicalize(stripLeadingSlashes(p)));
    }

    /** collapse a run of leading slashes to exactly one, leaving the rest alone */
    private static String stripLeadingSlashes(String p) {
        if (p == null) return "/";
        int i = 0;
        while (i < p.length() && p.charAt(i) == '/') i++;
        return (i == 0) ? p : "/" + p.substring(i);
    }
}
