# Hik TV Viewer — Android TV camera app

A simple Android TV app that shows **all the cameras on your Hikvision NVR** on one screen,
live. It opens straight into the camera wall — no menus to dig through. It works with Hikvision
**and** EZVIZ cameras, as long as they are added to the one NVR.

It is built to run smoothly on cheap, low-power TVs and TV sticks, and is designed for a normal
remote (D-pad + OK + Back + Volume — no special remote needed).

> **Latest version: 2.3.0**

---

## What it can do

- **Live camera wall** — opens directly to all your cameras in a grid that sizes itself (2×2, 3×3…).
- **Full screen** — open any camera and flick **left/right** to the next camera.
- **Zoom, Pan & PTZ** — digital zoom up to **5×** and pan around the zoomed image on any camera;
  real pan/tilt for PTZ cameras (Hikvision via the NVR, **EZVIZ via the EZVIZ cloud**).
- **Recorded playback** — a 12-hour timeline (AM/PM) you can scrub; the bar auto-hides while playing.
- **Synced multi-camera playback** — play *all* cameras from the **same point in time** at once.
- **Snapshots, motion alerts, picture-in-picture.**
- **Backup & Restore** — save all your settings so you never re-enter them after a reinstall.
- **Safe & private** — your logins are stored encrypted on the device; live video stays on your
  home network (only EZVIZ PTZ uses the EZVIZ cloud, exactly like the EZVIZ app does).

---

## Install it on your TV

1. Go to the **[Releases](../../releases)** tab and download the APK:
   - **Not sure which one? Download `app-universal-release.apk`** — it runs on any TV.
   - Smaller options: `app-armeabi-v7a-release.apk` (most TV sticks/boxes) or
     `app-arm64-v8a-release.apk` (newer 64-bit TVs).
2. Copy it to your TV and install (via a "Send files to TV" app, or `adb install <file>.apk`).
3. Open **Hik TV Viewer** from your TV's apps.

---

## First-time setup

The first launch is a simple **TV wizard**. From the welcome screen pick:

- **Set up new connection** — a 2-step form:
  1. **Server** — NVR IP address (e.g. `192.168.1.100`), HTTP port (usually `80`), RTSP port
     (usually `554`). Your TV must be on the **same network** as the NVR.
  2. **Sign in** — an NVR username/password with Live View + Playback + PTZ permission → **Connect**.
- **Restore from backup** — if you have a backup file (see below), pick it from the list (or
  **Browse files…**) to bring back all your settings instantly. Grant the storage permission when asked.

BACK steps the wizard back a page. On the NVR (one-time, in its web page): enable
**ISAPI / Hikvision-CGI**, use an account with **Live View + Playback + PTZ**, and make sure the
**RTSP port (554)** is open.

---

## How to use the remote

### Camera wall (grid)

| Button | What it does |
| --- | --- |
| D-pad | Move between cameras |
| **OK** | Open the selected camera full screen |
| **Hold ◀ (left)** | Open Settings |
| **Hold ▼ (down)** | Synced multi-camera playback |

### Full screen (one camera)

| Button | What it does |
| --- | --- |
| **◀ / ▶** | Previous / next camera |
| **OK** | Popup menu (camera settings, zoom/PTZ, playback, snapshot, audio, PiP, app settings) |
| **▲ (up)** | Open the Zoom / Pan / PTZ screen |
| **Hold ▼ (down)** | Open this camera's playback |
| **Hold ◀ (left)** | Open Settings |
| **Back** | Return to the wall |

### Zoom / Pan / PTZ screen

| Button | What it does |
| --- | --- |
| **OK** | Switch mode: **Zoom → Pan → PTZ → Zoom** (PTZ only if the camera supports it) |
| **▲ ▼ / Volume +−** | Zoom in / out (up to 5×) |
| **D-pad** | In **Pan** mode: move around the zoomed picture · In **PTZ** mode: move the camera |
| **Back** | Exit |

### Playback (one camera) / Multi-camera playback

| Button | What it does |
| --- | --- |
| **◀ / ▶** | Scrub back / forward |
| **▲ / ▼** | Jump ± 1 hour |
| **OK** | Play / pause |
| **Back** | Exit |

---

## PTZ on EZVIZ cameras (e.g. CS-H8c)

EZVIZ cameras don't allow local PTZ — not over ONVIF and not through the NVR (verified). The only
way to move them is the **EZVIZ cloud**, exactly like the EZVIZ phone app. The app supports this:

1. Open the camera → **OK → Camera settings → "EZVIZ cloud PTZ"**.
2. Enter your **EZVIZ email or phone number** (with country code, e.g. `+91…`) and **password**.
   - If you only sign in by QR code, set a password first in the EZVIZ app
     (Profile → Account Security). Turn **off 2FA**.
   - The **camera serial is auto-filled** from the NVR. (You can also use **"Auto-fill EZVIZ
     serial"** to pick the camera from your EZVIZ account.)
3. Tap **Test PTZ connection** — it logs in, sends a small move, and reports the result.
4. Then **▲ → OK until it shows PTZ → D-pad moves the camera** (pan/tilt; the H8c has no optical
   zoom, so use Zoom/Pan mode for close-ups).

> PTZ commands go through EZVIZ's servers (needs internet); live video stays local.

For a true ONVIF camera (e.g. a Hikvision PT camera), use **"Direct camera connection"** instead
(IP + ONVIF/ISAPI credentials) and PTZ works fully on the LAN.

---

## Backup & Restore

So you never type everything again — the backup captures **every setting** (NVR, cameras, alerts,
PTZ / EZVIZ account + serials, layout) so a restored device is identical:

- **Settings → Backup settings** writes to **Downloads/HikTVViewer_backup.json**. It asks whether
  to **protect it with a PIN** (AES-encrypted) or save it plain. The file survives an uninstall.
- **Restore** — either **Settings → Restore settings**, or the **Restore from backup** button on
  the first-run wizard. If the backup is PIN-encrypted you'll be asked for the PIN.
- ⚠️ A non-encrypted backup contains your passwords in plain text — keep the file private. A
  forgotten PIN on an encrypted backup can't be recovered.

---

## Make the live view smoother

If your cameras record in **H.265**, several at once can be heavy for a cheap TV. One-tap fix:
**Settings → "Optimize live (smooth)"** sets each camera's grid sub-stream to **H.264 @ 15 fps**
(full-screen / recording quality is unchanged). If video ever looks broken, try
**Settings → Video decoding → Software**.

### Green or corner-cropped video (Amlogic / Mi Box)

Some TV chips (Amlogic — Mi Box, S905 family) render hardware **H.265** as a green or corner-cropped
picture; others (e.g. Xiaomi MiTV) need the opposite. The app **auto-detects Amlogic** and uses the
right render path, so it should just work. If a device still shows green or laggy full-screen video,
flip **Settings → Video rendering** between **Compatible** and **Direct** — it's a per-TV setting and
doesn't affect your other TVs.

---

## Build it yourself (developers)

Needs **Android Studio (2024.1+)**, **JDK 17**, Android SDK.

```bash
./gradlew assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug           # install to a connected TV via adb
```

For a **signed release**, create `keystore.properties` in the project root pointing at your own key:

```properties
storeFile=release.keystore
storePassword=YOUR_PASSWORD
keyAlias=YOUR_ALIAS
keyPassword=YOUR_PASSWORD
```

```bash
./gradlew assembleRelease         # -> app/build/outputs/apk/release/*.apk (per-ABI + universal)
```

> The signing key (`release.keystore`, `keystore.properties`) is **not** in this repo on purpose —
> keep yours private. Without it, the release build is unsigned.

---

## How it works (tech)

- **Kotlin**, single Hikvision NVR; all cameras are NVR channels.
- **LibVLC** plays the RTSP streams (H.264 and H.265, hardware decoding, low-latency tuning).
  ExoPlayer's RTSP doesn't reliably do H.265, which is why LibVLC is used.
- **Hikvision ISAPI** (digest auth) for discovery, snapshots, recordings and Hikvision PTZ;
  **ONVIF** (SOAP) for direct-to-camera PTZ; **EZVIZ cloud** for EZVIZ PTZ.
- Grid uses each camera's small **sub-stream**; full screen uses the high-quality main stream.
  Streams are released when you leave a screen so a weak TV never decodes more than it must.
- **Per-chip render path**: MediaCodec "direct rendering" greens on some chips (MiTV → copy path)
  and is *required* on others (Amlogic / Mi Box → zero-copy). `DeviceQuirks` auto-detects Amlogic;
  a manual **Video rendering** setting overrides it per TV.

```text
app/src/main/java/com/hiktv/viewer/
├─ core/Session.kt              active NVR + camera list (in memory)
├─ data/
│  ├─ model/{Nvr,Camera}.kt     connection + camera info
│  ├─ store/NvrStore.kt         encrypted settings + backup/restore JSON
│  ├─ RtspUrls.kt               builds live / playback stream links
│  ├─ isapi/IsapiClient.kt      NVR API (discover, PTZ, snapshot, recordings, optimize)
│  ├─ onvif/OnvifPtz.kt         direct-to-camera PTZ over ONVIF
│  ├─ ezviz/EzvizCloud.kt       EZVIZ cloud login + PTZ + device list
│  └─ ptz/PtzController.kt      one PTZ interface (NVR / ISAPI / ONVIF / EZVIZ cloud)
├─ player/{PlayerEngine,CameraStream}.kt   shared LibVLC + one stream per surface
├─ util/
│  ├─ DeviceQuirks.kt           per-chip workarounds (Amlogic render path)
│  ├─ BackupManager.kt          read/write the backup file in Downloads
│  └─ BackupCrypto.kt           optional PIN (AES-GCM) backup encryption
└─ ui/
   ├─ setup/         first-run wizard (welcome / server / sign-in / restore picker)
   ├─ grid/          live camera wall
   ├─ fullscreen/    one camera, switch with ◀ ▶
   ├─ camera/        per-camera settings page
   ├─ control/       zoom / pan / PTZ
   ├─ playback/      recorded footage (single + synced multi-camera)
   └─ settings/      app settings, backup/restore, optimize
```

---

## Troubleshooting

- **"No cameras found"** — the NVR account lacks Live View permission, or ISAPI is off.
- **Green / corner-cropped full-screen video** — toggle **Settings → Video rendering**
  (Compatible ↔ Direct). Amlogic/Mi Box is auto-set to Direct; if a chip still misbehaves, switch it.
- **A tile stays black** — that camera is offline or its sub-stream is disabled.
- **EZVIZ PTZ doesn't move** — open the camera → **Test PTZ connection**; use your EZVIZ
  **account** login (not the device verification code), make sure the serial is right, and 2FA is off.
- **Video glitches / TV struggles** — run **Settings → Optimize live (smooth)**, or set
  **Video decoding → Software**.
- **Restore button missing on first boot** — grant the storage permission when prompted (it's
  needed to read the backup file); or restore later via **Settings → Restore settings**.

---

*Personal/home project. Use with cameras and an NVR you own.*
