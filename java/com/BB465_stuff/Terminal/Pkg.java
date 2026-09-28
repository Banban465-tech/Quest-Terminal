package com.BB465_stuff.Terminal;

import java.util.regex.Pattern;

/**
 * Package installer for the Termux repo.
 *
 * Nothing is compiled here. Termux publishes prebuilt Android-native arm64
 * packages, so installing one is a fetch plus an extract, and the busybox
 * already on the device does every step (wget, ar, tar, sha256sum, awk).
 *
 * What the previous version got wrong, all of it found on the device:
 *
 *  1. it called $BB curl, and this busybox has no curl applet at all, so the
 *     very first fetch failed and every install died silently.
 *  2. the index URL was missing /dists/stable/main/binary-aarch64.
 *  3. it ignored Depends, so installing python gave you a python with no
 *     libpython, no openssl and no ncurses. It would extract and still not run.
 *  4. it installed from x/data/ into $P/root/, but a Termux deb nests everything
 *     under data/data/com.termux/files/usr/, and that usr/ IS the prefix. The
 *     binary landed at $P/root/data/com.termux/files/usr/bin/python instead of
 *     $P/bin/python. Nothing was ever where the old script said it was.
 *  5. it never checked the SHA256 from the index, and it used a pipeline under
 *     `set -e`, which busybox ash does not check on the left-hand side, so a
 *     truncated download reported success.
 *  6. Termux binaries carry an absolute RPATH into Termux's own prefix, which
 *     cannot exist here, so LD_LIBRARY_PATH is not optional. Verified on the
 *     device: without it,
 *       CANNOT LINK EXECUTABLE "hello": library "libiconv.so" not found
 *     with it,
 *       Hello, world!
 *  7. it installed into PREFIX, which is on /storage/emulated/0. That is a
 *     FUSE mount that refuses symlink() and link() outright:
 *       ln -s somefile <PREFIX>/linktest  -> Permission denied
 *       ln         <PREFIX>/a <PREFIX>/b -> Permission denied
 *     and almost every Termux package ships symlinks, so untarring failed at
 *       tar: can't create symlink './data/.../share/doc/hello/copyright'
 *     with a partially extracted package left behind. Packages now go to
 *     ShellService.PKGROOT on /data/local/tmp, which is ext4.
 *  8. the work directory was on the sdcard too, so even the untar step could
 *     not create the symlinks it needed. It now lives under PKGROOT.
 *
 * Packages land in PKGROOT, not inside the app. The trade-offs: they outlive an
 * uninstall, a factory reset wipes them, they are not private to this app, and
 * they will not show up in a file manager because they are not on the sdcard.
 *
 * One caveat worth stating plainly: this busybox's wget has no TLS certificate
 * validation, and there is no other HTTPS client on the device. The SHA256
 * check catches corruption and truncation, but the index itself is fetched
 * unverified, so a network attacker could in principle serve a poisoned index
 * and matching hashes. On a trusted network this is fine. Do not run pkg
 * installs on hostile wifi.
 */
public final class Pkg {

    public static final String PREFIX = ShellService.PKGROOT;

    /** repo base. the index lives under dists/stable/main/binary-aarch64/ */
    static final String REPO = "https://packages.termux.dev/apt/termux-main";
    static final String INDEX = REPO + "/dists/stable/main/binary-aarch64/Packages.gz";

    /** where we keep the install manifest for each package, for 'pkg remove' */
    static final String MANIFESTS = PREFIX + "/manifests";

    /** a debian package name. rejecting anything else is safer than escaping it. */
    private static final Pattern VALID =
            Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9+._-]*");

    /** returns an error message, or null if the name is acceptable */
    public static String badName(String pkg) {
        if (pkg == null || pkg.length() == 0) return "no package name given";
        if (pkg.length() > 80) return "package name is too long";
        if (!VALID.matcher(pkg).matches()) {
            return "not a valid package name: " + pkg
                    + "\nnames look like: python, libffi, openssl";
        }
        return null;
    }

    /**
     * Start an install in the background instead of blocking on it.
     *
     * The install itself is correct and takes minutes for something like
     * python - seventeen packages, tens of megabytes. Running it through the
     * normal exec looks broken, because the service buffers a whole command and
     * only hands back the text when the process exits: the input stays locked,
     * the screen shows nothing at all, and there is no way to tell a slow
     * download from a hang. So the script is uploaded as a file, started with
     * nohup, and its output goes to a log the user can watch.
     *
     * The caller has to put the install script at RUNDIR/job.sh first; see
     * TerminalActivity.doPkg, which uploads it through Shell.uploadTo.
     */
    public static String launchScript() {
        StringBuilder s = new StringBuilder();
        s.append("BB=").append(ShellService.BUSYBOX).append('\n');
        s.append("D=").append(RUNDIR).append('\n');
        s.append("$BB mkdir -p $D\n");
        s.append("J=$D/job.sh\n");
        s.append("L=$D/job.log\n");
        s.append("if [ ! -f \"$J\" ]; then\n");
        s.append("  echo 'nothing to run - pkg install did not upload a script'\n");
        s.append("  exit 1\n");
        s.append("fi\n");
        s.append(": > $L\n");
        s.append("$BB rm -f $D/job.exit\n");
        // The job records its own exit code when it finishes. Asking whether
        // the pid is still alive with 'kill -0' looks equivalent and is not:
        // once the job ends the pid can be recycled by an unrelated process,
        // and status then reports "still running" forever on a job that
        // finished minutes ago. A file the job writes is unambiguous.
        s.append("nohup $BB sh -c \"$BB sh $J; echo \\$? > $D/job.exit\" > $L 2>&1 &\n");
        s.append("PID=$!\n");
        s.append("echo $PID > $D/job.pid\n");
        s.append("echo 'install running in the background'\n");
        s.append("echo '  pid:  ' $PID\n");
        s.append("echo '  log:  pkg status   (or pkg log for the last lines)'\n");
        s.append("echo \"\"\n");
        s.append("echo 'it downloads, checksums and extracts one package at a'\n");
        s.append("echo 'time. python is 17 packages, so give it a few minutes.'\n");
        s.append("echo 'this tab is usable again meanwhile.'\n");
        return s.toString();
    }

    /**
     * Is the background install still going, and what has it printed?
     *
     * Distinguishes the three states that matter: still running, finished
     * successfully, and died. The last one is the one that was invisible
     * before, because a failed install just stopped printing.
     */
    public static String statusScript() {
        StringBuilder s = new StringBuilder();
        s.append("BB=").append(ShellService.BUSYBOX).append('\n');
        s.append("D=").append(RUNDIR).append('\n');
        s.append("L=$D/job.log\n");
        s.append("P=$D/job.pid\n");
        s.append("if [ ! -f \"$L\" ]; then echo 'no install has been run yet'; exit 0; fi\n");
        s.append("RUNNING=yes\n");
        s.append("if [ -f $D/job.exit ]; then RUNNING=no; fi\n");
        s.append("if [ $RUNNING = yes ]; then\n");
        s.append("  echo 'install is still running'\n");
        s.append("  echo '  it is downloading, so give it a few minutes'\n");
        s.append("else\n");
        s.append("  RC=$($BB cat $D/job.exit 2>/dev/null)\n");
        s.append("  echo \"install finished, exit code ${RC:-unknown}\"\n");
        s.append("  if [ \"$RC\" != \"0\" ]; then\n");
        s.append("    echo '  that is not zero, so something went wrong.'\n");
        s.append("    echo '  the last lines below say where.'\n");
        s.append("  fi\n");
        s.append("  if $BB grep -q '^  ! ' $L 2>/dev/null; then\n");
        s.append("    echo '  it also reported a problem explicitly'\n");
        s.append("  fi\n");
        s.append("fi\n");
        s.append("echo ''\n");
        s.append("echo '--- last lines ---'\n");
        s.append("$BB tail -12 $L\n");
        s.append("echo '--- end ---'\n");
        return s.toString();
    }

    /**
     * Where everything actually is, and why it is not somewhere prettier.
     *
     * Worth having as a command because the answer is genuinely surprising:
     * the obvious place, the app's own data folder, cannot hold executables,
     * and that is not a preference.
     */
    public static String whereScript() {
        return "where packages live\n"
             + "\n"
             + "  prefix   " + PREFIX + "\n"
             + "             bin/ lib/ share/ are already on your PATH\n"
             + "  records  " + MANIFESTS + "\n"
             + "             one file per package, used by 'pkg remove'\n"
             + "  job log  " + RUNDIR + "/job.log\n"
             + "\n"
             + "why not the app's own folder, or /sdcard?\n"
             + "  the shared volume is a FUSE mount, and it cannot do the two\n"
             + "  things a package needs. both were tried on this device:\n"
             + "\n"
             + "    chmod 755 file      the exec bit does not stick, the file\n"
             + "                        stays -rw-rw---- and is never runnable\n"
             + "    ln -s a b           Permission denied, and Termux packages\n"
             + "                        ship symlinks, e.g. bin/python is a link\n"
             + "                        to bin/python3.14\n"
             + "\n"
             + "  and separately, SELinux refuses outright for uid 2000:\n"
             + "\n"
             + "    avc: denied { execute } scontext=u:r:shell:s0\n"
             + "         tcontext=u:object_r:media_rw_data_file:s0\n"
             + "\n"
             + "  that is a security policy. changing it needs root, which\n"
             + "  there is none of. /data/local/tmp is ext4 and allows all\n"
             + "  three, so that is where they go.\n"
             + "\n"
             + "  trade-offs: a factory reset wipes it, an app uninstall does\n"
             + "  not, and it will not show up in a file manager because it is\n"
             + "  not on the sdcard. 'cdpkg' goes straight there.\n";
    }

    /** where the background job keeps its script, log and pid */
    public static final String RUNDIR = PREFIX + "/run";

    /** the file the caller must upload the install script to */
    public static final String JOBFILE = RUNDIR + "/job.sh";

    public static String usage() {
        return "pkg\n"
             + "  install <name>   fetch, check the hash, extract, pull deps\n"
             + "  status           is the background install going, and what\n"
             + "                   has it printed so far\n"
             + "  list             what is installed\n"
             + "  info <name>      show what it would install, downloads nothing\n"
             + "  remove <name>    delete the files it installed\n"
             + "  search <text>    find package names\n"
             + "  where            where everything is, and why not somewhere\n"
             + "                   prettier\n"
             + "\nprebuilt arm64 packages, nothing is compiled here.\n"
             + "repo: " + REPO + "\n"
             + "into: " + PREFIX + "\n"
             + "\nan install runs in the background, because python is 17\n"
             + "packages and takes minutes. this tab stays usable.\n"
             + "watch it with:  pkg status\n"
             + "\nnote: this lives outside the app, so an uninstall will not\n"
             + "remove it. use 'pkg list' then 'pkg remove' to clean up.\n";
    }

    /**
     * Pull the Filename / Depends / SHA256 / Version / Size fields for one
     * package out of the on-device index. Runs inside the generated script,
     * where the index lives, because the transport clips replies at 60k
     * characters and the whole index is 3MB.
     */
    private static final String FIELDS_FN =
            "fields() {\n"
          + "  $BB awk -v want=\"$1\" 'BEGIN{RS=\"\";FS=\"\\n\"} {\n"
          + "    ok=0\n"
          + "    for(i=1;i<=NF;i++) if($i == \"Package: \" want) ok=1\n"
          + "    if(!ok) next\n"
          + "    for(j=1;j<=NF;j++) if($j ~ /^(Filename|Depends|SHA256|Version|Size):/) print $j\n"
          + "    exit\n"
          + "  }' \"$W/Packages\"\n"
          + "}\n";

    /**
     * Depend names from a Depends field: strip version constraints, take the
     * first of any 'a | b' alternative, trim, drop empties.
     */
    private static final String DEPS_CMD =
            "echo \"$DEPS\" | tr ',' '\\n' "
          + "| $BB sed -e 's/([^)]*)//g' -e 's/|.*//' "
          + "-e 's/^[ \\t]*//' -e 's/[ \\t].*$//' "
          + "| $BB grep -v '^$'";

    public static String installScript(String pkg) {
        StringBuilder s = new StringBuilder();
        s.append("BB=").append(ShellService.BUSYBOX).append('\n');
        s.append("P=").append(PREFIX).append('\n');
        s.append("R=").append(REPO).append('\n');
        s.append("W=$P/work\n");
        s.append("NAME=").append(pkg).append('\n');
        s.append("set -u\n");
        s.append("cd $W 2>/dev/null || { $BB mkdir -p $W; cd $W; }\n");
        s.append(FIELDS_FN);

        s.append("\n$BB rm -rf $W/*\n");
        s.append("$BB mkdir -p $P $P/tmp $P/lib\n");
        s.append("echo '[1/4] fetching the package index'\n");
        s.append("$BB wget -q -O Packages.gz ").append(INDEX).append('\n');
        s.append("if [ ! -s Packages.gz ]; then\n");
        s.append("  echo '  ! index download failed'\n");
        s.append("  exit 1\n");
        s.append("fi\n");
        s.append("$BB gzip -dc Packages.gz > Packages || { echo '  ! index is corrupt'; exit 1; }\n");
        s.append("TOTAL=$($BB grep -c '^Package:' Packages)\n");
        s.append("echo \"      $TOTAL packages in the index\"\n");

        s.append("\necho '[2/4] resolving dependencies'\n");
        s.append("QUEUE=$W/want\n");
        s.append("$BB truncate -s 0 $QUEUE 2>/dev/null || : > $QUEUE\n");
        s.append("echo \"$NAME\" > $QUEUE\n");
        s.append("ORDER=\"\"\n");
        s.append("COUNT=0\n");
        s.append("while [ -s $QUEUE ]; do\n");
        s.append("  p=$($BB head -1 $QUEUE)\n");
        s.append("  $BB sed -i '1d' $QUEUE\n");
        s.append("  case \" $ORDER \" in *\" $p \"*) continue;; esac\n");
        s.append("  ST=$(fields \"$p\")\n");
        s.append("  if [ -z \"$ST\" ]; then echo \"  ! '$p' is not in this index, skipped\"; continue; fi\n");
        s.append("  FILE=$(echo \"$ST\" | $BB sed -n 's/^Filename: //p')\n");
        s.append("  SHA=$(echo \"$ST\" | $BB sed -n 's/^SHA256: //p')\n");
        s.append("  DEPS=$(echo \"$ST\" | $BB sed -n 's/^Depends: //p')\n");
        s.append("  VER=$(echo \"$ST\" | $BB sed -n 's/^Version: //p')\n");
        s.append("  ORDER=\"$ORDER $p\"\n");
        s.append("  COUNT=$((COUNT+1))\n");
        s.append("  echo \"      $COUNT. $p $VER\"\n");

        s.append("  BASE=${FILE##*/}\n");
        s.append("  if ! $BB wget -q -O \"$BASE\" \"$R/$FILE\"; then\n");
        s.append("    echo \"  ! download failed: $p\"; exit 1\n");
        s.append("  fi\n");
        s.append("  if [ -n \"$SHA\" ]; then\n");
        s.append("    GOT=$($BB sha256sum \"$BASE\" | $BB cut -d' ' -f1)\n");
        s.append("    if [ \"$GOT\" != \"$SHA\" ]; then\n");
        s.append("      echo \"  ! sha256 mismatch on $p, not installing\"; exit 1\n");
        s.append("    fi\n");
        s.append("  fi\n");

        s.append("  $BB rm -rf x && $BB mkdir x && cd x || exit 1\n");
        s.append("  if ! $BB ar x \"../$BASE\" 2>/dev/null; then echo \"  ! ar failed on $p\"; cd $W; exit 1; fi\n");
        s.append("  DATA=$($BB ls data.tar.* 2>/dev/null | $BB head -1)\n");
        s.append("  case \"$DATA\" in\n");
        s.append("    *.xz) $BB tar -xJf \"$DATA\" || { echo \"  ! untar failed on $p\"; cd $W; exit 1; } ;;\n");
        s.append("    *.gz) $BB tar -xzf \"$DATA\" || { echo \"  ! untar failed on $p\"; cd $W; exit 1; } ;;\n");
        s.append("  *.zst) if [ -x $P/bin/unzstd ]; then\n");
        s.append("           $BB tar -xf \"$DATA\" -I $P/bin/unzstd || { echo \"  ! untar failed on $p\"; cd $W; exit 1; }\n");
        s.append("         else\n");
        s.append("           echo \"  ! $p uses a zstd archive and this busybox has no zstd\";\n");
        s.append("           echo \"     run 'pkg install zstd' first, it ships bin/unzstd\";\n");
        s.append("           cd $W; exit 1\n");
        s.append("         fi ;;\n");
        s.append("    *) echo \"  ! no data archive inside $p\"; cd $W; exit 1 ;;\n");
        s.append("  esac\n");
        s.append("  # a Termux deb nests the real prefix at data/data/com.termux/files/usr\n");
        s.append("  SRC=\"\"\n");
        s.append("  for cand in ./data/data/com.termux/files/usr ./prefix ./data; do\n");
        s.append("    if [ -d \"$cand\" ]; then SRC=$cand; break; fi\n");
        s.append("  done\n");
        s.append("  [ -z \"$SRC\" ] && SRC=.\n");
        s.append("  # record what we installed so 'pkg remove' can undo it\n");
        s.append("  $BB mkdir -p ").append(MANIFESTS).append('\n');
        s.append("  $BB tar -tf \"$DATA\" 2>/dev/null | $BB sed -e \"s#^$SRC/##\" -e 's#^\\./##' "
               + "| $BB grep -v '^$' > ").append(MANIFESTS).append("/\"$p\".list\n");
        s.append("  # -f matters on a reinstall: a version bump can turn a plain file\n");
        s.append("  # into a symlink, and cp without -f refuses to clobber the symlink\n");
        s.append("  if ! $BB cp -af \"$SRC\"/. $P/; then echo \"  ! copy into prefix failed\"; cd $W; exit 1; fi\n");
        s.append("  cd $W\n");
        s.append("  for d in $(").append(DEPS_CMD).append("); do\n");
        s.append("    case \" $ORDER \" in *\" $d \"*) continue;; esac\n");
        s.append("    echo \"$d\" >> $QUEUE\n");
        s.append("  done\n");
        s.append("done\n");

        s.append("\necho \"[3/4] refreshing the dynamic linker cache\"\n");
        s.append("$BB ldconfig -n $P/lib 2>/dev/null || true\n");

        s.append("\necho '[4/4] done'\n");
        s.append("$BB rm -rf $W\n");
        s.append("echo \"installed $COUNT package(s), $NAME plus dependencies\"\n");
        s.append("if [ -e $P/bin/$NAME ]; then\n");
        s.append("  echo \"  binary:  $P/bin/$NAME\"\n");
        s.append("  echo \"  try it:  $NAME --version\"\n");
        s.append("else\n");
        s.append("  echo \"  $NAME is a library or data package, no bin/$NAME\"\n");
        s.append("fi\n");
        s.append("echo \"\"\n");
        s.append("$BB ls ").append(MANIFESTS).append("/*.list 2>/dev/null | $BB wc -l "
               + "| $BB sed 's/^/  packages tracked: /'\n");
        return finish(s);
    }

    public static String removeScript(String pkg) {
        StringBuilder s = new StringBuilder();
        s.append("BB=").append(ShellService.BUSYBOX).append('\n');
        s.append("P=").append(PREFIX).append('\n');
        s.append("L=").append(MANIFESTS).append("/").append(pkg).append(".list\n");
        s.append("if [ ! -f \"$L\" ]; then\n");
        s.append("  echo 'no install record for ").append(pkg).append("'\n");
        s.append("  echo \"tracked packages:\"\n");
        s.append("  $BB ls ").append(MANIFESTS).append("/*.list 2>/dev/null\n");
        s.append("  exit 1\n");
        s.append("fi\n");
        s.append("echo 'removing ").append(pkg).append("'\n");
        s.append("$BB wc -l < \"$L\" | $BB sed 's/^/  manifest entries: /'\n");
        s.append("while IFS= read -r rel; do\n");
        s.append("  [ -z \"$rel\" ] && continue\n");
        s.append("  case \"$rel\" in\n");
        s.append("    */) # a directory: only take it away if it is now empty, and\n");
        s.append("        # stay quiet when it is not, because shared dirs are normal\n");
        s.append("        rmdir \"$P/$rel\" 2>/dev/null ;;\n");
        s.append("    *) rm -f \"$P/$rel\" ;;\n");
        s.append("  esac\n");
        s.append("done < \"$L\"\n");
        s.append("$BB rm -f \"$L\" \"$L.tmp\" 2>/dev/null\n");
        s.append("echo \"removed ").append(pkg).append("\"\n");
        s.append("echo \"libraries other packages need were left alone on purpose.\"\n");
        return finish(s);
    }

    public static String listScript() {
        StringBuilder s = new StringBuilder();
        s.append("BB=").append(ShellService.BUSYBOX).append('\n');
        s.append("L=").append(MANIFESTS).append('\n');
        s.append("$BB mkdir -p $L\n");
        s.append("N=$($BB ls $L/*.list 2>/dev/null | $BB wc -l)\n");
        s.append("if [ \"$N\" = \"0\" ]; then echo 'nothing installed yet'; exit 0; fi\n");
        s.append("echo \"$N package(s) installed in ").append(PREFIX).append("\"\n");
        s.append("$BB ls $L/*.list 2>/dev/null | $BB sed \"s#^$L/##; s#\\.list\\$##\" "
               + "| $BB sort | $BB sed 's/^/  /'\n");
        return finish(s);
    }

    /** what installing this would pull in, without touching the network */
    public static String infoScript(String pkg) {
        StringBuilder s = new StringBuilder();
        s.append("BB=").append(ShellService.BUSYBOX).append('\n');
        s.append("P=").append(PREFIX).append('\n');
        s.append("W=$P/work\n");
        s.append("$BB mkdir -p $W && cd $W\n");
        s.append(FIELDS_FN);
        s.append("if [ ! -s Packages ]; then\n");
        s.append("  echo '[1/2] fetching the package index'\n");
        s.append("  $BB wget -q -O Packages.gz ").append(INDEX).append(" || "
               + "{ echo '  ! index download failed'; exit 1; }\n");
        s.append("  $BB gzip -dc Packages.gz > Packages\n");
        s.append("fi\n");
        s.append("echo '[2/2] reading the index'\n");
        s.append("QUEUE=$W/wantinfo\n");
        s.append(": > $QUEUE\n");
        s.append("echo \"").append(pkg).append("\" > $QUEUE\n");
        s.append("ORDER=\"\"\n");
        s.append("while [ -s $QUEUE ]; do\n");
        s.append("  p=$($BB head -1 $QUEUE)\n");
        s.append("  $BB sed -i '1d' $QUEUE\n");
        s.append("  case \" $ORDER \" in *\" $p \"*) continue;; esac\n");
        s.append("  ST=$(fields \"$p\")\n");
        s.append("  if [ -z \"$ST\" ]; then echo \"  ! '$p' not in index\"; continue; fi\n");
        s.append("  DEPS=$(echo \"$ST\" | $BB sed -n 's/^Depends: //p')\n");
        s.append("  VER=$(echo \"$ST\" | $BB sed -n 's/^Version: //p')\n");
        s.append("  SZ=$(echo \"$ST\" | $BB sed -n 's/^Size: //p')\n");
        s.append("  ORDER=\"$ORDER $p\"\n");
        s.append("  echo \"  $p $VER  ${SZ:-?} bytes\"\n");
        s.append("  for d in $(").append(DEPS_CMD).append("); do\n");
        s.append("    case \" $ORDER \" in *\" $d \"*) continue;; esac\n");
        s.append("    echo \"$d\" >> $QUEUE\n");
        s.append("  done\n");
        s.append("done\n");
        s.append("$BB rm -rf $W\n");
        return finish(s);
    }

    public static String searchScript(String q) {
        StringBuilder s = new StringBuilder();
        s.append("BB=").append(ShellService.BUSYBOX).append('\n');
        s.append("P=").append(PREFIX).append('\n');
        s.append("W=$P/work\n");
        s.append("$BB mkdir -p $W && cd $W\n");
        s.append("if [ ! -s Packages ]; then\n");
        s.append("  echo 'fetching the package index'\n");
        s.append("  $BB wget -q -O Packages.gz ").append(INDEX).append(" || "
               + "{ echo '  ! index download failed'; exit 1; }\n");
        s.append("  $BB gzip -dc Packages.gz > Packages\n");
        s.append("  $BB rm -f Packages.gz\n");
        s.append("fi\n");
        s.append("$BB grep '^Package:' Packages | $BB sed 's/^Package: //' "
               + "| $BB grep -i \"").append(q).append("\" "
               + "| $BB sort | $BB head -60 | $BB sed 's/^/  /'\n");
        return finish(s);
    }

    /** common tail: leave no scratch dir behind */
    private static String finish(StringBuilder s) {
        s.append("\n");
        return s.toString();
    }
}
