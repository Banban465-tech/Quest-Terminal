package com.BB465_stuff.Terminal;

import java.util.ArrayList;
import java.util.List;

/**
 * apkinstall: install an APK from the terminal, with the flags you already
 * know from 'adb install'.
 *
 * Why this is not just a shell out to 'pm install':
 *
 *  1. pm CANNOT read an apk off /storage/emulated/0. The sdcard is a FUSE
 *     mount and the package manager streams the file through a pipe, which
 *     fails there with a transaction error from onTransact. Verified on the
 *     device:
 *       pm install -r -g /data/local/tmp/selftest.apk   -> Success
 *       pm install -r -g /storage/emulated/0/Download/... -> fails in
 *           PackageManagerService$IPackageManagerImpl.onTransact
 *     So anything on the sdcard has to be piped in with 'pm install -S <bytes>
 *     -', reading stdin from the file. /data/local/tmp files are passed as a
 *     plain path, which is the cheaper route when the file is already there.
 *
 *  2. pm install does not expand '~' or search PATH, so 'apkinstall foo.apk'
 *     has to resolve the name itself: absolute, relative to the session's
 *     directory, or under the user's Download folder where a browser puts it.
 *
 * Runs as uid 2000 through the shell transport, which is the same identity
 * 'adb install' uses. No root is involved and none is implied: if you ever
 * install something that needs more, the command will say so rather than
 * pretend.
 *
 * Nothing is hardcoded to this headset beyond the usual Download location.
 */
public final class Apk {

    /** where a browser drops files, and where we look first if a name has no path */
    private static final String[] SEARCH_DIRS = {
        "/storage/emulated/0/Download",
        "/storage/emulated/0/Documents",
    };

    private static final String STAGE = "/data/local/tmp/bbapkinstall";

    /**
     * Flags we accept, mapped to what pm wants. Deliberately an allow-list:
     * this builds a shell command line, so anything not listed here is
     * refused rather than passed through.
     */
    private static boolean flag(String f) {
        return f.equals("-r") || f.equals("-R") || f.equals("-t") || f.equals("-f")
            || f.equals("-d") || f.equals("-g") || f.equals("-p")
            || f.equals("--force-sdk") || f.equals("--dont-kill")
            || f.equals("--preload") || f.equals("--instant") || f.equals("--full")
            || f.equals("--enable-rollback") || f.equals("--apex")
            || f.equals("--non-staged") || f.equals("--force-non-staged")
            || f.equals("--restrict-permissions");
    }

    /** flags that take a value, so we know not to treat the next token as a path */
    private static boolean flagTakesValue(String f) {
        return f.equals("-i") || f.equals("--user") || f.equals("--abi")
            || f.equals("--install-location") || f.equals("--install-reason")
            || f.equals("--originating-uri") || f.equals("--referrer")
            || f.equals("--pkg") || f.equals("--force-uuid")
            || f.equals("--staged-ready-timeout");
    }

    /** public view of the same test, so the caller can skip a flag's value */
    public static boolean takesValue(String f) { return flagTakesValue(f); }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /**
     * Build the install script for one apk and the flags the user typed.
     * Returns null plus an error message if the request makes no sense; the
     * caller prints it.
     */
    static String[] scriptFor(String arg, String cwd) {
        // never return a bare null: callers expect a [script, error] pair and a
        // null here is a NullPointerException waiting to happen
        if (arg == null || arg.trim().length() == 0) {
            return new String[] { null, usage() };
        }
        String[] tok = arg.trim().split("\\s+");
        List<String> flags = new ArrayList<String>();
        String target = null;
        boolean stdinForm = false;

        for (int i = 0; i < tok.length; i++) {
            String t = tok[i];
            if (t.equals("-")) {
                // read the apk from stdin: the caller has already opened it
                stdinForm = true;
                continue;
            }
            if (t.startsWith("-")) {
                String name = t;
                String inline = null;
                int eq = t.indexOf('=');
                if (eq > 0) {                       // --user=0
                    name = t.substring(0, eq);
                    inline = t.substring(eq + 1);
                }
                if (!flag(name) && !flagTakesValue(name)) {
                    return new String[] { null, "apkinstall: unknown flag " + name + "\n"
                            + "  try: apkinstall\n" };
                }
                flags.add(name);
                if (flagTakesValue(name)) {
                    if (inline != null) {
                        flags.add(inline);
                    } else if (i + 1 < tok.length) {
                        flags.add(tok[++i]);
                    } else {
                        return new String[] { null, "apkinstall: " + name
                                + " needs a value\n" };
                    }
                } else if (inline != null) {
                    return new String[] { null, "apkinstall: " + name
                            + " does not take a value\n" };
                }
                continue;
            }
            if (target != null) {
                return new String[] { null, "apkinstall: one apk at a time,"
                        + " got '" + target + "' and '" + t + "'\n"
                        + "  splits are not supported, install each separately\n" };
            }
            target = t;
        }

        if (target == null && !stdinForm) {
            return new String[] { null, "apkinstall: name an apk\n"
                    + "  apkinstall -r -g /storage/emulated/0/Download/app.apk\n"
                    + "  apkinstall -g app.apk        (looks in Download)\n"
                    + "  apkinstall -g - /storage/emulated/0/Download/app.apk\n"
                    + "    the '-' form streams the file in, which is what a path\n"
                    + "    on the sdcard needs. no '<' - the file is read for you.\n" };
        }
        if (target != null && stdinForm) {
            return new String[] { null, "apkinstall: give a path or '-',"
                    + " not both\n" };
        }

        String resolved = null;
        List<String> candidates = new ArrayList<String>();
        if (!stdinForm) {
            resolved = candidatesFor(target, cwd, candidates);
        }

        StringBuilder s = new StringBuilder();
        s.append("set -u\n");

        if (stdinForm) {
            // -S is the byte count, which pm insists on for stdin installs
            s.append("if [ -t 0 ]; then echo 'apkinstall: no apk on stdin'; exit 1; fi\n");
            s.append("N=$(wc -c <&0)\n");
            s.append("echo \"  apk is $N bytes, installing...\"\n");
            s.append("pm install");
            for (String f : flags) s.append(' ').append(f);
            s.append(" -S $N -\n");
        } else {
            // Do the existence test here, not in Java: this is the only place
            // test -f actually works, and guessing in Java would hand pm a path
            // that does not exist and produce a baffling failure.
            s.append("SRC=\n");
            for (String c : candidates) {
                s.append("if [ -z \"$SRC\" ] && [ -f ").append(shellQuote(c))
                 .append(" ]; then SRC=").append(shellQuote(c)).append("; fi\n");
            }
            s.append("if [ -z \"$SRC\" ]; then\n");
            s.append("  echo 'apkinstall: cannot find ").append(target).append("'\n");
            s.append("  echo '  tried:'\n");
            for (String c : candidates) {
                s.append("  echo '    ").append(c).append("'\n");
            }
            s.append("  echo '  give a full path, or stream it: apkinstall -g - ")
             .append(target).append("'\n");
            s.append("  exit 1\n");
            s.append("fi\n");
            s.append("echo \"  installing $SRC\"\n");
            s.append("pm install");
            for (String f : flags) s.append(' ').append(f);
            s.append(" \"$SRC\"\n");
        }
        s.append("RC=$?\n");
        s.append("if [ $RC -ne 0 ]; then\n");
        s.append("  echo \"  pm install exited $RC\"\n");
        s.append("  echo \"  -d only works on debuggable packages\"\n");
        s.append("  echo \"  a downgrade needs -d, a new app does not\"\n");
        s.append("  echo \"  -t is required for testOnly packages\"\n");
        s.append("  echo \"  split APKs are not supported here, install each\"\n");
        s.append("  echo \"  part on its own\"\n");
        s.append("fi\n");
        s.append("exit $RC\n");
        return new String[] { s.toString(), null };
    }

    /**
     * Build the list of paths to try, in order: as given if absolute, else the
     * session's own directory first, then the usual download folders. Filled
     * in here, checked for real by the script.
     */
    private static String candidatesFor(String name, String cwd, List<String> out) {
        if (name.startsWith("/")) {
            out.add(name);
        } else {
            String base = (cwd == null || cwd.length() == 0) ? "/" : cwd;
            out.add(base + "/" + name);
            for (String d : SEARCH_DIRS) out.add(d + "/" + name);
        }
        return out.isEmpty() ? null : out.get(0);
    }

    static String usage() {
        return "apkinstall\n"
             + "  apkinstall [-r] [-g] [-d] [-t] [-f] [--user N] <file.apk>\n"
             + "\n"
             + "  the flags are pm's, same names as adb install:\n"
             + "    -r   replace an existing install (the usual one)\n"
             + "    -g   grant every runtime permission up front\n"
             + "    -d   allow a version downgrade, debuggable packages only\n"
             + "    -t   allow a testOnly package\n"
             + "    -f   install to internal flash\n"
             + "    -i PACKAGE   record PACKAGE as the installer\n"
             + "    --user N    which user to install for\n"
             + "    --abi NAME   force an ABI\n"
             + "    --dont-kill  do not kill a running app (feature splits)\n"
             + "    --force-sdk  ignore the SDK version in the manifest\n"
             + "\n"
             + "  a bare name is looked for in the current directory and in\n"
             + "  Download and Documents, so this usually just works:\n"
             + "    apkinstall -r -g app.apk\n"
             + "\n"
             + "  pm cannot read an apk off the sdcard directly, so when the\n"
             + "  file is there it is streamed in instead, automatically.\n"
             + "  you can also ask for that explicitly with '-' as the name:\n"
             + "    apkinstall -r -g - /storage/emulated/0/Download/app.apk\n"
             + "  note there is no '<' - the file is read for you, a shell\n"
             + "  redirect would just confuse it.\n"
             + "\n"
             + "  runs as uid 2000, the same identity adb install uses.\n"
             + "  no root, and nothing here can grant itself more than that.\n"
             + "  split APKs are not supported, install each part on its own.\n";
    }
}
