# Phone Client App (app-client)

## Overview

The phone client is a **pure orchestrator**. It does not relay video or touch. The VD server binds `9638` (video) and `9639` (input) directly to `0.0.0.0` and the car connects to those ports without the phone in the middle — was 4 socket ops + 2 process context switches per frame, now 2 socket ops + 0 context switches. The phone owns only the lifecycle and control plane: handshake, VD server deploy, car-app install, app allowlist filtering, and car log routing.

Flow:

1. Registers an mDNS service (`_dilinkauto._tcp`) and listens for TCP from the car on port `9637` (control + data, NIO ServerSocketChannel on `0.0.0.0`).
2. Accepts the control connection, reads `HANDSHAKE_REQUEST` (viewport, `screenDpi`, `appVersionCode`, `appVersionName`, `targetFps`, `dpiOverride`).
3. Opens a lifecycle ServerSocket on `0.0.0.0:19647` **before** sending `HANDSHAKE_RESPONSE` so the socket is ready when the VD server reverse-connects.
4. Sends `HANDSHAKE_RESPONSE` with `deviceName`, `vdServerJarPath`, `connectionMethod` (`CONNECTION_METHOD_SHIZUKU` when Shizuku is authorized, else `CONNECTION_METHOD_USB_ADB`), and the negotiated `vdDpi`.
5. If Shizuku is available, deploys `vd-server.jar` directly on the phone via `ShizukuManager.execBackground` (`CLASSPATH=... app_process / com.dilinkauto.vdserver.PipelineServer ...`). Otherwise the car deploys the VD server via USB or TCP ADB.
6. VD server reverse-connects on `localhost:19647`. Phone reads `MSG_DISPLAY_READY` (carries `displayId` + direct-injection flag), then sends `VD_PORTS_BOUND` to the car on the control connection — the car now connects video (`9638`) and input (`9639`) directly to the VD server.
7. Streams `MSG_STACK_EMPTY` (car back-pressured) from the VD server to the car over the control connection.
8. Routes `CAR_LOG` data frames from the car into `FileLog` (tag `CarLog`).
9. Sends the car-visible app list, filtered by the user's allowlist, with hash-suppressed icon PNGs.

**No screen capture. No MediaProjection. No notification forwarding.** All video comes from the VD server process running as shell UID. `NotificationService` and the onboarding "Notification Access" step were deleted in this fork.

## Components

### ClientApp

`Application` subclass. Creates the notification channel (`dilinkauto_service`), initializes `ShizukuManager` on `onCreate`. Hosts the phone-side `loadIconPng` helper that scales a launcher icon to a given size and PNG-encodes it for the wire payload (no phone-side cache; the car's `AppIconCache` persists icons across sessions).

### MainActivity

Entry point. Auto-starts `ConnectionService` when the app is opened (e.g. via car USB ADB `am start`) if onboarding is complete and the service is `IDLE`. Routes between four screens:

- **OnboardingScreen** (first launch): 6-step wizard — Welcome, All Files Access, Battery Optimization, Accessibility Service, Car Setup, Done. Each permission step explains what breaks without it and auto-advances on `ON_RESUME` once granted. The Car Setup step shows the live `installStatusFlow` and an install button. Any step can be skipped. There is no Notification Access step.
- **MainScreen** (subsequent launches): status card, start/stop button, Install on Car card, Share Logs button, Samsung warning card for Galaxy devices that need the Shizuku / wireless-debugging workaround.
- **SettingsScreen**: permissions status, Debug log toggle, about card.
- **AllowlistScreen**: phone-side picker for which launcher apps reach the car (see below).

`shareLogs()` calls `FileLog.zipLogs()` and shares the zip via `FileProvider`.

### ConnectionService

Foreground service (`foregroundServiceType="connectedDevice"`) that orchestrates the phone-car session. Auto-started by `MainActivity`; runs the listen loop on `Dispatchers.Main` with a `PARTIAL_WAKE_LOCK` (4h auto-release).

- **Control connection (port `9637`)**: NIO TCP accept on `0.0.0.0`. Handles `HANDSHAKE_REQUEST`, heartbeat, and the DATA channel (`APP_LIST`, `APP_UNINSTALLED`, `APP_INFO_DATA`, `CAR_LOG`, `LOG_TOGGLE`). `LAUNCH_APP`, `GO_BACK`, `GO_HOME`, `APP_UNINSTALL`, `APP_INFO` now travel directly car → VD server on port `9639` and are no longer handled here.
- **Lifecycle channel (port `19647`)**: delegated to `VirtualDisplayClient` (see below). The ServerSocket is opened **before** `HANDSHAKE_RESPONSE` is sent so the VD server's reverse-connect never lands on a closed socket — a regression that previously caused VD startup failures.
- **`deployAssets()`**: extracts `vd-server.jar` to `/sdcard/DiLinkAuto/` and `app-server.apk` to `filesDir`. CRC-checked; skips re-extraction when the asset matches the on-disk file.
- **`handleHandshake()`**: computes VD dims via `VdDimensions`, opens the lifecycle ServerSocket (or reuses it across a mid-stream re-handshake), sends `HANDSHAKE_RESPONSE`, then deploys the VD server via Shizuku and waits for `MSG_DISPLAY_READY`. On `MSG_DISPLAY_READY` it sends `VD_PORTS_BOUND`, hands the `displayId` to `InputInjectionService`, flips state to `STREAMING`, and calls `AppListBuilder.sendAppList`.
- **Mid-stream re-handshake**: when the car rotates, it reuses the control TCP connection. `handleHandshake` tears down the old VD (`stopVdServer` + `disconnect`) and deploys a fresh one at the new orientation, leaving the lifecycle ServerSocket open across the swap.
- **VD exit wait before relaunch**: After killing the old VD server, `ShizukuManager.waitForVdServerExit(VD_EXIT_WAIT_MS)` polls until the process exits before launching the replacement. Two live engines race for the same VirtualDisplay / DTA / 9638-9639 ports; the loser skips `cleanup()` entirely — one leaked VD per reconnect.
- **Cleanup idempotency guard**: `cleanupGuard` `AtomicBoolean` prevents `cleanupSession()` from running multiple times for a single session. `cleanupSession()` was callable from 7 places (two network callbacks, the listen-loop `finally`, the handshake-failure path, `stopEverything`, and `onDestroy`) with no guard — observed 1 disconnect → 9 "Force-waking physical display" lines. `resetCleanupGuard()` is called before establishing a new session so the next teardown is allowed to run.
- **Smart network callback**: `NetworkRequest.Builder().addTransportType(TRANSPORT_WIFI)` — only reacts to WiFi changes, ignores mobile data fluctuations. `onLost` is debounced 3s (4G hotspot resets can recover immediately); `onAvailable` restarts the listen loop only when `WAITING`. Proactive disconnect on `CONNECTED`/`STREAMING` so the heartbeat timeout does not have to expire.
- **`ACTION_ALLOWLIST_UPDATED`**: re-sends the app list so the car grid updates live while a session is active.
- **`ACTION_INSTALL_CAR`**: manual install path; uses `CarIpLocator` to find the car and `CarAppInstaller` to push the APK.
- **Package-removed receiver**: on `ACTION_PACKAGE_REMOVED` (with `EXTRA_REPLACING` filtered out), sends `APP_UNINSTALLED` and re-sends the full app list.
- **`setLogEnabled(context, enabled)`**: companion entry point called from `SettingsScreen`. Persists to `AppPrefs.LOG_ENABLED` + `LOG_ENABLED_USER_SET`, sets `FileLog.enabled`, and propagates to the car over the live control connection via `DataMsg.LOG_TOGGLE` (1-byte payload).
- **`cleanupSession()`**: cancels `handshakeJob`, stops/disconnects the VD client, clears `InputInjectionService`'s VD binding, disconnects the control connection, resets icon hashes, and hands the cached IME to `PhoneDisplayRestorer`. Runs on `PhoneDisplayRestorer`'s own process-lifetime scope so it cannot be cancelled by `onDestroy()`.
- **mDNS registration** runs in a background `launch` with a 5s `withTimeoutOrNull` so `NsdManager` cannot hang the listen loop when there is no network.
- **`FileLog.rotate()`** on `onCreate` — archives the previous session log.

State flow: `IDLE → WAITING → CONNECTED → STREAMING`, exposed via `serviceState: StateFlow<State>`.

### VirtualDisplayClient

Lifecycle-only. Accepts the VD server's reverse connection on `localhost:19647` (the VD server connects **to** the phone, never the other way around). Takes no `videoConnection`/`controlConnection` params — video and touch are no longer relayed through the phone.

- `startListening(port = SERVER_PORT)`: synchronous `ServerSocketChannel` bind on `0.0.0.0:19647`. Called **before** `HANDSHAKE_RESPONSE` so the socket is open when the VD server connects back.
- `acceptConnection(port, timeoutMs = 60000)`: non-blocking accept loop. First byte must be `MSG_DISPLAY_READY` (carries `displayId: Int` + `directInjection: Byte` flag). On success, sets `isConnected`, fires `onDisplayReady` (which `ConnectionService` uses to send `VD_PORTS_BOUND`), and starts the command relay.
- **Command relay** (`Dispatchers.IO`): reads `MSG_STACK_EMPTY` and forwards it via the `onStackEmpty` callback → `ConnectionService` sends `ControlMsg.VD_STACK_EMPTY` to the car.
- `stopVdServer()`: writes `CMD_STOP` (`0xFF`) to the VD server under `writeLock` for graceful shutdown. **Returns `Boolean`** — `true` when the byte was handed to the socket, `false` when the lifecycle channel is already gone. A `false` return is expected whenever the channel is already closed — the engine's `readLifecycleCommands()` treats the resulting EOF/IOException exactly like `CMD_STOP` (sets `running=false` → `finally cleanup()`), so the teardown still happens; the caller just must not assume it was graceful.
- `disconnect()`: closes reader, server socket, and channel; resets `displayId = -1`.
- On disconnect the VD server's own cleanup runs only if it received `CMD_STOP`. If the lifecycle channel breaks first, `PhoneDisplayRestorer` is the safety net (see below).

### AllowlistScreen

Phone-side picker for which launcher apps the car is allowed to show. The selection lives in `SharedPreferences` (`dilinkauto_allowlist` / `allowed_packages`), read by `AppListBuilder.sendAppList` to filter the wire payload before it reaches the car. Toggling a row writes the prefs, marks `allowlist_configured = true`, and fires `ConnectionService.ACTION_ALLOWLIST_UPDATED` so the running service re-sends the list immediately — the car grid updates live without a reconnect. UI: search box, Select All / Deselect All, per-row Switch, and a counter. Pre-seeded with common map apps on first run (see `AppListBuilder`).

### AppListBuilder

Builds and sends the car-visible app list on behalf of `ConnectionService`.

- Filters launcher apps by the allowlist (`ALLOWLIST_PACKAGES_KEY`) before going on the wire, shrinking the wire payload and the car's icon-decode work.
- Skips disabled launcher components (Xiaomi HyperOS disables some without removing the package).
- Icon data is sent once per package per session — `lastSentIconHash` (keyed on `lastUpdateTime`) suppresses unchanged icons. `resetIconHashes()` forces a full re-send on disconnect or car-app reinstall.
- `categorizeApp(pkg)` classifies into `AppCategory.NAVIGATION / MUSIC / COMMUNICATION / OTHER` for the car grid.
- **First-run seeding**: `seedDefaultAllowlist` intersects a hardcoded set of common map packageNames (`com.baidu.BaiduMap`, `com.autonavi.minimap`, `com.google.android.apps.maps`, `com.waze`, `com.soso.map`, `com.tencent.map`, `com.mapabc.mapabc`) with the actually-installed launcher apps and persists the result, so out-of-the-box the car gets a useful grid instead of every installed launcher.

### CarAppInstaller

Installs the embedded `app-server.apk` onto the car via `dadb` (ADB-over-WiFi, port `5555`). Owns: ADB key-pair generation (`filesDir/adbkey` + `adbkey.pub`), `Dadb.create()` with a hard 15-second `Future.get` timeout (the blocking socket I/O cannot be interrupted by coroutine cancellation), `pm install -r`, and `am start --activity-clear-task -n com.dilinkauto.server/.MainActivity` (`AppTargets.CAR_MAIN_ACTIVITY`). Status strings flow back to the caller via an `onStatus` callback so the `_installStatusStatic` observable stays in `ConnectionService`. Extracted from the install logic that previously lived in `ConnectionService`.

### CarIpLocator

Locates the car's ADB-over-WiFi service (port `5555`) for the "Install on Car" flow. Strategies in order of latency: the active control connection's remote IP, subnet enumeration, ARP table (`/proc/net/arp`), `ip neigh` neighbor cache (always reaped in `finally`), parallel `/24` subnet scan (32 concurrent probes, 150ms per-probe timeout), and the WiFi gateway. Plain `object` — no `Service` dependency — so it is unit-testable in isolation. `probePortSync` is the synchronous 500ms variant used by the manual install path.

### VdDimensions

Pure viewport math for the phone-side VirtualDisplay created in response to a `HandshakeRequest`. Lives in **protocol-core** (moved from app-client on 2026-10-09) so the rules can be read and tested as plain JVM code — the caller passes the phone's real physical pixel size, no Android types involved.

- VD width/height start from the car viewport, even-aligned (H.264 needs even dimensions).
- **Anti-crop scale**: if the car viewport is narrower than the phone's physical width (orientation-resolved: a portrait phone's long edge acts as the width for a landscape car), the VD is scaled up to match. Many Chinese ROMs (Meizu, Xiaomi) hardcode IME width to the physical display width, and the IME width does not follow VD density; a narrower VD chops the keyboard horizontally no matter what DPI is negotiated. Scaling preserves the car's aspect ratio while satisfying the OS width requirement.
- **Real physical metrics only**: the phone size must come from `WindowManager.maximumWindowMetrics` (API 30+) / `Display.getRealMetrics` (API 29) — `resources.displayMetrics` reports compat-scaled values in screen-compat mode (Meizu 20 Inf: reports 2992 for a physically 3192px screen), which left the IME 200px (~6.7%) wider than the VD at every DPI setting.
- **DPI**: a car-side `dpiOverride` (coerced to `[120, 480]` via `VdDeployArgs.coerceDpiOverride`) bypasses the portrait-app-safe cap; otherwise `VideoConfig.calculateOptimalDpi` auto-calibrates for a ~380dp logical width in landscape.
- Returns `(vdWidth, vdHeight, dpi)` for the VD deploy args and the `VirtualDisplay` creation.

### InstallStatus

Enum classifying the free-form status string produced by `installCarApp`. Centralizes the `status.contains(...)` parsing so `OnboardingScreen`, `CarInstallCard`, and the install-stage checklist agree on what is in-progress, terminal, or auth-needed. Exposes `stageKeywords` (Searching / Connecting / Checking / Push / Install / Launching) for the stage-progress UIs, `parse(status)`, `stageIndex(status)`, `isInProgress`, and `isTerminal`.

### PhoneDisplayRestorer

Restores the phone's physical display and IME after the VD server tears down. The VD server powers off the physical panel directly via `DisplayControl.setDisplayPowerMode(0)` — a deeper off than `PowerManager` can recover from. Its cleanup runs only if it received `CMD_STOP`; when the lifecycle channel breaks first (or the process hangs in a native futex), `PhoneDisplayRestorer` is the safety net. Four layers, tried in order, each independent:

1. **Shizuku**: `VdDeploy.stopCommand` (SIGTERM → wait 1s → SIGKILL) → `cmd display power-on 0` → IME restore via `ImeRestore.imeRestoreCommandLine`. The two-stage stop lets the JVM shutdown hook run `cleanup()` (releases VD, restores IME/letterbox/screen settings, re-powers panel) before the force kill.
2. `PowerManager.wakeUp()` via reflection (system-level wake).
3. Launch `MainActivity` with `FLAG_TURN_SCREEN_ON` (WindowManager triggers display on).
4. `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP | ON_AFTER_RELEASE` wake lock.

**Owns its own `CoroutineScope`** — deliberately NOT the Service's scope:

- **Problem**: The previous version ran on the Service's `serviceScope`, which `ConnectionService.onDestroy()` cancels. When the user hit "stop" (or the system reclaimed the Service) the restore coroutine was cancelled *between* the `pkill` and the `cmd display power-on`, leaving the physical panel off with nothing left to turn it back on. Observed in logs: 3× "Force-waking physical display" followed by 3× "Shizuku display restore failed: Job was cancelled".
- **Fix**: `restoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)` — a process-lifetime scope that no Service lifecycle can cancel. `withContext(NonCancellable)` wraps the restore so once we start killing the engine we must run the power-on too. `inFlight` `AtomicBoolean` collapses concurrent restore requests into one.

### AppVersion

Reads an installed app's version label as a string, with a `versionCode` fallback for pre-semver peers. `label(context, preferCode, packageName)` returns `versionName ?: versionCode.toString()`; `nameOrEmpty` is the empty-string variant used by the About card. Extracted from duplicated sites in `ConnectionService` (car-install version check) and the about card.

### Versioning

Semantic-version parsing and comparison for the car-install version check (skip reinstall when the car already has the embedded version). Pure functions so the car-install path and tests can compare versions without pulling in any state machine. Scheme matches the project's tag format: `0.17.0` (release), `0.17.0-dev` (dev, num 0), `0.17.0-dev-02` (dev, num 2). Release > dev of the same base; dev builds order by `devNum`. Non-numeric components coerce to 0 for robustness against malformed wire input.

### ShizukuManager

Manages the Shizuku lifecycle and provides shell-level command execution via `IShizukuService.newProcess` (UID 2000). `init(context)` registers binder-received / binder-dead / permission-result listeners. `checkPermission()` caches `isAvailable`. `execAndWait(command)` runs `sh -c` with a 30s deadline (drains stdout + stderr concurrently so a >64KB stderr write cannot deadlock the pipe), returns combined output. `execBackground(command)` is fire-and-forget (used for `app_process` so the server outlives the shell stream). `copyToFile(source, destinationPath)` streams bytes via `cat > 'path'` for paths only shell can reach.

**`waitForVdServerExit(timeoutMs)`** — polls until no vd-server process remains, or `timeoutMs` elapses. Uses `VdDeploy.probeCommand` (`pkill -0` existence check) so it needs no `ps`/`pidof`. Used at two points where "the old engine is gone" must be true before proceeding:

- Before launching a new engine, so two instances never race for the same VirtualDisplay / DTA / 9638-9639 ports.
- After the graceful stop, to confirm `cleanup()` actually ran before we declare the session torn down.

Returns `true` if no vd-server is running (or Shizuku is unavailable, in which case we cannot verify and report `true` to avoid hanging).

### FileLog

File-based logger that bypasses Android logcat filtering (HyperOS filters `Log.i/d` for non-system apps).

- Writes to `/sdcard/DiLinkAuto/client.log`. Single writer thread drains a lock-free `ConcurrentLinkedQueue`; `SimpleDateFormat` is only touched from the writer thread.
- `loadEnabled(prefs)`: default ON for debug/pre-release (`BuildConfig.DEBUG`), OFF for release. Once the user explicitly toggles, that choice persists via `AppPrefs.LOG_ENABLED` + `LOG_ENABLED_USER_SET`.
- `rotate()`: archives the current log as `client-YYYYMMDD-HHmmss.log`, starts fresh, prunes to 9 archived + 1 current.
- `zipLogs()`: produces `dilinkauto-logs.zip` from all `.log` files in `/sdcard/DiLinkAuto/` and additionally includes `/data/local/tmp/vd-server.log` when it exists — the VD server's own log is part of every Share Logs submission.
- Also calls `android.util.Log.*` for standard logcat output.

The toggle is in **Settings → Debug**. Toggling it calls `ConnectionService.setLogEnabled`, which propagates the choice to the car over the live control connection via `DataMsg.LOG_TOGGLE` (1-byte payload) so the car's `carLogEnabled` follows the phone's setting.

### PermissionChecker

Runtime permission checks for the phone UI's onboarding and settings screens. Extracted from `OnboardingScreen` and `SettingsScreen`, which had each check written inline three times (initial read, periodic re-check, and onboarding's `pollPermission` switch). `hasAllFilesAccess()`, `hasBatteryExemption(context, pkg)`, `hasAccessibility(context, pkg)` — all plain `Context` receivers so they can be called from any `@Composable` or coroutine scope.

## Permissions Required

| Permission | Purpose |
|-----------|---------|
| `MANAGE_EXTERNAL_STORAGE` | All Files Access for sdcard deployment of `vd-server.jar` to `/sdcard/DiLinkAuto/` |
| Accessibility Service (`InputInjectionService`) | Holds the virtual-display binding (`displayId` + VD dims) for the car connection. Touch injection itself happens in the VD server process via `InputManager` reflection; the AccessibilityService path is unused for injection. No event monitoring. |
| Shizuku API (`moe.shizuku.manager.permission.API_V23`) | Elevated shell access for ADB-free VD server deployment (`app_process` as shell UID) |
| `QUERY_ALL_PACKAGES` | App launcher grid (deprecated by Play policy; fine for sideloaded car head units) |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` | `ConnectionService` foreground type |
| `WAKE_LOCK` | 4h `PARTIAL_WAKE_LOCK` so streaming survives screen-off |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Battery exemption so streaming stays alive when the screen is off |
| `POST_NOTIFICATIONS` | Foreground service notification (Android 13+) |
| `INTERNET` / `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE` / `CHANGE_WIFI_STATE` | TCP sockets, smart network callback, mDNS |

`NotificationService` and the corresponding `BIND_NOTIFICATION_LISTENER_SERVICE` permission are **deleted** from the manifest and onboarding in this fork.

## Dependencies

- Jetpack Compose + Material 3 (Compose BOM 2023.10.01)
- `material-icons-extended` (still present in `build.gradle.kts`; the perf audit recommended dropping it but the dep has not been removed)
- `kotlinx-coroutines`
- `dev.mobile:dadb:1.2.10` (WiFi ADB for car-app install)
- `dev.rikka.shizuku:api / aidl / provider:13.1.5`
- Protocol module (`:protocol`) — shared with the car app and VD server
- `app-server.apk` and `vd-server.jar` embedded in `app-client/src/main/assets/` by the `embedServerApk` and `buildVdServer` tasks (only the phone APK is installed manually; the car APK and VD JAR are pushed/embedded automatically)

Build: Kotlin 1.9.22, Android Gradle Plugin 8.2.2, `compileSdk = 34`, `minSdk = 29` (app-client), JDK 17. Release builds require `RELEASE_KEYSTORE_PASSWORD` and `RELEASE_KEY_PASSWORD` environment variables. See [./setup.md](./setup.md) for build commands and [./architecture.md](./architecture.md) for the cross-module layout.
