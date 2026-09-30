
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$sdk  = if ($env:ANDROID_HOME) { $env:ANDROID_HOME }
        elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT }
        else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }

$bt     = Join-Path $sdk 'build-tools\36.0.0'
$android= Join-Path $sdk 'platforms\android-36\android.jar'
$build  = Join-Path $root 'build'
$flat   = Join-Path $build 'flat'
$gen    = Join-Path $build 'gen'
$classes= Join-Path $build 'classes'
$dexdir = Join-Path $build 'dex'
$jni    = Join-Path $build 'jniLibs\arm64-v8a'

$keytool = Join-Path (Split-Path (Split-Path (Get-Command java).Source)) 'bin\keytool.exe'
$javac   = Join-Path (Split-Path (Split-Path (Get-Command java).Source)) 'bin\javac.exe'
$jar     = Join-Path (Split-Path (Split-Path (Get-Command java).Source)) 'bin\jar.exe'

function Step($m) { Write-Host "==> $m" -ForegroundColor Cyan }

#  clean
Step 'clean'
foreach ($d in @($flat, $gen, $classes, $dexdir)) {
    if (Test-Path $d) { Remove-Item $d -Recurse -Force }
    New-Item -ItemType Directory -Path $d -Force | Out-Null
}

#unpack the Shizuku AARs
Step 'unpack Shizuku artifacts'
$aarCp = @()
foreach ($aar in (Get-ChildItem (Join-Path $root 'libs') -Filter *.aar)) {
    $d = Join-Path $build $aar.BaseName
    if (Test-Path $d) { Remove-Item $d -Recurse -Force }
    New-Item -ItemType Directory -Path $d -Force | Out-Null
    Push-Location $d
    & $jar xf $aar.FullName
    Pop-Location
    $cj = Join-Path $d 'classes.jar'
    if (Test-Path $cj) { $aarCp += $cj }
    Write-Host "    $($aar.Name) -> $cj"
}

# aapt2
Step 'aapt2 compile (res)'
$resFiles = Get-ChildItem (Join-Path $root 'res') -Recurse -Filter * |
            Where-Object { $_.Extension -in '.xml', '.png', '.ttf', '.arsc' }
foreach ($f in $resFiles) {
    & (Join-Path $bt 'aapt2.exe') compile -o $flat $f.FullName
    if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed on $($f.Name)" }
}

Step 'aapt2 link'
$flats = Get-ChildItem $flat -Filter *.flat | ForEach-Object { $_.FullName }
$resApk = Join-Path $build 'res.apk'
& (Join-Path $bt 'aapt2.exe') link `
    -o $resApk `
    -I $android `
    --manifest (Join-Path $root 'AndroidManifest.xml') `
    --java $gen `
    --min-sdk-version 29 `
    --target-sdk-version 34 `
    --version-code 2 `
    --version-name 2.0 `
    $flats
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

# aidl
Step 'aidl'
$aidlDir = Join-Path $root 'libs\aidl'
if (Test-Path $aidlDir) {
    foreach ($a in (Get-ChildItem $aidlDir -Recurse -Filter *.aidl)) {
        & (Join-Path $bt 'aidl.exe') `
            --lang=java `
            -I $aidlDir `
            -p (Join-Path (Split-Path $android) 'framework.aidl') `
            -o $gen `
            $a.FullName
        if ($LASTEXITCODE -ne 0) { throw "aidl failed on $($a.Name)" }
    }
    Write-Host "    $((Get-ChildItem $aidlDir -Recurse -Filter *.aidl).Count) aidl files"
}

# javac
Step 'javac'
$sources = @()
$sources += (Get-ChildItem (Join-Path $root 'java') -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$sources += (Get-ChildItem $gen -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$cp = (@($aarCp) + @($android)) -join ';'
& $javac -nowarn -encoding UTF-8 -source 8 -target 8 `
    -bootclasspath $android -cp $cp -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }
Write-Host "    $((Get-ChildItem $classes -Recurse -Filter *.class).Count) class files"

# jar + dex
Step 'jar'
$classesJar = Join-Path $build 'classes.jar'
Push-Location $classes
& $jar cf $classesJar .
Pop-Location

Step 'd8'
$dexInputs = @($classesJar) + @($aarCp)
& (Join-Path $bt 'd8.bat') --min-api 29 --lib $android --output $dexdir $dexInputs
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }
Get-ChildItem $dexdir -Filter *.dex | ForEach-Object { Write-Host "    $($_.Name)  $($_.Length) bytes" }

# aapt add stores whatever path you hand it, so passing an absolute path puts

Add-Type -AssemblyName System.IO.Compression.FileSystem
Step 'zip dex + native libs'
$unsigned = Join-Path $build 'unsigned.apk'
Copy-Item $resApk $unsigned -Force

# libadb.so comes from the NDK build. Nothing else ships as a native library:
# the old libbusybox.so is no longer packed, since the device's toybox covers it.
$prebuilts = Join-Path $root 'libs\prebuilts'
$native = Get-ChildItem $jni -Filter *.so -ErrorAction SilentlyContinue
if (-not $native) { Write-Warning '    no .so in build\jniLibs\arm64-v8a - adb will report "not in this build"' }
foreach ($n in $native) { Write-Host "    native: $($n.Name)  $($n.Length) bytes" }

$zip = [System.IO.Compression.ZipFile]::Open($unsigned, 'Update')
try {
    foreach ($dex in (Get-ChildItem $dexdir -Filter *.dex)) {
        $entry = $zip.CreateEntry('classes.dex', 'Optimal')
        $es = $entry.Open()
        $fs = [System.IO.File]::OpenRead($dex.FullName)
        $fs.CopyTo($es); $fs.Dispose(); $es.Dispose()
        Write-Host "    + classes.dex  $($dex.Length) bytes"
    }
    foreach ($n in $native) {
        $apkName = "lib/arm64-v8a/$($n.Name)"
        $entry = $zip.CreateEntry($apkName, 'Optimal')
        $es = $entry.Open()
        $fs = [System.IO.File]::OpenRead($n.FullName)
        $fs.CopyTo($es); $fs.Dispose(); $es.Dispose()
        Write-Host "    + $apkName"
    }
} finally { $zip.Dispose() }

# align + sign
Step 'zipalign'
$aligned = Join-Path $build 'aligned.apk'
& (Join-Path $bt 'zipalign.exe') -f 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

Step 'sign'
# Signing details come from the environment. Two reasons, not one:
#   - a password written here is a password in a public repository
#   - a script that *mints* its own key silently produces a different signer on
#     every machine, so nobody can update anybody else's build
# The key is an input. Set these in build-local.ps1 (gitignored) or your shell.
$keystore = if ($env:KEYSTORE)      { $env:KEYSTORE }      else { throw 'set KEYSTORE' }
$storepass = if ($env:KEYSTORE_PASS) { $env:KEYSTORE_PASS } else { throw 'set KEYSTORE_PASS' }
$keyalias  = if ($env:KEY_ALIAS)     { $env:KEY_ALIAS }     else { throw 'set KEY_ALIAS' }
if (-not (Test-Path $keystore)) { throw "KEYSTORE does not exist: $keystore" }
$apk = Join-Path $build 'terminal.apk'
& (Join-Path $bt 'apksigner.bat') sign `
    --ks $keystore --ks-pass "pass:$storepass" --key-pass "pass:$storepass" `
    --ks-key-alias $keyalias --min-sdk-version 29 `
    --out $apk $aligned
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }

Step 'verify'
& (Join-Path $bt 'apksigner.bat') verify --print-certs $apk
Write-Host ''
Write-Host "built: $apk  ($([math]::Round((Get-Item $apk).Length / 1MB, 2)) MB)" -ForegroundColor Green
