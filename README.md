<div align="center">

# 📺 Hik TV Viewer

### Watch every camera on your Hikvision NVR — live, on your Android TV.

Opens straight to a wall of all your cameras (Hikvision **and** EZVIZ on one NVR).
Built to stay smooth on cheap TV sticks, driven entirely by a normal remote. 📱

![version](https://img.shields.io/badge/version-2.3.6-2D7DF6)
![platform](https://img.shields.io/badge/Android%20TV-6%2B-3DDC84?logo=androidtv&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white)
![LibVLC](https://img.shields.io/badge/video-LibVLC-FF8800)

**[⬇️ Download](../../releases/latest)** · [Setup](#-first-time-setup) · [Remote](#-using-the-remote) · [Fix problems](#-troubleshooting)

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

> **Which APK?** 32-bit TVs (`armv7l`/`armv8l`) → **v7a** or **universal**. Use **arm64-v8a** only if `getprop ro.product.cpu.abi` says `arm64-v8a`.

---

## 🚀 First-time setup

A quick 3-step wizard:

1. **Server** — NVR IP (e.g. `192.168.1.100`), HTTP port `80`, RTSP port `554`. The TV must be on the **same network**.
2. **Sign in** — an NVR account with **Live View + Playback + PTZ**.
3. ✅ Done — it remembers everything and opens straight to your cameras.

> **On the NVR, once:** enable **ISAPI / Hikvision-CGI** and make sure **RTSP port 554** is open.

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
| **◀ ▶** / **OK** | Previous-next camera / popup menu |
| **▲** / **Hold ▼** | Zoom-Pan-PTZ screen / this camera's playback |
| **Back** | Return to the wall |

**🔎 Zoom / Pan / PTZ** (open with **▲** from fullscreen)

| Button | Action |
| :-- | :-- |
| **OK** | Switch mode: Zoom → Pan → PTZ |
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

EZVIZ cameras expose no usable local PTZ — they move only through the EZVIZ cloud, like the EZVIZ app.

1. Open the camera → **OK → Camera settings → "EZVIZ cloud PTZ"**.
2. Enter your **EZVIZ email/phone** (with country code) and **password**.
   - QR-only account? Set a password first in the EZVIZ app.
   - **Turn off 2FA**, or login is blocked. The camera **serial auto-fills** from the NVR.
3. **Test PTZ connection** → then **▲ → OK until it shows PTZ → D-pad moves the camera**.

> PTZ commands go via EZVIZ's servers (needs internet); **live video stays local**. The CS-H8c is pan/tilt only — use Zoom/Pan mode for close-ups.
</details>

---

## ⚡ Performance on weak TVs

H.265 on cheap chips is the hard part — and **different chips need opposite settings**. The app auto-detects the chip and picks the right path, so it should just work.

| Chip (example) | What the app does |
| :-- | :-- |
| **MediaTek** (Xiaomi MiTV) | Hardware H.265 with the copy render path |
| **Amlogic** (Mi Box, S905) | Hardware overlay on single screens; **software grid** (Amlogic greens hardware video on the multi-tile path) |

**Wall still heavy?** Run **⚙️ Settings → Optimize live** — it switches each grid sub-stream to plain **H.264 @ 15 fps, smart codec off, CBR** on the NVR. That smart codec is the #1 cause of freezes/artifacts, so this is the biggest single fix (fullscreen/recording quality is unchanged, and you can **undo it any time with "Restore sub-streams"**). Still off? Try **Video decoding → Software** or **Video rendering → Direct**.

---

## 💾 Backup & restore

**Settings → Backup** saves *everything* (NVR, cameras, alerts, PTZ/EZVIZ accounts, layout) to `Downloads/HikTVViewer_backup.json` — it survives uninstall, so a restored TV is identical.

- Choose a **PIN / passphrase** to encrypt it (AES-GCM), or save it plain.
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

## 🛠️ Build & contribute

Android Studio 2024.1+, JDK 17.

```bash
./gradlew assembleDebug      # → app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # signed per-ABI + universal (needs keystore.properties)
```

The code lives under `app/src/main/java/com/hiktv/viewer/` — **`player/`** (shared LibVLC engine + per-stream tuning), **`data/`** (ISAPI · ONVIF · EZVIZ cloud), **`util/`** (per-chip quirks, backup, crash log) and **`ui/`** (one package per screen).

---

<div align="center">

*Personal / home project. Use with cameras and an NVR you own.* ❤️

</div>
