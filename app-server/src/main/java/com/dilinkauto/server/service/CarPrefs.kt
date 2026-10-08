package com.dilinkauto.server.service

import android.content.Context
import com.dilinkauto.protocol.AppPrefs
import com.dilinkauto.protocol.VideoConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SharedPreferences facade for [CarConnectionService] (audit R3-SRP-01 item 9).
 *
 * Owns every pref key the car service touches, so the service no longer carries
 * hand-written `prefs.getX(...)` / `prefs.edit()...apply()` accessors — and the
 * future Wi-Fi/ADB/handshake split can take this facade instead of the whole
 * service.
 *
 * The four user-facing startup settings additionally expose an observable mirror
 * ([devModeFlow] etc.) so the UI binds to one source of truth instead of keeping
 * shadow state (R3-SRP-06). The property setters are the only write paths: they
 * update prefs and the flow together, so the rendered value cannot drift from the
 * stored one. The mirrors are initialized from the persisted values at
 * construction — there is no sync window.
 *
 * Instantiate lazily from the service (`by lazy`): a Service's field initializers
 * run before `attachBaseContext`, and opening the prefs file needs the base context.
 */
internal class CarPrefs(context: Context) {

    private val prefs = context.getSharedPreferences(AppPrefs.FILE_NAME, Context.MODE_PRIVATE)

    /** Set when the user explicitly disconnected; suppresses auto-reconnect until re-plug / START. */
    var userDisconnected: Boolean
        get() = prefs.getBoolean(AppPrefs.USER_DISCONNECTED, false)
        set(value) = prefs.edit().putBoolean(AppPrefs.USER_DISCONNECTED, value).apply()

    /** Manual phone IP; honored by the WiFi track only while [devMode] is on. */
    val devPhoneIp: String?
        get() = prefs.getString(AppPrefs.DEV_PHONE_IP, null)

    // ─── Dev mode ───

    private val _devMode = MutableStateFlow(prefs.getBoolean(AppPrefs.DEV_MODE, false))
    val devModeFlow: StateFlow<Boolean> = _devMode.asStateFlow()

    var devMode: Boolean
        get() = prefs.getBoolean(AppPrefs.DEV_MODE, false)
        set(value) {
            prefs.edit().putBoolean(AppPrefs.DEV_MODE, value).apply()
            _devMode.value = value
        }

    // ─── Startup video settings ───

    private val _startupDpi = MutableStateFlow(prefs.getInt(AppPrefs.STARTUP_DPI, 0))
    val startupDpiFlow: StateFlow<Int> = _startupDpi.asStateFlow()

    /**
     * Car-side startup DPI override. 0 = auto-calibrate via VideoConfig.calculateOptimalDpi
     * (the portrait-app-safe cap, default). Non-zero in [120, 480] bypasses the cap and is
     * sent to the phone as `dpiOverride` in HandshakeRequest; the phone uses it verbatim and
     * echoes it back as `vdDpi`. Read at handshake construction time, so a change takes effect
     * on the next connect (or mid-stream rotation re-handshake), not live.
     */
    var startupDpi: Int
        get() = prefs.getInt(AppPrefs.STARTUP_DPI, 0)
        set(value) {
            prefs.edit().putInt(AppPrefs.STARTUP_DPI, value).apply()
            _startupDpi.value = value
        }

    private val _startupFps = MutableStateFlow(prefs.getInt(AppPrefs.STARTUP_FPS, VideoConfig.TARGET_FPS))
    val startupFpsFlow: StateFlow<Int> = _startupFps.asStateFlow()

    /**
     * Persisted startup FPS. Unlike DPI/bitrate this one is also applied to the
     * live [CarConnectionService.targetFps] immediately — go through
     * [CarConnectionService.setStartupFps] when changing it at runtime.
     */
    var startupFps: Int
        get() = prefs.getInt(AppPrefs.STARTUP_FPS, VideoConfig.TARGET_FPS)
        set(value) {
            prefs.edit().putInt(AppPrefs.STARTUP_FPS, value).apply()
            _startupFps.value = value
        }

    private val _startupBitrate = MutableStateFlow(prefs.getInt(AppPrefs.STARTUP_BITRATE, VideoConfig.DEFAULT_BITRATE))
    val startupBitrateFlow: StateFlow<Int> = _startupBitrate.asStateFlow()

    var startupBitrate: Int
        get() = prefs.getInt(AppPrefs.STARTUP_BITRATE, VideoConfig.DEFAULT_BITRATE)
        set(value) {
            prefs.edit().putInt(AppPrefs.STARTUP_BITRATE, value).apply()
            _startupBitrate.value = value
        }
}
