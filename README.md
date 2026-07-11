<div align="center">

# 📺 Hik TV Viewer

### Watch every camera on your Hikvision NVR — live, on your Android TV.

Opens straight to a wall of all your cameras (Hikvision **and** EZVIZ on one NVR).
Built to stay smooth on cheap TV sticks, driven entirely by a normal remote. 📱

![version](https://img.shields.io/badge/version-2.4.0-2D7DF6)
![platform](https://img.shields.io/badge/Android%20TV-6%2B-3DDC84?logo=androidtv&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white)
![LibVLC](https://img.shields.io/badge/video-LibVLC%203.7.5-FF8800)

**[⬇️ Download](../../releases/latest)** · [Setup](#-first-time-setup) · [Remote](#-using-the-remote) · [Fix problems](#-troubleshooting) · [Changelog](CHANGELOG.md) · [Dev docs](docs/DEVELOPERS.md)

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
| **▲** / **Hold ▼** | Zoom-Pan-PTZ overlay / this camera's playback |
| **Back** | Return to the wall |

**🔎 Zoom / Pan / PTZ** (open with **▲** from fullscreen)

Opens **instantly, right on the live picture** — it reuses the stream already playing, so there's nothing to reconnect.

| Button | Action |
| :-- | :-- |
| **OK** | Switch mode: Zoom → Pan → PTZ (PTZ only if the camera supports it) |
| **▲▼ / Vol** | Zoom in-out · **D-pad** pans the image (Pan) or moves the camera (PTZ) |
| **Back** | Close the overlay (back to plain fullscreen) |

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

Decode threads are **capped per tile**, so a 4-camera wall doesn't spawn ~20 decode threads on a 4-core TV — that thrash used to leave the wall black for minutes before the first frame.

> **Mi Box / Amlogic owners:** the old `libGLES_mali` crash (app dying on the wall, in fullscreen, or looping on launch) is **fixed in 2.4.0**. If you're on an older build, update.

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

## 🧠 For developers

- **Kotlin**, one Hikvision NVR; every camera is an NVR channel. **[LibVLC](https://www.videolan.org/vlc/libvlc.html)** plays the RTSP streams (H.264 + H.265). ISAPI for control, ONVIF / EZVIZ cloud for PTZ.
- The **grid** uses each camera's sub-stream; **fullscreen** uses the main stream; streams are released when you leave a screen so a weak TV never over-decodes.

```text
Remote ─▶ Activity (grid / fullscreen / control / playback / settings)
                │
                ├─ Session ......... in-memory NVR + camera list
                ├─ NvrStore ....... encrypted settings + backup JSON
                ├─ IsapiClient .... NVR REST (discover / PTZ / snapshot / optimize)
                └─ CameraStream ─▶ PlayerEngine (one shared LibVLC) ─▶ Surface/TextureView
```

📖 **Everything else — project layout, the per-chip video rules, the LibVLC pipeline (watchdog, reconnect, off-thread teardown), decoder-instance probing, crash capture, the EZVIZ/ONVIF/ISAPI specifics, "Optimize" internals, backup crypto and security — is in [`docs/DEVELOPERS.md`](docs/DEVELOPERS.md).** Read it first if you're extending the app (with or without an AI agent); it captures things that took real debugging to learn.

### Build

**JDK 17+.** Gradle 9.6.1 · Android Gradle Plugin 9.2.0 · Kotlin 2.x (AGP's built-in Kotlin — no separate Kotlin plugin) · compileSdk 37 · minSdk 23 · targetSdk 35.

```bash
./gradlew assembleDebug      # → app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # signed per-ABI + universal (needs keystore.properties)
```

Full build, signing and release steps are in [`docs/DEVELOPERS.md`](docs/DEVELOPERS.md#build-from-source).

---

## 💡 Limitations & ideas

- **Amlogic multi-camera grid** is software-decoded (the chip greens hardware video on the multi-tile path), so it's heavier than on other TVs — lighten it with **Optimize live**.
- EZVIZ PTZ depends on EZVIZ's **undocumented cloud API** and needs internet.
- Possible future additions: auto-sequence/cycle mode, an events timeline that jumps into playback.

---

<div align="center">

*Personal / home project. Use with cameras and an NVR you own.* ❤️

</div>
