# Changelog

All notable changes to Hik TV Viewer. Newest first.

---

## 2.5.1 — Fix: empty camera wall on launch

**versionCode 23**

- **Fixed a black / empty camera wall in 2.5.0.** When the NVR's cameras were already cached (every launch after the first), the wall was filled from inside a layout callback, which Android drops — so no tiles appeared. The wall is now filled right after layout. **If 2.5.0 shows a black screen, update to 2.5.1.**

---

## 2.5.0 — Faster start, safer backups, better playback

**versionCode 22**

### ⚡ Faster / lighter (low-end TVs)

- **The encrypted credential store is opened once**, not on every screen and every camera switch, and it is warmed up in the background at launch. If the Keystore ever gets out of sync the store resets instead of crash-looping.
- **The camera wall no longer builds every stream twice** at start-up; tiles are sized first, then filled once, and started one after another.
- **Tile previews are lighter**: sub-stream snapshots, half the memory, decoded and saved off the UI thread, and not re-fetched if fresh.
- **Synced playback** searches all cameras in parallel and builds its players one at a time.
- **Smoother timeline** (no per-frame allocation), a baseline profile for faster cold start, and tiles show video from the first decoded frame instead of flashing black.

### 🐛 Fixed

- **Restoring a bad backup no longer wipes your settings** — it is validated first and reports the failure.
- **Backup encryption no longer crashes on Android 6-7** (API 23-25).
- **Setup restore** accepts passphrases (not just digits) and no longer freezes the TV while decrypting.
- **PTZ** can no longer miss a STOP and keep panning.
- **Playback**: resume no longer jumps back to where you started, an error no longer leaves a dead player running, the MENU key toggles the bar, and the multi-camera timeline is tall enough to see recordings.
- **Setup** only switches to new credentials once the NVR accepts them, and validates host and ports.
- Returning to the wall waits for the video surfaces (no green frame), keeps focus on the same tile, and motion alerts back off instead of retrying every 3 s.
- Snapshot names with `/` no longer fail; the EZVIZ login error dialog has a Close button.

### ⏪ Playback

- **Busy days are no longer cut off at 200 recordings** — results are paged.
- **Scrub past midnight to move to the previous or next day** (up to 90 days back), in single and synced playback.

### 🔧 Under the hood

Removed the unused DataStore scaffold and dead code; trimmed unused permissions (`WAKE_LOCK`, `ACCESS_NETWORK_STATE`); docs refreshed. Update in place — your NVR and settings are kept.

---

## 2.4.0 — Crash fixes on Amlogic, much faster start, modern toolchain

**versionCode 21** · Verified on a Mi Box 4 (Amlogic S905) and a Xiaomi MiTV (MediaTek m7632).

### 🐛 Fixed — the app no longer crashes on Amlogic (Mi Box)

- **Fixed the `libGLES_mali` crash** that killed the app on Mi Box / Amlogic TVs (a native `SIGSEGV` in the Mali GPU driver on LibVLC's video-output thread). It hit the camera wall *and* fullscreen, and could crash-loop on launch. The fix is **LibVLC 3.7.5**, whose video output no longer trips the buggy Mali driver.
- **Zoom / Pan / PTZ (▲) no longer crashes or stalls.** It used to open a *second* video decoder and surface on top of the one already playing — which raced the GPU driver (crash) and took seconds to reconnect. It's now an **in-place overlay that reuses the stream already on screen**: opens instantly, nothing to reconnect, nothing to race.

### ⚡ Faster

- **The wall now paints in seconds instead of minutes on weak TVs.** LibVLC was giving *every* tile its own full set of decode threads (~5 each → ~20 threads for a 4-camera wall on a 4-core TV), thrashing the CPU so badly the first frame could take minutes. Decode threads are now capped per tile.

### 🐛 Other fixes

- **PTZ mode no longer sticks to the wrong camera.** After viewing a PTZ camera, opening PTZ on a camera *without* PTZ wrongly stayed in PTZ mode.
- **Synced playback no longer spins up decoders off-screen.** Leaving the screen while recordings were still loading could allocate up to 9 decoders in the background (a crash/reboot path on weak chips).
- **Motion alerts no longer fail silently.** A permission/path error from the NVR (401/403/404) was parsed as an event stream forever — alerts simply never arrived, with no clue why.
- **Recorded-playback timelines no longer land days off** on NVR firmwares that report timezones as `+0530` (without a colon).
- Holding **OK** in the zoom overlay no longer flips through modes repeatedly.

### 🔧 Under the hood

- **Toolchain modernized:** Gradle 9.6.1, Android Gradle Plugin 9.2.0, Kotlin 2.x (AGP built-in), compileSdk 37. `minSdk` stays **23** and `targetSdk` stays **35** — the app still runs on Android 6+ TVs.
- **Dependencies updated** to current stable: AndroidX (core, appcompat, lifecycle, recyclerview), Material, Coroutines, LibVLC **3.7.5**, okhttp-digest.
- `security-crypto` moved off an **alpha** build onto stable 1.1.0 (it's deprecated upstream; a DataStore + Tink replacement is scaffolded but not yet enabled).
- Removed two unused dependencies (`leanback`, `constraintlayout`) that were shipping in every APK.
- Migrated off deprecated `onBackPressed()` / `requestPermissions()` APIs.

> **Note:** OkHttp stays on 4.12.0 — `okhttp-digest`, required for the NVR's Digest auth, has no OkHttp 5 build yet.

---

## 2.3.6 — Stability, performance & pan fixes

See the [v2.3.6 release](../../releases/tag/v2.3.6).

## 2.3.5 and earlier

See [Releases](../../releases).
