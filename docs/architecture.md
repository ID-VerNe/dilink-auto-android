# Architecture

## Overview

DiLink-Auto is a five-module Gradle project that mirrors a phone's VirtualDisplay onto a BYD DiLink car head unit over WiFi. The phone runs the launcher apps on a shell-UID VirtualDisplay; the VD server encodes the rendered frames to H.264 and streams them directly to the car. The phone is a pure orchestrator — it does not relay video or touch.

```
DiLink-Auto/
├── protocol/        Android library -- shared by all three apps (Gradle module)        minSdk 26
├── app-client/      Android application -- runs on the phone                            minSdk 29
├── app-server/      Android application -- runs on the car                              minSdk 26
├── vd-server/       Android library -- shell-privileged process, compiled to a JAR      minSdk 29
└── docs/            documentation
```

Tested against a BYD Qin PLUS DM-i 2023 Champion 55KM Leading trim — a DiLink 4.0 low-spec head unit (Snapdragon 439, 8x Cortex-A53, Adreno 505, 4GB RAM, 16GB eMMC, 1280x800, 2.4GHz-only WiFi, Android 9 / API 28, H.264 hardware decode capped at 1080p). The streaming pipeline is tuned for this hardware; see the DiLink 4.0 perf section for the rationale.

## Virtual Display Architecture

The **phone** creates a VirtualDisplay at the car's viewport resolution and a shell-UID process (`vd-server`) that owns the encoder. The VD server binds `9638` (video) and `9639` (input) directly; the car connects to those ports on the phone's IP and exchanges H.264 + touch with the VD server, with no phone-app relay in the hot path. The phone app's role is orchestration: handshake, VD lifecycle (the `VD_PORTS_BOUND` control message), and car-log routing. `VirtualDisplayClient` on the phone is lifecycle-only — it owns the localhost command channel, not video or touch.

A separate lifecycle channel on `localhost:19647` carries `MSG_DISPLAY_READY` / `MSG_STACK_EMPTY` / `CMD_STOP` between the phone and the VD server. The VD server reverse-connects to the phone on this port (NIO, non-blocking).

```
+- Phone -----------------------------------------------------------+
|                                                                   |
|  +---------------------+                                          |
|  | Physical Screen      |  Independent -- user can use phone      |
|  | (shows DiLink Auto   |  normally while streaming               |
|  |  app or anything)    |                                          |
|  +---------------------+                                          |
|                                                                   |
|  +---------------------+      +--------------------------------+  |
|  | ConnectionService    |      | VD Server (shell UID 2000)     |  |
|  | 1 NIO TCP server     |      | Deployed by PHONE app          |  |
|  |  9637: control+data  | 19647| Reverse-connects lifecycle     |  |
|  |  Handshake           |  NIO | VirtualDisplay (car viewport)  |  |
|  |  VD lifecycle        |      | GL render -> H.264 encoder     |  |
|  |  Car-app install     |      | TCP write 9638 (video)         |  |
|  |  Car log routing     |      | Touch read 9639 (input)        |  |
|  |  Allowlist filter    |      | Display power + IME restore    |  |
|  +----------+-----------+      | App launcher (am start)        |  |
|             |                  | Input injector (IInputManager) |  |
|             |                  +--------------------------------+  |
+-------------+----------------------+-------------------------------+
              | WiFi TCP (control)   | WiFi TCP (direct VD <-> car)
              | 9637: control + data | 9638: H.264 video
              |                      | 9639: touch input
              v                      v
+- Car (BYD DiLink 4.0) --------------------------------------------+
|                                                                   |
|  CarConnectionService                                             |
|  Parallel connection model: WiFi control + WiFi direct video/input|
|  + USB track (ADB) and TCP ADB fallback                           |
|                                                                   |
|  Track A (WiFi control):                                          |
|  +-- Gateway IP + mDNS discovery                                  |
|  +-- Control connect (9637) -> handshake (viewport+DPI+FPS)       |
|  +-- Awaits VD_PORTS_BOUND -> connectVideoAndInput (9638/9639)    |
|  +-- Receives H.264 video -> VideoDecoder -> SurfaceView          |
|  +-- Sends touch events via input connection (9639)               |
|                                                                   |
|  Track B (USB ADB / TCP ADB):                                     |
|  +-- Used to deploy the VD server onto the phone (shell UID)      |
|  +-- USB host mode OR TCP ADB to phone:5555                       |
|  +-- Launch phone app (am start)                                  |
|                                                                   |
|  UI: CarLaunchScreen -> (app icons arrive) -> CarShell + NavBar   |
|  Two modes: launch (connection-focused, no nav) and streaming     |
|  Nav bar (rail or bottom): Eject / Home / Back only               |
|  40dp icons                                                       |
+-------------------------------------------------------------------+
```

### Direct VD streaming (no phone relay)

Earlier releases routed video through the phone app: VD server wrote frames to localhost, the phone forwarded them to the car over WiFi. That was four socket operations and two process context switches per frame. The current architecture binds `9638` and `9639` on the VD server itself (`0.0.0.0`); the car connects to the phone's IP on those ports directly. Two socket ops, zero context switches. The phone's `VirtualDisplayClient` was simplified to lifecycle-only — it accepts the VD server's reverse connection on `localhost:19647`, reads `MSG_DISPLAY_READY` / `MSG_STACK_EMPTY`, and sends `CMD_STOP` on teardown. Video and touch no longer touch the phone app.

### Why shell UID for the VD server

`app_process` must run as shell UID (2000) to create VirtualDisplays that can host third-party apps and to reflect into `InputManager` for touch injection. The phone deploys `vd-server.jar` to `/sdcard/DiLinkAuto/` and starts it via `CLASSPATH=jar app_process / com.dilinkauto.vdserver.PipelineServer W H DPI PHONE_HOST EW EH FPS` (the argv tail is built by `VdDeployArgs.format`). Three deploy sites share `VdDeploy.buildDeployPlan`: phone Shizuku, car USB ADB, car TCP ADB.

## Module Responsibilities

### protocol (Android Library, minSdk 26)

Shared by all three apps. Zero UI dependencies. Owns the wire format, the ADB client, and the cross-module constants that used to diverge across files (`AppPrefs`, `AppTargets`, `VdDeploy`, `VdDeployArgs`, `WifiGatewayIp`, `ImeRestore`).

| Component | File | Purpose |
|-----------|------|---------|
| Frame codec | `FrameCodec.kt` | Binary frame encoding/decoding, reusable header buffer, NIO `writeAll` with 5s deadline |
| Channels | `Channel.kt` | Channel IDs (control, video, audio, data, input) — each on a dedicated TCP connection |
| Control messages | `MessageType.kt` | `ControlMsg`, `VideoMsg`, `AudioMsg`, `DataMsg`, `InputMsg` byte constants. `VD_PORTS_BOUND` (0x31) signals the car to connect video/input directly to the VD server. `LOG_TOGGLE` toggles car-side logging. |
| Messages | `Messages.kt` | Serializable data classes. `HandshakeRequest` carries `appVersionCode`, `appVersionName`, `targetFps`, `dpiOverride` (0 = auto, [120, 480] = user value). `HandshakeResponse` echoes `vdDpi`. |
| Connection | `Connection.kt` | TCP connection with heartbeat/watchdog (10s timeout), bounded coroutine-channel write queue (64 frames, suspends on backpressure), `THREAD_PRIORITY_URGENT_DISPLAY` on the reader thread |
| VideoConfig | `VideoConfig.kt` | `TARGET_FPS = 24`, `FRAME_INTERVAL_MS = ~42ms`, `calculateOptimalDpi(width, height, carReportedDpi)` — caps DPI so portrait-only apps (Amap, WeChat) get >=360dp logical width in a landscape VD |
| NioReader | `NioReader.kt` | Selector-based non-blocking reader, configurable select timeout |
| Discovery | `Discovery.kt` | mDNS service registration/discovery. Port constants: `DEFAULT_PORT=9637`, `VIDEO_PORT=9638`, `INPUT_PORT=9639`, `LIFECYCLE_PORT=19647`, `ADB_PORT=5555` |
| UsbAdbConnection | `adb/UsbAdbConnection.java` | ADB protocol over USB (CNXN, AUTH, OPEN, WRTE), `logSink` callback |
| AdbProtocol | `adb/AdbProtocol.java` | ADB message constants and serialization |
| TcpAdbConnection | `adb/TcpAdbConnection.kt` | Persistent ADB over TCP — single socket reused for all shell commands. Used by the car-side `RemoteAdbController`. (`dadb` is still used by the phone-side `CarAppInstaller` for the car APK install.) |
| Cross-module constants | `AppPrefs.kt`, `AppTargets.kt`, `VdDeploy.kt`, `VdDeployArgs.kt`, `WifiGatewayIp.kt`, `ImeRestore.kt` | Shared SharedPreferences keys, `am start` component strings, `app_process` kill/launch command builders, DPI override range (120..480), WiFi gateway formatter, IME restore commands + `linkpc` exclusion predicate |

### app-client (Phone Application, minSdk 29)

Manages handshake, VD lifecycle, car-app install, allowlist filtering, and `FileLog`.

| Component | File | Purpose |
|-----------|------|---------|
| ConnectionService | `service/ConnectionService.kt` | Accepts 9637, runs the handshake, opens the lifecycle ServerSocket on `0.0.0.0:19647`, deploys the VD server (Shizuku when available), sends `VD_PORTS_BOUND` on `MSG_DISPLAY_READY`, routes car logs to `FileLog`, applies the allowlist before sending the app list. `cleanupGuard` `AtomicBoolean` prevents 7 `cleanupSession()` call sites from firing repeatedly; `resetCleanupGuard()` before each new session. `VD_EXIT_WAIT_MS` (3000ms) before launching a replacement engine to avoid two live instances racing for the same VD/DTA/ports |
| VirtualDisplayClient | `display/VirtualDisplayClient.kt` | Lifecycle-only. `startListening()` opens the ServerSocket synchronously; `acceptConnection()` waits for the VD server's reverse connection on `localhost:19647`, reads `MSG_DISPLAY_READY` (displayId + direct-injection flag), relays `MSG_STACK_EMPTY`, sends `CMD_STOP` |
| VdDimensions | `service/VdDimensions.kt` | Pure viewport math: car viewport (even-aligned) + anti-crop scale for Chinese-ROM IME hardcoding + DPI override vs. auto-calibrate |
| AppListBuilder | `service/AppListBuilder.kt` | Builds the car-visible app list, filtered by the user's allowlist. Icon PNGs sent once per package per session (hash-suppressed) |
| AllowlistScreen | `AllowlistScreen.kt` | Phone-side picker for which launcher apps reach the car. Toggling a row fires `ACTION_ALLOWLIST_UPDATED` so the running service re-sends the list live |
| CarAppInstaller | `service/CarAppInstaller.kt` | Installs the embedded `app-server.apk` onto the car via `dadb` over WiFi (15s connect timeout) |
| CarIpLocator | `service/CarIpLocator.kt` | Locates the car's ADB-over-WiFi service (port 5555): control-connection remote IP, subnet enumeration, ARP, neighbor cache, parallel /24 scan, gateway |
| PhoneDisplayRestorer | `service/PhoneDisplayRestorer.kt` | Restores the phone's physical display and IME after the VD server tears down. **Owns a process-lifetime `CoroutineScope(SupervisorJob() + Dispatchers.IO)`** — deliberately not the Service's scope, which `onDestroy()` cancels. `NonCancellable` context ensures `cmd display power-on` cannot be skipped mid-restore. `inFlight` `AtomicBoolean` collapses concurrent restore requests into one. Layered: Shizuku (`VdDeploy.stopCommand` + `cmd display power-on` + IME restore), then `PowerManager.wakeUp`, then `FLAG_TURN_SCREEN_ON`, then a wake lock |
| FileLog | `FileLog.kt` | File-based logging to `/sdcard/DiLinkAuto/client.log`, rotation (10 files max), bypasses HyperOS logcat filtering. Defaults to ON for debug/pre-release, OFF for release; user choice persists via `AppPrefs.LOG_ENABLED` |
| MainActivity | `MainActivity.kt` | UI — start/stop, onboarding, settings, allowlist, install-on-car |

### app-server (Car Application, minSdk 26)

Parallel connection model: WiFi control (9637) + WiFi direct video/input (9638/9639), with USB ADB and TCP ADB tracks for VD-server deployment.

| Component | File | Purpose |
|-----------|------|---------|
| CarConnectionService | `service/CarConnectionService.kt` | Parallel state machine. WiFi track: gateway IP + mDNS + control connect + handshake + await `VD_PORTS_BOUND` + connect video/input directly to the VD server. USB/TCP ADB track: deploy VD server onto the phone. Rotation triggers a mid-stream re-handshake on the reused control connection. Reconnect loop stops after 3 consecutive ADB failures (`noAdbCount >= 3`); active sessions are not killed by reconnect attempts |
| VideoDecoder | `decoder/VideoDecoder.kt` | H.264 hardware decode via `MediaCodec.createDecoderByType(MIMETYPE_VIDEO_AVC)` (REGULAR_CODECS lists hardware first; selects `OMX.qcom.video.decoder.avc` on the BYD). 4-frame `ArrayBlockingQueue`, post-flush IDR resync (skips P-frames until a keyframe arrives), `outputSurfaceValid` gate prevents rendering to a destroyed surface, `debugFrameStats` per-30-frame decode-time log. Feed thread at `THREAD_PRIORITY_URGENT_DISPLAY` |
| AppIconCache | `AppIconCache.kt` | Car-side icon cache. `prepareAll` decodes/resizes all icons to grid size in parallel (`Semaphore(4)`), intermediates recycled. `getPrepared` is O(1) `ConcurrentHashMap` lookup. `clear()` on disconnect frees ~5-9MB heap + eMMC PNGs |
| CarShell | `ui/screen/CarShell.kt` | Streaming layout (mirror + nav bar) or launch screen. Accepts `CONNECTING` state during mid-stream rotation re-handshake so the video-wait overlay covers the redeploy gap instead of flashing CarLaunchScreen |
| MirrorScreen | `ui/screen/MirrorScreen.kt` | `SurfaceView` (not `TextureView`) — bypasses Adreno 505 per-frame GL composite. `surfaceCreated` calls `switchSurface` to re-attach the decoder with zero keyframe loss across HOME<->APP navigation; `surfaceDestroyed` gates the render flag off without stopping the decoder |
| CarLaunchScreen | `ui/screen/CarLaunchScreen.kt` | Full-screen launch/connection screen, branding, manual IP, dev-mode toggle, startup-DPI numeric input |
| HomeScreen | `ui/screen/HomeScreen.kt` | App grid (64dp icons, long-press Pin-to-Top / Unpin / uninstall / app info). `@Immutable AppTileData` wrapper excludes the unstable `ByteArray` icon so the grid skips recomposition on `appList` reassignment |
| PersistentNavBar | `ui/nav/PersistentNavBar.kt` | Three buttons only: Eject / Home / Back. Landscape: left rail. Portrait: bottom bar. Width computed to guarantee an even viewport for the H.264 encoder |
| NavBarComponents | `ui/nav/NavBarComponents.kt` | `NavActionButton` — 40dp icon, `onSurfaceVariant` tint |
| HandshakeFactory | `service/HandshakeFactory.kt` | Builds `HandshakeRequest` for both initial connect and mid-stream rotation re-handshake |
| VdServerDeployer | `service/VdServerDeployer.kt` | Deploys the VD server onto the phone from the car via USB ADB or TCP ADB. `shellBackground` keeps the ADB stream open so `app_process` is not killed by shell exit. Encode dims clamped to 1920x1080 (Snapdragon 439 VPU cap) |
| CarLogWriter | `service/CarLogWriter.kt` | Car -> phone log relay. `ConcurrentLinkedQueue` buffer with `AtomicInteger` counter (O(1) cap check — was O(n) `size()`), 10k line cap when control connection is down |
| CarTouchSender | `service/CarTouchSender.kt` | Encodes and sends touch events on the input connection (9639). Single-thread executor serializes sends |
| RemoteAdbController | `adb/RemoteAdbController.kt` | Persistent TCP ADB connection to the phone via `TcpAdbConnection` |
| CarCrashHandler | `CarCrashHandler.kt` | Uncaught exception handler. Saves a crash report to `filesDir/crash-pending.log`; flushed to the phone via `carLogSend` on the next successful connection |
| CarTheme | `ui/theme/CarTheme.kt` | Dark color scheme, tokenized colors (`onSurfaceVariant` replaces `Color.Gray` / `0xFF888888`), labels floored to 14sp |
| MainActivity | `MainActivity.kt` | Fullscreen immersive, USB intent forwarding, rotation re-handshake (`onCarViewportChanged`) |

### vd-server (Shell-Privileged Process, minSdk 29)

Android library module (`com.android.library`), compiled via `bundleLibRuntimeToJarDebug` then D8 into a JAR by the `buildVdServer` task in `app-client/build.gradle.kts`. Depends on `:protocol` and `kotlinx-coroutines-core`. Deployed by the phone to `/sdcard/DiLinkAuto/vd-server.jar`. Runs as shell via `app_process`.

| Component | File | Purpose |
|-----------|------|---------|
| PipelineServer | `PipelineServer.kt` | Process entry point and lifecycle owner. Creates the encoder (`createEncoderByType`, CBR 4Mbps Main profile, I-frame interval 1s), binds `9638` (video) and `9639` (input) on `0.0.0.0`, accepts the car's connections, owns the persistent shell, the LifeWriter, the watchdog (forces `cleanup()` if the pipeline thread hangs in a native MediaCodec call), and cleanup ordering (reset global window/rotation state → move foreground app → restore panel + IME → release threads/GL/encoder/VD). Physical panel power-off happens AFTER the car connects, not before |
| GlPipeline | `GlPipeline.kt` | EGL14 + GLES20 render loop on the pipeline thread. Single-threaded: `parkNanos` pace -> `updateTexImage` -> fullscreen quad -> `eglSwapBuffers` -> encoder drain -> TCP write. Natural flow control: a TCP stall blocks the next swap, slowing encoder input. Adaptive bitrate: floor 1.5Mbps, 2s clean recovery window, 0.5Mbps steps (down-shifts at >15ms write time) |
| TouchInjector | `TouchInjector.kt` | `InputManager` reflection (`injectInputEvent`), `MotionEvent.setDisplayId` reflection, multi-touch state. Falls back to `input -d` shell taps when reflection is unavailable |
| DisplayPowerController | `DisplayPowerController.kt` | `DisplayControl.setDisplayPowerMode` reflection (loaded from `services.jar` via `DelegateLastClassLoader`); shell fallback `cmd display power-on/off` is API 29+. On API 26-28 the fallback is a no-op — a `DisplayControl` reflection failure means the physical panel is not restored (now logged, was silent). Persists and restores the original IME (gated by `ImeRestore.shouldRestoreIme` — excludes the linkpc IME) |
| VirtualDisplayCreator | `VirtualDisplayCreator.kt` | Shell-UID VirtualDisplay via `DisplayManagerGlobal` reflection (trust flag `0x6c49`, `OWN_DISPLAY_GROUP` + `OWN_FOCUS` + `TRUSTED`), `DisplayManager` fallback with `mDisplayIdToMirror` forced to 0. Applies the Android 12L+ letterbox style so portrait apps render at a sensible aspect ratio. `create()` only creates the VD — `configureEnvironment()` (which applies letterbox style + disables screen-off/wake gestures) must be called separately AFTER the caller has snapshotted the original settings via `DisplayPowerController.saveCurrentIme()` |
| FakeContext | `FakeContext.kt` | Spoofs `com.android.shell` for DisplayManager access. Uses `ActivityThread.getSystemContext()` for a real system Context |
| PipeLog | `PipeLog.kt` | `println`-based logging (no `android.util.Log` in `app_process`); `ShellExec` runs commands against the persistent `sh` process |

**PipelineServer architecture.** Single pipeline thread processes each frame sequentially — `clock.wait()` -> `updateTexImage()` -> GL render -> `eglSwapBuffers` -> encoder drain -> TCP write. No queues between stages. Flow control is natural: if TCP stalls, the pipeline blocks, delaying the next swap. Uses `System.nanoTime()` + `LockSupport.parkNanos()` for drift-free 24fps timing. Three threads total: Pipeline (urgent priority), TouchReader, Lifecycle/LifeWriter (background priority).

**VD teardown (`cleanup()`).** Idempotent (guarded by `cleanedUp` `AtomicBoolean`). Order is deliberate:

1. **Global state first** — `cmd window reset-letterbox-style` + `wm user-rotation -d <id> free`. These are device-wide (not per-display), so a leaked value corrupts every later session. Doing them first means even a process killed midway has already returned the device to normal.
2. **Move foreground app** back to the physical display (`am display move-stack`).
3. **Restore panel + IME** — while `shellInput` is still usable. `setDisplayPower(true)` + `input keyevent 224` + `restoreIme()`.
4. **Release resources** — LifeWriter thread interrupted, shell destroyed, GL/encoder/VD released.

The previous order had `virtualDisplay.release()` dead last with nothing after it to fail, so a mid-way exception or a SIGKILL from the phone side left the panel off and the VD alive — one leaked display per reconnect.

**Two-stage VD stop.** `VdDeploy.stopCommand` is a single shell line: `pkill -f [P]ipelineServer; sleep 1; pkill -9 -f [P]ipelineServer; exit 0`. SIGTERM lets the JVM run the shutdown hook (which triggers `cleanup()`); SIGKILL after 1s catches processes that got wedged. The `[P]ipelineServer` bracket trick prevents `pkill -f` from matching the wrapper shell's own cmdline. The old `pkill -9` skipped the shutdown hook entirely, so `cleanup()` never ran.

## Connection Flow

```
1. Phone and car on the same network (or phone plugged into car USB)
2. Car app launches, starts parallel WiFi + USB/TCP ADB tracks

   Track A (WiFi control):
   a. Gateway IP discovery + mDNS lookup
   b. NIO connect to phone control port (9637)
   c. Handshake: car sends viewport + DPI + appVersionCode + targetFps + dpiOverride
   d. Phone responds with device info + vdServerJarPath + connectionMethod + vdDpi
   e. Phone opens lifecycle ServerSocket on 0.0.0.0:19647
   f. If Shizuku available: phone deploys VD server directly.
      Else car deploys via ADB (USB or TCP).
   g. VD server starts: CLASSPATH=jar app_process / PipelineServer W H DPI PHONE_HOST EW EH FPS
   h. VD server reverse-connects to phone localhost:19647 (NIO non-blocking)
   i. Phone accepts, reads MSG_DISPLAY_READY (displayId + direct-injection flag)
   j. Phone sends VD_PORTS_BOUND to car on the control connection
   k. Car connects video (9638) + input (9639) directly to the VD server in parallel
   l. VD server accepts both -- session fully established

   Track B (USB ADB / TCP ADB):
   a. USB: scan USB devices for ADB interface -> CNXN -> AUTH -> connected
      TCP: dev-mode phone-IP lookup, connect to phone:5555 via TcpAdbConnection
   b. shellBackground(launchCommand) -- keeps ADB stream open so app_process survives
   c. Launch phone app via am start (AppTargets.PHONE_MAIN_ACTIVITY)

3. VD server creates VirtualDisplay (car viewport + auto-calibrated DPI)
4. VD server powers off the phone's physical panel via DisplayControl
5. VD server saves the original IME and sets the VD's IME policy
6. Pipeline thread: EGL/GL init -> SurfaceTexture -> encoder -> bind 9638/9639
7. Car starts VideoDecoder on the SurfaceView surface when MirrorScreen creates it
8. Video streams: VD -> SurfaceTexture -> GL -> encoder -> TCP 9638 -> car VideoDecoder -> SurfaceView
9. Touch: car SurfaceView onTouch -> TouchEvent encode -> TCP 9639 -> VD TouchInjector -> InputManager
```

States: `IDLE -> CONNECTING -> CONNECTED -> STREAMING`

A mid-stream car-panel rotation reuses the control TCP connection. `MainActivity.onConfigurationChanged` calls `CarConnectionService.onCarViewportChanged`, which tears down video/input + the old VD server and re-sends a `HandshakeRequest` at the new dims. The phone deploys a fresh VD server and re-sends `VD_PORTS_BOUND`. The streaming-layout gate accepts `state == CONNECTING && appList.isNotEmpty()` so the video-wait overlay covers the ~2s redeploy gap instead of flashing `CarLaunchScreen` (which would destroy the SurfaceView and lose the decoder state).

A sustained black stream (no visible content for >5s) triggers the same re-handshake path via `rehandshakeForBlackScreen()` — the phone tears down and redeploys the VD server, which is the recovery a wedged compositor needs.

## DiLink 4.0 Low-Spec Performance (Snapdragon 439)

The reference car is a low-spec DiLink 4.0 head unit: 8x Cortex-A53 (no big cores), Adreno 505, 4GB RAM, 1280x800, 2.4GHz-only WiFi, API 28, H.264 hardware decode capped at 1080p. The streaming pipeline was retuned for this hardware. The nine fixes:

1. **Encode dims capped to 1920x1080** (`VdDeployArgs.MAX_ENCODE_WIDTH/HEIGHT`). Decoupled from VD dims via the `EW EH` args; the VD stays scaled-up to preserve the IME-crop fix, the encoder uses car-reported dims clamped to 1080p. Without this, >1080p phones trigger software decode on the 439 -> single-digit fps.
2. **Bitrate 8 Mbps -> 4 Mbps CBR** (`PipelineServer.BITRATE = 4_000_000`). Adaptive fallback tightened: floor 1.5 Mbps, 2s recovery window, 0.5 Mbps steps (was 2 Mbps / 5s / 1 Mbps).
3. **Framerate 30 -> 24 fps** (`VideoConfig.TARGET_FPS = 24`). Per-frame budget 33ms -> 42ms, ~20% lower WiFi/GPU/allocation load.
4. **MirrorScreen TextureView -> SurfaceView** (`MirrorScreen.kt`). Bypasses the Adreno 505 per-frame GL composite (~2-5ms/frame at 1280x800). The `outputSurfaceValid` gate prevents rendering to a destroyed surface across navigation hide/show; `switchSurface` re-attaches the decoder with zero keyframe loss.
5. **Thread priorities.** `THREAD_PRIORITY_URGENT_DISPLAY` on the encode pipeline, the decode feed thread, and the socket-drain reader; `THREAD_PRIORITY_BACKGROUND` on the LifeWriter. A53 has no big cores, so the scheduler must favor the hot path.
6. **`carLogEnabled` defaults to `BuildConfig.DEBUG`** (`CarLogWriter.setEnabled`); `ConcurrentLinkedQueue.size()` (O(n)) replaced by `AtomicInteger` (`CarLogWriter.bufferCount`).
7. **`AppIconCache.clear()` on disconnect** frees ~5-9MB heap + eMMC PNGs; `prepareAll` parallelized with `Semaphore(4)`, intermediate bitmaps recycled.
8. **`Debug.getPss()` removed from startup** (deprecated binder call, 100-500ms on A53).
9. **`@Immutable AppTileData` wrapper** (`HomeScreen.kt`) excludes the unstable `ByteArray` icon from `AppTile` inputs so the icon grid skips recomposition on `appList` reassignment.

## API 28 Car Compatibility

The BYD head unit runs Android 9 (API 28). Several framework APIs the project used are API 29+:

- **`MediaCodecInfo.isHardwareAccelerated()` (API 29+)** threw `NoSuchMethodError` on the BYD, killing the process. Fix: removed the speculative hardware-decoder picker; `VideoDecoder.start()` calls `MediaCodec.createDecoderByType(MIMETYPE_VIDEO_AVC)` directly. `REGULAR_CODECS` lists hardware first and selects `OMX.qcom.video.decoder.avc` on the BYD.
- **`am display move-stack` (API 29+)** gated on `SDK_INT >= 29` in `PipelineServer.moveTopApp`. On older levels the foreground app is left in place rather than a silent shell failure masking as success.
- **`cmd display power-on/off` (API 29+)** is the shell fallback in `DisplayPowerController`. On API 26-28 a `DisplayControl` reflection failure means the physical panel is not restored — now logged via `logErr`, was silent.
- **`minSdk` raised to 26** for `protocol` and `app-server` (was 24). Re-arms the NewApi lint gate. `app-client` stays at 29.

## Removed Features (relative to upstream)

- **Notification forwarding.** Phone notifications -> car screen, the nav-bar notification button, the notification list screen, the phone-side `NotificationService` (manifest entry, onboarding step, strings), and the `FOCUSED_APP` / `APP_SHORTCUTS` / `NOTIFICATION_*` protocol messages are all deleted. The car nav bar has no notification button.
- **Recent-apps rail, clock, network info** in the nav bar: deleted. The nav bar is three buttons only (Eject / Home / Back).
- **Audio streaming, media controls, navigation widgets** are not implemented. (`NowPlayingBar` exists in the source tree but is only composed when `MediaMetadata` is present, which the phone never sends.)

## Session Stability

- **Active sessions are not killed by reconnect attempts.** The WiFi gateway retry loop and manual connect stop after 3 consecutive ADB failures (`noAdbCount >= 3`) instead of looping indefinitely.
- **TCP ADB reconnects on phone IP change.** Dev mode tracks `lastAdbHost` and reconnects when the phone's IP changes.
- **Auto-fallback to TCP ADB when USB unavailable.** `VdServerDeployer.deploy` falls back to TCP ADB using the phone-host IP if known.
- **User disconnect stays IDLE**, no auto-reconnect. Persisted to SharedPreferences (`user_disconnected`).
- **VD leak prevention.** `ConnectionService.cleanupGuard` prevents duplicate `cleanupSession()` from 7 call sites (was: 1 disconnect → 9 wake calls). VD exit wait (`VD_EXIT_WAIT_MS = 3000ms`) before launching a replacement engine prevents two live instances racing for the same VD/DTA/9638-9639 ports.
- **Black screen self-heal.** `VideoDecoder.onSustainedBlackScreen` fires after 5s of continuous tiny keyframes (not on transient 3-frame bursts during app warm-up). `CarConnectionService.rehandshakeForBlackScreen()` rebuilds the phone-side VD via a re-handshake at the current dimensions. `blackScreenRecoveryInFlight` latch ensures at most one rebuild per session, preventing the reconnect storm that originally caused the VD leak.

## Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| **Direct VD streaming (no phone relay)** | VD server binds 9638/9639; car talks to VD directly. Was 4 socket ops + 2 context switches per frame; now 2 socket ops + 0. Phone is pure orchestrator. |
| **Lifecycle channel on `localhost:19647`** | Carries `MSG_DISPLAY_READY` / `MSG_STACK_EMPTY` / `CMD_STOP` between phone and VD server. Separate from video/touch so a stuck encoder cannot block lifecycle commands. |
| **`VD_PORTS_BOUND` control message** | Phone tells the car when the VD server has bound 9638/9639. Car connects video/input only after this signal, avoiding connect races. |
| **3 dedicated TCP connections** | Control/video/input on separate sockets. Eliminates cross-channel backpressure (app list can't stall video). |
| **Single-threaded pipeline, no queues** | `clock -> GL -> encoder -> TCP` on one thread. Natural flow control: a TCP stall blocks the next `eglSwapBuffers`, slowing the encoder. Uses `System.nanoTime()` + `LockSupport.parkNanos()` for drift-free 24fps. |
| **Shizuku deploys VD server when available** | Phone runs `app_process` directly via Shizuku; the car only needs WiFi. The car waits for `VD_PORTS_BOUND` instead of deploying via ADB. |
| **`shellBackground` for `app_process` launch** | Keeps the ADB stream open so the shell does not exit and kill the VD server process. |
| **NIO non-blocking everywhere** | All sockets non-blocking: WiFi connections, lifecycle localhost, Selector-based reads. No blocking I/O in the pipeline. |
| **24fps / 4Mbps CBR** | Tuned for the Snapdragon 439 + Adreno 505 + 2.4GHz WiFi. 42ms per-frame budget; adaptive bitrate floor 1.5Mbps for congested links. |
| **SurfaceView (not TextureView) on the car** | Bypasses the Adreno 505 per-frame GL composite. `outputSurfaceValid` gate + `switchSurface` handle surface destroy/re-create across navigation without keyframe loss. |
| **`createDecoderByType` direct** | `isHardwareAccelerated()` is API 29+ and threw on the BYD API-28 head unit. `REGULAR_CODECS` lists hardware first; `OMX.qcom.video.decoder.avc` is selected on the BYD. |
| **Encode dims clamped to 1920x1080** | Snapdragon 439 VPU caps hardware AVC decode at 1080p. Encoding larger forces software decode on the 8x A53 (single-digit fps). |
| **DPI auto-calibration** | `VideoConfig.calculateOptimalDpi` caps DPI so portrait-only apps get >=360dp logical width in a landscape VD. Optional car-side override in [120, 480]. |
| **Anti-crop VD scale** | VD width scaled up to match the phone's physical width. Chinese ROMs (Meizu, Xiaomi) hardcode IME width to the physical display; a narrower VD chops the keyboard horizontally. |
| **IME restore on teardown** | The VD's linkpc IME must not outlive the session. `ImeRestore.shouldRestoreIme` excludes it; the phone's `PhoneDisplayRestorer` and the VD's `DisplayPowerController` both restore the original IME. |
| **Watchdog forces `cleanup()`** | If the pipeline thread hangs in a native MediaCodec call after `CMD_STOP`, the lifecycle reader's `finally` never runs. The watchdog polls `running` + `cleanedUp` and forces `cleanup()` + `System.exit(1)` after a 3s grace. |
| **Mid-stream rotation re-handshake** | Control connection is reused; only video/input + VD server are recycled. The streaming-layout gate accepts `CONNECTING && appList.isNotEmpty()` so the SurfaceView is not destroyed during the redeploy gap. |
| **Reconnect stops after 3 ADB failures** | `noAdbCount >= 3` stops the reconnect loop instead of looping indefinitely. User is told to plug the phone into car USB. |
| **Two-stage VD stop (`stopCommand`)** | SIGTERM → wait 1s → SIGKILL in one shell line. The old `pkill -9` skipped the JVM shutdown hook, so `cleanup()` never ran — leaking VD, keeping panel off, losing screen timeout. The bracket pattern `[P]ipelineServer` prevents self-matching the wrapper shell. |
| **Cleanup idempotency (`cleanupGuard`)** | `AtomicBoolean` guard on `cleanupSession()`. Was 7 call sites with no guard — 1 disconnect fired the whole teardown 9 times, each racing a `pkill` against the previous restore. `resetCleanupGuard()` before each new session. |
| **VD exit wait before relaunch** | `ShizukuManager.waitForVdServerExit()` / `VdServerDeployer.waitForVdServerExit()` poll until the old engine exits. Two live instances race for the same VirtualDisplay / DTA / 9638-9639 ports; the loser skips `cleanup()` entirely. |
| **`saveCurrentIme()` before `create()`** | The snapshot must run before `configureEnvironment()` writes `screen_off_timeout=2147483647`, or the restore path sees the sentinel and does nothing. Old code snapshotted after `create()` — the user's real timeout was lost forever. |
| **Process-lifetime PhoneDisplayRestorer scope** | Owns `CoroutineScope(SupervisorJob() + Dispatchers.IO)` — not the Service scope, which `onDestroy()` cancels. `NonCancellable` ensures `cmd display power-on` cannot be skipped. `inFlight` collapses concurrent requests. |
| **Black screen self-heal** | `onSustainedBlackScreen` fires after 5s of continuous tiny keyframes (not on 3-frame bursts). `rehandshakeForBlackScreen()` rebuilds the VD. `blackScreenRecoveryInFlight` latch prevents storms. |
| **`stopVdServer()` returns Boolean** | Callers can now check if CMD_STOP succeeded or fell back to shell kill. Was void — callers assumed graceful stop always worked. |
| **Allowlist filters the wire payload** | Phone-side `AllowlistScreen` selects which launcher apps reach the car. `AppListBuilder.sendAppList` filters before building the wire payload; `ACTION_ALLOWLIST_UPDATED` triggers a live re-send. Pre-seeded with common map apps on first run. |
| **Car APK embedded in phone APK** | `embedServerApk` bundles `app-server.apk` into `app-client` assets; `CarAppInstaller` pushes it to the car via `dadb`. Enables "Install on Car" from the phone. |
| **`buildVdServer` task** | `bundleLibRuntimeToJarDebug` -> D8 -> `vd-server.dex` -> `vd-server.jar` -> copied to `app-client/src/main/assets/`. Bundles Kotlin stdlib + coroutines (needed at `app_process` runtime). |
| **Heartbeat on control only** | Video and input connections have no heartbeat overhead. Control connection watchdog detects dead peers (10s timeout). |
| **FileLog bypasses HyperOS logcat filtering** | File-based logging with rotation on `/sdcard/DiLinkAuto/`. Defaults to ON for debug/pre-release, OFF for release; user choice persists. |
| **Car log relay with bounded buffer** | `CarLogWriter` buffers up to 10k lines when the control connection is down, flushes on reconnect. `AtomicInteger` counter for O(1) cap check. |
| **Crash report flush on next connect** | `CarCrashHandler` writes to `filesDir/crash-pending.log`; the next successful connection sends it to the phone via `carLogSend`. |
| **logSink callbacks** | `VideoDecoder`, `UsbAdbConnection`, and `CarCrashHandler` route logs through the protocol to the phone's `FileLog`. |
| **ADB prehashed auth** | `AUTH_SIGNATURE` uses `NONEwithRSA` + SHA-1 DigestInfo prefix (prehashed). Matches AOSP's `RSA_sign(NID_sha1)`. "Always allow" persists correctly. |
| **Display power via DisplayControl** | `DisplayControl.setDisplayPowerMode` loaded from `services.jar` via `DelegateLastClassLoader`. Falls back to `cmd display power-on/off` (API 29+). The phone's `PhoneDisplayRestorer` does a layered restore (Shizuku -> PowerManager.wakeUp -> FLAG_TURN_SCREEN_ON -> wake lock). |
| **Trusted VD flags** | `0x6c49` (`OWN_DISPLAY_GROUP` + `OWN_FOCUS` + `TRUSTED`) prevents activity migration off the VD. |
| **Even viewport width** | Nav bar width adjusted to guarantee H.264-compatible even dimensions. |
| **App launch dedup** | `am start` without `--activity-clear-task`. Existing apps resume instead of restarting. |

## Technology Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin 1.9.22 (all modules); Java for `UsbAdbConnection` / `AdbProtocol` |
| Build | Gradle 8.7, AGP 8.2.2, JDK 17 |
| UI | Jetpack Compose + Material 3 (Compose BOM 2023.10.01) |
| Video | MediaCodec H.264 — encoder: VD server 4Mbps CBR Main, I-frame 1s; decoder: car `createDecoderByType` (hardware-first) |
| GPU | EGL14 + GLES20 + SurfaceTexture (single-threaded pipeline) |
| Networking | NIO `ServerSocketChannel` / `SocketChannel` / `Selector`, Android NSD (mDNS) |
| USB ADB | Custom protocol in `protocol/` module (`UsbAdbConnection`, `AdbProtocol`), `logSink` for diagnostics |
| TCP ADB | `TcpAdbConnection` (single socket, reused for all shell commands) for the car -> phone path; `dadb` 1.2.10 for the phone -> car install path |
| Elevated shell | Shizuku (`dev.rikka.shizuku:api/aidl/provider:13.1.5`) — phone-side, optional |
| Async | Kotlin Coroutines + Flow |
| Min API | 26 (`protocol`, `app-server`); 29 (`app-client`, `vd-server`) |
| Releases | See [Releases](https://github.com/ID-VerNe/dilink-auto-android/releases/latest) |
| Protocol Version | `PROTOCOL_VERSION = 1` |

## Port Reference

| Port | Direction | Purpose |
|------|-----------|---------|
| 9637 | car -> phone | Control + data (handshake, app list, heartbeat, `VD_PORTS_BOUND`) |
| 9638 | VD server -> car | H.264 video (VD server binds `0.0.0.0`) |
| 9639 | car -> VD server | Touch input (VD server binds `0.0.0.0`) |
| 19647 | VD server -> phone | Lifecycle channel (reverse-connect to phone localhost) |
| 5555 | car -> phone | ADB TCP (dev-mode phone-IP lookup, self-install) |
