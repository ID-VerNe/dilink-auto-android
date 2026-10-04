# Protocol Specification

## Overview

DiLink-Auto uses a custom binary protocol over **3 dedicated TCP connections** between phone and car, plus one internal lifecycle connection between the phone app and the VD server:

| Connection | Port | Direction | Content |
|------------|------|-----------|---------|
| **Control + Data** | 9637 | Bidirectional | Handshake, heartbeat, app commands, app list, car logs, media metadata |
| **Video** | 9638 | VD server -> Car | H.264 CONFIG + FRAME only |
| **Input** | 9639 | Car -> VD server | Touch events only |
| **Lifecycle** | 19647 | VD server -> Phone (localhost) | Display ready / stack empty / stop signals |
| **ADB TCP** | 5555 | Car -> Phone (or Phone -> Car for dadb) | Persistent single-socket ADB for shell commands and car APK auto-update |

The VD server binds the video and input ports directly on `0.0.0.0:9638` and `0.0.0.0:9639`, so video frames and touch events flow directly between the VD server and the car without phone-side relaying. The phone app is not on the video or input data path.

The control connection (9637) is established first and carries the handshake, heartbeat, app commands, and the DATA channel (app list, car logs, media metadata). After handshake the phone deploys the VD server, which binds 9638/9639 and reverse-connects to the phone on `localhost:19647`. When the VD server signals display-ready on the lifecycle channel, the phone sends `VD_PORTS_BOUND` to the car on the control connection; only then does the car open video (9638) and input (9639) connections to the VD server. Heartbeat/watchdog runs only on the control connection; video and input have no heartbeat overhead. Any connection dying cascades to full session teardown.

The lifecycle channel on `localhost:19647` is documented in [client.md](./client.md). It uses a small fixed message set (`MSG_DISPLAY_READY`, `MSG_STACK_EMPTY`, `CMD_STOP`) and is the only channel the VD server reverse-initiates to the phone.

## Wire Format

```
+-----------------+------------+--------------+-----------------+
| Frame Length     | Channel ID | Message Type | Payload          |
| (4 bytes)        | (1 byte)   | (1 byte)     | (N bytes)        |
| big-endian uint32|            |              |                  |
+-----------------+------------+--------------+-----------------+
```

- **Frame Length**: `uint32` big-endian. Value = `2 + payload_size` (does not include the 4-byte length field itself).
- **Max payload**: 128 MB (`FrameCodec.MAX_PAYLOAD_SIZE`).
- **Header overhead**: 6 bytes per frame (`FrameCodec.HEADER_SIZE`).
- **Header encoding**: big-endian. The `FrameCodec` encodes the length as 4 big-endian bytes followed by the 1-byte channel ID and 1-byte message type. A `ThreadLocal` 6-byte header buffer avoids per-frame allocation.
- **Write path**: a single dedicated writer coroutine drains a bounded `Channel<Frame>` of size 64 with `BufferOverflow.SUSPEND`, so a slow TCP consumer applies backpressure to readers instead of growing unbounded. Gathering writes (`channel.write(ByteBuffer[])`) combine header + payload into one syscall.
- **Read path**: `FrameCodec.readFrame(NioReader)` for coroutine callers (phone, car) and `FrameCodec.readFrameBlocking(NioReader)` for non-coroutine callers (VD server). Both share the same payload-size validation.
- **Write timeout**: `FrameCodec.writeAll` throws if no progress is made for 5 seconds (send buffer full / peer not reading).

## Channels

| ID | Name | Connection | Direction | Purpose |
|----|------|------------|-----------|---------|
| 0 | CONTROL | Control (9637) | Bidirectional | Handshake, heartbeat, app commands, VD lifecycle signals |
| 1 | VIDEO | Video (9638) | VD server -> Car | H.264 encoded video frames |
| 2 | AUDIO | (reserved) | Reserved | Reserved (not implemented) |
| 3 | DATA | Control (9637) | Bidirectional | App list, app info data, car logs, media metadata, log toggle |
| 4 | INPUT | Input (9639) | Car -> VD server | Touch events (batched MOVE for multi-touch) |

The DATA channel multiplexes on the same TCP socket as CONTROL. Video frames are dispatched inline on the reader thread for low latency; DATA and CONTROL frames are dispatched to a coroutine scope so heavy processing (e.g., app list decode) does not block the reader from draining TCP.

## Control Channel (0x00)

### HANDSHAKE_REQUEST (0x01) -- Car -> Phone

```
+----------------------+--------+
| protocolVersion      | int32  |
| deviceName length    | int16  |
| deviceName           | UTF-8  |
| screenWidth          | int32  |  car display width in pixels (after nav bar)
| screenHeight         | int32  |  car display height in pixels
| supportedFeatures    | int32  |  bitmask (FEATURE_VIDEO | FEATURE_AUDIO | ...)
| displayMode          | byte   |  0=MIRROR, 1=VIRTUAL (default)
| screenDpi            | int32  |  car display density (e.g. 160)
| appVersionCode       | int32  |  car app version code (for version-mismatch auto-update)
| targetFps            | int32  |  car's requested FPS (e.g. 24)
| appVersionName len   | int16  |  version name string length
| appVersionName       | UTF-8  |  car app version name
| dpiOverride          | int32  |  trailing field, default 0
+----------------------+--------+
```

`dpiOverride` is a trailing field. `0` means auto-calibrate via `VideoConfig.calculateOptimalDpi` (the portrait-app-safe cap). A non-zero value in `[120, 480]` bypasses the cap and is used verbatim, fixing "UI too small" for landscape apps at the cost of squeezing portrait-only apps. The decoder's remaining-bytes guard reads `0` when the field is absent, so older car peers that do not send it decode as auto. The car coerces input through `VdDeployArgs.coerceDpiOverride` so the phone and car agree on what counts as a valid override.

### HANDSHAKE_RESPONSE (0x02) -- Phone -> Car

```
+----------------------+--------+
| protocolVersion      | int32  |
| accepted             | byte   |  1=accepted, 0=rejected
| deviceName length    | int16  |
| deviceName           | UTF-8  |
| displayWidth         | int32  |  VD width (matches car request)
| displayHeight        | int32  |  VD height (matches car request)
| virtualDisplayId     | int32  |  -1 (set by VD server, not phone)
| adbPort              | int32  |  5555 (phone's ADB TCP port)
| vdServerJarPath len  | int16  |
| vdServerJarPath      | UTF-8  |  path to deployed VD JAR (e.g. /sdcard/DiLinkAuto/vd-server.jar)
| connectionMethod     | byte   |  0=USB_ADB, 1=WIFI_ADB, 2=SHIZUKU
| vdDpi                | int32  |  DPI the VD will use (auto or override)
+----------------------+--------+
```

### HEARTBEAT (0x03) / HEARTBEAT_ACK (0x04) -- Control connection only

Empty payload. Sent every 3 seconds on the control connection. If no frame is received within 10 seconds, the connection is considered dead (watchdog timeout). Video and input connections have no heartbeat. The reader enqueues `HEARTBEAT_ACK` inline (no separate coroutine) so heartbeat acks cannot be starved by a busy writer queue.

### DISCONNECT (0x05) -- Bidirectional

Empty payload. Graceful shutdown.

### LAUNCH_APP (0x10) -- Car -> Phone

```
+----------------------+--------+
| packageName          | UTF-8  |  raw bytes, no length prefix
+----------------------+--------+
```

Phone forwards to VD server which runs `am start --display <id> -n <component>` (no `--activity-clear-task` -- existing apps resume).

### GO_HOME (0x11) / GO_BACK (0x12) -- Car -> Phone

Empty payload. Phone forwards to VD server. GO_BACK sends `input -d <id> keyevent 4`, then checks for empty stack. GO_HOME is handled by the car launcher (the phone does not forward it to the VD server).

### APP_STARTED (0x13) -- Phone -> Car

Same format as LAUNCH_APP. Defined for confirmation; the car-side handler is a no-op.

### APP_STOPPED (0x14) -- Phone -> Car

Defined in `MessageType.kt`. No send/receive references outside the constant definition; retained for protocol completeness.

### VD_STACK_EMPTY (0x15) -- Phone -> Car

Empty payload. Sent after GO_BACK when the VD server detects no remaining app tasks on the virtual display (via `dumpsys activity activities`). The car uses this to switch from mirror view to home screen.

### APP_INFO (0x17) -- Car -> Phone

Payload: UTF-8 package name. Car requests the phone to open the system app info/settings screen for the given package.

### APP_UNINSTALL (0x1B) -- Car -> Phone

Payload: UTF-8 package name. Car requests the phone to uninstall the given package. The phone handles the uninstall system dialog and sends back `APP_UNINSTALLED` via the data channel when done.

### GO_RECENT (0x1F) -- Car -> Phone

Empty payload. Triggers the recent-apps view on the virtual display.

### VD_SERVER_READY (0x20)

Defined in `MessageType.kt` as a Car -> Phone signal, but unused in the current codebase (no send or receive references). The `VD_PORTS_BOUND` message (0x31) is the active signal for VD readiness. Documented here to avoid confusion when reading the constant table.

### UPDATING_CAR (0x30) -- Phone -> Car

Empty payload. Sent before the phone starts auto-updating the car app via dadb (WiFi ADB on port 5555). The car shows "Updating car app..." status and stops reconnecting. After the update, the car app restarts fresh.

### VD_PORTS_BOUND (0x31) -- Phone -> Car

Empty payload. Sent after the VD server has bound the video (9638) and input (9639) ports and signaled display-ready on the lifecycle channel. The car waits for this message before calling `connectVideoAndInput(phoneHost)`, which opens the video and input TCP connections directly to the VD server. This gate prevents the car from connecting to ports that the VD server has not yet bound, which was a race in earlier versions.

## Video Channel (0x01)

### CONFIG (0x01) -- VD server -> Car

H.264 SPS/PPS NAL units with start codes. Sent once at encoder start.

### FRAME (0x02) -- VD server -> Car

H.264 NAL units representing a video frame.

**Encoding parameters** (set by VD server `PipelineServer`):
- Codec: H.264/AVC
- Profile: Main
- Resolution: encode dimensions capped at 1920x1080 (`VdDeployArgs.MAX_ENCODE_WIDTH/HEIGHT`), decoupled from VD dimensions via the `EW EH` args. The VD stays scaled-up to preserve the IME-crop fix; the encoder uses car-reported dims clamped to 1080p. Without this cap, phones with >1080p output trigger software decode on low-spec car SoCs (e.g., Snapdragon 439) and drop to single-digit fps.
- Bitrate: 4 Mbps CBR (`PipelineServer.BITRATE = 4_000_000`). Adaptive fallback: floor 1.5 Mbps, 2s recovery window, 0.5 Mbps steps.
- Frame rate: 24 fps (`VideoConfig.TARGET_FPS = 24`). Car requests this via `targetFps` in handshake.
- Frame interval: `1000 / 24` ~= 42 ms (`VideoConfig.FRAME_INTERVAL_MS`). Used as the max wait for video-path loops (encoder drain, SurfaceScaler redraw).
- IDR interval: 1 second (`PipelineServer.I_FRAME_INTERVAL = 1`).
- SurfaceScaler: periodic re-draw every `FRAME_INTERVAL_MS` ensures encoder output on static content.

## Data Channel (0x03)

### APP_LIST (0x03) -- Phone -> Car

List of installed apps with icons. Payload layout:

```
+----------------------+--------+
| count                | int16  |  number of entries
| N x entry:           |        |
|   packageName len    | int16  |
|   packageName        | UTF-8  |
|   appName len        | int16  |
|   appName            | UTF-8  |
|   category           | byte   |  0=NAVIGATION, 1=MUSIC, 2=COMMUNICATION, 3=OTHER
|   iconSize           | int32  |  PNG bytes length (e.g. 96x96 icon)
|   iconPng            | bytes  |
|   iconHash len       | int16  |
|   iconHash           | UTF-8  |  hash string for cache invalidation
+----------------------+--------+
```

The car uses `iconHash` to skip re-decoding icons it has already cached. Clearing the icon hash cache on disconnect prevents stale icons after a session ends.

### APP_UNINSTALLED (0x06) -- Phone -> Car

Payload: UTF-8 package name. Phone confirms an app was uninstalled (in response to `APP_UNINSTALL`). Car removes the app from its launcher grid.

### APP_INFO_DATA (0x07) -- Phone -> Car

Payload: `AppInfoDataMessage`. Phone sends app metadata for display in a car-side dialog when the user selects "App Info" from the context menu.

```
+----------------------+--------+
| packageName len      | int16  |
| packageName          | UTF-8  |
| appName len          | int16  |
| appName              | UTF-8  |
| versionName len      | int16  |
| versionName          | UTF-8  |
| versionCode          | int64  |
| installTime          | int64  |  epoch millis
| targetSdk           | int32  |  targetSdkVersion of the app
+----------------------+--------+
```

### MEDIA_METADATA (0x10) -- Phone -> Car

Track info. Three length-prefixed strings (title, artist, album) followed by `durationMs` (int64). Reserved for media widget integration; not actively populated.

### MEDIA_PLAYBACK_STATE (0x11) -- Phone -> Car

1-byte state (0=stopped, 1=playing, 2=paused) followed by `positionMs` (int64). Reserved.

### MEDIA_ACTION (0x12) -- Car -> Phone

1-byte enum: 0=PLAY, 1=PAUSE, 2=NEXT, 3=PREVIOUS, 4=SEEK. Reserved (not yet wired to MediaSession).

### NAVIGATION_STATE (0x20) -- Phone -> Car

Navigation state data. Reserved for navigation widget integration.

### CAR_LOG (0x30) -- Car -> Phone

UTF-8 text line. Car routes all logs (including VideoDecoder and UsbAdbConnection) through this channel. Phone writes to FileLog (`/sdcard/DiLinkAuto/client.log`) with tag `CarLog`.

### LOG_TOGGLE (0x31) -- Phone <-> Car

1-byte payload (0 or 1). Toggles car-side logging. Defaults: ON for debug/pre-release builds, OFF for release. The user choice persists. When off, zero disk writes occur on the car.

## Input Channel (0x04)

### TOUCH_DOWN (0x01) -- Car -> VD server

Single-pointer down event. Payload is a `TouchEvent` (25 bytes):

```
+----------------------+--------+
| action               | byte   |  InputMsg.TOUCH_DOWN (0x01)
| pointerId            | int32  |  multi-touch pointer ID
| x                    | float  |  normalized 0.0-1.0
| y                    | float  |  normalized 0.0-1.0
| pressure             | float  |
| timestamp            | int64  |
+----------------------+--------+
```

### TOUCH_MOVE (0x02) -- Car -> VD server

Single-pointer move event. Same `TouchEvent` payload format as TOUCH_DOWN.

### TOUCH_UP (0x03) -- Car -> VD server

Single-pointer up event. Same `TouchEvent` payload format as TOUCH_DOWN.

### TOUCH_MOVE_BATCH (0x04) -- Car -> VD server

```
+----------------------+--------+
| count                | byte   |  number of pointers
| N x pointer:         |        |
|   pointerId          | int32  |
|   x                  | float  |  normalized 0.0-1.0
|   y                  | float  |  normalized 0.0-1.0
|   pressure           | float  |
|   timestamp          | int64  |
+----------------------+--------+
```

Batched MOVE events -- all active pointers in one message. Each pointer is 24 bytes (no per-pointer action byte). Reduces syscalls for multi-touch gestures.

### KEY_EVENT (0x10) -- Car -> VD server

Key event (e.g., media keys, navigation keys). Reserved for future use.

## Lifecycle Channel (localhost:19647)

Internal to the phone: the VD server reverse-connects to the phone app on `localhost:19647`. This is a separate small protocol, not the frame protocol above. Messages:

| Value | Direction | Meaning |
|-------|-----------|---------|
| `0x10` (`MSG_DISPLAY_READY`) | VD server -> Phone | VirtualDisplay is up and the VD server has bound 9638/9639. Triggers the phone to send `VD_PORTS_BOUND` to the car. |
| `0x11` (`MSG_STACK_EMPTY`) | VD server -> Phone | No activities on the VD after GO_BACK. Triggers the phone to send `VD_STACK_EMPTY` to the car. |
| `0xFF` (`CMD_STOP`) | Phone -> VD server | Stop the VD server process. |

The VD server uses `FrameCodec.readFrameBlocking(NioReader)` and `NioReader.readIntOrNullBlocking()` for its non-coroutine read loop. See [client.md](./client.md) for the orchestrator side.

## ADB Reference

The car-side RemoteAdbController uses `TcpAdbConnection` -- a persistent single-socket ADB-over-TCP client that keeps one socket open and multiplexes shell commands through it (unlike dadb, which opens a new connection per command). The phone-side CarAppInstaller still uses dadb for car APK auto-update.

ADB protocol details (`AdbProtocol.java`):
- Header: 24 bytes, little-endian (command, arg0, arg1, dataLen, checksum, magic).
- Commands: `A_CNXN` (0x4e584e43), `A_AUTH` (0x48545541), `A_OPEN` (0x4e45504f), `A_OKAY` (0x59414b4f), `A_CLSE` (0x45534c43), `A_WRTE` (0x45545257).
- `A_VERSION = 0x01000000`.
- `MAX_PAYLOAD = 256 KB`. `TcpAdbConnection` negotiates `maxPayload = min(peerMax, 256KB)` from the CNXN response.
- AUTH: `AUTH_TOKEN` (1) = device sends 20-byte token, `AUTH_SIGNATURE` (2) = host signs with SHA-1 DigestInfo + NONEwithRSA (prehashed, matching AOSP `RSA_sign(NID_sha1)`), `AUTH_RSAPUBLICKEY` (3) = host sends public key for user approval.
- Key storage: `TcpAdbConnection` writes PEM-format keys (`adbkey` / `adbkey.pub`) in its `keyDir` so dadb can read the same keys.

## Constants

```
PROTOCOL_VERSION      = 1
CONTROL_PORT          = 9637  (phone <-> car, handshake + heartbeat + commands + data)
VIDEO_PORT            = 9638  (VD server binds 0.0.0.0 -> car connects)
INPUT_PORT            = 9639  (VD server binds 0.0.0.0 -> car connects)
LIFECYCLE_PORT        = 19647 (VD server reverse-connects to phone localhost)
ADB_PORT              = 5555  (standard ADB TCP port)
TARGET_FPS            = 24
FRAME_INTERVAL_MS     = 1000 / TARGET_FPS  (~= 42 ms)
HEARTBEAT_INTERVAL    = 3000 ms  (control connection only)
HEARTBEAT_TIMEOUT     = 10000 ms (control connection only)
SOCKET_BUF_BYTES      = 262144   (256 KB requested send/receive buffer)
WRITE_QUEUE_CAPACITY  = 64       (frames, BufferOverflow.SUSPEND)
MAX_PAYLOAD_SIZE      = 134,217,728 bytes  (128 MB)
HEADER_SIZE           = 6 bytes  (4 length + 1 channel + 1 type)
SERVICE_TYPE (mDNS)   = "_dilinkauto._tcp."
SERVICE_NAME (mDNS)   = "DiLink-Auto"
DISPLAY_MODE_MIRROR   = 0
DISPLAY_MODE_VIRTUAL  = 1
```

Feature bitmasks (for `supportedFeatures` in `HandshakeRequest`):

```
FEATURE_VIDEO          = 0x01
FEATURE_AUDIO          = 0x02
FEATURE_MEDIA_CONTROL  = 0x08
FEATURE_NAVIGATION     = 0x10
```

Connection methods (for `connectionMethod` in `HandshakeResponse`):

```
CONNECTION_METHOD_USB_ADB   = 0
CONNECTION_METHOD_WIFI_ADB  = 1
CONNECTION_METHOD_SHIZUKU   = 2
```

## Byte Order

All multi-byte integers and floats in the DiLink-Auto frame protocol are **big-endian**. The ADB protocol (used on port 5555) is **little-endian** per the ADB specification; the two are separate protocols on separate sockets.
