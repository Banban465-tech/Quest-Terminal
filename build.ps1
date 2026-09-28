# build_terminal.ps1 - raw SDK toolchain, no gradle.
# aapt2 -> javac -> d8 -> jar -> zipalign -> apksigner

$ErrorActionPreference = "Stop"

$SDK         = "C:\Users\rcary\AppData\Local\Android\Sdk"
$BT          = "$SDK\build-tools\36.0.0"
$ANDROID_JAR = "$SDK\platforms\android-36\android.jar"
$ROOT        = "C:\Users\rcary\Downloads\ac_tools\terminal"
$KEYSTORE    = "C:\Users\rcary\Downloads\ac_tools\my-release-key.keystore"
$KSPASS      = "bb465ontop"
$KEYALIAS    = "bb565"

$SHZ = "C:\Users\rcary\Downloads\ac_tools\shizuku_lib"
$SHZ_JARS = @(
    "$SHZ\api-13.1.5-classes.jar",
    "$SHZ\aidl-13.1.5-classes.jar",
    "$SHZ\shared-13.1.5-classes.jar",
    "$SHZ\provider-13.1.5-classes.jar",
    "$SHZ\annotation-1.3.0.jar"
) | Where-Object { Test-Path $_ }
if ($SHZ_JARS.Count -eq 0) { throw "shizuku jars missing in $SHZ" }

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

Step "zipalign"
& "$BT\zipalign.exe" -f -p 4 "$BUILD\unsigned.apk" "$BUILD\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

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
