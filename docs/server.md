# Car Server App (app-server)

## Overview

The car server app runs on the BYD DiLink infotainment system. It uses a **parallel connection model** with WiFi (3 dedicated connections) and USB tracks running simultaneously:

1. Track A (WiFi): gateway IP + mDNS discovery, control connect (9637), handshake with phone
2. After handshake: video (9638) + input (9639) connections opened in parallel
3. Track B (USB): scan devices, USB ADB connect (with logSink diagnostics), launch phone app
4. `checkAndAdvance()` evaluates state when any prerequisite changes
5. Receives H.264 video and renders on car display via SurfaceView
6. Captures touch events and sends to phone via input connection for VD input injection

States: IDLE → CONNECTING → CONNECTED → STREAMING

Car APK is embedded in the phone APK. The phone installs the car app via dadb on demand (onboarding "Car setup" step or the main-screen "Install on Car" button).

### Two-Mode UI

The car app separates the connection flow from the streaming experience into two distinct modes, matching the phone app's approach:

- **Launch mode** (`CarLaunchScreen`): Full-screen, connection-focused. Shows branding, step-by-step connection instructions, connection status, manual IP entry, and a `startup_dpi` override input. No navigation bar — the entire screen is dedicated to getting connected. Shown when the car app starts and remains until app icons are received from the phone via the control connection.

- **Streaming mode** (`CarShell` with `PersistentNavBar`): The layout with left navigation bar (Eject / Home / Back) and content area (app grid, mirror view). Shown once `appList` is non-empty and the state is CONNECTED or STREAMING. During a mid-stream rotation re-handshake, `onCarViewportChanged` sets state to CONNECTING and tears down the VD, but the streaming-layout gate also accepts `state == CONNECTING && appList.isNotEmpty()` so the layout stays put and a "waiting for video" overlay covers the ~2s redeploy gap instead of flashing back to `CarLaunchScreen` (which would destroy the SurfaceView's surface and block decoder restart).

The transition trigger: when the phone sends `APP_LIST` via the control connection and the connection state reaches CONNECTED/STREAMING, the UI switches from launch mode to streaming mode.

## Components

### CarConnectionService

Foreground service managing the full connection lifecycle with a parallel prerequisite state machine and 3 dedicated connections.

**3-Connection Architecture:**
- `controlConnection`: handshake, heartbeat, app commands, DATA channel (app list, media metadata, car logs)
- `videoConnection`: H.264 video frames only (phone → car)
- `inputConnection`: touch events only (car → phone)
- Heartbeat/watchdog on control connection only; video and input have no heartbeat overhead
- Any connection dying cascades → full session teardown

**Parallel Track Architecture:**
- `connectionScope`: parent Job for all discovery coroutines, cancelled on disconnect
- Track A and Track B run simultaneously
- `checkAndAdvance()` evaluates overall state when any prerequisite changes
- User disconnect: stays IDLE, no auto-reconnect (persisted to SharedPreferences)
- Reconnect loop stops after 3 consecutive ADB failures (`noAdbCount >= 3`); TCP ADB auto-reconnects on phone IP change and auto-falls back to TCP ADB when USB unavailable

**Track A — WiFi:**
- Discovery: gateway IP (hotspot/LAN, retries every 3s) → mDNS → manual IP
- Control connection: NIO non-blocking SocketChannel connect to phone TCP:9637
- Handshake (built via `HandshakeFactory.buildHandshakeRequest`): sends viewport dimensions + DPI + appVersionCode + appVersionName + targetFps + `dpiOverride` → receives phone info + vdServerJarPath + connectionMethod + adjusted VD DPI (`handshakeVdDpi`)
- On handshake response: waits for `VD_PORTS_BOUND`, then opens video (9638) + input (9639) connections in parallel, sets `wifiReady = true` after all 3 established
- Video: receives H.264 frames via video connection, dispatches to `VideoDecoder`. CONFIG (SPS/PPS) and the most recent IDR are cached inside `VideoDecoder` so the codec can be (re)started at any time; the decoder is NOT started on an offscreen `SurfaceTexture(0)` — decoded frames with no consumer would waste hardware-decode bandwidth and a temporary gralloc allocation. The decoder starts when `MirrorScreen`'s real `SurfaceView` surface becomes available.
- Touch: delegated to `CarTouchSender` (single-thread executor) via `sendTouchEvent()` / `sendTouchBatch()`
- Heartbeat: 3s interval, 10s watchdog timeout (control connection only)
- Backoff: exponential delay on reconnect failures

**Track B — USB:**
- Registers `BroadcastReceiver` for `USB_DEVICE_ATTACHED` / `USB_DEVICE_DETACHED`
- `MainActivity` forwards USB intents to the service
- Scan USB devices for ADB interface
- USB ADB connect via `UsbAdbConnection` (in protocol/ module), with `logSink` routing all ADB auth logs to `CarLogWriter`
- Launches phone app: `am start -n com.dilinkauto.client/.MainActivity`
- Dev mode: TCP ADB path uses `RemoteAdbController` instead of `UsbAdbConnection`, auto-reconnects on phone IP change

**State Flows:**
- `_state`, `_phoneName`, `_appList`, `_mediaMetadata`, `_playbackState`: primary state exposed to UI
- `_videoReady`: true when first non-config video frame arrives
- `_statusMessage`: human-readable status for UI display
- `_vdStackEmpty` (SharedFlow): emitted when phone reports VD has no activities (triggers navigation to home)

**VD Server Deploy:**
- Delegated to `VdServerDeployer.deploy()` (or `deployDirect()` for the dev-mode TCP-ADB path). Uses `VdDeploy.buildDeployPlan` to assemble the kill + launch commands with `CLASSPATH` from the handshake's `vdServerJarPath`. `shellBackground` keeps the ADB stream open so `app_process` isn't killed by a non-interactive shell exit. The deployer owns the retry counter and the TCP-ADB fallback policy (retries twice on transient "no ADB", then falls back to TCP ADB using the phone-host IP if known).
- Args: `W H DPI PORT EW EH FPS` — `targetFps` from `VideoConfig.TARGET_FPS` (24)

**Rotation Re-Handshake:**
- `onCarViewportChanged(widthPx, heightPx, dpi)` is called by `MainActivity.onConfigurationChanged` when the car panel rotates. If the viewport dims changed and the state is STREAMING or CONNECTED, the service cancels discovery loops, tears down video/input (clearing their disconnect listeners so `handleDisconnect` is not invoked for the intentional teardown), stops the decoder, clears `wifiReady`/`vdServerStarted`/`handshakeDone`, sets state to CONNECTING, and re-sends `HANDSHAKE_REQUEST` on the existing control connection with the new dims + current `startupDpi`. The phone tears down the old VD server, deploys a fresh one at the new dims, re-binds 9638/9639, and sends `VD_PORTS_BOUND` again — same flow as the initial connect, just without re-establishing the control TCP connection.

**Black Screen Re-Handshake:**
- `videoDecoder.onSustainedBlackScreen` is wired in `onCreate` to call `rehandshakeForBlackScreen()`, which rebuilds the phone-side VD at the *current* dimensions (no viewport change — the engine is wedged, not the display). Latched by `vdServerStarted && !blackScreenRecoveryInFlight` so at most one rebuild fires per session, preventing the reconnect storm that originally caused VD leaks.
- `rehandshakeOnExistingControl(ctrl, newVpW, newVpH, dpi)` — shared by the rotation and black-screen paths. Tears down video/input + stops the decoder, then sends `HANDSHAKE_REQUEST` on the existing control connection.

**Car Log Routing:**
- `CarLogWriter` routes all car-side logs through the DATA channel (`DataMsg.CAR_LOG`) to the phone. Callers do a non-blocking `send` (logcat + queue); a dedicated coroutine on `Dispatchers.IO` formats, encodes, and ships each line. When the control connection is down, lines are buffered in a bounded `ConcurrentLinkedQueue` (10k cap, `AtomicInteger` counter for O(1) cap check) and flushed on reconnect. `videoDecoder.logSink`, `CarCrashHandler.logSink`, and `adb.setLogSink()` all wire through `carLogSend` so every log path reaches the phone. All logs visible in phone's `/sdcard/DiLinkAuto/client.log`. Logging defaults to `BuildConfig.DEBUG` (off in release); the phone toggles it via `LOG_TOGGLE`.

### HandshakeFactory

Builds the car's `HandshakeRequest` to the phone (`service/HandshakeFactory.kt`). Populates `deviceName` (`DiLink-${Build.MODEL}`), viewport dims, DPI, `appVersionCode`/`appVersionName`, `targetFps`, and `dpiOverride`. The same builder is used at both call sites — initial WiFi connect and mid-stream rotation re-handshake — so only the viewport dims and DPI differ between the two sites; callers compute those from the current display state. Extracted from `CarConnectionService` to remove duplication between `connectToPhone` and `onCarViewportChanged`.

### VdServerDeployer

Owns deployment of the vd-server (`PipelineServer`) onto the phone from the car, via whichever ADB transport is currently available (USB or TCP), plus the retry/fallback policy (`service/VdServerDeployer.kt`). Two entry points:

- `deploy()` — runs the availability check, retry, and TCP-ADB fallback. Called from the state machine when both tracks are ready or after a USB-ADB connection completes post-handshake.
- `deployDirect(controller)` — for the dev-mode TCP-ADB path, which already has a fresh `RemoteAdbController` in hand and bypasses the availability probe.

Reads viewport dims + DPI from the host (which owns the display) and reports status through the host's message sink. `vdWidth`/`vdHeight` come from `getViewportSize`; DPI comes from `handshakeVdDpi` (if the phone adjusted it) or `VideoConfig.calculateOptimalDpi` otherwise. Uses `VdDeploy.buildDeployPlan` for the kill + launch commands. Extracted from `CarConnectionService` (which went 1237 → 1137 lines).

**VD exit wait**: Before launching a replacement engine, `waitForVdServerExit()` polls the phone over ADB using `VdDeploy.probeExitCodeCommand` (exit 0 = alive, exit 1 = gone). If the old engine doesn't exit within 3s, a force kill (`VdDeploy.stopCommand`) is issued. Two live engines race for the same VirtualDisplay / DTA / 9638-9639 binds and the loser never runs `cleanup()` — one leaked VD per reconnect, which is what turns the car screen black after a few cycles.

### CarLogWriter

Car → phone log relay (`service/CarLogWriter.kt`). Callers do a non-blocking `send` (logcat + queue); a dedicated coroutine on `Dispatchers.IO` formats, encodes, and ships each line as a `DataMsg.CAR_LOG` frame on the control connection. When the control connection is down, lines are buffered in a bounded `ConcurrentLinkedQueue` (10k cap, `AtomicInteger` counter) and flushed on reconnect, so diagnostic context around disconnects/crashes is preserved. `carLogEnabled` defaults to `BuildConfig.DEBUG`; the phone toggles via `LOG_TOGGLE`. Extracted from `CarConnectionService` to isolate the log machinery from the connection state machine.

### CarTouchSender

Encodes and sends touch events from the car to the phone's VD server on the input connection (port 9639, `service/CarTouchSender.kt`). A dedicated single-thread executor keeps touch encoding off the UI thread and serializes sends so the input channel's frame ordering stays stable. Drop/send counters gate log spam: only the first 3 and every 100th event is logged. Extracted from `CarConnectionService`.

### VideoDecoder

H.264 decoder using `MediaCodec` with Surface output (GPU-direct rendering).

- Codec creation: `MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)` — hardware-first via `REGULAR_CODECS` (lists hardware decoders first; selects `OMX.qcom.video.decoder.avc` on the BYD). The speculative `findHardwareAvcDecoder` + `createByCodecName` picker was removed because it called `MediaCodecInfo.isHardwareAccelerated()`, an API 29+ method that threw `NoSuchMethodError` on the API-28 BYD head unit and killed the process.
- Frame queue: 4 frames — small buffer, drop on overflow
- `onFrameReceived()`: queues frames even before `start()` is called; CONFIG and keyframes are cached separately
- `start()`: feeds cached CONFIG first, then the cached IDR keyframe (so the codec has a reference frame immediately), then drains the queue
- Drop-oldest on queue full: prefers dropping P-frames, evicts queued P-frames for keyframes/CONFIG
- `KEY_LOW_LATENCY = 1`, `KEY_PRIORITY = 0`, `KEY_OPERATING_RATE = fps` for minimum decode delay
- CONFIG (SPS/PPS) cached and replayed on decoder restart; a fresh CONFIG also discards the stale cached keyframe
- Post-flush IDR resync: after 10+ consecutive `dequeueInputBuffer` drops the codec is flushed, CONFIG re-fed, and `seekingKeyFrame` set; the feed loop drains P-frames until the next IDR arrives before resuming normal feeding
- `isRunning` property for early-start coordination
- `switchSurface(newSurface)` uses `MediaCodec.setOutputSurface()` to re-attach a new surface without stop/start (eliminates the keyframe-drop storm that happens with stop()+start())
- `invalidateSurface()` gates `releaseOutputBuffer`'s render flag off so frames aren't pushed to a destroyed surface during navigation hide/show
- `outputSurfaceValid` gate prevents rendering to a destroyed surface across navigation hide/show
- `logSink` callback routes all decoder logs to phone via `carLogSend`
- `debugFrameStats` flag (toggled by `LOG_TOGGLE`): per-30-frame log includes cumulative decode time and queue depth
- Feed thread runs at `Process.THREAD_PRIORITY_URGENT_DISPLAY` (8x A53 has no big cores; tell the scheduler this is latency-critical)

**Black screen self-heal:**

- `onSustainedBlackScreen: (() -> Unit)?` — callback fired when the stream has been persistently black for `BLACK_SCREEN_SUSTAIN_MS` (5000ms). A handful of tiny I-frames right after start is normal (the VD can briefly composite nothing before the first app frame lands), and re-handshaking on that would cause reconnect loops — the very thing that leaked VDs. Requiring a sustained window means we only escalate on a genuinely stuck stream.
- `tinyKeyframeStreak` / `blackScreenAlerted` — existing detection: after 3 consecutive tiny keyframes (<2048 bytes), logs "Suspected BLACK SCREEN".
- `blackScreenSinceMs` / `blackScreenRecoveryFired` — tracks how long the stream has been black. Fires `onSustainedBlackScreen` only after 5s of continuous tiny keyframes, and at most once per session.
- `resetBlackScreenState()` — re-arms the detector at each `start()` and `stop()`.

### AppIconCache

Car-side icon cache that decodes and resizes icons once, then serves them instantly during scroll.

- `putSource(packageName, pngBytes)`: Stores high-resolution (192x192) source PNG received from phone via app list. Persists to disk as `{packageName}_src.png`.
- `prepareAll(apps, sizePx)`: Decodes and resizes all icons in parallel on background threads, bounded by `Semaphore(4)` (8 A53 cores; cap parallelism to bound native heap churn). Increments `preparedVersion` to trigger UI recomposition. Intermediates recycled.
- `getPrepared(packageName)`: Synchronous O(1) `ConcurrentHashMap` lookup — zero coroutines, zero I/O, zero decoding during scroll. Returns ready-to-render `ImageBitmap`.
- `get(packageName, sizePx)`: Full decode+resize path (used by NavBar for few icons). Returns `Bitmap` at requested pixel size. Does not pollute the grid-size `prepared` cache.
- `evict(packageName)`: Removes cached data for uninstalled apps.
- `clear()`: Clears all in-memory and on-disk icon caches. Called on disconnect — the phone resends icons on the next `APP_LIST`, so retaining them across sessions just wastes ~5-9MB of heap on a 4GB device and grows the eMMC cache unbounded.

### ServerApp

Application class. Creates notification channel `dilinkauto_car_service` with `IMPORTANCE_LOW`. Initializes `AppIconCache` at `filesDir/icons`. Installs `CarCrashHandler` and logs device info on create.

### CarCrashHandler

Uncaught exception handler for the car app (`CarCrashHandler.kt`). On crash: saves a crash report with stack trace and device info to `filesDir/crash-pending.log`. The report is sent to the phone on the next successful connection via `carLogSend`. `consumePendingCrash()` returns and archives the pending report so it isn't re-sent on every connection. `buildDeviceInfo(context)` extends the shared `DeviceInfo.buildDeviceInfoBlock` with a Memory section and `memThreshold` line.

### RemoteAdbController

Direct ADB client using a persistent TCP ADB connection (`adb/RemoteAdbController.kt`). Uses `TcpAdbConnection` which keeps a single socket open for all commands. Provides `shell`, `shellBackground` (keeps ADB stream open — prevents `app_process` from being killed by a non-interactive shell exit), `shellNoWait`, and `disconnect`. Used by the dev-mode TCP-ADB path and by `VdServerDeployer.deployDirect`.

### CarShell

Top-level car shell: streaming layout (mirror + nav bar) or launch screen (`ui/screen/CarShell.kt`). Extracted from `MainActivity` so the Activity holds only its lifecycle concerns (service binding, USB intent forwarding, immersive mode, rotation re-handshake) and this file owns the Compose tree and screen-state routing.

- `showStreamingMode = appList.isNotEmpty() && (isConnected || state == CONNECTING)` — the CONNECTING branch keeps the streaming layout visible during a mid-stream rotation redeploy so the video-wait overlay covers the gap instead of flashing `CarLaunchScreen`.
- `CarContentArea`: shared content area for both landscape (Row) and portrait (Column) layouts — renders `MirrorContent`, the "waiting for video" overlay when the stream isn't ready, and `HomeContent` when current screen is HOME.
- App info dialog (`AlertDialog`) shown when `appInfoData` is non-null.
- `vdStackEmpty` collected → switches to HOME screen.

### MainActivity

Car-side launcher Activity (`MainActivity.kt`). Owns only process-level concerns:

- Service bind / unbind and forwarding the USB-attach intent (`ACTION_USB_DEVICE_ATTACHED`) to `CarConnectionService`.
- Immersive mode (sticky fullscreen via `SYSTEM_UI_FLAG_IMMERSIVE_STICKY` on focus gain).
- Rotation re-handshake: `onConfigurationChanged` pushes the new viewport dims into `CarConnectionService.onCarViewportChanged` so the phone recreates the VD at the new orientation without tearing down the bound service. `configChanges` in the manifest (`orientation|screenSize|smallestScreenSize|screenLayout|keyboardHidden`) lets the Activity receive `onConfigurationChanged` instead of being recreated.
- No `screenOrientation` lock — the car screen may be a rotatable panel.

The Compose tree (streaming vs launch screen, nav bar, app-info dialog) lives in `CarShell`.

### CarLaunchScreen

Full-screen connection-focused composable shown before the phone connection is established — no nav bar, no app grid (`ui/screen/CarLaunchScreen.kt`).

- DiLink Auto branding (icon, title, tagline)
- `ConnectionStatusCard` with colored indicator dot (green=streaming, orange=connected/connecting, gray=idle) and live status text. Contains:
  - Dev mode toggle (TCP ADB instead of USB)
  - `startup_dpi` numeric input — 0 = Auto (use `VideoConfig.calculateOptimalDpi`), [120, 480] = override (coerced via `VdDeployArgs.coerceDpiOverride`). ASCII-only digit filter; the field displays the parent state directly to avoid drift. Read at handshake construction time, so a change takes effect on the next connect or mid-stream rotation re-handshake.
- "How to connect" instructions: 4 numbered steps
- Manual IP entry (`ManualConnectBox`)
- Replaced by streaming mode layout when `appList` becomes non-empty and state reaches CONNECTED/STREAMING

### ConnectionStatusScreen

Connection-status panel shown inside `HomeContent` when the link is not yet streaming (`ui/screen/ConnectionStatusScreen.kt`). Distinct from `CarLaunchScreen` (the full pre-streaming screen) — this composable shares `ManualConnectBox` with it. Persists the last-used IP and pre-fills with the WiFi gateway when no saved IP exists.

### NowPlayingBar

Bottom bar showing the current media track and transport controls (`ui/screen/NowPlayingBar.kt`). Only composed inside `HomeContent` when `MediaMetadata` is present. Large touch targets (48dp skip, 56dp play/pause) for car use.

### PersistentNavBar / PersistentBottomNavBar

Landscape (left rail, `PersistentNavBar`) and portrait (bottom bar, `PersistentBottomNavBar`) navigation bars — **only shown in streaming mode**. Both render the same trimmed action set: **Eject (disconnect) / Home / Back** — three buttons only. Notifications button, recent-apps rail, clock, and network info were removed. 76dp bar (`NAV_BAR_TARGET_DP = 76f`), 40dp icons (label exposed as `contentDescription` only — no text composable). Width computed to guarantee an even viewport for the H.264 encoder (`navBarWidthPx` adjusts by +1px to make the viewport even).

### HomeScreen / HomeContent / AppGrid / AppTile

The live composables formerly in `LauncherScreen.kt` now live in `HomeScreen.kt`.

- `HomeContent`: main content area when streaming mode is active. Shows `AppGrid` when streaming with apps, an explanatory empty state when streaming with an empty allowlist, or `ConnectionStatus` otherwise. Renders `NowPlayingBar` when media metadata is present.
- `AppGrid`: 64dp app icons in dynamically calculated fixed grid columns (3-12 based on available width / 100dp). Search field with `imePadding()`. Alphabetical sort, pinned apps first. `windowSoftInputMode="adjustResize"` in manifest.
- `AppTileData` (`@Immutable`): stable wrapper for `AppTile` inputs. `AppInfo` carries an unstable `ByteArray` (`iconPng`), which made `AppTile` non-skippable and forced full-grid recomposition on any `appList` reassignment. This wrapper excludes the byte array — `AppTile` reads the prepared icon from the cache by packageName, so the wrapper's identity is the only input that matters for recomposition.
- `AppTile`: long-press opens a context menu with **Pin to Top / Unpin**, **Uninstall**, and **App Info**. Pin state persists to SharedPreferences (`dilinkauto_pinned`). App uninstall sends `APP_UNINSTALL` via control channel; phone processes the system dialog and sends `APP_UNINSTALLED` to refresh the car's app list. `AppInfoDataMessage` displays app info in a car-side dialog.

### MirrorScreen

Mirror content — `SurfaceView` for video + touch forwarding (`ui/screen/MirrorScreen.kt`). SurfaceView (not TextureView) so the decoder's output goes through a hardware overlay instead of an extra per-frame GL composite pass. On the Adreno 505 the TextureView composite cost (~2-5ms/frame at 1280x800) is a meaningful slice of the 42ms budget at 24fps; SurfaceView bypasses it entirely.

Tradeoff: SurfaceView destroys its surface when the view goes INVISIBLE, where TextureView kept it alive. Navigation HOME<->APP toggles `visible`, so `surfaceCreated`/`surfaceDestroyed` fire on each switch. The decoder is NOT stopped on `surfaceDestroyed` — it stays running, and `VideoDecoder.invalidateSurface()` gates the render flag off so frames aren't dropped to a destroyed surface. `surfaceCreated` calls `switchSurface` (`setOutputSurface`) to re-attach, restoring rendering with zero keyframe loss. `handleDisconnect`/`shutdown` owns the final decoder stop.

The persistent nav bar is a sibling (not overlapping) this composable in both landscape and portrait layouts (`CarShell`), so SurfaceView's default z-order — surface below the window hierarchy — renders the nav bar above the video correctly.

Touch forwarding: `ACTION_DOWN`/`ACTION_POINTER_DOWN`/`ACTION_UP`/`ACTION_POINTER_UP` send single-pointer `TouchEvent`s; `ACTION_MOVE` batches all active pointers into one `TouchMoveBatch` (reduces syscalls for multi-touch); `ACTION_CANCEL` releases all pointers to prevent ghost fingers. Coordinates are normalized to `[0,1]` by dividing by view dimensions.

### NavBarComponents

Individual nav bar widget composables (`ui/nav/NavBarComponents.kt`). `NavActionButton` (40dp icon, no label text — the label is exposed as `contentDescription` only) is the only remaining widget — `ClockDisplay`, `NetworkInfo`, `RecentAppIcon`, and `NotificationsButton` were deleted along with the features they backed.

### CarTheme

Material3 dark color scheme (`CarDark`) with category-specific app tile colors: Navigation (green), Music (pink), Communication (blue), Other (gray). Tokenized colors (`onSurfaceVariant = 0xFFAAAAAA` replaces scattered literal grays; error red consolidated to a single `0xFFEF5350`). Typography floors labels to 14sp (`labelSmall`/`labelLarge` both 14sp).

### Car Display Info

Tested on BYD DiLink 4.0 (Qin PLUS DM-i 2023 Champion 55KM Leading trim — low-spec):
- SoC: Snapdragon 439 (8x Cortex-A53, Adreno 505), 4GB RAM, 16GB eMMC
- Screen: 1280x800 @ 240dpi, 2.4GHz-only WiFi, Android 9 / API 28
- H.264 hardware decode capped at 1080p

## DiLink 4.0 Low-Spec Performance

Nine fixes target the Snapdragon 439 / Adreno 505 / 4GB RAM head unit:

1. Encode dims capped to 1920x1080 (decoupled from VD dims via `EW EH` args; VD stays scaled-up to preserve the IME-crop fix, encode uses car-reported dims clamped to 1080p). Without this, >1080p phones trigger software decode on the 439 → single-digit fps.
2. Bitrate 8 Mbps → 4 Mbps; adaptive fallback tightened (floor 1.5 Mbps, 2s recovery window, 0.5 Mbps steps — was 2 Mbps / 5s / 1 Mbps).
3. Framerate 30 → 24 fps (`VideoConfig.TARGET_FPS = 24`). Per-frame budget 33ms → 42ms, -20% WiFi/GPU/allocation.
4. MirrorScreen TextureView → SurfaceView (bypass Adreno 505 per-frame GL composite; `outputSurfaceValid` gate prevents rendering to destroyed surface across navigation hide/show).
5. Thread priorities: `THREAD_PRIORITY_URGENT_DISPLAY` on encode/decode/socket-drain threads; `THREAD_PRIORITY_BACKGROUND` on LifeWriter. A53 has no big cores.
6. `carLogEnabled` defaults to `BuildConfig.DEBUG` (was true); `ConcurrentLinkedQueue.size()` (O(n)) → `AtomicInteger`.
7. `AppIconCache.clear()` on disconnect (frees ~5-9MB heap + eMMC PNGs); `prepareAll` parallelized with `Semaphore(4)`, intermediates recycled.
8. `Debug.getPss()` removed from startup (deprecated binder call, 100-500ms on A53).
9. `@Immutable AppTileData` wrapper so the icon grid skips recomposition on `appList` reassignment.

## API 28 Car Compatibility

- `MediaCodecInfo.isHardwareAccelerated()` (API 29+) threw `NoSuchMethodError` on the BYD API-28 head unit, killing the process. Fix: removed the speculative hardware-decoder picker; `VideoDecoder.start()` calls `MediaCodec.createDecoderByType` directly.
- app-server `minSdk` 24 → 26; protocol `minSdk` 24 → 26 (re-arms NewApi lint gate).
- `am display move-stack` (API 29+) gated on `SDK_INT >= 29` with log on older levels (was silently masking shell failure as success).
- `cmd display power-on/off` shell fallback is API 29+; on API 26-28 a DisplayControl reflection failure means the physical panel is not restored — now logged, was silent.

## Rotation Black-Screen Fix

`onCarViewportChanged` sets state to CONNECTING and tears down the VD for redeploy, but the streaming-layout gate used `isConnected` (false during the ~2s redeploy) → `CarShell` flipped to `CarLaunchScreen` → SurfaceView removed → new surface couldn't restart the decoder. Gate now also accepts `state == CONNECTING && appList.isNotEmpty()`. See `CarShell.showStreamingMode`.

## Module Structure

```
app-server/
├── service/        CarConnectionService, HandshakeFactory, VdServerDeployer, CarLogWriter, CarTouchSender
├── decoder/        VideoDecoder
├── adb/            RemoteAdbController
├── ui/screen/      CarShell, CarLaunchScreen, HomeScreen, MirrorScreen, ConnectionStatusScreen, NowPlayingBar
├── ui/nav/         PersistentNavBar, NavBarComponents
├── ui/theme/       CarTheme
├── ServerApp.kt
├── CarCrashHandler.kt
└── MainActivity.kt
```

minSdk 26, compileSdk 34, Kotlin 1.9.22, Android Gradle 8.2.2, JDK 17.

## Dependencies

- Jetpack Compose (BOM 2023.10.01) + Material 3 + material-icons-extended
- Protocol module (shared with phone app; includes `UsbAdbConnection`, `AdbProtocol`, `TcpAdbConnection`, `VideoConfig`, `Discovery`, `VdDeploy`, `VdDeployArgs`, `AppPrefs`, `WifiGatewayIp`)
- `TcpAdbConnection` (in protocol/) for TCP ADB fallback

See [architecture.md](./architecture.md) for the cross-module diagram, [protocol.md](./protocol.md) for the wire format and port assignments, and [client.md](./client.md) for the phone-side orchestrator. Download from [Releases](https://github.com/ID-VerNe/dilink-auto-android/releases/latest).
