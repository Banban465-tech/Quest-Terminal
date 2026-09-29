package com.BB465_stuff.Terminal;

// No Android imports, so this stays unit-testable off-device.
public final class CdPath {

    private CdPath() { }

    // All the same place, but pwd reports back whichever name was typed, so the
    // alias is rewritten before the prompt reads it. Longest first.
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
     * Both orders fail: canonicalize-first misses the alias behind leading
     * slashes, normalize-first resolves '..' against the alias. So collapse the
     * leading slashes, rewrite the alias, then tidy.
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
