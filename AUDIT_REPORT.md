# Deep Audit — Hik TV Viewer (Android TV / Kotlin / LibVLC / Hikvision NVR)

**Version audited:** 2.3.5 (versionCode 19) · **LibVLC:** `org.videolan.android:libvlc-all:3.6.0` · **compileSdk/targetSdk:** 35 · **minSdk:** 23
**Method:** read-only source audit, 7 parallel area subagents (A–G) + a fresh-context adversarial verifier that re-read the cited code for every Critical/High finding. All findings are evidence-based with `file:line` citations. No source was modified.

---

## 1. Executive summary

Overall health: **the architecture is sound and several hard-won constraints are correctly implemented** — release-on-`onStop`/rebuild-on-`onStart` is on every video screen, PiP is handled correctly, the Amlogic software-grid rule holds on the *grid*, RTSP credential encoding and Digest auth are correct, LibVLC R8 keep-rules are present, and signing hygiene is clean. The field symptoms are real and trace to a small number of concrete causes, most of them cheap to fix.

Top 5 risks:
1. **Synced multi-playback hardware-decodes up to 9 concurrent main-stream players with no Amlogic guard and no decoder-instance cap** (A-04, Critical) — the clearest "auto-close/reboot under load" and Amlogic-green path.
2. **"Optimize live" relabels the codec to H.264 but never disables the NVR's H.265+/H.264+ SmartCodec, never sets CBR/GOP** (C1, High) — the most likely root cause of artifacts + periodic freezes on this exact NVR, and it's an easy extension of existing code.
3. **No crash capture anywhere** (B1/B2, High) — every field auto-close is undiagnosable without a PC + adb; blocks root-causing everything else.
4. **Memory/threading pressure paths**: no `onTrimMemory` + `largeHeap` (B3 → LMK kill), and LibVLC `stop()/release()` runs synchronously on the UI thread for N tiles at once (B4 → ANR kill).
5. **Software decode path is under-tuned** (A-01/A-05, High) — no bounded `avcodec-threads`, no `avcodec-fast`, no `getMaxSupportedInstances()` probe; the "Software" mode and the always-software Amlogic grid pay for it.

Two playback screens (`PlaybackActivity`, `MultiPlaybackActivity`) build their own `MediaPlayer` inline, **bypassing the CameraStream watchdog/reconnect** (A-06) — so the grid's stall-recovery does not protect recorded playback.

---

## 2. Findings table (adversarially verified)

| ID | Area | Sev | Conf | file:line | One-liner |
|----|------|-----|------|-----------|-----------|
| **A-04** | A | **Critical** | High | MultiPlaybackActivity.kt:76,203,217-225,307 | Synced playback HW-decodes up to 9 main-stream players, no Amlogic guard, no `:vout=android_display` |
| **A-01** | A | High | High | CameraStream.kt:143-147; PlayerEngine.kt:23-45 | No bounded `avcodec-threads`/`avcodec-fast`; grid `avcodec-skip-frame=0` (never drops under load) |
| **A-05** | A | High | High | (grep) whole repo | No decoder-capability probing (`getMaxSupportedInstances`) before choosing HW/SW per tile |
| **A-06** | A | High | High | PlaybackActivity.kt:154-170; MultiPlaybackActivity.kt:213-231 | Playback builds its own MediaPlayer, bypasses the liveness watchdog (MultiPlayback has no listener at all) |
| **B1** | B | High | High | HikApp.kt:11-14 | No `setDefaultUncaughtExceptionHandler` / no persistent crash capture anywhere |
| **B2** | B | High | High | SettingsActivity.kt:238-244; IsapiClient.kt:203-215 | Diagnostics only pings ISAPI; no last-crash view to read a field failure |
| **B3** | B | High | High* | (grep); AndroidManifest.xml:36 | No `onTrimMemory`/`ComponentCallbacks2` + `largeHeap=true` → app never sheds memory (LMK) |
| **B4** | B | High | High* | CameraStream.kt:212-227; GridAdapter.kt:96-98; GridActivity.kt:78-83 | LibVLC `stop()/release()` runs synchronously on the UI thread for N tiles at once (ANR) |
| **C1** | C | High | High | IsapiClient.kt:242-249 | Optimize sets codec=H.264 + 15 fps but never disables SmartCodec (H.264+/H.265+), no CBR, no GOP |
| **C2** | C | High | High | IsapiClient.kt:244-248,371-379 | Optimize is a blind one-way PUT: no re-GET verify, no revert, success = HTTP 2xx only |
| **E1** | E | High | High | MultiPlaybackActivity.kt:284-297 | `onStart` rebuilds up to 9 decoders via delayed post with no STARTED guard; `onStop` never cancels it |
| A-02 | A | Medium | High | CameraStream.kt:78,156-159 | `retryDelay` resets to 1 s on every `Playing` → backoff defeated for a flapping stream |
| A-07 | A | Medium | High | GridActivity.kt:124-125 | Grid "Hardware" decode mode has no Amlogic guard — a Settings toggle can force HW H.265 on the Amlogic grid |
| A-08 | A | Medium | High | PlayerEngine.kt:30-32; CameraStream.kt:138-148 | Global `--drop-late-frames`/`--skip-frames` defeat the per-media `highQuality` "keep frames" intent |
| A-09 | A | Medium | High | DeviceQuirks.kt:14-23 | Only Amlogic classified; Redmi X50 (MediaTek) gets no quirk handling, relies on a manual toggle |
| B5 | B | Medium | Med | CameraStream.kt:60-70,84-85 | Under CPU starvation `TimeChanged` stops → watchdog force-reconnects → reconnect-storm amplifier |
| B6 | B | Medium | High | GridAdapter.kt:69 | Cached snapshot JPEG decoded synchronously on the UI thread during bind (and decoded twice) |
| B7 | B | Medium | Med | SettingsActivity.kt:321,334; BackupCrypto.kt:57-61 | PBKDF2 decrypt + `importJson` run on the main thread in the restore path (ANR) |
| C3 | C | Medium | High | IsapiClient.kt:246-247; GridAdapter.kt:136 | No sub-stream envelope: resolution/bitrate/bitrate-type/GOP never set; grid plays whatever exists |
| C5 | C | Medium | Med | MultiPlaybackActivity.kt:204,219,230,307 | Multi-playback capped at 9 but not Amlogic-aware; forces green-prone HEVC direct-render (see A-04) |
| D1 | D | Medium | High | IsapiClient.kt:399-409 | 35 s `readTimeout` + no `callTimeout` applied to all short calls → ~70–140 s discovery freeze on a dead NVR |
| D2 | D | Medium | High | IsapiClient.kt:226-232 | ISAPI PTZ **stop** has no retry (ONVIF/EZVIZ retry 3×) → runaway pan on a dropped key-up |
| D3 | D | Medium | Med | EzvizCloud.kt:78-94; PtzController.kt:68,77 | EZVIZ session expiry never recovered (re-login only when `sessionId==null`) |
| E2 | E | Medium | High | ControlActivity.kt:301-313 | Control `onStart` rebuilds stream via delayed post, no STARTED guard, `onStop` doesn't cancel |
| E3 | E | Medium | High | NvrStore.kt:16-27 (9 call sites) | `EncryptedSharedPreferences`/Keystore init on the main thread, re-done on every stream (re)build |
| E4 | E | Medium | High | SettingsActivity.kt:287-301; SetupActivity.kt:149-162 | Backup/restore file I/O on the main thread (ANR on slow flash / large Downloads) |
| E5 | E | Medium | Med | ControlActivity.kt:241-245,308-320 | PTZ stop fired through `lifecycleScope` (cancelled on exit) → runaway PTZ on hard-exit-while-moving |
| F1 | F | Medium | High | res/xml/network_security_config.xml:8 | Cleartext + user-CA trust permitted **globally**, not scoped to the NVR/LAN |
| F2 | F | Medium | High | BackupManager.kt:23-33 | Unencrypted backup writes cleartext NVR/EZVIZ passwords to world-readable `Downloads/` |
| F3 | F | Medium | Med | SettingsActivity.kt:279; BackupCrypto.kt:22 | 4-digit-min PIN (10⁴ space) + 60k PBKDF2 → encrypted backup brute-forceable offline |
| G2 | G | Medium | Med | proguard-rules.pro; build.gradle.kts:103 | No `-keep` for Tink/security-crypto under R8 full mode → possible release-only launch crash |
| A-03 | A | Low | High | PlayerEngine.kt:44 | LibVLC left at `-v` in production (per-stream info logging; also leaks credentialed RTSP URL — see F7) |
| A-10 | A | Low | High | FullscreenActivity.kt:88-111 | Fullscreen decodes audio even when muted (created `muted=false` then volume 0) |
| A-11 | A | Low | Med | CameraStream.kt:75-91 | Reconnect ignores `MediaPlayer.Event.Stopped` (watchdog backstops, so ~7 s slower recovery) |
| B8 | B | Low-Med | High | Fullscreen/Control/Playback/MultiPlayback startStream | `NvrStore(this)` built on hot paths + no decode-mode/quirk/state-transition logging |
| B9 | B | Medium | Med | MultiPlaybackActivity.kt:76,307 | `MAX_CELLS=9` concurrent decoders (see A-04/A-05) — SIGSEGV/reboot risk |
| D4 | D | Low | High | GridActivity.kt:157-173 | alertStream reconnect is a flat 3 s retry (no exponential backoff) |
| D5 | D | Low | High | IsapiClient.kt:50-71,364-369 | Discovery swallows all errors into an empty list → transient failure looks like "no cameras" |
| D8 | D | Low | Med | OnvifPtz.kt:38-41 | ONVIF client has no `callTimeout`/`writeTimeout` (bounded only by defaults) |
| E6 | E | Low | High | GridActivity.kt:280; SetupActivity.kt:272 | Deprecated `onBackPressed` / `requestPermissions` / `getExternalStoragePublicDirectory` under targetSdk 35 |
| E7 | E | Low | High | AndroidManifest.xml:6 | `WAKE_LOCK` declared but unused + no `keepScreenOn` → screensaver can blank the live wall |
| E8 | E | Low | High | GridActivity.kt:165-168 | Blanket `catch (_: Exception) {}` in the motion loop hides real errors |
| E11 | E | Low | High | CameraStream.kt:211-218 | `CameraStream.stop()` is dead code; every tile detach fully `release()`s and recreates the player |
| F4 | F | Low | High | build.gradle.kts:103 | `security-crypto:1.1.0-alpha06` (alpha) with no fallback → keystore failure = launch crash |
| F7 | F | Low | Med | RtspUrls.kt:20,31 | NVR creds embedded in RTSP URL; leaks to logcat if LibVLC verbose (ties to A-03) |
| G5 | G | Low | High | build.gradle.kts:76-83 | No `x86_64` split (universal fallback works; real TVs unaffected) |
| G7 | G | Low | High | build.gradle.kts:27-49 | Release silently falls back to **unsigned** when `keystore.properties` absent (CI footgun) |

**\*** B3/B4 code facts are confirmed; their downstream death-class (LMK/ANR) and LibVLC `stop()` blocking duration need on-device confirmation (§5, §6).

**Verified NOT problems** (checked and cleared, so they don't get re-flagged later): PiP release/rebuild is correct (E10); `Session` shared state is adequately `@Volatile`-guarded with defensive copies (E9); RTSP credential URL-encoding is complete and correct (D6); Digest auth setup is correct (D7); Optimize touches sub-streams only, main/recording untouched (C4); LibVLC/okhttp-digest R8 keep-rules present (G1/G3); no keystore committed, `.gitignore` complete (G4); `SnapshotCache` is bounded (one file per channel, `inSampleSize=2`) — not the OOM source; no static Context/Activity leak; no credentials in Log/Toast; EncryptedSharedPreferences fails closed (no plaintext fallback).

---

## 3. Detailed findings by area

### Area A — Video pipeline & performance

**A-04 [Critical]** `MultiPlaybackActivity.kt:76,203,217-225,307`. `MAX_CELLS=9`, `cams += online.take(MAX_CELLS)`; `val hw = store.decoderMode != 2` and `NvrStore` default `decoderMode=0` → `hw=true` by default. The Media is built with `setHWDecoderEnabled(hw,false)` and **no** `if (DeviceQuirks.isAmlogic)` software override and **no** `:vout=android_display` — unlike `PlaybackActivity.kt:174` and the grid (`GridActivity.kt:125`), both of which guard Amlogic. So on Amlogic in default mode, synced playback hardware-decodes up to 9 concurrent main-stream players on the multi-SurfaceView GL path the README forbids. *Impact:* symptom 3 (auto-close/reboot — exceeds the 2–4 concurrent MediaCodec sessions cheap SoCs expose) on both devices; symptom 4 (green frames) on Mi Box. *Fix:* force `hw=false` on Amlogic here (mirror the grid), lower `MAX_CELLS` on Amlogic/low-RAM (e.g. 4), and ideally cap by A-05's probed instance count; consider using sub-streams for the multi-wall. *Risk:* low-med (more CPU on the already-heavy Amlogic multi-wall, but correctness > that).

**A-01 [High]** `CameraStream.kt:143-147`, `PlayerEngine.kt:23-45`. Grep confirms **zero** `avcodec-threads`/`avcodec-fast` anywhere; software grid path sets `:avcodec-skip-frame=0` (NONE — never drops frames under load). Loop-filter skip (`=4`) and `drop-late-frames`/`skip-frames` *are* present, so scope the finding to *missing thread-count and fast-decode tuning + non-escalating skip-frame*. *Impact:* symptoms 2/3/5, worst on the always-software Amlogic grid. *Fix:* bound `:avcodec-threads` (1–2/tile), add `:avcodec-fast`, escalate `:avcodec-skip-frame` to 1/2 under load. *Risk:* low (mild quality cost, reversible via decode mode).

**A-05 [High]** whole repo (grep). No `MediaCodecList`/`getMaxSupportedInstances`/`CodecCapabilities`. HW/SW choice is a coarse pref + `isAmlogic` flag; only static `MAX_CELLS` + startup staggering bound concurrency, never the chip's real limit. *Impact:* symptom 3, both devices. *Fix:* one-time probe of the HEVC/AVC decoder's `getMaxSupportedInstances()`, cache it, cap simultaneous HW streams (grid Hardware mode, MAX_CELLS), overflow → software; conservative default (2) if unknown. *Risk:* low (read-only probe).

**A-06 [High]** `PlaybackActivity.kt:154-170`, `MultiPlaybackActivity.kt:213-231`. Both build `MediaPlayer` inline; Playback handles only Playing/EncounteredError/EndReached, MultiPlayback sets **no event listener at all** (only `setEventListener(null)` at :274). Neither has the `lastProgressAt`/watchdog silent-stall recovery from `CameraStream.kt:59-70`. *Impact:* symptom 1 in recorded playback — a silent demux stall freezes the picture while the playhead keeps advancing, no self-heal. *Fix:* add the `TimeChanged`-fed idle watchdog to both (reconnect must re-seek to the current playhead, not the original start), or refactor to reuse CameraStream. *Risk:* med (re-anchor correctness).

**A-02 [Med]** `CameraStream.kt:78,156-159`. `Playing` resets `retryDelay=1000` every time, so a stream that flaps (connect→1 frame→error) reconnects every ~1 s forever despite the 8 s cap. *Fix:* only reset after ≥10 s stably Playing.
**A-07 [Med]** `GridActivity.kt:124-125`. `adapter.hardware = store.decoderMode == 1` with no `&& !isAmlogic` — a Settings toggle forces HW H.265 on the Amlogic grid (green). *Fix:* add the guard; hide "Hardware" on Amlogic.
**A-08 [Med]** `PlayerEngine.kt:30-32`. Global `--drop-late-frames`/`--skip-frames` can't be un-set per-media, defeating the control screen's `highQuality` "keep frames" intent. *Fix:* move both flags out of global into the non-highQuality media branch (all live/playback callers already add them per-media).
**A-09 [Med]** `DeviceQuirks.kt:14-23`. Only Amlogic detected; Redmi X50 (MediaTek) falls through to defaults + a manual toggle. *Fix:* add a MediaTek/`L50M6` branch once verified on-device.
**A-03 [Low]** `PlayerEngine.kt:44` — `-v` in production. **A-10 [Low]** `FullscreenActivity.kt:88-111` — decodes audio while muted. **A-11 [Low]** `CameraStream.kt:75-91` — no `Stopped` handling (watchdog backstops).

### Area B — Stability (why the app dies)

Death-class read: **Mi Box 4K** primary = LMK (B3) + ANR (B4/B6/B8), amplified by B5; SIGSEGV mainly via multi-playback (B9). **Redmi X50** primary = native SIGSEGV (decoder churn on switch/reconnect, B5) + B9 MediaCodec exhaustion; ANR (B4) shares the reconnect-storm trigger. Java OOM is *low* probability (LibVLC buffers are native, not on the large Java heap).

**B1 [High]** `HikApp.kt:11-14` is `super.onCreate(); instance = this` — nothing else; no uncaught handler, no crash file. **B2 [High]** Diagnostics (`SettingsActivity.kt:238-244`) only pings ISAPI. Together: a field auto-close leaves no on-device trace. *Fix:* install a `setDefaultUncaughtExceptionHandler` writing a capped rotating file under `filesDir` (then chain to the default so the process still dies), and add a Diagnostics "Last crash" row + "copy to Downloads". *Caveat:* a Java handler won't catch native SIGSEGV — call that out; only logcat/breakpad does.

**B3 [High\*]** grep: no `onTrimMemory`/`onLowMemory`/`ComponentCallbacks2`; `largeHeap=true` (Manifest:36). App holds max memory and offers nothing back under pressure → prime LMK target on a 4K TV. *Fix:* `onTrimMemory` on `RUNNING_CRITICAL` → drop snapshot bitmaps + release non-focused tiles (reuse `releaseAllStreams()`/`stopStream()`); reconsider `largeHeap`. *Confirm on-device.*

**B4 [High\*]** `CameraStream.kt:212-227` calls `player.stop()` synchronously; `GridAdapter.releaseAllStreams()` loops over N holders from `GridActivity.onStop()` (main thread). N synchronous native stops serialize on the UI thread; a network blip triggers the same via a reconnect storm. *Fix:* move `stop()/release()` to a dedicated teardown executor, or at least stagger releases as startup already is. *The "stop() blocks until decoder threads join" duration needs on-device confirmation.*

**B5 [Med]** `CameraStream.kt:60-70,84-85` — under CPU starvation `TimeChanged` stops arriving though the stream is fine → watchdog force-reconnects → more load → other tiles' watchdogs trip. *Fix:* overload-aware watchdog (lengthen timeout / global cap on concurrent watchdog reconnects). **B6 [Med]** `GridAdapter.kt:69` — `SnapshotCache.load` (`BitmapFactory.decodeFile`) on the UI thread during bind, then decoded again off-thread. *Fix:* decode off-thread, drop the double decode, `RGB_565` for previews. **B7 [Med]** `SettingsActivity.kt:321,334` — 60k-round PBKDF2 decrypt + `importJson` on main. *Fix:* wrap in `Dispatchers.Default`. **B8 [Low-Med]** per-stream `NvrStore(this)` on hot paths + no state/quirk logging. **B9 [Med]** `MAX_CELLS=9` (see A-04/A-05).

### Area C — NVR stream configuration

**C1 [High]** `IsapiClient.kt:242-249`. `optimizeSubStream` regex-replaces only `<videoCodecType>`→H.264 and `<maxFrameRate>`→1500 on `{ch}02`. Grep: `SmartCodec`/`videoQualityControlType`/`GovLength` appear nowhere. So H.264+/H.265+ SmartCodec, if enabled on the NVR, stays enabled — base H.264 + SmartCodec = **H.264+**, the exact artifact/freeze codec on the DS-7608NXI-K1. *Impact:* symptoms 1 & 4 (NVR-config cause, not a decoder bug), all devices. *Fix:* extend the same PUT body to also force `<SmartCodec><enabled>false</enabled></SmartCodec>` (insert if absent), `<videoQualityControlType>CBR</videoQualityControlType>`, and `<GovLength>` ≈ 2× fps (30). Apply SmartCodec-off/CBR/GOP regardless of the codec swap so it survives H.265-only cameras. *Risk:* low (sub-stream only).

**C2 [High]** `IsapiClient.kt:244-248,371-379`. Returns `true` iff the PUT is HTTP 2xx — no re-GET, no stored original, no revert. Hikvision often returns 200 while silently coercing/ignoring fields; user sees "Updated N of M" that may be false. *Fix:* parse `ResponseStatus/subStatusCode` and/or re-GET and assert; snapshot the pre-change XML to `NvrStore` for a "Restore original sub-streams" action. *Risk:* negligible (adds verification + one GET).

**C3 [Med]** No resolution/bitrate/bitrate-type/GOP envelope; grid trusts whatever the sub-stream is (`GridAdapter.kt:136`). *Fix:* clamp to ≤ D1/720p CBR ~512–1024 kbit, GOP ≈ 30, after reading `/capabilities`. **C5 [Med]** multi-playback not Amlogic-aware (see A-04). **C4 [Info/good]** Optimize is sub-stream-only — main/recording genuinely untouched. **C6 [Info]** no Channel-Zero anywhere — a viable "Lite grid" evaluation (§5 bucket 4), not implemented.

### Area D — Network & API robustness

Threading is clean here: no `runBlocking`/`Dispatchers.Main`/`GlobalScope`; all network/crypto is on `Dispatchers.IO`. The gaps are recovery-latency and silent-failure, not ANR.

**D1 [Med]** `IsapiClient.kt:399-409` — `connectTimeout(6s)`, `readTimeout(35s)`, no `writeTimeout`/`callTimeout`; the 35 s read (sized for the alertStream keep-alive) applies to every short call, so `discoverCameras()`'s 2–4 sequential GETs against a dead NVR block ~70–140 s. *Fix:* separate short-call client with `callTimeout(10s)`. **D2 [Med]** `IsapiClient.kt:226-232` — ISAPI PTZ stop is single-shot while ONVIF (`OnvifPtz.kt:112`) and EZVIZ (`PtzController.kt:78`) retry 3× → runaway pan on a dropped key-up. *Fix:* mirror the 3× retry, surface the final failure. **D3 [Med]** `EzvizCloud.kt:78-94` — session expiry never recovered (re-login only when `sessionId==null`). *Fix:* detect the invalid-session code, null the session, re-login once, replay. **D4/D5/D8 [Low]** flat 3 s alertStream retry; discovery swallows errors into empty-list (looks like "no cameras"); ONVIF no callTimeout.

### Area E — Correctness & lifecycle

**E1 [High]** `MultiPlaybackActivity.kt:284-297` — `onStart` posts `binding.grid.postDelayed({… playAllFrom(playhead)},250)` with no `lifecycle.isAtLeast(STARTED)` check; `onStop`/`onDestroy` remove callbacks on the `ui` handler but the runnable was posted on `binding.grid` — never cancelled. A fast onStart→onStop within 250 ms rebuilds up to 9 decoders while backgrounded. `PlaybackActivity.kt:279-284` guards exactly this. *Fix:* add the STARTED gate + hoist the runnable and `removeCallbacks` in `onStop`.

**E2 [Med]** same pattern in `ControlActivity.kt:301-313`. **E3 [Med]** `NvrStore` Keystore init on main, per stream build (overlaps B8). **E4 [Med]** backup/restore file I/O on main. **E5 [Med]** `ControlActivity.kt:241-245` — PTZ stop via `lifecycleScope` (cancelled on exit) → runaway PTZ on hard-exit-while-moving; use an app-scoped coroutine. **E6/E7/E8/E11 [Low]** deprecated `onBackPressed`/permissions/storage APIs; unused `WAKE_LOCK` + no `keepScreenOn` (screensaver blanks the wall); silent motion-loop catch; dead `CameraStream.stop()`. **E9/E10 [Info]** Session threading and PiP verified correct.

### Area F — Security & privacy (quick pass)

No credentials in logs/Toasts; EncryptedSharedPreferences correct and fails closed; plain-backup warning is surfaced; `BackupCrypto` uses `SecureRandom` + PBKDF2-HMAC-SHA256 + AES-GCM. **F1 [Med]** `network_security_config.xml:8` — cleartext + `user` CA trust are global, not NVR-scoped (MITM surface). **F2 [Med]** `BackupManager.kt:23-33` — unencrypted backup puts cleartext NVR/EZVIZ passwords in world-readable `Downloads/`, survives uninstall. **F3 [Med]** 4-digit-min PIN (10⁴) + 60k PBKDF2 → offline brute-force in minutes. **F4/F5/F6/F7 [Low]** alpha security-crypto with no fallback; repeated `NvrStore` instances on one file; API-dictated EZVIZ MD5; creds in RTSP URL leak under LibVLC verbose (fix via A-03).

### Area G — Build & release health

**G1/G3/G4 [Pass]** LibVLC + okhttp-digest R8 keep-rules present; no keystore committed; `.gitignore` complete. **G2 [Med]** no `-keep` for `com.google.crypto.tink.**`/`androidx.security.**` under R8 full mode (AGP 8.7 default) — Tink registers key-managers via reflection; `NvrStore` runs at launch, so a stripped registration = release-only crash. *Fix:* add the Tink/security-crypto keep + `-dontwarn` rules. **G5/G7 [Low]** no x86_64 split (universal fallback works); release silently unsigned without `keystore.properties`. **G6/G8/G9 [Pass]** version/ABI/harness consistent; viewBinding/leanback safe under minify.

---

## 4. Symptom → root-cause matrix

Ranked hypotheses per reported symptom (finding IDs), device, confidence, and how to confirm on-device.

### Symptom 1 — Live playback stops/freezes, recovery unreliable
| Rank | Cause | Device | Conf | Confirm |
|---|---|---|---|---|
| 1 | **C1** SmartCodec H.264+/H.265+ left on → GOP/keyframe freezes + artifacts | Both | High | `GET /ISAPI/Streaming/channels/{ch}02` → read `<SmartCodec><enabled>` |
| 2 | **A-06** playback bypasses the silent-stall watchdog | Both | High | Reproduce a demux stall on a recording; picture freezes, playhead advances |
| 3 | **B5** watchdog reconnect-storm under load starves other tiles | Both | Med | `adb shell top -H -m 10` during a full wall; correlate 100% CPU + repeated CONNECTING |
| 4 | **A-02** backoff defeated for flapping streams | Both | Med | logcat state transitions after B8 logging added |
| 5 | **D1** 35 s read timeout → long reconnect freeze; **D3/D5** EZVIZ/discovery recovery | Both | Med | Pull NVR Ethernet during discovery; time the fallthrough |

### Symptom 2 — Slow / laggy / stuttering (worst on grid)
| Rank | Cause | Device | Conf | Confirm |
|---|---|---|---|---|
| 1 | **C1/C3** heavy/smart-coded sub-stream (no res/bitrate/GOP envelope) | Both | High | `GET` sub-stream config; check resolution/bitrate/SmartCodec |
| 2 | **A-01/A-05** untuned software decode (no thread bound/fast, no probe) | Mi Box (always SW) | High | `adb shell top -m 5` on a full wall |
| 3 | **B6** main-thread snapshot decode jank during bind | Both | Med | StrictMode / Perfetto trace on grid scroll |
| 4 | **A-03** `-v` per-stream logging overhead | Both | Low | Compare CPU with `-v` vs silent |

### Symptom 3 — App auto-closes under CPU/decode overload
| Rank | Cause | Device | Conf | Confirm |
|---|---|---|---|---|
| 1 | **A-04/B9** 9 HW main-stream decoders in multi-playback, no Amlogic guard/cap | Both (esp Amlogic) | High | `adb shell dumpsys media.codec`; `adb logcat -b crash -d` during 9-cam synced playback |
| 2 | **B4** synchronous LibVLC `stop()`×N on the UI thread → ANR | Both | High\* | `adb logcat -b events | grep am_anr`; `cat /data/anr/traces.txt` (main thread in libvlc stop JNI) |
| 3 | **B3** no `onTrimMemory` + `largeHeap` → LMK kill | Mi Box | High\* | `adb shell dumpsys meminfo com.hiktv.viewer`; `logcat -d | grep -i "lowmemorykiller\|Killing"` |
| 4 | **A-05** no decoder-instance probing (grid Hardware / multi) | Both | High | `dumpsys media.codec | grep -iE "hevc\|instances"` |
| 5 | **A-07** Settings "Hardware" forces HW on Amlogic grid; **E1/E2** unguarded rebuilds | Mi Box / Both | Med | logcat around wall↔settings transitions under load |

### Symptom 4 — Poor quality / artifacts / dropped frames
| Rank | Cause | Device | Conf | Confirm |
|---|---|---|---|---|
| 1 | **C1** SmartCodec (H.265+/H.264+) hostile to LibVLC | Both | High | `GET` channel config |
| 2 | **A-04/A-07** HW H.265 on Amlogic multi-tile/grid → green | Mi Box | High | Visual on Mi Box |
| 3 | **A-08** global frame-drop flags defeat control-screen `highQuality` | Both (zoom/detail) | Med | Compare zoom detail with flags moved per-media |
| 4 | **A-09** Redmi X50 (MediaTek) gets no quirk tuning | Redmi X50 | Med | `getprop ro.board.platform/ro.hardware/ro.product.model`; A/B `directRender` |

### Symptom 5 — Software path needs a real implementation; H.265+ maybe unhandled
| Rank | Cause | Device | Conf | Confirm |
|---|---|---|---|---|
| 1 | **A-01/A-05** no `avcodec-threads`/`avcodec-fast`, no instance probe, `skip-frame=0` | Both | High | Code (confirmed) + on-device CPU under load |
| 2 | **C1** H.265+ never read or disabled by the app | Both | High | `GET` channel config for SmartCodec flag |
| 3 | **C6** no Channel-Zero "one composited decoder" option | Both | Info | `GET /ISAPI/ContentMgmt/ChannelZero/capabilities` |

---

## 5. Prioritized fix plan

### Bucket 1 — Quick wins (≤1 day each; app-side, low risk)
| Fix | Findings | Effort | Risk |
|---|---|---|---|
| Add `&& !DeviceQuirks.isAmlogic` to grid Hardware mode | A-07 | 5 min | Low |
| Force software + `:vout=android_display` on Amlogic in MultiPlayback; lower `MAX_CELLS` on weak/Amlogic | A-04, C5, B9 | ~half day | Low-Med |
| Add `:avcodec-threads` (bounded) + `:avcodec-fast` + skip-frame escalation to the SW path | A-01 | ~2 h | Low |
| Fix backoff: only reset `retryDelay` after ≥10 s stable Playing | A-02 | ~1 h | Low |
| Add STARTED guard + `removeCallbacks` in `onStop` (MultiPlayback, Control) | E1, E2 | ~1 h | Low |
| Move `--drop-late-frames`/`--skip-frames` out of global into per-media | A-08 | ~1 h | Low |
| Add `callTimeout(10s)` for short ISAPI calls (separate client from alertStream) | D1 | ~2 h | Low |
| Retry ISAPI PTZ stop 3× (mirror ONVIF/EZVIZ) | D2 | ~1 h | Low |
| Drop LibVLC `-v` → silent in release (also closes F7) | A-03, F7 | 10 min | Low |
| Create fullscreen stream muted (`:no-audio`); rebuild on unmute | A-10 | ~1 h | Low |
| Add Tink/security-crypto R8 keep rules | G2 | 15 min | Low |
| `keepScreenOn` on video screens (or drop `WAKE_LOCK`) | E7 | 30 min | Low |

### Bucket 2 — Structural fixes
| Fix | Findings | Effort | Risk |
|---|---|---|---|
| Crash logger (uncaught handler → capped file) + Diagnostics "Last crash" view | B1, B2 | 1–2 days | Low |
| `onTrimMemory` → shed snapshots + release non-focused tiles | B3 | 1–2 days | Med |
| Off-main-thread player teardown / staggered release | B4 | 1–2 days | Med |
| Decoder-instance probe (`getMaxSupportedInstances`) → cap HW sessions | A-05 | 1–2 days | Med |
| Add silent-stall watchdog to Playback/MultiPlayback (re-anchor to playhead) | A-06 | 1–2 days | Med |
| Overload-aware watchdog (global reconnect cap) | B5 | 1 day | Med |
| Move remaining main-thread I/O off main (snapshot decode, restore crypto, NvrStore init, backup) | B6, B7, E3, E4 | 1–2 days | Low |
| Optimize: verify (re-GET) + store original + "Restore sub-streams" | C2 | 1 day | Low |
| Sub-stream envelope enforcement (read `/capabilities`, clamp res/bitrate/GOP) | C3 | 1–2 days | Med |
| State/decode-mode/quirk logging | B8 | ~half day | Low |
| MediaTek/Redmi X50 quirk branch (needs device) | A-09 | 1 day + device | Med |
| App-scoped PTZ stop; EZVIZ session recovery; discovery error-vs-empty | E5, D3, D5 | 1–2 days | Low-Med |
| Security: scope cleartext/user-CA to NVR; default backup to PIN or app-private; stronger PIN/KDF | F1, F2, F3 | 1–2 days | Med |

### Bucket 3 — NVR-side changes (DS-7608NXI-K1, via extended ISAPI Optimize or web UI)
| Change | Findings | Effort | Risk |
|---|---|---|---|
| **Disable H.265+/H.264+ SmartCodec on sub-streams** (extend the existing Optimize PUT) | C1 | ~half day | Low-Med |
| Sub-streams ≤ 720p/15 fps **CBR**, **GOP ≈ 2× fps** | C1, C3 | ~half day | Med |
| Optional (with explicit consent): SmartCodec-off on **main** streams too (helps fullscreen/playback symptom 1/4) | C1 | ~half day | Med |

### Bucket 4 — Bigger ideas (evaluate, do not build in this pass)
- **Channel-Zero "Lite grid"** (C6): probe `ContentMgmt/ChannelZero/capabilities`; if present, one composited RTSP channel-0 decoder replaces N sub-stream decoders on the weakest TVs (needs an enable-PUT; typically ≤ 4CIF).
- **Alternative per-tile grid decoder** (e.g. `alexeyvasilyev/rtsp-client-android`, zero-buffer RTSP→MediaCodec) for grid tiles only, keeping LibVLC for fullscreen/playback.
- **VLC 4.x `--low-delay`** once stable (RTSP-over-LibVLC has a ~0.5 s latency floor today).

---

## 6. What was NOT verified + on-device verification plan

Every finding above is confirmed from source *except* the runtime consequences below, which are code-plausible but need a device. Use the `.claude/skills/run-hik-tv-viewer` harness (build/install/launch/screenshot) plus these:

| Question | Finding | Command |
|---|---|---|
| Is the auto-close an LMK kill, ANR, or SIGSEGV? | B3, B4, B9, A-04 | `adb logcat -b crash -d`; `adb logcat -b events \| grep am_anr`; `adb shell cat /data/anr/traces.txt`; `adb shell dumpsys meminfo com.hiktv.viewer`; `adb logcat -d \| grep -i "lowmemorykiller\|Killing"` |
| Real concurrent HW decoder limit on each chip | A-05, A-04 | `adb shell dumpsys media.codec \| grep -iE "hevc\|avc\|instances"`; reproduce with 9-cam synced playback |
| Is SmartCodec actually ON on this NVR? | C1 | `GET /ISAPI/Streaming/channels/{ch}02` → `<SmartCodec><enabled>`, `<videoQualityControlType>`; `GET …/capabilities` for settable ranges |
| How does the Redmi X50 classify? | A-09 | `adb shell getprop ro.board.platform`, `ro.hardware`, `ro.product.model`; then A/B `directRender` visually |
| Does the release APK crash on Tink stripping? | G2 | Install `app-<abi>-release.apk`, launch, `adb logcat \| grep -iE "GeneralSecurityException\|KeyManager\|Tink"`; verify creds save/reload across reinstall |
| Does LibVLC `stop()` block long enough to ANR? | B4 | Perfetto/`top -H` during a wall→settings transition under load; confirm main thread parked in libvlc `stop`/`detachViews` |
| Does the NVR accept the Optimize PUT (vs silent coerce)? | C1, C2 | Capture raw PUT response body; re-`GET` and diff the fields |
| EZVIZ session-expiry code | D3 | Idle overnight, attempt PTZ, log the returned `code` |
| Does Channel-Zero exist on this NVR? | C6 | `GET /ISAPI/ContentMgmt/ChannelZero/capabilities` |

---

*End of audit. No source was modified. Awaiting approval of the Phase 5 fix plan (Bucket 1 first, smallest-risk, one fix at a time, each followed by `./gradlew assembleDebug` and — if a TV is on ADB — a harness smoke test).*
