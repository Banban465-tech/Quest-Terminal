package com.BB465_stuff.Terminal;

import java.util.regex.Pattern;

/**
 * Package installer for the Termux repo. Nothing is compiled here: Termux ships
 * prebuilt arm64 packages, so an install is a fetch plus an extract, and the
 * toybox applets already on the device (/system/bin) do every step.
 *
 * Constraints that shape the generated script, all verified on the device:
 *  - BB is left empty for compatibility with copies of the scripts that expect
 *    it, so "$BB cmd" runs plain "cmd" and resolves through PATH
 *  - a deb nests under data/data/com.termux/files/usr/, and that usr/ IS the
 *    prefix, so binaries land at $P/bin, not $P/root/...
 *  - LD_LIBRARY_PATH is not optional: Termux binaries carry an absolute RPATH
 *    into Termux's own prefix, without it they fail with
 *    CANNOT LINK EXECUTABLE "libiconv.so" not found
 *  - verify the index SHA256, and do not rely on `set -e` over a pipeline:
 *    toybox ash does not check the left-hand side
 *  - untar on ext4, never the sdcard: /storage is FUSE and refuses symlink(),
 *    and nearly every Termux package ships symlinks
 *  - decompress by piping xz/gzip into tar, not with tar -J/-z: which of those
 *    flags a given toybox build accepts varies, and the pipe always works
 *  - the device build has no zstd, which is fine: every package in the current
 *    index is a .deb carrying data.tar.xz (checked across the whole index), and
 *    the one code path that would want unzstd is served by the zstd package
 *  - 'ar' is not a toybox applet, so unpack falls back through tar -xf, then
 *    busybox if a copy of it is installed
 *  - the Debian xz-tools xz binary is a no-op on Android's toybox linker; use
 *    xzcat (an alias of xz -dc there), or busybox xz -dc when xzcat is missing
 *
 * Packages land in PKGROOT, outside the app: they outlive an uninstall, a
 * factory reset wipes them, they are not private, and a file manager will not
 * show them.
 *
 * toybox wget does not validate TLS and there is no other HTTPS client
 * on the device, so the index itself is fetched unverified - a hostile network
 * could serve a poisoned index. The SHA256 catches corruption, not that. This
 * headset additionally has no /etc/resolv.conf, so name resolution fails for
 * every client here and the index has to arrive over an IP or a pushed file.
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

    /**
     * Applet prefix the generated scripts expand. Meta Quest ships toybox in
     * /system/bin, so every applet resolves through PATH on its own and BB
     * stays empty: "$BB cmd" then runs plain "cmd".
     */
    public static final String BB = "";

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
     * An install takes minutes (python is 17 packages), and the service buffers
     * a whole command until the process exits - so run it through the normal
     * exec and the input locks with nothing on screen and no way to tell a slow
     * download from a hang. Instead the script is uploaded as a file, started
     * with nohup, and logs where the user can watch it.
     *
     * The caller uploads to RUNDIR/job.sh first; see TerminalActivity.doPkg.
     */
    public static String launchScript() {
        StringBuilder s = new StringBuilder();
        s.append("BB=").append(BB).append('\n');
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
        // The job writes its own exit code. 'kill -0 $pid' looks equivalent and
        // is not: once the job ends the pid can be recycled, and status then
        // reports "still running" forever.
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
     * Still running, finished, or died - the third was invisible before, since
     * a failed install just stopped printing.
     */
    public static String statusScript() {
        StringBuilder s = new StringBuilder();
        s.append("BB=").append(BB).append('\n');
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

    /** header prefix of a watchScript() reply: lines, done, exit code */
    public static final String WATCH = "BBJ|";

    /**
     * One poll of the background job: a header line, then the whole log.
     *
     * The transport only hands back a command once the process has exited, so
     * there is nothing to stream into - the app calls this on a timer and shows
     * the lines it has not seen yet.
     *
     * The header counts whole lines only, because 'wc -l' counts newlines. A
     * line the job is halfway through writing stays invisible until it is
     * finished, which keeps half a package name off the screen.
     */
    public static String watchScript() {
        StringBuilder s = new StringBuilder();
        s.append("BB=").append(BB).append('\n');
        s.append("D=").append(RUNDIR).append('\n');
        s.append("L=$D/job.log\n");
        // nohup has not created it yet, so this is "not finished", not "finished"
        s.append("if [ ! -f \"$L\" ]; then echo '").append(WATCH).append("0|0|no log yet'; exit 0; fi\n");
        s.append("N=$($BB wc -l < \"$L\" | $BB tr -d ' ')\n");
        s.append("RC=$($BB cat $D/job.exit 2>/dev/null)\n");
        s.append("if [ -z \"$RC\" ]; then D=0; RC=running; else D=1; fi\n");
        s.append("echo \"").append(WATCH).append("$N|$D|$RC\"\n");
        s.append("$BB cat \"$L\"\n");
        return s.toString();
    }

    /**
     * Where everything is, and why not somewhere prettier: the app's own data
     * folder cannot hold executables, and that is not a preference.
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
             + "its progress prints in this tab as it goes.\n"
             + "check on it later with:  pkg status\n"
             + "\nnote: this lives outside the app, so an uninstall will not\n"
             + "remove it. use 'pkg list' then 'pkg remove' to clean up.\n";
    }

    /**
     * Runs inside the generated script, where the index lives: the transport
     * clips replies at 60k characters and the whole index is 3MB.
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
        s.append("BB=").append(BB).append('\n');
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
        // toybox has no ar applet, so a .deb falls back through tar (GNU tar
        // reads ar archives) and then a busybox copy if one is installed.
        s.append("  if $BB ar x \"../$BASE\" 2>/dev/null; then :\n");
        s.append("  elif $BB tar -xf \"../$BASE\" 2>/dev/null; then :\n");
        s.append("  elif command -v busybox >/dev/null 2>&1 && busybox ar x \"../$BASE\" 2>/dev/null; then :\n");
        s.append("  else echo \"  ! cannot unpack $p: no ar on this device\"; cd $W; exit 1; fi\n");
        s.append("  DATA=$($BB ls data.tar.* 2>/dev/null | $BB head -1)\n");
        s.append("  case \"$DATA\" in\n");
        // Decompress through a pipe rather than tar -J/-z/-I: which of those
        // flags a toybox build accepts varies, while gzip is universal. The
        // Debian xz binary is a no-op under Android's linker, so .xz goes
        // through xzcat (toybox's xz -dc alias), or busybox when xzcat is
        // missing. Same bytes out.
        s.append("    *.xz) if command -v xzcat >/dev/null 2>&1; then xzcat \"$DATA\"\n");
        s.append("          elif command -v busybox >/dev/null 2>&1; then busybox xz -dc \"$DATA\"\n");
        s.append("          else echo \"  ! no xz decoder for $p\"; cd $W; exit 1\n");
        s.append("          fi \\\n");
        s.append("          | $BB tar -xf - || { echo \"  ! untar failed on $p\"; cd $W; exit 1; } ;;\n");
        s.append("    *.gz) $BB gzip -dc \"$DATA\" | $BB tar -xf - || { echo \"  ! untar failed on $p\"; cd $W; exit 1; } ;;\n");
        s.append("  *.zst) if [ -x $P/bin/unzstd ]; then\n");
        s.append("           $P/bin/unzstd -c \"$DATA\" | $BB tar -xf - || { echo \"  ! untar failed on $p\"; cd $W; exit 1; }\n");
        s.append("         else\n");
        s.append("           echo \"  ! $p uses a zstd archive and this device has no zstd\";\n");
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
        // list through the same pipe as the extract, so a device that cannot
        // decompress internally still yields the manifest 'pkg remove' needs
        s.append("  case \"$DATA\" in\n");
        s.append("    *.xz) if command -v xzcat >/dev/null 2>&1; then xzcat \"$DATA\"\n");
        s.append("          elif command -v busybox >/dev/null 2>&1; then busybox xz -dc \"$DATA\"\n");
        s.append("          fi 2>/dev/null | $BB tar -tf - ;;\n");
        s.append("    *.gz) $BB gzip -dc \"$DATA\" | $BB tar -tf - ;;\n");
        s.append("    *.zst) [ -x $P/bin/unzstd ] && $P/bin/unzstd -c \"$DATA\" | $BB tar -tf - ;;\n");
        s.append("  esac 2>/dev/null | $BB sed -e \"s#^$SRC/##\" -e 's#^\\./##' "
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
        s.append("BB=").append(BB).append('\n');
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
        s.append("BB=").append(BB).append('\n');
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
        s.append("BB=").append(BB).append('\n');
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
        s.append("BB=").append(BB).append('\n');
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
