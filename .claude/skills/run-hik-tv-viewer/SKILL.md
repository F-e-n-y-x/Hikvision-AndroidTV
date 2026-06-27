---
name: run-hik-tv-viewer
description: Build, install, launch, screenshot and drive the Hik TV Viewer Android TV app (Kotlin/LibVLC NVR viewer) on an emulator or device. Use when asked to run, start, build, install, launch, screenshot, smoke-test, or drive this Android TV app, or to reproduce its grid/fullscreen/menu/PTZ/playback screens.
---

# Run: Hik TV Viewer (Android TV)

Hik TV Viewer is a Kotlin **Android TV** app that opens into a live wall of Hikvision-NVR
cameras (LibVLC/RTSP). There is no desktop window — you drive it on an **Android TV
emulator (or device) over `adb`**, using the PowerShell driver
`.claude/skills/run-hik-tv-viewer/driver.ps1`. The driver wraps the exact
build/install/launch/key/screenshot commands and writes screenshots to
`.claude/skills/run-hik-tv-viewer/shots/`.

All paths below are relative to the repo root. Commands are PowerShell (this is a Windows host).

## Prerequisites

- **Android SDK** at `%LOCALAPPDATA%\Android\Sdk` (the driver auto-finds it; or set `ANDROID_HOME`).
  Needs `platform-tools/adb.exe`, `emulator/`, and the `android-29` **android-tv** system image.
- **JDK 17** — the driver auto-points `JAVA_HOME` at Android Studio's bundled JBR
  (`C:\Program Files\Android\Android Studio\jbr`) if it isn't already set.
- **A running Android TV emulator or device.** This repo has an AVD named `Television_1080p`.
  List/confirm it:

  ```powershell
  & "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -list-avds
  & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
  ```

  If no device shows, start the emulator (leave it running in another window):

  ```powershell
  Start-Process "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -ArgumentList "-avd","Television_1080p"
  ```

## Build

```powershell
& .\.claude\skills\run-hik-tv-viewer\driver.ps1 build release
```

This runs `gradlew assembleRelease` (signed via `keystore.properties`). Output APK:
`app\build\outputs\apk\release\app-release.apk` (~80 MB — LibVLC bundles native libs for
all ABIs, so install takes a few seconds). Use `build debug` for an unsigned debug APK.

## Run — agent path (this is the one to use)

One-shot install + launch + screenshot + focus check:

```powershell
& .\.claude\skills\run-hik-tv-viewer\driver.ps1 smoke
```

It installs the release APK, launches `com.hiktv.viewer/.ui.setup.SetupActivity`, waits, and
saves `shots\smoke.png`. On a configured NVR the app auto-opens the live grid (credentials
persist across installs via EncryptedSharedPreferences).

Drive individual interactions:

```powershell
$drv = ".\.claude\skills\run-hik-tv-viewer\driver.ps1"
& $drv key down          # move D-pad focus (up|down|left|right|ok|back|chup|chdown|<NN>)
& $drv key ok            # OK on a focused tile -> fullscreen camera
& $drv key back          # back out
& $drv menu              # open the in-app menu (KEYCODE_MENU 82) — reliable
& $drv longkey left      # the real "long-press LEFT" menu gesture
& $drv shot grid         # screenshot -> shots\grid.png
```

Verified flow that produced real screenshots this session: `smoke` → live 2×2 camera grid
with lime focus border; `menu` / `longkey left` → the Menu dialog (Auto-detect, Connection
settings, Set number of cameras, Run diagnostics, Change grid layout, Refresh, Playback);
`key ok` on a tile → fullscreen main-stream view with the PTZ hint bar.

## Run — human path

Open the folder in **Android Studio** → Run on the TV emulator/device. Or sideload onto a
real Android TV: `adb connect <tv-ip>:5555` then
`adb install -r app\build\outputs\apk\release\app-release.apk`. Useless headless — there's
no window; use the agent path above.

## Gotchas

- **`adb input keyevent --longpress 21` only opens the menu because the app checks
  `KeyEvent.isLongPress()`.** A plain `--longpress` sends one flagged event, not a held key
  with repeats, so the app's time-based detection alone would miss it. If driving a build
  before that fix, use `driver.ps1 menu` (KEYCODE_MENU 82) instead — that path always works.
- **Screenshots taken <1 s after returning from fullscreen can be black.** The grid tears
  down/reconnects its RTSP streams on resume (network-caching ~250 ms + connect). `smoke`
  sleeps 6 s; if you script your own flow, sleep ~1–2 s before `shot`. (A black grid shot is
  ~13 KB; a live one is ~2 MB — size is a quick liveness check.)
- **The app needs the NVR reachable on the LAN.** The emulator reaches the host's LAN via
  NAT, so a real NVR at e.g. `192.168.11.200` works from `emulator-5554`. With no/unreachable
  NVR you get the empty state (logo + "Auto-detect cameras" / "Connection settings" buttons).
- **Cleartext HTTP is intentionally allowed** (network-security-config) — ISAPI runs on port
  80. Without it the connection dies with "CLEARTEXT not permitted".
- **Launcher activity is `.ui.setup.SetupActivity`**, not a `MainActivity`.

## Troubleshooting

- **"No device/emulator attached"** (driver throws) → start the emulator (see Prerequisites),
  wait for `adb devices` to show `emulator-5554  device`.
- **`INSTALL_FAILED_UPDATE_INCOMPATIBLE` / signature mismatch** → a different-keystore build
  is installed: `adb -s emulator-5554 uninstall com.hiktv.viewer` then re-run `smoke`.
- **Grid shows the empty state / "No cameras"** → `driver.ps1 menu` → *Run diagnostics*
  (shows each NVR ISAPI endpoint's HTTP code + body), or *Set number of cameras (manual)*.
- **Multiple devices attached** → set `$env:ANDROID_SERIAL = "emulator-5554"` before driver calls.
