# 🧩 Developer & AI hand-off notes

> Hard-won, non-obvious facts about this codebase. **If you fork this and continue — with or
> without an AI agent — read this first.** It captures things that took real debugging to learn.
> For the user guide, see the [README](../README.md).

## Contents

- [Architecture](#architecture)
- [Project structure](#project-structure)
- [Architecture rules (don't break these)](#architecture-rules-dont-break-these)
- [The video pipeline](#the-video-pipeline)
- [Per-chip video — the big one](#per-chip-video--the-big-one)
- [Decoder-instance limits](#decoder-instance-limits)
- [Stability: crashes, ANRs, memory](#stability-crashes-anrs-memory)
- [ISAPI (Hikvision REST)](#isapi-hikvision-rest)
- ["Optimize live" — sub-stream reconfig](#optimize-live--sub-stream-reconfig)
- [EZVIZ cloud PTZ](#ezviz-cloud-ptz)
- [ONVIF](#onvif)
- [Backup file & crypto](#backup-file--crypto)
- [Security](#security)
- [Gotchas](#gotchas)
- [Build from source](#build-from-source)
- [Releasing a new version](#releasing-a-new-version)

---

## Architecture

- **Kotlin**, one Hikvision NVR; every camera is an NVR channel. The RTSP channel id is
  `channel*100 + streamType` (**101** = ch1 main, **102** = ch1 sub).
- **[LibVLC](https://www.videolan.org/vlc/libvlc.html)** (`org.videolan.android:libvlc-all:3.6.0`)
  plays the RTSP streams — H.264 **and** H.265, hardware decode, low-latency tuning. ExoPlayer's RTSP
  doesn't reliably do H.265, which is why LibVLC is used.
- **ISAPI** (HTTP Digest, OkHttp + okhttp-digest) for discovery, snapshots, recordings, Hikvision PTZ
  and stream config; **ONVIF** (SOAP) for direct-to-camera PTZ; **EZVIZ cloud** for EZVIZ PTZ.
- The **grid** uses each camera's small **sub-stream**; **fullscreen / playback** use the high-res
  **main** stream. Streams are released when you leave a screen, so a weak TV never decodes more than
  it must.
- `minSdk 23`, `targetSdk / compileSdk 35`, ViewBinding, R8 + resource shrink on release, ABI splits
  (`armeabi-v7a`, `arm64-v8a`, `x86`) + universal.

```text
Remote ─▶ Activity (grid / fullscreen / control / playback / settings)
                │
                ├─ Session ............ in-memory NVR + camera list
                ├─ NvrStore ........... encrypted settings + backup JSON
                ├─ IsapiClient ........ NVR REST (discover / PTZ / snapshot / search / optimize)
                ├─ OnvifPtz / EzvizCloud / PtzController .... PTZ backends
                └─ CameraStream ─▶ PlayerEngine (one shared LibVLC) ─▶ Surface/TextureView
```

## Project structure

```text
app/src/main/java/com/hiktv/viewer/
├─ HikApp.kt                    Application: shared singletons + installs CrashLog
├─ core/Session.kt              active NVR + camera list (in memory, @Volatile)
├─ data/
│  ├─ model/{Nvr,Camera}.kt     connection + camera info
│  ├─ store/NvrStore.kt         encrypted settings + backup/restore JSON + sub-stream originals
│  ├─ RtspUrls.kt               builds live / playback stream links (credential URL-encoding)
│  ├─ isapi/IsapiClient.kt      NVR API: discover, PTZ, snapshot, recordings, optimize, serial
│  ├─ isapi/EventListener.kt    alert-stream → motion/area events flow
│  ├─ onvif/OnvifPtz.kt         direct-to-camera PTZ over ONVIF (SOAP + WS-UsernameToken)
│  ├─ ezviz/EzvizCloud.kt       EZVIZ cloud login + PTZ + device list
│  └─ ptz/PtzController.kt      one PTZ interface (NVR / direct ISAPI / ONVIF / EZVIZ cloud)
├─ player/
│  ├─ PlayerEngine.kt           single shared LibVLC, low-latency global options
│  └─ CameraStream.kt           one RTSP stream ↔ one surface: reconnect, watchdog, zoom/pan, teardown
├─ util/
│  ├─ DeviceQuirks.kt           per-chip workarounds (Amlogic render path)
│  ├─ DecoderCaps.kt            probes concurrent HW decoder-instance limits
│  ├─ CrashLog.kt               file-based crash capture (Settings → Last crash)
│  ├─ BackupManager.kt          read/write the backup file in Downloads (MediaStore + direct)
│  ├─ BackupCrypto.kt           optional PIN (AES-GCM, PBKDF2) backup encryption
│  ├─ DialogIme.kt              TV keyboard BACK handling for dialogs
│  ├─ SnapshotCache.kt          disk JPEG cache for instant grid previews
│  └─ Notifications.kt          motion/area notification channel
└─ ui/
   ├─ setup/        first-run wizard (welcome / server / sign-in / restore picker)
   ├─ grid/         live camera wall (GridActivity + GridAdapter)
   ├─ fullscreen/   one camera, switch with ◀ ▶, popup menu, PiP
   ├─ camera/       per-camera settings (PTZ setup, direct/EZVIZ, snapshot, rename)
   ├─ control/      zoom / pan / PTZ
   ├─ playback/     recorded footage — PlaybackActivity (single) + MultiPlaybackActivity (synced)
   └─ settings/     app settings, backup/restore, optimize, diagnostics
```

## Architecture rules (don't break these)

- **One NVR only.** All cameras (Hikvision + EZVIZ) are reached as NVR **channels**.
- **One settings store.** Everything lives in a single `EncryptedSharedPreferences` via `NvrStore`.
  The backup is just `prefs.all` serialized to JSON, so it's automatically complete — **don't add a
  second prefs store.**
- **Release on `onStop`, rebuild on `onStart`** for every video screen (grid, fullscreen, control,
  playback, multi-playback). This is what keeps weak TVs from running out of decoder/surface sessions.
  Keep that pattern for any new video screen, and gate the delayed rebuild on `STARTED` + cancel it in
  `onStop` so a fast in-out can't rebuild decoders in the background.
- **PiP is the exception:** while `isInPictureInPictureMode`, `onStop` must *not* release the stream;
  `onDestroy` always does.

## The video pipeline

- **`PlayerEngine`** owns the single shared `LibVLC` with global low-latency options
  (`--rtsp-tcp`, `--avcodec-hw=any`, `--clock-jitter=0`, `--clock-synchro=0`, no audio time-stretch /
  OSD / SPU). Verbose logging is **off in release** (`--quiet`) — `-v` on an N-tile wall wastes CPU
  and prints the credentialed RTSP URI to logcat; debuggable builds get `-vv`.
- **`CameraStream`** = one `MediaPlayer` bound to one surface. Per-media options set caching
  (grid 300 ms · fullscreen 250 ms · playback 1500–1800 ms), `:rtsp-tcp`, HW/SW decode, the
  green-fixes (`:no-mediacodec-dr` copy path / `:vout=android_display` overlay), `:no-audio` on muted
  tiles, and frame-drop policy. Grid tiles are muted and start **staggered** so the decoder isn't
  asked for many sessions in the same instant.
- **Liveness watchdog:** LibVLC can silently stop delivering frames on a live RTSP stream without
  firing `EncounteredError`/`EndReached`. `lastProgressAt` is bumped on every `TimeChanged`; if it
  stops advancing while we believe we're playing, the watchdog forces a `stop()/play()` reconnect.
- **Reconnect backoff:** exponential, capped at 8 s. It is **only reset after the stream has been
  stably Playing for ≥10 s** — resetting on the first `Playing` event let a flapping stream reconnect
  in a tight ~1 s loop and thrash the decoder.
- **Teardown off the UI thread:** `stop()`/`release()` join native decoder threads and can block.
  Releasing N grid tiles at once from `onStop` on the main thread is a real ANR path, so the native
  teardown runs on a shared single background thread (`CameraStream.TEARDOWN`); only the cheap
  `detachViews()` stays on the main thread.
- **Playback** (`PlaybackActivity` / `MultiPlaybackActivity`) builds its own `MediaPlayer` inline —
  it does **not** go through `CameraStream`, so it has no liveness watchdog (a known gap; a silent
  demux stall on a recording won't self-heal).

## Per-chip video — the big one

`util/DeviceQuirks.isAmlogic` reads `Build.HARDWARE` / `Build.BOARD` / `ro.hardware` /
`ro.board.platform`.

| | MediaTek (MiTV) | Amlogic (Mi Box) |
| --- | --- | --- |
| Hardware H.265, zero-copy | green | green/corner + Mali SIGSEGV |
| Hardware H.265, copy (`:no-mediacodec-dr`) | **clean** | green/corner |
| Hardware, `:vout=android_display` overlay (single surface) | n/a | **clean + realtime** |
| Multi-tile grid hardware | ok (copy) | **green** (overlay can't clip N tiles → software only) |

So:

- **Amlogic** → `directRender` (skip `:no-mediacodec-dr`) **+** per-stream `:vout=android_display` on
  single-surface screens, **SurfaceView** (not TextureView), and **software** on any multi-tile screen
  — the grid **and** synced playback (the single-surface overlay can't clip N cells, so both must stay
  software there).
- **MiTV / MediaTek** → hardware H.265 with the copy path.
- Both are gated by `DeviceQuirks` plus an optional manual **Video rendering** toggle
  (`NvrStore.directRender`). Digital zoom/pan is applied as a view-level transform on whichever
  surface LibVLC created (TextureView **or** SurfaceView), so pan works on the Amlogic overlay path
  too — it's SurfaceFlinger-composited, which avoids the Mali/GL crash TextureView would cause there.
- The **Redmi X50 (L50M6-RA)** is MediaTek but has no dedicated quirk branch yet — it relies on the
  manual toggle. If it green/artifacts out of the box, add a MediaTek branch to `DeviceQuirks` after
  reading `getprop ro.board.platform / ro.hardware / ro.product.model` on the actual panel.

## Decoder-instance limits

Cheap TV SoCs allow only ~2–4 concurrent hardware HEVC sessions; exceeding it silently black-screens
or greens tiles, or crashes. `util/DecoderCaps` probes `MediaCodecList` →
`CodecCapabilities.getMaxSupportedInstances()` for HEVC/AVC (cached, conservative fallback of 2), and
synced multi-playback caps its cell count by that number (or a modest bound on the software Amlogic
path) before spinning up N main-stream players.

## Stability: crashes, ANRs, memory

- **Crash capture** — `util/CrashLog` installs a `Thread.setDefaultUncaughtExceptionHandler` in
  `HikApp.onCreate` that writes the stack + device/app info to `filesDir/last_crash.txt`, then chains
  to the previous handler so the process still dies. Surfaced in **Settings → Last crash**. A JVM
  handler **cannot** catch native SIGSEGV (Mali / decoder) — for those use `adb logcat -b crash -d`.
- **Memory pressure** — `GridActivity.onTrimMemory` sheds the live decoders (the biggest native
  consumer) on `TRIM_MEMORY_RUNNING_CRITICAL` and restores them ~4 s later, so the app is a smaller
  LMK target during a spike. `largeHeap=true` is set, which raises the app's footprint — keep the trim
  hook in mind if you change decode load.
- **ANR paths to avoid** — blocking LibVLC teardown on the UI thread (handled, see above); PBKDF2 /
  file I/O for backup/restore now runs on a background dispatcher with a progress dialog.

## ISAPI (Hikvision REST)

`data/isapi/IsapiClient.kt`, one instance per NVR, all work on `Dispatchers.IO`. Digest auth via
`DigestAuthenticator` + `CachingAuthenticatorDecorator` + `AuthenticationCacheInterceptor`. Two
OkHttp clients share a pool: a **short-call** client (`callTimeout` 15 s) for request/response calls,
and the original **35 s-read** client for the long-poll `alertStream` (reused by `EventListener`).

Endpoints used:

- `ContentMgmt/InputProxy/channels` — names + ip + serial (the authoritative camera list)
- `Streaming/channels[/{id}]` — online state, per-stream config, the "Optimize" PUT, sub-stream capture/restore
- `PTZCtrl/channels/{ch}/continuous` — continuous PTZ (stop = zero vector, retried 3×)
- `Streaming/channels/{id}/picture` — JPEG snapshot
- `ContentMgmt/search` — recordings
- `Event/notification/alertStream` — motion/area events (long poll)
- `System/deviceInfo` — connectivity test / diagnostics

RTSP URLs (`data/RtspUrls.kt`) embed `user:pass`; credentials are URL-encoded with `+`→`%20`
correction so passwords with spaces/specials survive.

## "Optimize live" — sub-stream reconfig

`IsapiClient.optimizeSubStream(channel)` GETs the sub-stream `StreamingChannel` XML and rewrites, for
`{ch}02` only (main/recording untouched):

- `videoCodecType` → **H.264**, `maxFrameRate` → **1500** (15 fps, centi-fps)
- `videoQualityControlType` → **CBR** and `GovLength` → **30** (~2× fps GOP; fast keyframe recovery)
- **SmartCodec** (`<SmartCodec><enabled>`) → **false** — H.264+/H.265+ is the #1 cause of third-party
  freezes/artifacts on this NVR family

Only tags that already exist are edited (`setTag` never inserts unknown tags → safe across firmware).
After the PUT it **re-GETs and verifies** the codec swap + smart-codec-off actually took (Hikvision can
`200 OK` while silently ignoring fields). The original XML per channel is saved to `NvrStore`
(`saveSubStreamOriginals`, earliest kept across repeated runs) so **Settings → Restore sub-streams**
can PUT it back verbatim.

## EZVIZ cloud PTZ

`data/ezviz/EzvizCloud.kt`, ported from [pyEzviz](https://github.com/BaQs/pyEzviz):

- **Login:** `POST https://{apiDomain}/v3/users/login/v5`, form
  `account / password(md5 hex) / featureCode / msgType=0 / cuName=SGFzc2lv`. On meta code `1100`
  follow `loginArea.apiDomain` and retry; `6002` = 2FA (must be off); `1013/1014/101/1001` = bad creds.
- **PTZ:** `PUT https://{apiDomain}/v3/devices/{serial}/ptzControl` (capital **C** matters), form
  `command=UP|DOWN|LEFT|RIGHT, action=START|STOP, channelNo=1, speed=1..10`, header `sessionId`.
- **Device list:** `GET /v3/userdevices/v1/resources/pagelist` → `deviceInfos[].deviceSerial / name`.
- **The EZVIZ serial = the last 9 chars of the NVR's `<serialNumber>`** for that channel (auto-filled
  by `IsapiClient.cameraSerial`). The 6-letter "verification code" is **not** the account password.
- **Session recovery:** sessions expire server-side after hours idle. The controller
  (`EzvizCloudPtzController`) re-authenticates once and retries on any PTZ failure
  (`invalidateSession()` + re-login) so PTZ doesn't silently die; `stop()` is safety-critical (a
  dropped STOP leaves the camera panning) so it retries 3×, then re-auths and retries again.

## ONVIF

`data/onvif/OnvifPtz.kt`: SOAP 1.2 + WS-UsernameToken digest, syncs to the camera clock first (beats
skew), puts the WS-Action in the Content-Type. Consumer EZVIZ usually expose **no** ONVIF port at all
(verified by scanning), so EZVIZ falls back to the cloud path above.

## Backup file & crypto

`Downloads/HikTVViewer_backup.json` (survives uninstall).

- **Plain** = the settings JSON (`NvrStore.exportJson` = `prefs.all`).
- **Encrypted** = `{"hiktv_enc":1,"salt","iv","iter","data"}` — AES-GCM (128-bit tag), key =
  PBKDF2-HMAC-SHA256(pin, salt, `iter`). `iter` is stored **per file** (currently 210k) so the cost
  can be raised without breaking older backups (pre-`iter` files fall back to 60k). The PIN accepts an
  alphanumeric passphrase. Encrypt/decrypt + file I/O run off the main thread.
- A fresh install needs `READ_EXTERNAL_STORAGE` + `requestLegacyExternalStorage` to read a previous
  install's file.

## Security

- `res/xml/network_security_config.xml` permits cleartext for the LAN NVR (its IP is user-entered, so
  it can't be a static domain allow-list) but does **not** globally trust user-installed CAs — that
  would let a rogue user CA MITM real HTTPS (e.g. EZVIZ cloud login). The NVR's own self-signed cert is
  trusted **in code**, scoped to the configured host (`IsapiClient.trustSelfSigned`).
- Credentials are stored in `EncryptedSharedPreferences` (fails closed — no plaintext fallback) and
  never written to Log/Toast.
- R8 keep rules cover LibVLC (`org.videolan.**`), okhttp-digest, and Tink / `androidx.security.crypto`
  (the last is reflection-based and would otherwise risk a release-only launch crash under R8 full
  mode).

## Gotchas

- TV dialogs need `DialogIme` so **BACK** closes the on-screen keyboard, not the whole popup.
- The Mi remote's "menu/m" key isn't reliably delivered to apps — use D-pad/OK and long-press instead.
- `adb logcat -b crash -d` reads an already-recorded native crash without re-running anything.
- Grid tiles fit the screen (no scroll), so there are no "off-screen" tiles to pause — the memory lever
  is releasing live decoders, not paging tiles.

## Build from source

Requires **Android Studio 2024.1+**, **JDK 17**, the Android SDK.

```bash
# Debug (no signing key needed)
./gradlew assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug           # install to a connected TV via adb
```

For **signed release** APKs, create `keystore.properties` in the project root:

```properties
storeFile=release.keystore
storePassword=YOUR_PASSWORD
keyAlias=YOUR_ALIAS
keyPassword=YOUR_PASSWORD
```

```bash
./gradlew clean assembleRelease   # -> app/build/outputs/apk/release/*.apk  (per-ABI + universal)
```

> The signing key (`release.keystore`, `keystore.properties`) is **not** committed — keep yours
> private and backed up (you need the same key to ship updates). Without it the release is unsigned.

There's a driver skill at `.claude/skills/run-hik-tv-viewer/` (a PowerShell ADB harness) that builds,
installs, launches and screenshots the app on a TV/emulator — handy for automated testing.

## Releasing a new version

1. Bump `versionCode` + `versionName` in `app/build.gradle.kts` (and the README badge).
2. `./gradlew clean assembleRelease` (needs `keystore.properties`).
3. `gh release create vX.Y.Z app/build/outputs/apk/release/*.apk --title "…" --notes "…"`.
4. Install on a TV: `adb install -r app-universal-release.apk`.

Per-ABI APKs are ~21 MB; the universal APK (~80 MB) bundles all ABIs.
