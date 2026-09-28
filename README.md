# Quest Terminal

A terminal for Meta Quest (Q1–Q3) and other Android devices, built around
Shizuku/ByteZuku so it gets a real **uid 2000 shell** without root.

There is no Gradle. `build.ps1` drives the raw SDK toolchain directly:
`aapt2 → javac → jar → d8 → zipalign → apksigner`.

## What it does

**A real shell, not a fake prompt.** Commands go over a Binder UserService that
Shizuku runs as uid 2000, so `dumpsys`, `pm`, `settings` and friends behave the
way they do from `adb shell`. The transport is raw synchronous `Parcel` rather
than `Messenger`, because `Messenger` silently drops `Message.obj`.

**PowerShell 7.6.6.** `pwshstart` gives a live session with a real `PS` prompt
and your own `>>` continuation for multi-line blocks; `pwshstop` ends it.
`pwsh <expr>` runs a one-shot.

**Package manager.** `pkg install python` resolves dependencies from the Termux
repo, verifies each package's SHA256, and extracts it — in the background, with
`pkg status` showing progress and the real exit code.

**APK installer.** `apkinstall` with the `pm` flags you already know from
`adb install` (`-r`, `-g`, `-d`, `-t`, `--user`, …).

**Things a Quest does differently**, which is most of the reason this exists:
`screencap` returns a black frame, and implicit `android.settings.*` intents
never arrive because vrshell's `AndroidIntentsRelayActivity` swallows them, so
every settings target here is named by component.

Type `help` in the app for the full list.

## Requirements

- Shizuku or ByteZuku, running, with this app granted access
- Android 10+ (API 29+)

Root is optional. Everything works without it; `askforsu` requests it if you
want ptrace and `/proc/<pid>/mem`.

## Building

You need a JDK, the Android SDK, and the Shizuku API jars. Configuration is
entirely through environment variables, so nothing machine-specific is in the
script and no credential is committed:

| variable | meaning |
|---|---|
| `ANDROID_SDK_ROOT` | your SDK (or `ANDROID_HOME`) |
| `SHIZUKU_LIB` | folder holding `api-*.jar`, `aidl-*.jar`, `shared-*.jar`, `provider-*.jar`, `annotation-*.jar` |
| `KEYSTORE` | path to **your** `.keystore` |
| `KEYSTORE_PASS` | its password |
| `KEY_ALIAS` | key alias inside it |
| `BUILD_TOOLS_VER` | optional, default `36.0.0` |
| `PLATFORM` | optional, default `android-36` |
| `NO_SIGN` | set to `1` to skip signing and just produce `aligned.apk` |

```powershell
$env:ANDROID_SDK_ROOT = "$env:LOCALAPPDATA\Android\Sdk"
$env:SHIZUKU_LIB     = "C:\path\to\shizuku_lib"
$env:KEYSTORE        = "C:\path\to\my.keystore"
$env:KEYSTORE_PASS   = "..."
$env:KEY_ALIAS       = "..."
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

The signed APK lands at `build\terminal.apk`.

### Signing

Bring your own key. This repository does not contain one, and `.gitignore`
excludes `*.keystore` so you cannot commit one by accident. If you change the
key after installing, Android will refuse the update unless you uninstall
first — the signature has to match.

## After installing

If you update the app and commands start failing oddly, run:

```
adb shell pkill -f bbterm
```

The `:bbterm` UserService is owned by the Shizuku manager and survives
`am force-stop`, so a new APK can end up talking to a service from the previous
build. The app detects this on connect and says so, but only that command (or a
reboot) clears it.

## Layout

| path | |
|---|---|
| `java/com/BB465_stuff/Terminal/TerminalActivity.java` | UI, sessions, builtins |
| `…/ShellService.java` | the uid 2000 Binder service |
| `…/Shell.java` | Shizuku binding, permission watcher, build handshake |
| `…/Pkg.java` | package manager script generation |
| `…/Apk.java` | `apkinstall` script generation |
| `…/PsInput.java` | when a PowerShell statement is complete (no Android deps, unit tested) |
| `…/CdPath.java` | `/sdcard` alias and path tidying (same) |

`PsInput` and `CdPath` are deliberately free of Android imports — they hold the
fiddly string logic and are unit tested on a plain JVM.

## Bundled fonts

Tinos and Open Sans SemiCondensed, both Apache 2.0. See `res/font/NOTICE.txt`.

## License

No license file yet. Add one if you intend to share this.
