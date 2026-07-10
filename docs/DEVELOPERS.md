# Developer & AI hand-off notes

> Hard-won, non-obvious facts about this codebase. **If you fork this and continue — with or
> without an AI agent — read this first.** It captures things that took real debugging to learn.
> For the user-facing guide, see the [README](../README.md). For the full audit and fix history,
> see [AUDIT_REPORT.md](../AUDIT_REPORT.md).

---

## How it works (architecture)

- **Kotlin**, one Hikvision NVR; every camera is an NVR channel.
- **[LibVLC](https://www.videolan.org/vlc/libvlc.html)** plays the RTSP streams (H.264 **and** H.265,
  hardware decoding, low-latency tuning). ExoPlayer's RTSP doesn't reliably do H.265 — that's why
  LibVLC is used.
- **Hikvision ISAPI** (HTTP Digest) for discovery, snapshots, recordings, Hikvision PTZ and stream
  config; **ONVIF** (SOAP) for direct-to-camera PTZ; **EZVIZ cloud** for EZVIZ PTZ.
- The **grid** uses each camera's small **sub-stream**; **full screen** uses the high-res main stream.
  Streams are **released when you leave a screen**, so a weak TV never decodes more than it must.
- **Per-chip render path** (`util/DeviceQuirks`): see [Per-chip video](#per-chip-video-the-big-one).

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
│  ├─ DecoderCaps.kt            probes concurrent HW decoder-instance limits
│  ├─ CrashLog.kt               file-based crash capture (shown in Settings → Last crash)
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

## Architecture rules

- One NVR only. All cameras (Hikvision + EZVIZ) are reached as NVR **channels**; the RTSP channel id is
  `channel*100 + streamType` (101 = ch1 main, 102 = ch1 sub).
- All settings live in **one** `EncryptedSharedPreferences` via `NvrStore`. The backup is just
  `prefs.all` serialized to JSON, so it's automatically complete — don't add a second prefs store.
- Streams are released on `onStop` and rebuilt on `onStart` for fullscreen/control/grid; this is what
  keeps weak TVs from running out of decoder/surface sessions. Keep that pattern for any new video screen.
- Blocking LibVLC `stop()/release()` runs on a shared background thread (`CameraStream.TEARDOWN`) so
  tearing down N grid tiles at once from `onStop` can't ANR the UI thread.

## Per-chip video (the big one)

`util/DeviceQuirks.isAmlogic` (reads `Build.HARDWARE` / `ro.board.platform`):

| | MediaTek (MiTV) | Amlogic (Mi Box) |
| --- | --- | --- |
| Hardware H.265, zero-copy | green | green/corner + Mali SIGSEGV |
| Hardware H.265, copy (`:no-mediacodec-dr`) | **clean** | green/corner |
| Hardware, `:vout=android_display` overlay (single surface) | n/a | **clean + realtime** |
| Multi-tile grid hardware | ok (copy) | **green** (overlay can't clip N tiles → software only) |

So: Amlogic → `directRender` (skip `:no-mediacodec-dr`) **+** per-stream `:vout=android_display` on
single-surface screens, SurfaceView (not TextureView), software grid **and** software synced-playback
(the multi-tile overlay can't clip N cells). MiTV → copy path. Both gated by `DeviceQuirks` + an
optional manual **Video rendering** toggle (`NvrStore.directRender`).

Concurrent hardware decoders are capped by a real probe (`util/DecoderCaps.getMaxSupportedInstances`)
before synced playback spins up N main streams — cheap SoCs allow only ~2-4 HEVC sessions.

## EZVIZ cloud PTZ

`data/ezviz/EzvizCloud.kt`, ported from [pyEzviz](https://github.com/BaQs/pyEzviz):

- Login: `POST https://{apiDomain}/v3/users/login/v5`, form `account / password(md5 hex) / featureCode /
  msgType=0 / cuName=SGFzc2lv`; on meta code `1100` follow `loginArea.apiDomain` and retry; `6002` = 2FA.
- PTZ: `PUT https://{apiDomain}/v3/devices/{serial}/ptzControl` (capital **C** matters), form
  `command=UP|DOWN|LEFT|RIGHT, action=START|STOP, channelNo=1, speed=1..10`, header `sessionId`.
- Device list: `GET /v3/userdevices/v1/resources/pagelist` → `deviceInfos[].deviceSerial / name`.
- **The EZVIZ serial = the last 9 chars of the NVR's `<serialNumber>`** for that channel (auto-filled by
  `IsapiClient.cameraSerial`). The 6-letter "verification code" is NOT the account password.
- Sessions expire server-side after hours idle; the controller re-authenticates and retries once on any
  PTZ failure (`EzvizCloud.invalidateSession()` + re-login) so PTZ doesn't silently die.

## ONVIF

`data/onvif/OnvifPtz.kt`: SOAP 1.2 + WS-UsernameToken digest, syncs to the camera clock first (beats
skew), puts the WS-Action in the Content-Type. Note: consumer EZVIZ usually expose **no** ONVIF port at
all (verified by scanning), so EZVIZ falls back to the cloud path above.

## ISAPI endpoints used

`data/isapi/IsapiClient.kt`: `ContentMgmt/InputProxy/channels` (names + ip + serial),
`Streaming/channels[/{id}]` (online + per-stream config + the H.264 "Optimize" PUT — which also disables
SmartCodec and sets CBR/GOP, then re-GETs to verify), `PTZCtrl/channels/{ch}/...`,
`Streaming/channels/{id}/picture` (snapshot), `ContentMgmt/search` (recordings),
`Event/notification/alertStream` (events), `System/deviceInfo` (connectivity test). Short calls use a
bounded-timeout client; the long-poll alertStream uses the 35 s-read client.

## Backup file

`Downloads/HikTVViewer_backup.json`. Plain = the settings JSON; encrypted =
`{"hiktv_enc":1,"salt","iv","iter","data"}` (AES-GCM, PBKDF2-SHA256; `iter` is stored so the cost can be
raised without breaking older files). A fresh install needs `READ_EXTERNAL_STORAGE` +
`requestLegacyExternalStorage` to read a previous install's file.

## Gotchas

- TV dialogs need `DialogIme` so BACK closes the keyboard not the popup.
- The Mi remote's "menu/m" key isn't reliably delivered, so use D-pad/OK and long-press instead.
- `adb logcat -b crash -d` reads an already-recorded native crash without re-running anything. Java/Kotlin
  crashes are also captured to a file and shown in **Settings → Last crash** (native SIGSEGVs are not — a
  JVM handler can't catch those).

---

## Build from source

Requires **Android Studio (2024.1+)**, **JDK 17**, and the Android SDK.

```bash
# Debug build (no signing key needed)
./gradlew assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug           # install to a connected TV via adb
```

To build the **signed release** APKs, create `keystore.properties` in the project root pointing at your
own signing key:

```properties
storeFile=release.keystore
storePassword=YOUR_PASSWORD
keyAlias=YOUR_ALIAS
keyPassword=YOUR_PASSWORD
```

```bash
./gradlew assembleRelease        # -> app/build/outputs/apk/release/*.apk  (per-ABI + universal)
```

> The signing key (`release.keystore`, `keystore.properties`) is **not** committed — keep yours private
> and backed up (you need the same key to ship updates). Without it the release is unsigned.

There's also a **driver skill** at `.claude/skills/run-hik-tv-viewer/` (a PowerShell ADB harness) that
builds, installs, launches and screenshots the app on a TV/emulator — handy for automated testing.

---

## Releasing a new version

1. Bump `versionCode` + `versionName` in `app/build.gradle.kts` (and the README badge).
2. `./gradlew clean assembleRelease` (needs `keystore.properties`).
3. `gh release create vX.Y.Z app/build/outputs/apk/release/*.apk --title "…" --notes "…"`.
4. Install on a TV: `adb install -r app-universal-release.apk`.

Per-ABI APKs are ~21 MB; the universal APK (~80 MB) bundles all ABIs.
