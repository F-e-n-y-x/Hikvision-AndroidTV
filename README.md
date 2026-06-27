# Hik TV Viewer — Android TV camera app

A simple Android TV app that shows **all the cameras on your Hikvision NVR** on one screen,
live. It opens straight into the camera wall — no menus to dig through. It works with Hikvision
**and** EZVIZ cameras, as long as they are added to the one NVR.

It is built to run smoothly on cheap, low-power TVs and TV sticks.

> **Latest version: 2.2.0**

---

## What it can do

- **Live camera wall** — opens directly to all your cameras in a grid that sizes itself (2×2, 3×3…).
- **Full screen** — open any camera full screen and flick **left/right** to the next camera.
- **PTZ (move the camera)** — for pan/tilt cameras like the EZVIZ H8c, move the camera with the
  D-pad. Works through the NVR, or **directly to the camera over ONVIF** when the NVR can't do it.
- **Zoom & pan** — zoom into any camera and pan around the zoomed picture.
- **Playback** — watch recorded footage on a timeline (12-hour clock with AM/PM), scrub back and forth.
- **Snapshots** — save a still picture from a camera.
- **Motion alerts** — get a badge/notification when a camera you picked sees motion.
- **Picture-in-picture** — keep a camera floating in a corner.
- **Safe & private** — your NVR login is stored encrypted on the device. Everything stays on your
  home network; no cloud account is used.

---

## Install it on your TV

1. Go to the **[Releases](../../releases)** tab of this repo.
2. Download the APK:
   - **Not sure which one? Download `app-universal-release.apk`** — it works on any TV.
   - For smaller downloads: `app-armeabi-v7a-release.apk` (most TV sticks/boxes) or
     `app-arm64-v8a-release.apk` (newer 64-bit TVs).
3. Copy the APK to your TV and install it. Easy ways to do that:
   - Use a "Send files to TV" / "File Commander" app, **or**
   - Install over USB debugging with `adb install app-universal-release.apk`.
4. Open **Hik TV Viewer** from your TV's apps.

> Android TV may warn about "unknown sources" — allow it for the file manager you used.

---

## First-time setup

When you open the app the first time, it asks for your NVR details:

1. **NVR IP address** — the local IP of your Hikvision NVR (for example `192.168.1.100`).
   Your TV must be on the **same Wi-Fi/network** as the NVR.
2. **HTTP port** — usually `80`.
3. **RTSP port** — usually `554`.
4. **Username / Password** — an NVR account that is allowed to view live, playback and PTZ.
5. Press **Connect**.

After this, the app remembers everything and opens straight to the camera wall next time.

### On the NVR (one-time, in the NVR's own web page)

- Turn on **ISAPI / Hikvision-CGI** (Configuration → Network → Advanced → Integration Protocol).
- Use an account with **Live View + Playback + PTZ** permission.
- Make sure the **RTSP port (554)** is open.

---

## How to use the remote

This app is made for a normal Android TV remote (like the Mi remote) — just a **D-pad, OK, Back,
and Volume**. You don't need a special remote.

### Camera wall (grid)

| Button | What it does |
|---|---|
| D-pad | Move between cameras |
| **OK** | Open the selected camera full screen |
| **Hold ◀ (left)** | Open Settings |

### Full screen (one camera)

| Button | What it does |
|---|---|
| **◀ / ▶** | Previous / next camera |
| **OK** | Open this camera's controls & settings |
| **▲ (up)** | Quick actions (snapshot, audio, picture-in-picture) |
| **Hold ▼ (down)** | Open Playback (recorded footage) |
| **Hold ◀ (left)** | Open Settings |
| **Back** | Return to the wall |

### Camera controls screen (zoom / pan / PTZ)

| Button | What it does |
|---|---|
| **OK** | Switch mode: **Zoom → Pan → PTZ → Zoom** (PTZ only shows if the camera supports it) |
| **▲ ▼ / Volume +−** | Zoom in / out |
| **D-pad** | In **Pan** mode: move around the zoomed picture. In **PTZ** mode: move the camera |
| **Back** | Exit |

### Playback (recorded footage)

| Button | What it does |
|---|---|
| **◀ / ▶** | Scrub back / forward on the timeline |
| **▲ / ▼** | Jump ± 1 hour |
| **OK** | Play / pause |
| **Back** | Exit |

The timeline bar shrinks out of the way a few seconds after playback starts, so it doesn't cover
the video.

---

## Pan/tilt (PTZ) cameras like the EZVIZ H8c

Some cameras (e.g. **EZVIZ CS-H8c**) can pan and tilt, but the NVR won't pass those controls
through. The app can talk **directly to the camera over ONVIF** instead.

1. In the **EZVIZ app**, turn on **ONVIF** for the camera and **create an ONVIF username and
   password**. ⚠️ This is a *separate* login — not your device password or the verification code.
2. In Hik TV Viewer: open the camera (press **OK**) → **Direct camera connection**.
   - The camera's IP is filled in automatically when the NVR knows it.
   - Enter the **ONVIF username and password**, keep **"Use ONVIF"** ticked, and **Save**.
3. Tap **Test PTZ connection** — it tells you exactly what works (clock, login, profile, a test move).
4. Now open **Camera controls**, press **OK** until it shows **PTZ**, and use the D-pad to move the camera.

> The H8c has no optical zoom (it only pans/tilts). Use **Zoom/Pan** mode for digital zoom.

---

## Make the live view smoother (recommended)

If your cameras record in **H.265**, showing several of them at once can be heavy for a cheap TV.
There's a one-tap fix:

**Settings → "Optimize live (smooth)"** — this sets each camera's small "sub-stream" (the one used
in the grid) to **H.264 at 15 fps**. That decodes much more easily, so the wall stays smooth.
Your full-screen / recording quality is **not** changed.

If video ever looks broken or the TV struggles, open **Settings → Video decoding** and try
**Software** mode.

---

## Build it yourself (for developers)

You need **Android Studio (2024.1+)** with **JDK 17** and the Android SDK.

```bash
# debug build (no signing needed)
./gradlew assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug           # install to a connected TV via adb
```

To build a **signed release** (the APKs in the Releases tab), create a `keystore.properties`
file in the project root pointing to your own signing key:

```properties
storeFile=release.keystore
storePassword=YOUR_PASSWORD
keyAlias=YOUR_ALIAS
keyPassword=YOUR_PASSWORD
```

Then:

```bash
./gradlew assembleRelease        # -> app/build/outputs/apk/release/*.apk
```

> The signing key (`release.keystore`, `keystore.properties`) is **not** in this repo on purpose —
> keep yours private. Without it, the release build is unsigned.

The build also produces per-CPU APKs (`armeabi-v7a`, `arm64-v8a`, `x86`) plus a `universal` one.

---

## How it works (tech)

- **Kotlin**, single Hikvision NVR, all cameras are NVR channels.
- **LibVLC** plays the RTSP streams (handles both H.264 and H.265, with hardware decoding and
  low-latency tuning). This is why LibVLC is used instead of ExoPlayer — ExoPlayer's RTSP doesn't
  reliably play H.265.
- **Hikvision ISAPI** (digest auth) is used to find cameras, take snapshots, search recordings and
  send PTZ. **ONVIF** (SOAP) is used for direct-to-camera PTZ.
- The grid uses each camera's small **sub-stream**; full screen uses the high-quality main stream.
- Streams are released when you leave the screen, so a weak TV is never decoding more than it must.

```
app/src/main/java/com/hiktv/viewer/
├─ core/Session.kt              the active NVR + camera list (in memory)
├─ data/
│  ├─ model/{Nvr,Camera}.kt     connection + camera info
│  ├─ store/NvrStore.kt         encrypted settings storage
│  ├─ RtspUrls.kt               builds the live / playback stream links
│  ├─ isapi/IsapiClient.kt      talks to the NVR (discover, PTZ, snapshot, recordings)
│  ├─ onvif/OnvifPtz.kt         direct-to-camera PTZ over ONVIF
│  └─ ptz/PtzController.kt      one PTZ interface (NVR / direct ISAPI / ONVIF)
└─ ui/
   ├─ setup/SetupActivity.kt        first-run connection screen
   ├─ grid/                         the live camera wall
   ├─ fullscreen/                   one camera, switch with ◀ ▶
   ├─ camera/CameraSettingsActivity per-camera page
   ├─ control/ControlActivity       zoom / pan / PTZ
   ├─ playback/                     recorded footage + timeline
   └─ settings/SettingsActivity     app settings
```

---

## Troubleshooting

- **"No cameras found"** — the NVR account is missing Live View permission, or ISAPI is turned off.
- **Wrong password / locked out** — Hikvision locks an account after several wrong tries; wait or
  unlock it in the NVR web page.
- **A tile is black but shows the name** — that camera is offline or its sub-stream is disabled.
- **PTZ doesn't move** — open the camera → **Test PTZ connection**; for EZVIZ make sure you used the
  **ONVIF** username/password, not the device password.
- **Video glitches or the TV struggles** — run **Settings → Optimize live (smooth)**, or set
  **Video decoding → Software**.

---

*Personal/home project. Use with cameras and an NVR you own.*
