<p align="center">
  <img src="terminal.png" width="110" alt="Quest Terminal">
</p>

<h1 align="center">Quest Terminal</h1>

<p align="center">
  <a href="https://img.shields.io/github/stars/Banban465-tech/Quest-Terminal?style=flat&label=stars"><img src="https://img.shields.io/github/stars/Banban465-tech/Quest-Terminal?style=flat&label=stars" alt="stars"></a>
  <a href="https://img.shields.io/github/forks/Banban465-tech/Quest-Terminal?style=flat&label=forks"><img src="https://img.shields.io/github/forks/Banban465-tech/Quest-Terminal?style=flat&label=forks" alt="forks"></a>
  <a href="https://img.shields.io/github/issues/Banban465-tech/Quest-Terminal?style=flat&label=issues"><img src="https://img.shields.io/github/issues/Banban465-tech/Quest-Terminal?style=flat&label=issues" alt="issues"></a>
  <a href="https://img.shields.io/github/last-commit/Banban465-tech/Quest-Terminal?style=flat&label=last%20commit"><img src="https://img.shields.io/github/last-commit/Banban465-tech/Quest-Terminal?style=flat&label=last%20commit" alt="last commit"></a>
  <a href="https://img.shields.io/badge/gradle-none-3ddc84?style=flat&label=build"><img src="https://img.shields.io/badge/gradle-none-3ddc84?style=flat&label=build" alt="no gradle"></a>
  <a href="https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fraw.githubusercontent.com%2FBanban465-tech%2FQuest-Terminal%2Fmain%2Fstats.json&query=lines&label=java&suffix=%20LOC&color=blue"><img src="https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fraw.githubusercontent.com%2FBanban465-tech%2FQuest-Terminal%2Fmain%2Fstats.json&query=lines&label=java&suffix=%20LOC&color=blue" alt="lines of java"></a>
</p>

A terminal for Meta Quest (Q1–Q3) and Other meta quest devices, built around
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

**Package manager.** `example: pkg install python` resolves dependencies from the Termux
repo, verifies each package's SHA256, and extracts it — in the background, with
`pkg status` showing progress and the real exit code.

**APK installer.** `apkinstall` with the `pm` flags you already know from
`adb install` (`-r`, `-g`, `-d`, `-t`, `--user`, …).

Type `help` in the app for the full list.

## Requirements

- Shizuku or ByteZuku, running, with this app granted access
- Android 10+ (API 29+) (if your running the usual quest it should be already that)

Root is optional. Most things works without it; `askforsu` requests it if you
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

Bring your own key. This repository does not contain one. So if you want to build it from source, you will have to make one

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

## Project stats

Regenerated by `.github/workflows/stats.yml` on every push, so these are never
stale. The table below is written straight into this file; the LOC badge at the
top reads `stats.json` through shields.io.

<!-- stats:start -->

| metric | count |
| --- | ---: |
| lines of java | 6,175 |
| lines of code | 4,814 |
| comment + blank | 1,361 |
| source files | 10 |
| shell builtins | 93 |
| resource files | 21 |
| commits | 1 |
| contributors | 1 |

<details><summary>largest files</summary>

| file | lines | code |
| --- | ---: | ---: |
| `TerminalActivity.java` | 3,604 | 2,890 |
| `Shell.java` | 689 | 538 |
| `ShellService.java` | 591 | 427 |
| `CdPath.java` | 469 | 354 |
| `Pkg.java` | 469 | 354 |
| `Apk.java` | 235 | 179 |

</details>

<sub>generated by `update_stats.py` on `main` at `34e9d79` &middot; 2026-09-29 23:29 UTC</sub>
<!-- stats:end -->
