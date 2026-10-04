# Setup Guide

## Prerequisites

- **Phone:** Any Android 10+ device with USB Debugging enabled
- **Car:** BYD DiLink 3.0+ head unit running Android 8.0+ (API 26+). The car app targets `minSdk 26`; the protocol shared library has the same floor. Tested car: BYD Qin PLUS DM-i 2023 Champion 55KM Leading trim — DiLink 4.0 low-spec head unit (Snapdragon 439, 1280x800, Android 9 / API 28, 2.4GHz-only WiFi, H.264 hardware decode capped at 1080p). Other DiLink 3.0+ units should work but are not regularly exercised.
- **USB cable:** Phone to car USB port (only required for the USB ADB track; the WiFi track is cable-free)
- **Development:** Android Studio or Gradle, JDK 17

**No internet connection is required** — DiLink-Auto streams over your phone's WiFi hotspot (the car connects directly to the phone). An internet connection is only needed for the apps running on your phone (e.g., maps, music), not for DiLink-Auto itself.

## Phone Setup (One-Time)

1. Install the DiLink Auto client APK (`app-client/build/outputs/apk/debug/app-client-debug.apk`). Pre-built builds are on [Releases](https://github.com/ID-VerNe/dilink-auto-android/releases/latest); building from source is covered below.
2. Open the app — the onboarding screen walks through each permission in turn:
   - **All Files Access** — required to deploy the virtual display server jar to `/sdcard/DiLinkAuto/`
   - **Battery Optimization** — exempt the app so streaming stays alive when the screen is off
   - **Accessibility Service** — enables car touchscreen input injection into the virtual display
   - **Car setup** — pushes the embedded car APK to the head unit over WiFi ADB (see Car Setup below)
3. Each step opens the relevant system settings screen. Grant the permission, then press Back to return; the app re-checks on resume and auto-advances once detected.
4. Any step can be skipped and completed later from the main screen.

No Wireless Debugging pairing codes or special WiFi configuration are needed. Shizuku (below) is the only optional elevated-shell path.

### Optional: Shizuku (ADB-Free Connection)

[Shizuku](https://github.com/RikkaApps/Shizuku) gives normal apps elevated shell privileges, letting DiLink-Auto deploy the Virtual Display server without a USB ADB connection from the car. When Shizuku is active, the phone deploys the VD server directly and the car only needs WiFi connectivity — the USB cable is not required for streaming.

1. Install Shizuku from [GitHub Releases](https://github.com/RikkaApps/Shizuku/releases) or Google Play
2. Start Shizuku once via ADB (`adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh`) or via root
3. In DiLink Auto: Settings → Shizuku card → tap to open the Shizuku app and authorize DiLink Auto
4. With Shizuku running, the phone deploys the VD server directly — no USB ADB or Wireless Debugging required

## Car Setup

**No internet connection required.** The car APK is embedded inside the phone APK as an asset; the phone pushes it to the car over local WiFi. No manual car installation is needed for the normal flow.

Install the car app from the phone's onboarding "Car setup" step or the main-screen "Install on Car" button; both trigger the same path (dadb over WiFi ADB on port 5555).

Manual install (only if you have ADB access to the car and want to bypass the auto-push): `adb install app-server-debug.apk`.

## Daily Use

1. Plug the phone into the car's USB port — the car app auto-launches — or connect the phone and car to the same WiFi network.
2. Phone and car connect over the WiFi track (USB is only used to bootstrap ADB; streaming itself is over WiFi TCP).
3. The phone deploys the VD server (`vd-server.jar` extracted to `/sdcard/DiLinkAuto/`, started as `app_process` running as shell UID) and streaming begins at 24 fps, 4 Mbps CBR H.264 Main profile.
4. Use the car touchscreen to interact with phone apps. The persistent nav bar at the bottom of the car screen has three buttons: **Eject** (disconnect session), **Home**, and **Back**. There is no notification button, recent-apps rail, clock, or network info — those were removed in this fork.
5. Long-press an app tile on the car launcher to pin it to the top (or unpin). The phone-side Allowlist screen (`AllowlistScreen`) controls which installed apps appear on the car launcher at all.

### Car-Side DPI Override

Low-spec DiLink 4.0 head units like the tested 1280x800 BYD ship with a default density that renders the car UI — and the virtual display it hosts — too small for in-car touch use. The car app exposes a **Startup DPI** override in the ConnectionStatusCard on `CarLaunchScreen`:

- Leave the field empty (or `0`) for **Auto** — the phone picks a safe DPI via `VideoConfig.calculateOptimalDpi`, which caps density so portrait-only apps (Amap, WeChat, etc.) get at least ~360dp of logical width inside the landscape virtual display.
- Enter a value in the range **[120, 480]** to force a specific DPI for the next session. The car coerces input through `VdDeployArgs.coerceDpiOverride` so the phone and car always agree on what counts as a valid override.
- The override is stored in the car's `startup_dpi` SharedPreferences and applied at initial WiFi connect as well as at mid-stream rotation re-handshake. Changing it does not affect a running session — reconnect to apply.

## Building

```bash
# Build phone APK (triggers server build + embed automatically)
./gradlew :app-client:assembleDebug

# Phone APK location:
#   app-client/build/outputs/apk/debug/app-client-debug.apk
#   (includes the embedded car APK and vd-server.jar as assets)
```

The `buildVdServer` task compiles `vd-server/` to `vd-server.dex`, packages it as `vd-server.jar`, and copies it into `app-client/src/main/assets/`. The `embedServerApk` task bundles `app-server.apk` into the client assets the same way. Only the phone APK needs to be installed manually — the car APK and VD server jar are pushed/embedded automatically.

Release builds require the `RELEASE_KEYSTORE_PASSWORD` and `RELEASE_KEY_PASSWORD` environment variables to be set.

## How It Works

When the phone connects to the car:

1. **Car starts parallel tracks** — WiFi discovery and USB detection run simultaneously.
2. **Track A (WiFi):** gateway IP + mDNS discovery → NIO connect to phone control port `9637`.
3. **Track B (USB):** scan devices → USB ADB connect → launch phone app via `am start`.
4. **Handshake:** car sends viewport dimensions + DPI override + appVersionCode + targetFps; phone responds with device info and `vdServerJarPath`.
5. **Three-connection setup:** after handshake the car opens video (`9638`) and input (`9639`) connections; the VD server reverse-connects to the phone on localhost:`19647` for the lifecycle channel.
6. **Phone deploys VD server** — extracts `vd-server.jar` to `/sdcard/DiLinkAuto/`, starts `app_process` as shell UID with the VD dimensions, DPI, encode dims, and FPS as args.
7. **VD server creates the VirtualDisplay** at the negotiated DPI (Auto or override) and runs the GL pipeline: frame clock → GL render → encoder drain → TCP write, all on a single thread with `System.nanoTime()` / `LockSupport.parkNanos()` timing.
8. **Video streams** over WiFi TCP on port `9638` — H.264 Main profile, 4 Mbps CBR, 24 fps, encode dimensions capped at 1920x1080. Adaptive bitrate falls back in 0.5 Mbps steps down to a 1.5 Mbps floor with a 2s recovery window.

Port reference: `9637` control+data, `9638` video, `9639` touch input, `19647` lifecycle (VD server → phone localhost), `5555` ADB TCP.

## Troubleshooting

### ADB auth dialog appears every time

This was a real bug in upstream — the AUTH_TOKEN was double-hashed. ADB sends a raw 20-byte token that must be treated as a pre-hashed SHA-1 digest; the old code used `SHA1withRSA` which hashed it again. The fix uses `NONEwithRSA` with a SHA-1 DigestInfo prefix (prehashed), matching AOSP's `RSA_sign(NID_sha1)`. The phone now accepts AUTH_SIGNATURE on reconnect and "Always allow" persists correctly.

If the dialog appears on the first connection after updating, check "Always allow" — it should not reappear. If it persists, check the phone's Developer Options for "Disable ADB authorization timeout" (Android 11+).

### Phone app does not launch

- Ensure USB Debugging is enabled on the phone
- Check that the car's USB port supports host mode (not all ports do)
- Try a different USB cable (some cables are charge-only)

### All Files Access permission denied

- The phone app needs `MANAGE_EXTERNAL_STORAGE` to deploy `vd-server.jar` to `/sdcard/DiLinkAuto/`
- Settings → Apps → DiLink Auto → Permissions → All Files Access → ON

### Video not streaming / black screen

- Ensure phone and car are on the same network
- Check that both apps are running (phone shows "Streaming", car shows video)
- The VD server may need a moment to start — wait 5-10 seconds after connecting
- On API 26-28 cars, `cmd display power-on/off` shell fallback is unavailable (API 29+ only); a DisplayControl reflection failure means the physical panel is not restored and the failure is now logged rather than silently masked. Check the log for `DisplayControl` errors.
- Check `/sdcard/DiLinkAuto/client.log` for diagnostic information

### Rotation causes a black screen mid-session

A race used to leave the decoder unable to restart after rotation: `onCarViewportChanged` set state to CONNECTING and tore down the VD for redeploy, but the streaming-layout gate keyed off `isConnected` (false during the ~2s redeploy), which flipped CarShell back to `CarLaunchScreen` and removed the SurfaceView before the new surface could restart the decoder. The gate now also accepts `state == CONNECTING && appList.isNotEmpty()`, so the streaming layout stays mounted across rotation redeploy. If you still see it, reconnect.

### Connection drops

- Reconnect attempts no longer kill an active session — the WiFi gateway retry loop stops after 3 consecutive ADB failures, and TCP ADB reconnects on phone IP change rather than tearing down the running stream.
- If persistent, check `/sdcard/DiLinkAuto/client.log` for "Network lost" entries.

### Car app not installing

- Manual trigger: "Install on Car" button in the phone app
- Ensure dadb can reach the car over WiFi ADB (port 5555)

### Car UI is too small / apps are cramped

This is the symptom the **Startup DPI** override exists for. On the tested 1280x800 BYD head unit, the default density leaves portrait-only apps with as little as ~132dp of logical width inside the landscape virtual display. Leave the Startup DPI field on Auto (empty) to let `VideoConfig.calculateOptimalDpi` pick a safe cap, or enter a value in `[120, 480]` to force a specific density. Reconnect to apply.

### Apps appear letterboxed or don't fill the screen

This is normal. DiLink-Auto mirrors apps onto a landscape virtual display that matches the car's screen. Some apps (especially those designed primarily for phones) only support portrait orientation and will appear letterboxed or narrow. The Auto DPI mode is designed to give those apps enough logical width to be usable; forcing a high DPI override can make them cramped again.

### Logs

- Phone logs: `/sdcard/DiLinkAuto/client.log` (current session)
- Previous sessions: `/sdcard/DiLinkAuto/client-YYYYMMDD-HHmmss.log`
- VD server logs: `/data/local/tmp/vd-server.log` (on phone, readable via ADB)
- Car logs: routed to the phone's `client.log` via the protocol DATA channel (tag: `CarLog`)
- Pull logs: `adb shell "cat /sdcard/DiLinkAuto/client.log"`
- A debug diagnostic log toggle lives in Settings; when off, zero disk writes occur. It propagates to the car via the `LOG_TOGGLE` data message. Defaults: ON for debug/pre-release builds, OFF for release; the user choice persists.

## HyperOS (Xiaomi) Tips

For reliable operation on HyperOS:

1. Settings → Apps → DiLink Auto → Autostart → Enable
2. Settings → Battery → DiLink Auto → No Restrictions
3. Lock the app in Recent Apps (long-press the card → Lock)

## Samsung One UI Tips

Samsung devices running One UI 5+ (Android 13+) have additional security and power-saving features that can block DiLink-Auto from working correctly. This applies to Galaxy A, M, S, Z, and Tab series.

### Disable Auto Blocker (Critical for USB ADB)

**Auto Blocker** blocks USB commands and can prevent the car from connecting to your phone via USB ADB. This is the most common Samsung-specific issue.

1. Settings → Security and privacy → Auto Blocker → Off
2. If you prefer to keep Auto Blocker on, at minimum disable the **"Block commands over USB cable"** option

### Allow All Files Access

Samsung's Permission Manager may auto-revoke permissions from apps you haven't opened recently:

1. Settings → Apps → DiLink Auto → Permissions → Files and media → Allow management of all files
2. Toggle **"Allow management of all files"** ON
3. Verify it stays ON after closing settings (Samsung may show a confirmation popup)

### Disable Battery Optimization

Samsung's battery management is more aggressive than stock Android:

1. Settings → Apps → DiLink Auto → Battery → Unrestricted
2. Settings → Battery → Background usage limits → Never sleeping apps → Add DiLink Auto
3. Settings → Battery → Background usage limits → Deep sleeping apps → Remove DiLink Auto if listed

### Lock App in Recents

Samsung One UI may kill background apps to free memory:

1. Open Recent Apps (swipe up from bottom with 3-button nav, or gesture nav)
2. Tap the DiLink Auto icon at the top of its card
3. Select **"Keep open"**

### Disable Samsung Device Care Auto-Optimization

Samsung's Device Care can automatically stop background services:

1. Settings → Battery and device care → Automation → Auto optimize daily → Off
2. Settings → Battery and device care → Automation → Auto restart → Off

### If You See a "DeX" Permission Popup

Some Samsung devices show a popup about "Samsung DeX" or "external display" permissions when an app tries to create a virtual display. Even though Galaxy A/M series don't support DeX, the dialog may still appear. Simply tap **"Allow"** or **"Start now"**. If the dialog keeps reappearing, go to Settings → Connected devices → Samsung DeX and disable "Auto start when HDMI is connected."

### Knox Security Considerations

Samsung Knox may show a security notification when DiLink-Auto accesses:

- Virtual display surface (for video encoding)
- USB debugging bridge (for car ADB connection)
- All files storage (for deploying the VD server)

These are expected behaviors. Tap "Allow" or "OK" on any Knox-related prompts. If prompts persist, you can temporarily lower Knox protection to "Medium" under Settings → Security and privacy → Samsung Knox.
