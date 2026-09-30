# build.ps1 - raw SDK toolchain, no gradle.
# aapt2 -> javac -> jar -> d8 -> zipalign -> apksigner
#
# Configuration comes from the environment, because hardcoding an SDK path and a
# signing password made this script work on exactly one machine and leaked a
# credential into a public repository.
#
#   ANDROID_SDK_ROOT   or ANDROID_HOME   e.g. C:\Users\you\AppData\Local\Android\Sdk
#   SHIZUKU_LIB                            folder holding the Shizuku api jars
#   KEYSTORE                              path to your .keystore
#   KEYSTORE_PASS                         its password
#   KEY_ALIAS                             key alias
#   BUILD_TOOLS_VER   optional, default 36.0.0
#   PLATFORM          optional, default android-36
#
# Only KEYSTORE, KEYSTORE_PASS and KEY_ALIAS are really required, and only for
# signing. To try a build without a key, set NO_SIGN=1 and only aligned.apk is
# produced.

$ErrorActionPreference = "Stop"

function Need($name, $hint) {
    $v = [Environment]::GetEnvironmentVariable($name)
    if ([string]::IsNullOrWhiteSpace($v)) {
        throw "$name is not set. $hint"
    }
    return $v
}

$SDK = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT }
       elseif ($env:ANDROID_HOME)     { $env:ANDROID_HOME }
       else { throw "set ANDROID_SDK_ROOT (or ANDROID_HOME) to your Android SDK" }
if (-not (Test-Path $SDK)) { throw "ANDROID_SDK_ROOT does not exist: $SDK" }

$BT_VER = if ($env:BUILD_TOOLS_VER) { $env:BUILD_TOOLS_VER } else { "36.0.0" }
$PLAT   = if ($env:PLATFORM)        { $env:PLATFORM }        else { "android-36" }
$BT          = "$SDK\build-tools\$BT_VER"
$ANDROID_JAR = "$SDK\platforms\$PLAT\android.jar"

# the project directory is wherever this script lives
$ROOT = Split-Path -Parent $MyInvocation.MyCommand.Path

$SHZ = if ($env:SHIZUKU_LIB) { $env:SHIZUKU_LIB } else { "$ROOT\shizuku_lib" }
$SHZ_JARS = @(
    "$SHZ\api-13.1.5-classes.jar",
    "$SHZ\aidl-13.1.5-classes.jar",
    "$SHZ\shared-13.1.5-classes.jar",
    "$SHZ\provider-13.1.5-classes.jar",
    "$SHZ\annotation-1.3.0.jar"
) | Where-Object { Test-Path $_ }
if ($SHZ_JARS.Count -eq 0) {
    throw "no Shizuku jars found in $SHZ. set SHIZUKU_LIB to the folder holding api-*.jar etc."
}

$BUILD = "$ROOT\build"
$SRC   = "$ROOT\java"
$RES   = "$ROOT\res"

$javacExe = (Get-Command javac -ErrorAction Stop).Source
$javabin  = Split-Path $javacExe
$jarExe   = Join-Path $javabin "jar.exe"

function Step($m) { Write-Host "`n=== $m ===" -ForegroundColor Cyan }

Step "clean"
Get-ChildItem $BUILD -ErrorAction SilentlyContinue | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
# Start from a clean output tree. d8 does not clear stale files from its
# --output directory and this script did not either, so a leftover from an
# older build could survive and be mistaken for the current artifact. That is
# not hypothetical: a stale terminal.apk sat in build\dex while the real one
# was missing, and it made the build look like it had produced nothing at all.
# Do this before anything is generated, not just before d8 - cleaning after
# javac deleted the classes.jar that d8 needs, and the build failed.
foreach ($d in @("gen","classes","dex","flat")) {
    Remove-Item "$BUILD\$d" -Recurse -Force -ErrorAction SilentlyContinue
}
Remove-Item "$BUILD\terminal.apk","$BUILD\terminal.apk.idsig" -Force -ErrorAction SilentlyContinue
Remove-Item "$BUILD\aligned.apk","$BUILD\unsigned.apk","$BUILD\classes.jar" -Force -ErrorAction SilentlyContinue
foreach ($d in @("gen","classes","dex","flat")) {
    New-Item -ItemType Directory -Force -Path "$BUILD\$d" | Out-Null
}

Step "aapt2 compile"
& "$BT\aapt2.exe" compile --dir "$RES" -o "$BUILD\flat"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }
$flat = @(Get-ChildItem "$BUILD\flat" -Filter *.flat)
Write-Host "  $($flat.Count) flat file(s)"

Step "aapt2 link"
$linkArgs = @("link", "-o", "$BUILD\unsigned.apk",
              "-I", $ANDROID_JAR,
              "--manifest", "$ROOT\AndroidManifest.xml",
              "--java", "$BUILD\gen",
              "--min-sdk-version", "29",
              "--target-sdk-version", "34") + @($flat | ForEach-Object { $_.FullName })
& "$BT\aapt2.exe" @linkArgs
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Step "javac"
$javaFiles = @(Get-ChildItem "$BUILD\gen" -Filter *.java -Recurse -ErrorAction SilentlyContinue) + `
             @(Get-ChildItem "$SRC"   -Filter *.java -Recurse)
Write-Host "  $($javaFiles.Count) source file(s)"
& $javacExe -encoding UTF-8 -nowarn -source 17 -target 17 `
    -cp "$ANDROID_JAR;$($SHZ_JARS -join ';')" -d "$BUILD\classes" @($javaFiles.FullName)
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Step "jar classes"
# d8 wants individual .class files, and listing them all goes past cmd.exe's
# 8191-char limit via d8.bat once the class count grows. Jar them first so the
# command line stays short no matter how many classes the app has.
& $jarExe --create --file "$BUILD\classes.jar" -C "$BUILD\classes" .
if ($LASTEXITCODE -ne 0) { throw "jar create failed" }

  Step "d8"
  $classFiles = @(Get-ChildItem "$BUILD\classes" -Filter *.class -Recurse)
Write-Host "  $($classFiles.Count) class file(s) in classes.jar"
# shizuku classes must be DEXED IN, not --lib, or ShizukuProvider is missing at runtime
$shizukuInputs = @($SHZ_JARS | Where-Object { $_ -like "*-classes.jar" })
& "$BT\d8.bat" --output "$BUILD\dex" --lib $ANDROID_JAR --min-api 29 `
    "$BUILD\classes.jar" @($shizukuInputs)
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }
if (-not (Test-Path "$BUILD\dex\classes.dex")) { throw "no classes.dex" }

Step "add classes.dex"
& $jarExe --update --file "$BUILD\unsigned.apk" -C "$BUILD\dex" classes.dex
if ($LASTEXITCODE -ne 0) { throw "jar update failed" }

# Native libraries. adb ships as lib/arm64-v8a/libadb.so on purpose: it is the
# only executable thing an app can carry, because the package manager extracts
# lib/* with the exec bit already set into nativeLibraryDir, which is not
# app-writable so the Android 10 write-execute rule never applies. A binary
# dropped in the app's data dir cannot be exec'd at all.
$jni = "$ROOT\libs\arm64-v8a"
$natives = @(Get-ChildItem $jni -Filter *.so -ErrorAction SilentlyContinue)
if ($natives.Count -eq 0) {
    Write-Warning "  no .so in libs\arm64-v8a - adbsetup will report 'not in this build'"
} else {
    Step "add native libs"
    # jar, not System.IO.Compression. ZipArchive's Update mode rewrites every
    # entry and leaves an archive ART rejects with "Failed to extract
    # 'classes.dex': Inconsistent information", which kills the app before any
    # of its own code runs. jar keeps the entries it does not touch intact.
    $stage = "$BUILD\stage"
    if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $stage | Out-Null
    Push-Location $stage
    try {
        foreach ($n in $natives) {
            $rel = "lib/arm64-v8a/$($n.Name)"
            New-Item -ItemType Directory -Force -Path "lib\arm64-v8a" | Out-Null
            Copy-Item $n.FullName "lib\arm64-v8a\$($n.Name)" -Force
            & $jarExe uf "$BUILD\unsigned.apk" $rel
            if ($LASTEXITCODE -ne 0) { throw "jar could not add $rel" }
            Write-Host "    + $rel  $($n.Length) bytes"
        }
    } finally { Pop-Location }
}

Step "zipalign"
& "$BT\zipalign.exe" -f -p 4 "$BUILD\unsigned.apk" "$BUILD\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

if ($env:NO_SIGN -eq "1") {
    Step "apksigner SKIPPED"
    Write-Host "  NO_SIGN=1, so aligned.apk is unsigned. it will not install."
    Write-Host "  copy it yourself if you just want to look at it."
    $apk = Get-Item "$BUILD\aligned.apk"
    Write-Host "`nOK  $($apk.FullName)  $($apk.Length) bytes  (UNSIGNED)" -ForegroundColor Yellow
    return
}

# key details come from the environment, never from this file
$KEYSTORE = Need "KEYSTORE"       "point it at your .keystore"
$KSPASS   = Need "KEYSTORE_PASS" "the keystore password"
$KEYALIAS = Need "KEY_ALIAS"     "the key alias inside it"
if (-not (Test-Path $KEYSTORE)) { throw "KEYSTORE does not exist: $KEYSTORE" }

Step "apksigner"
& "$BT\apksigner.bat" sign `
    --ks $KEYSTORE --ks-pass "pass:$KSPASS" --key-pass "pass:$KSPASS" `
    --ks-key-alias $KEYALIAS `
    --out "$BUILD\terminal.apk" "$BUILD\aligned.apk" 2>&1 |
    Where-Object { $_ -notmatch "restricted method|loadLibrary|native-access" }
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

Step "verify"
& "$BT\apksigner.bat" verify --print-certs "$BUILD\terminal.apk" 2>&1 |
    Select-String "Signer #1 certificate DN" | ForEach-Object { "  " + $_.ToString().Trim() }

$apk = Get-Item "$BUILD\terminal.apk"
Write-Host "`nOK  $($apk.FullName)  $($apk.Length) bytes" -ForegroundColor Green
