<div align="center">

# 📺 Hik TV Viewer

### Watch every camera on your Hikvision NVR — live, on your Android TV.

Opens straight to a wall of all your cameras (Hikvision **and** EZVIZ on one NVR).
Built to stay smooth on cheap TV sticks, driven entirely by a normal remote. 📱

![version](https://img.shields.io/badge/version-2.3.6-2D7DF6)
![platform](https://img.shields.io/badge/Android%20TV-6%2B-3DDC84?logo=androidtv&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white)
![LibVLC](https://img.shields.io/badge/video-LibVLC-FF8800)

**[⬇️ Download](../../releases/latest)** · [Setup](#-first-time-setup) · [Remote](#-using-the-remote) · [Fix problems](#-troubleshooting) · [For developers](#-how-it-works)

</div>

---

## ✨ What you get

| | |
| :-- | :-- |
| 🟩 **Live wall** | Every camera at once, auto-sized (2×2, 3×3 …), opens instantly |
| 🔍 **Fullscreen** | Any camera — flick **◀ ▶** to the next |
| 🔎 **Zoom + PTZ** | 5× digital zoom & pan, real pan/tilt where supported |
| ⏪ **Playback** | Scrub a 12-hour timeline — or **all cameras synced** to the same moment |
| 🔔 **Alerts + PiP** | Motion notifications, picture-in-picture, snapshots |
| 🔒 **Private** | Logins encrypted on-device; video never leaves your LAN |

---

## ⬇️ Install

Grab an APK from **[Releases](../../releases/latest)** — **not sure which? Take `app-universal-release.apk`** (runs on any TV).

```bash
adb connect <tv-ip>:5555          # accept the prompt on the TV
adb install -r app-universal-release.apk
```

…or copy it across with a "Send files to TV" app, then open **Hik TV Viewer** from the apps row.

> **Which APK?** 32-bit TVs (`armv7l`/`armv8l`) → **v7a** or **universal**. Use **arm64-v8a** only if `getprop ro.product.cpu.abi` says `arm64-v8a`. Per-ABI APKs are ~21 MB; the universal APK (~80 MB) bundles all ABIs.

---

## 🚀 First-time setup

A quick 3-step wizard:

1. **Server** — NVR IP (e.g. `192.168.1.100`), HTTP port `80`, RTSP port `554`. The TV must be on the **same network**.
2. **Sign in** — an NVR account with **Live View + Playback + PTZ**.
3. ✅ Done — it remembers everything and opens straight to your cameras. **Back** steps the wizard back.

> **On the NVR, once:** enable **ISAPI / Hikvision-CGI**, use an account with **Live View + Playback + PTZ**, and make sure **RTSP port 554** is open.

---

## 🎮 Using the remote

A plain Android TV remote — **D-pad · OK · Back · Volume**. No special remote needed.

**📺 Camera wall**

| Button | Action |
| :-- | :-- |
| **D-pad** / **OK** | Move between cameras / open one fullscreen |
| **Hold ◀** / **Hold ▼** | Settings / synced multi-camera playback |

**🔍 Fullscreen**

| Button | Action |
| :-- | :-- |
| **◀ ▶** / **OK** | Previous-next camera / popup menu (PTZ, zoom, playback, snapshot, PiP…) |
| **▲** / **Hold ▼** | Zoom-Pan-PTZ screen / this camera's playback |
| **Back** | Return to the wall |

**🔎 Zoom / Pan / PTZ** (open with **▲** from fullscreen)

| Button | Action |
| :-- | :-- |
| **OK** | Switch mode: Zoom → Pan → PTZ (PTZ only if the camera supports it) |
| **▲▼ / Vol** | Zoom in-out · **D-pad** pans the image (Pan) or moves the camera (PTZ) |

**⏪ Playback** — **◀ ▶** scrub · **▲ ▼** jump ±1h · **OK** play/pause · **Back** exit.

---

## 🕹️ PTZ — moving the camera

The app figures out the right path automatically; **Test PTZ connection** on the camera page reports exactly what works.

| Camera | How it moves |
| :-- | :-- |
| Hikvision PTZ on the NVR | Through the NVR over **ISAPI** — automatic |
| Any ONVIF PTZ camera | **Direct connection** → camera IP + ONVIF user/pass |
| **EZVIZ** (e.g. CS-H8c) | **EZVIZ cloud** — the only way these move (see below) |

<details>
<summary>🔧 <b>EZVIZ cloud PTZ setup</b> (click to expand)</summary>

<br>

EZVIZ cameras expose **no usable local PTZ** (not ONVIF, not via the NVR — verified). They move only through the EZVIZ cloud, exactly like the EZVIZ phone app.

1. Open the camera → **OK → Camera settings → "EZVIZ cloud PTZ"**.
2. Enter your **EZVIZ email or phone** (with country code, e.g. `+91…`) and **password**.
   - QR-code-only account? Set a password first in the EZVIZ app (Profile → Account Security).
   - **Turn off 2FA** on the account, or login is blocked.
   - The camera **serial auto-fills** from the NVR (or use **Auto-fill EZVIZ serial**).
3. **Test PTZ connection** → it logs in, nudges the camera, and reports the result.
4. Then **▲ → OK until it shows PTZ → D-pad moves the camera**.

> PTZ commands go through EZVIZ's servers (needs internet); **live video stays local**. The CS-H8c is pan/tilt only (no optical zoom) — use Zoom/Pan mode for close-ups.
</details>

---

## ⚡ Performance on weak TVs

H.265 on cheap chips is the hard part — and **different chips need opposite settings**. The app auto-detects the chip and picks the right path, so it should just work.

| Chip (example) | What the app does |
| :-- | :-- |
| **MediaTek** (Xiaomi MiTV) | Hardware H.265 with the copy render path (its zero-copy path is all-green) |
| **Amlogic** (Mi Box, S905) | Hardware overlay on single screens; **software grid** (Amlogic greens hardware video on the multi-tile path) |

**Wall still heavy?** Run **⚙️ Settings → Optimize live** — it switches each grid sub-stream to plain **H.264 @ 15 fps, smart codec off, CBR** on the NVR. That smart codec is the #1 cause of freezes/artifacts, so this is the biggest single fix (fullscreen/recording quality is unchanged, and you can **undo it any time with "Restore sub-streams"**). Still off? Try **Video decoding → Software** or **Video rendering → Direct**.

---

## 💾 Backup & restore

**Settings → Backup** saves *everything* (NVR, cameras, alerts, PTZ/EZVIZ accounts + serials, layout) to `Downloads/HikTVViewer_backup.json` — it survives uninstall, so a restored TV is identical.

- Choose a **PIN / passphrase** to encrypt it (AES-GCM), or save it plain.
- **Restore** from the first-run wizard's **Restore from backup** button, or **Settings → Restore**.
- ⚠️ A plain backup holds passwords in clear text — keep it private. A forgotten PIN can't be recovered.

---

## ⚙️ Settings

| Setting | What it does |
| :-- | :-- |
| Connection · Refresh · Set count | NVR connection and the camera list |
| Grid layout · Video decoding · Video rendering | Per-TV video tuning |
| Optimize live · Restore sub-streams | Lighten the wall / undo it |
| Alerts | Motion/area notifications, per camera |
| Backup · Restore | Save / load everything (optional PIN) |
| Diagnostics · Last crash | Check the NVR / view the last app crash on this TV |

---

## 🔧 Troubleshooting

| Problem | Fix |
| :-- | :-- |
| "No cameras found" | The NVR account lacks Live View, or ISAPI is off |
| Green / cropped fullscreen | **Settings → Video rendering** (Compatible ↔ Direct) |
| A tile stays black | That camera is offline or its sub-stream is disabled |
| Wall laggy / glitchy | **Optimize live**, then **Video decoding → Software** |
| EZVIZ PTZ won't move | Camera → **Test PTZ** · use the EZVIZ **account** (not the verify code) · turn off 2FA |
| App crashed on a TV | **Settings → Last crash** — or `adb logcat -b crash -d` for native crashes |

---

## 🛠️ Build from source

Requires **Android Studio 2024.1+**, **JDK 17**, and the Android SDK.

```bash
./gradlew assembleDebug      # → app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug       # install to a connected TV via adb
```

To build the **signed release** APKs, create `keystore.properties` in the project root:

```properties
storeFile=release.keystore
storePassword=YOUR_PASSWORD
keyAlias=YOUR_ALIAS
keyPassword=YOUR_PASSWORD
```

```bash
./gradlew clean assembleRelease   # → app/build/outputs/apk/release/*.apk  (per-ABI + universal)
```

> The signing key (`release.keystore`, `keystore.properties`) is **not** committed — keep yours private and backed up (you need the same key to ship updates). There's also a driver skill at `.claude/skills/run-hik-tv-viewer/` (a PowerShell ADB harness) that builds, installs, launches and screenshots the app.

**To release:** bump `versionCode` + `versionName` in `app/build.gradle.kts` (and the badge above) → `./gradlew clean assembleRelease` → `gh release create vX.Y.Z app/build/outputs/apk/release/*.apk`.

---

## 🧠 How it works

- **Kotlin**, one Hikvision NVR; every camera is an NVR channel (RTSP id = `channel*100 + stream`, 101 = ch1 main, 102 = ch1 sub).
- **[LibVLC](https://www.videolan.org/vlc/libvlc.html)** plays the RTSP streams (H.264 **and** H.265, hardware decode, low-latency). ExoPlayer's RTSP doesn't reliably do H.265 — that's why LibVLC is used.
- **ISAPI** (HTTP Digest) for discovery, snapshots, recordings, Hikvision PTZ and stream config; **ONVIF** (SOAP) for direct-to-camera PTZ; **EZVIZ cloud** for EZVIZ PTZ.
- The **grid** uses each camera's small **sub-stream**; **fullscreen** uses the high-res main stream. Streams are released when you leave a screen, so a weak TV never decodes more than it must.

```text
Remote ─▶ Activity (grid / fullscreen / control / playback / settings)
                │
                ├─ Session ............ in-memory NVR + camera list
                ├─ NvrStore ........... encrypted settings + backup JSON
                ├─ IsapiClient ........ NVR REST (discover / PTZ / snapshot / search / optimize)
                ├─ OnvifPtz / EzvizCloud / PtzController .... PTZ backends
                └─ CameraStream ─▶ PlayerEngine (one shared LibVLC) ─▶ Surface/TextureView
```

<details>
<summary>📁 <b>Project structure</b></summary>

```text
app/src/main/java/com/hiktv/viewer/
├─ core/Session.kt              active NVR + camera list (in memory)
├─ data/
│  ├─ model/{Nvr,Camera}.kt     connection + camera info
│  ├─ store/NvrStore.kt         encrypted settings + backup/restore JSON
│  ├─ RtspUrls.kt               builds live / playback stream links
│  ├─ isapi/IsapiClient.kt      NVR API: discover, PTZ, snapshot, recordings, optimize, serial
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
│  ├─ CrashLog.kt               file-based crash capture (Settings → Last crash)
│  ├─ BackupManager.kt          read/write the backup file in Downloads
│  ├─ BackupCrypto.kt           optional PIN (AES-GCM, PBKDF2) backup encryption
│  ├─ DialogIme.kt              TV keyboard BACK handling for dialogs
│  ├─ SnapshotCache.kt          disk JPEG cache for instant grid previews
│  └─ Notifications.kt          motion/area notification channel
└─ ui/
   ├─ setup/ · grid/ · fullscreen/ · camera/ · control/ · playback/ · settings/
```
</details>

<details>
<summary>🧩 <b>Developer & AI hand-off notes</b> — hard-won, read before extending</summary>

<br>

> Non-obvious facts that took real debugging to learn. If you fork this and continue (with or without an AI agent), read this first.

**Architecture rules**

- One NVR only. All cameras (Hikvision + EZVIZ) are reached as NVR **channels**.
- All settings live in **one** `EncryptedSharedPreferences` via `NvrStore`. The backup is just `prefs.all` serialized to JSON, so it's automatically complete — don't add a second prefs store.
- Streams are released on `onStop` and rebuilt on `onStart` for every video screen; this is what keeps weak TVs from running out of decoder/surface sessions. Keep that pattern.
- Blocking LibVLC `stop()/release()` runs on a shared background thread (`CameraStream.TEARDOWN`) so tearing down N grid tiles at once from `onStop` can't ANR the UI.

**Per-chip video** (`util/DeviceQuirks.isAmlogic`, reads `Build.HARDWARE` / `ro.board.platform`)

| | MediaTek (MiTV) | Amlogic (Mi Box) |
| --- | --- | --- |
| Hardware H.265, zero-copy | green | green/corner + Mali SIGSEGV |
| Hardware H.265, copy (`:no-mediacodec-dr`) | **clean** | green/corner |
| Hardware, `:vout=android_display` overlay (single surface) | n/a | **clean + realtime** |
| Multi-tile grid hardware | ok (copy) | **green** (overlay can't clip N tiles → software) |

So: Amlogic → `directRender` + per-stream `:vout=android_display` on single-surface screens, SurfaceView (not TextureView), **software grid and software synced-playback** (the multi-tile overlay can't clip N cells). MiTV → copy path. Concurrent hardware decoders are capped by a real probe (`util/DecoderCaps`, `getMaxSupportedInstances`) before synced playback spins up N main streams — cheap SoCs allow only ~2-4 HEVC sessions.

**EZVIZ cloud PTZ** (`data/ezviz/EzvizCloud.kt`, ported from [pyEzviz](https://github.com/BaQs/pyEzviz))

- Login: `POST https://{apiDomain}/v3/users/login/v5`, form `account / password(md5 hex) / featureCode / msgType=0 / cuName=SGFzc2lv`; on code `1100` follow `loginArea.apiDomain` and retry; `6002` = 2FA.
- PTZ: `PUT https://{apiDomain}/v3/devices/{serial}/ptzControl` (capital **C**), form `command=UP|DOWN|LEFT|RIGHT, action=START|STOP, channelNo=1, speed=1..10`, header `sessionId`.
- The EZVIZ serial = the **last 9 chars of the NVR's `<serialNumber>`** for that channel (auto-filled). The 6-letter "verification code" is NOT the account password.
- Sessions expire after hours idle; the controller re-authenticates and retries once on any PTZ failure so PTZ doesn't silently die.

**ONVIF** (`data/onvif/OnvifPtz.kt`): SOAP 1.2 + WS-UsernameToken digest, syncs to the camera clock first, puts the WS-Action in the Content-Type. Consumer EZVIZ usually expose **no** ONVIF port, so EZVIZ falls back to the cloud path.

**ISAPI endpoints** (`data/isapi/IsapiClient.kt`): `ContentMgmt/InputProxy/channels` (names + ip + serial), `Streaming/channels[/{id}]` (online + per-stream config + the "Optimize" PUT — H.264, SmartCodec off, CBR/GOP, then re-GET to verify), `PTZCtrl/channels/{ch}/...`, `Streaming/channels/{id}/picture` (snapshot), `ContentMgmt/search` (recordings), `Event/notification/alertStream` (events), `System/deviceInfo` (connectivity). Short calls use a bounded-timeout client; the alertStream uses a 35 s-read client.

**Backup file**: `Downloads/HikTVViewer_backup.json`. Plain = the settings JSON; encrypted = `{"hiktv_enc":1,"salt","iv","iter","data"}` (AES-GCM, PBKDF2-SHA256; `iter` stored so cost can rise without breaking old files).

**Gotchas**: TV dialogs need `DialogIme` so BACK closes the keyboard not the popup · the Mi remote's "menu/m" key isn't reliably delivered, so use D-pad/OK and long-press · `adb logcat -b crash -d` reads a native crash after the fact; Java/Kotlin crashes are also saved to a file and shown in **Settings → Last crash** (a JVM handler can't catch native SIGSEGVs).
</details>

---

## 💡 Limitations & ideas

- **Amlogic multi-camera grid** is software-decoded (the chip greens hardware video on the multi-tile path), so it's heavier than on other TVs — lighten it with **Optimize live**.
- EZVIZ PTZ depends on EZVIZ's **undocumented cloud API** and needs internet.
- Possible future additions: auto-sequence/cycle mode, an events timeline that jumps into playback.

---

<div align="center">

*Personal / home project. Use with cameras and an NVR you own.* ❤️

</div>
