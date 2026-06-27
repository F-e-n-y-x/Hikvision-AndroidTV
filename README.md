<div align="center">

# 📺 Hik TV Viewer

**A fast, simple Android TV app for watching all the cameras on your Hikvision NVR — live.**

It opens straight into a wall of every camera (Hikvision **and** EZVIZ, as long as they're on the
one NVR), and is built to stay smooth on cheap, low-power TVs and TV sticks using only a normal
remote (D‑pad + OK + Back + Volume).

![version](https://img.shields.io/badge/version-2.3.5-2D7DF6)
![platform](https://img.shields.io/badge/platform-Android%20TV%206%2B-3DDC84)
![language](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white)
![player](https://img.shields.io/badge/video-LibVLC-FF8800)
![build](https://img.shields.io/badge/build-Gradle%20%7C%20signed%20APK-02303A)

</div>

---

## Table of contents

- [What it does](#what-it-does)
- [Download & install](#download--install)
- [First-time setup](#first-time-setup)
- [Using the remote](#using-the-remote)
- [PTZ — moving the camera](#ptz--moving-the-camera)
- [Backup & restore](#backup--restore)
- [Performance & TV compatibility](#performance--tv-compatibility)
- [Settings reference](#settings-reference)
- [Build from source](#build-from-source)
- [How it works (architecture)](#how-it-works-architecture)
- [Project structure](#project-structure)
- [Developer & AI hand-off notes](#developer--ai-hand-off-notes)
- [Troubleshooting](#troubleshooting)
- [Releasing a new version](#releasing-a-new-version)
- [Limitations & ideas](#limitations--ideas)

---

## What it does

**Watching**

- 🟩 **Live camera wall** that opens instantly and sizes itself (2×2, 3×3 …).
- 🔍 **Full screen** any camera; flick **◀ ▶** to the next/previous one.
- 🔎 **Zoom & pan** up to **5×** on any camera, plus real **pan/tilt (PTZ)** where supported.
- ⏪ **Recorded playback** on a 12‑hour timeline (AM/PM) you can scrub.
- 🎞️ **Synced multi-camera playback** — every camera at the **same moment** in time, together.

**Living with it**

- 📸 Snapshots, 🔔 motion/area **alerts**, 🪟 **picture-in-picture**.
- 💾 **Backup & restore** (optionally **PIN-encrypted**) so you never re-enter anything.
- 🔒 Logins stored **encrypted**; everything stays on your home network (only EZVIZ PTZ uses the
  EZVIZ cloud — exactly like the EZVIZ app).
- ⚡ Tuned for **weak TV chips** with per-device video handling (see
  [TV compatibility](#performance--tv-compatibility)).

---

## Download & install

1. Open the **[Releases](../../releases)** tab and grab an APK:
   - **Not sure which? Take `app-universal-release.apk`** — it runs on any TV.
   - Smaller: `app-armeabi-v7a-release.apk` (most TV sticks/boxes, incl. 32‑bit `armv8l`) or
     `app-arm64-v8a-release.apk` (true 64‑bit TVs only).
2. Put it on the TV and install — via a "Send files to TV"/file-manager app, or over ADB:

   ```bash
   adb connect <tv-ip>:5555      # accept the debugging prompt on the TV
   adb install -r app-universal-release.apk
   ```

3. Open **Hik TV Viewer** from the TV's apps row.

> **Which APK for which TV?** `armv7l` and `armv8l` TVs are both 32‑bit → use **v7a** or
> **universal**. Only use **arm64‑v8a** on a TV whose `getprop ro.product.cpu.abi` is `arm64-v8a`.

---

## First-time setup

The first launch is a simple **TV wizard**:

1. **Welcome** → choose **Set up new connection** or **Restore from backup**.
2. **Server** → NVR IP (e.g. `192.168.1.100`), HTTP port (usually `80`), RTSP port (usually `554`).
   The TV must be on the **same network** as the NVR.
3. **Sign in** → an NVR username/password with **Live View + Playback + PTZ** permission → **Connect**.

After this it remembers everything and opens straight to the cameras. **Back** steps the wizard back.

**On the NVR (once, in its own web page):** enable **ISAPI / Hikvision‑CGI**, use an account with
**Live View + Playback + PTZ**, and make sure the **RTSP port (554)** is open.

---

## Using the remote

Designed for a plain Android TV remote — **D‑pad, OK, Back, Volume**. No special remote needed.

### Camera wall (grid)

| Button | Action |
| --- | --- |
| D‑pad | Move between cameras |
| **OK** | Open the selected camera full screen |
| **Hold ◀** | Open Settings |
| **Hold ▼** | Synced multi-camera playback |

### Full screen (one camera)

| Button | Action |
| --- | --- |
| **◀ / ▶** | Previous / next camera |
| **OK** | Popup menu — PTZ, zoom/pan, camera settings, playback, snapshot, audio, PiP, app settings |
| **▲** | Open the Zoom / Pan / PTZ screen |
| **Hold ▼** | Open this camera's playback |
| **Hold ◀** | Open Settings |
| **Back** | Return to the wall |

### Zoom / Pan / PTZ screen

| Button | Action |
| --- | --- |
| **OK** | Switch mode: **Zoom → Pan → PTZ → Zoom** (PTZ only if the camera supports it) |
| **▲▼ / Volume ±** | Zoom in / out (up to 5×) |
| **D‑pad** | **Pan** mode: move the zoomed image · **PTZ** mode: move the camera |
| **Back** | Exit |

### Playback (single & multi-camera)

| Button | Action |
| --- | --- |
| **◀ / ▶** | Scrub back / forward |
| **▲ / ▼** | Jump ± 1 hour |
| **OK** | Play / pause |
| **Back** | Exit |

---

## PTZ — moving the camera

PTZ goes through whatever path the camera supports; the app figures it out and a **Test PTZ
connection** button on the camera page reports exactly what works.

| Camera type | How PTZ works |
| --- | --- |
| Hikvision PTZ on the NVR | Through the NVR over **ISAPI** — automatic. |
| Any ONVIF PTZ camera | **Direct camera connection** → enter the camera IP + ONVIF user/pass. |
| **EZVIZ (e.g. CS‑H8c)** | **EZVIZ cloud** — the *only* way EZVIZ cameras can be moved (see below). |

### EZVIZ cloud PTZ

EZVIZ cameras expose **no usable local PTZ** (not ONVIF, not via the NVR — verified). They move only
through the EZVIZ cloud, exactly like the EZVIZ phone app. To set it up:

1. Open the camera → **OK → Camera settings → "EZVIZ cloud PTZ"**.
2. Enter your **EZVIZ email or phone number** (with country code, e.g. `+91…`) and **password**.
   - QR‑code‑only account? Set a password first in the EZVIZ app (Profile → Account Security).
   - Turn **off 2FA** on the account, or login is blocked.
   - The **camera serial auto-fills** from the NVR (or use **Auto-fill EZVIZ serial** to pick it).
3. **Test PTZ connection** → it logs in, nudges the camera, and reports the result.
4. Then **▲ → OK until it shows PTZ → D‑pad moves the camera**.

> PTZ commands go through EZVIZ's servers (needs internet); **live video stays local**. The CS‑H8c
> is pan/tilt only (no optical zoom) — use Zoom/Pan mode for close-ups.

---

## Backup & restore

The backup captures **every setting** (NVR, cameras, alerts, PTZ/EZVIZ account + serials, layout),
so a restored TV is identical.

- **Settings → Backup settings** writes to `Downloads/HikTVViewer_backup.json`. It asks whether to
  protect it with a **PIN** (AES‑GCM encrypted) or save it plain. The file **survives uninstall**.
- **Restore**: the **Restore from backup** button on the first‑run wizard (pick a file or browse), or
  **Settings → Restore settings**. Encrypted backups ask for the PIN.
- ⚠️ A plain backup contains passwords in clear text — keep it private. A forgotten PIN can't be
  recovered.

---

## Performance & TV compatibility

H.265 + cheap TV chips is the hard part, and **different chips need opposite settings**. The app
auto-detects the chip and picks the right path — it should just work.

| Chip (example TV) | What the app does |
| --- | --- |
| **MediaTek** (Xiaomi MiTV) | Hardware H.265 with the **copy** render path (its zero‑copy path is all‑green). |
| **Amlogic** (Mi Box, S905) | Single‑surface screens (full screen, playback, zoom/PTZ) use the **hardware overlay** (`vout=android_display`) → clean, realtime, no Mali crash. The **grid stays software** — Amlogic greens *any* hardware video on the multi‑tile path. |

**Make the live wall lighter:** if several H.265 cameras strain a weak TV, run
**Settings → "Optimize live (smooth)"** — it sets each camera's grid sub‑stream to **H.264 @ 15 fps**
on the NVR (full‑screen / recording quality is unchanged). If video still misbehaves, try
**Settings → Video decoding → Software** or **Settings → Video rendering → Direct/Compatible**.

---

## Settings reference

| Setting | What it does |
| --- | --- |
| Connection | Edit the NVR IP / ports / login. |
| Refresh cameras / Scan channels | Re‑discover cameras from the NVR. |
| Set number of cameras | Manually define channels 1..N. |
| Grid layout | Auto / 1 / 2 / 3 / 4 columns. |
| Video decoding | Balanced (default) · Hardware · Software. |
| Video rendering | Compatible ↔ Direct (per‑TV render path override). |
| Optimize live (smooth) | Convert grid sub‑streams to lightweight H.264. |
| Alerts | Pick cameras to get motion/area notifications for. |
| Diagnostics | Show what the NVR returns (for connection issues). |
| Backup / Restore settings | Save / load everything (optional PIN). |

---

## Build from source

Requires **Android Studio (2024.1+)**, **JDK 17**, and the Android SDK.

```bash
# Debug build (no signing key needed)
./gradlew assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug           # install to a connected TV via adb
```

To build the **signed release** APKs, create `keystore.properties` in the project root pointing at
your own signing key:

```properties
storeFile=release.keystore
storePassword=YOUR_PASSWORD
keyAlias=YOUR_ALIAS
keyPassword=YOUR_PASSWORD
```

```bash
./gradlew assembleRelease        # -> app/build/outputs/apk/release/*.apk  (per‑ABI + universal)
```

> The signing key (`release.keystore`, `keystore.properties`) is **not** committed — keep yours
> private and backed up (you need the same key to ship updates). Without it the release is unsigned.

There's also a **driver skill** at `.claude/skills/run-hik-tv-viewer/` (a PowerShell ADB harness) that
builds, installs, launches and screenshots the app on a TV/emulator — handy for automated testing.

---

## How it works (architecture)

- **Kotlin**, one Hikvision NVR; every camera is an NVR channel.
- **[LibVLC](https://www.videolan.org/vlc/libvlc.html)** plays the RTSP streams (H.264 **and** H.265,
  hardware decoding, low‑latency tuning). ExoPlayer's RTSP doesn't reliably do H.265 — that's why
  LibVLC is used.
- **Hikvision ISAPI** (HTTP Digest) for discovery, snapshots, recordings, Hikvision PTZ and stream
  config; **ONVIF** (SOAP) for direct‑to‑camera PTZ; **EZVIZ cloud** for EZVIZ PTZ.
- The **grid** uses each camera's small **sub‑stream**; **full screen** uses the high‑res main stream.
  Streams are **released when you leave a screen**, so a weak TV never decodes more than it must.
- **Per‑chip render path** (`util/DeviceQuirks`): see [TV compatibility](#performance--tv-compatibility).

```text
Remote ─▶ Activity (grid / fullscreen / control / playback / settings)
                │
                ├─ Session ............ in-memory NVR + camera list
                ├─ NvrStore ........... encrypted settings + backup JSON
                ├─ IsapiClient ........ NVR REST (discover / PTZ / snapshot / search / optimize)
                ├─ OnvifPtz / EzvizCloud / PtzController .... PTZ backends
                └─ CameraStream ─▶ PlayerEngine (one shared LibVLC) ─▶ Surface/TextureView
```

---

## Project structure

```text
app/src/main/java/com/hiktv/viewer/
├─ core/Session.kt              active NVR + camera list (in memory)
├─ data/
│  ├─ model/{Nvr,Camera}.kt     connection + camera info
│  ├─ store/NvrStore.kt         encrypted settings + backup/restore JSON
│  ├─ RtspUrls.kt               builds live / playback stream links
│  ├─ isapi/IsapiClient.kt      NVR API: discover, PTZ, snapshot, recordings, optimize, cameraIp/Serial
│  ├─ isapi/EventListener.kt    alert-stream → motion/area events flow
│  ├─ onvif/OnvifPtz.kt         direct-to-camera PTZ over ONVIF (SOAP + WS-UsernameToken)
│  ├─ ezviz/EzvizCloud.kt       EZVIZ cloud login + PTZ + device list
│  └─ ptz/PtzController.kt      one PTZ interface (NVR / direct ISAPI / ONVIF / EZVIZ cloud)
├─ player/
│  ├─ PlayerEngine.kt           single shared LibVLC, low-latency global options
│  └─ CameraStream.kt           one RTSP stream ↔ one surface, reconnect, zoom/pan, per-chip options
├─ util/
│  ├─ DeviceQuirks.kt           per-chip workarounds (Amlogic render path)
│  ├─ BackupManager.kt          read/write the backup file in Downloads (MediaStore + direct)
│  ├─ BackupCrypto.kt           optional PIN (AES-GCM, PBKDF2) backup encryption
│  ├─ DialogIme.kt              TV keyboard BACK handling for dialogs
│  ├─ SnapshotCache.kt          disk JPEG cache for instant grid previews
│  └─ Notifications.kt          motion/area notification channel
└─ ui/
   ├─ setup/        first-run wizard (welcome / server / sign-in / restore picker)
   ├─ grid/         live camera wall
   ├─ fullscreen/   one camera, switch with ◀ ▶, popup menu
   ├─ camera/       per-camera settings page (PTZ setup, direct/EZVIZ, snapshot, rename)
   ├─ control/      zoom / pan / PTZ
   ├─ playback/     recorded footage — PlaybackActivity (single) + MultiPlaybackActivity (synced)
   └─ settings/     app settings, backup/restore, optimize, diagnostics
```

---

## Developer & AI hand-off notes

> Hard‑won, non‑obvious facts. If you fork this and continue with an AI agent, **read this first** —
> it captures things that took real debugging to learn.

**Architecture rules**

- One NVR only. All cameras (Hikvision + EZVIZ) are reached as NVR **channels**; the RTSP channel id is
  `channel*100 + streamType` (101 = ch1 main, 102 = ch1 sub).
- All settings live in **one** `EncryptedSharedPreferences` via `NvrStore`. The backup is just
  `prefs.all` serialized to JSON, so it's automatically complete — don't add a second prefs store.
- Streams are released on `onStop` and rebuilt on `onStart` for fullscreen/control/grid; this is what
  keeps weak TVs from running out of decoder/surface sessions. Keep that pattern for any new video screen.

**Per‑chip video (the big one)** — `util/DeviceQuirks.isAmlogic` (reads `Build.HARDWARE` / `ro.board.platform`):

| | MediaTek (MiTV) | Amlogic (Mi Box) |
| --- | --- | --- |
| Hardware H.265, zero‑copy | green | green/corner + Mali SIGSEGV |
| Hardware H.265, copy (`:no-mediacodec-dr`) | **clean** | green/corner |
| Hardware, `:vout=android_display` overlay (single surface) | n/a | **clean + realtime** |
| Multi‑tile grid hardware | ok (copy) | **green** (overlay can't clip N tiles → software only) |

So: Amlogic → `directRender` (skip `:no-mediacodec-dr`) **+** per‑stream `:vout=android_display` on
single‑surface screens, SurfaceView (not TextureView), software grid. MiTV → copy path. Both gated by
`DeviceQuirks` + an optional manual **Video rendering** toggle (`NvrStore.directRender`).

**EZVIZ cloud PTZ** (`data/ezviz/EzvizCloud.kt`, ported from [pyEzviz](https://github.com/BaQs/pyEzviz)):

- Login: `POST https://{apiDomain}/v3/users/login/v5`, form `account / password(md5 hex) / featureCode /
  msgType=0 / cuName=SGFzc2lv`; on meta code `1100` follow `loginArea.apiDomain` and retry; `6002` = 2FA.
- PTZ: `PUT https://{apiDomain}/v3/devices/{serial}/ptzControl` (capital **C** matters), form
  `command=UP|DOWN|LEFT|RIGHT, action=START|STOP, channelNo=1, speed=1..10`, header `sessionId`.
- Device list: `GET /v3/userdevices/v1/resources/pagelist` → `deviceInfos[].deviceSerial / name`.
- **The EZVIZ serial = the last 9 chars of the NVR's `<serialNumber>`** for that channel (auto-filled by
  `IsapiClient.cameraSerial`). The 6‑letter "verification code" is NOT the account password.

**ONVIF** (`data/onvif/OnvifPtz.kt`): SOAP 1.2 + WS‑UsernameToken digest, syncs to the camera clock
first (beats skew), puts the WS‑Action in the Content‑Type. Note: consumer EZVIZ usually expose **no**
ONVIF port at all (verified by scanning), so EZVIZ falls back to the cloud path above.

**ISAPI endpoints used** (`data/isapi/IsapiClient.kt`): `ContentMgmt/InputProxy/channels` (names + ip +
serial), `Streaming/channels[/{id}]` (online + per‑stream config + the H.264 "Optimize" PUT),
`PTZCtrl/channels/{ch}/...`, `Streaming/channels/{id}/picture` (snapshot), `ContentMgmt/search`
(recordings), `Event/notification/alertStream` (events), `System/deviceInfo` (connectivity test).

**Backup file**: `Downloads/HikTVViewer_backup.json`. Plain = the settings JSON; encrypted =
`{"hiktv_enc":1,"salt","iv","data"}` (AES‑GCM, PBKDF2‑SHA256). A fresh install needs
`READ_EXTERNAL_STORAGE` + `requestLegacyExternalStorage` to read a previous install's file.

**Gotchas**: TV dialogs need `DialogIme` so BACK closes the keyboard not the popup · the Mi remote's
"menu/m" key isn't reliably delivered, so use D‑pad/OK and long‑press instead · `adb logcat -b crash -d`
reads an already-recorded native crash without re-running anything.

---

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| "No cameras found" | The NVR account lacks Live View permission, or ISAPI is off. |
| Green / corner‑cropped full‑screen video | Toggle **Settings → Video rendering** (Compatible ↔ Direct). Amlogic auto-uses Direct. |
| A tile stays black | That camera is offline or its sub‑stream is disabled. |
| Live wall laggy / glitchy | Run **Settings → Optimize live (smooth)**; or **Video decoding → Software**. |
| EZVIZ PTZ won't move | Camera → **Test PTZ connection**. Use the EZVIZ **account** login (not the verification code), check the serial, and turn off 2FA. |
| Restore button missing on first boot | Grant the storage permission when prompted; or use **Settings → Restore settings** later. |
| App crashes on a specific TV | Grab `adb logcat -b crash -d` from that TV — the native backtrace pinpoints the chip/driver issue. |

---

## Releasing a new version

1. Bump `versionCode` + `versionName` in `app/build.gradle.kts`.
2. `./gradlew assembleRelease` (needs `keystore.properties`).
3. `gh release create vX.Y.Z app/build/outputs/apk/release/*.apk --title "…" --notes "…"`.
4. Install on a TV: `adb install -r app-universal-release.apk`.

Per‑ABI APKs are ~21 MB; the universal APK (~80 MB) bundles all ABIs.

---

## Limitations & ideas

- **Amlogic multi‑camera grid** is software‑decoded (the chip greens hardware video on the multi‑tile
  path), so it's heavier than on other TVs — lighten it with **Optimize live**.
- EZVIZ PTZ depends on EZVIZ's **undocumented cloud API** and needs internet.
- Possible future additions: auto‑sequence/cycle mode, an events timeline that jumps into playback.

---

<div align="center">

*Personal / home project. Use with cameras and an NVR you own.*

</div>
