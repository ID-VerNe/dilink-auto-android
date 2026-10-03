package com.dilinkauto.client.service

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import com.dilinkauto.client.ClientApp
import com.dilinkauto.client.FileLog
import com.dilinkauto.protocol.AppCategory
import com.dilinkauto.protocol.AppInfo
import com.dilinkauto.protocol.AppListMessage
import com.dilinkauto.protocol.Connection
import com.dilinkauto.protocol.DataMsg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Builds and sends the car-visible app list on behalf of [ConnectionService].
 *
 * The list is filtered by the user's allowlist (see [com.dilinkauto.client.AllowlistScreen])
 * before going on the wire, so the car only receives selected packages — shrinking
 * the wire payload and the car's icon-decode work. Icon data is sent once per
 * package per session ([lastSentIconHash] suppresses unchanged icons); the
 * car's AppIconCache persists them across sessions.
 */
internal class AppListBuilder(
    private val context: Context,
    private val scope: CoroutineScope
) {
    // Tracks the last icon hash sent per package — survives across reconnections
    // within the same service lifetime to avoid re-sending unchanged icons.
    private val lastSentIconHash = mutableMapOf<String, String>()

    /** Re-send the app list to the car. No-op if [conn] is null. */
    fun sendAppList(conn: Connection?) {
        if (conn == null) return
        val pm = context.packageManager

        scope.launch(Dispatchers.IO) {
            try {
                val allApps = pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
                ).filter { info ->
                    // Skip hidden apps (Xiaomi HyperOS, some custom ROMs disable
                    // the launcher component without removing the package)
                    val pkg = info.activityInfo.packageName
                    val cn = android.content.ComponentName(pkg, info.activityInfo.name)
                    val state = pm.getComponentEnabledSetting(cn)
                    state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                }.map { info ->
                    val pkg = info.activityInfo.packageName
                    // Use lastUpdateTime as a lightweight change indicator.
                    // The car-side AppIconCache handles persistence, multi-size
                    // resizing, and in-memory Bitmap caching.
                    val hash = try {
                        pm.getPackageInfo(pkg, 0).lastUpdateTime.toString()
                    } catch (_: Exception) { "" }
                    // Only include icon data if the hash differs from last sent
                    val prevHash = lastSentIconHash[pkg]
                    val iconPng = if (hash.isNotEmpty() && hash == prevHash) {
                        ByteArray(0) // car can use its cached icon
                    } else {
                        lastSentIconHash[pkg] = hash
                        ClientApp.loadIconPng(pm, pkg, 192)
                    }
                    AppInfo(
                        pkg,
                        info.loadLabel(pm).toString(),
                        categorizeApp(pkg),
                        iconPng,
                        hash
                    )
                }.sortedBy { it.category.id }

                // Apply the user's car-app allowlist: only selected packages reach the car,
                // shrinking the wire payload and the car's icon-decode work. On first run
                // the allowlist is pre-seeded with common map apps that are actually installed.
                val prefs = context.getSharedPreferences(ConnectionService.ALLOWLIST_PREFS, Context.MODE_PRIVATE)
                if (!prefs.getBoolean(ConnectionService.ALLOWLIST_CONFIGURED_KEY, false)) {
                    seedDefaultAllowlist(pm, prefs)
                }
                val allowed = prefs.getStringSet(ConnectionService.ALLOWLIST_PACKAGES_KEY, null)
                val apps = if (allowed != null) allApps.filter { it.packageName in allowed } else allApps

                conn.sendData(DataMsg.APP_LIST, AppListMessage(apps).encode())
                val skipped = apps.count { it.iconPng.isEmpty() }
                FileLog.i(TAG, "App list sent: ${apps.size}/${allApps.size} apps allowed (${skipped} icons skipped/unchanged)")
            } catch (e: Exception) {
                FileLog.e(TAG, "Failed to send app list", e)
            }
        }
    }

    /** Clear the icon-hash cache so the next [sendAppList] re-sends every icon. */
    fun resetIconHashes() {
        lastSentIconHash.clear()
    }

    /** Common map app packageNames used to pre-seed the allowlist on first run. */
    private val DEFAULT_MAP_PACKAGES = setOf(
        "com.baidu.BaiduMap",        // Baidu Maps
        "com.autonavi.minimap",      // AMap (Gaode)
        "com.google.android.apps.maps", // Google Maps
        "com.waze",                  // Waze
        "com.soso.map",              // Sogou Map
        "com.tencent.map",           // Tencent Map
        "com.mapabc.mapabc"          // Mapabc
    )

    /**
     * First-run seeding: intersect the default map package list with the launcher
     * apps actually installed. Non-installed defaults are no-ops. Persist the
     * result and mark the allowlist configured so this only runs once.
     */
    private fun seedDefaultAllowlist(pm: PackageManager, prefs: SharedPreferences) {
        val installedLauncher = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).map { it.activityInfo.packageName }.toSet()
        val seed = DEFAULT_MAP_PACKAGES.intersect(installedLauncher)
        prefs.edit()
            .putStringSet(ConnectionService.ALLOWLIST_PACKAGES_KEY, seed)
            .putBoolean(ConnectionService.ALLOWLIST_CONFIGURED_KEY, true)
            .apply()
        FileLog.i(TAG, "Allowlist seeded with ${seed.size} default map apps: $seed")
    }

    private fun categorizeApp(pkg: String): AppCategory = when {
        pkg.contains("map", true) || pkg.contains("navi", true) ||
        pkg.contains("waze", true) || pkg.contains("amap", true) ||
        pkg.contains("gaode", true) -> AppCategory.NAVIGATION

        pkg.contains("music", true) || pkg.contains("spotify", true) ||
        pkg.contains("podcast", true) || pkg.contains("player", true) ||
        pkg.contains("qqmusic", true) || pkg.contains("netease", true) -> AppCategory.MUSIC

        pkg.contains("whatsapp", true) || pkg.contains("telegram", true) ||
        pkg.contains("wechat", true) || pkg.contains("tencent.mm", true) ||
        pkg.contains("messenger", true) || pkg.contains("sms", true) ||
        pkg.contains("dialer", true) || pkg.contains("phone", true) -> AppCategory.COMMUNICATION

        else -> AppCategory.OTHER
    }

    companion object {
        private const val TAG = "AppListBuilder"
    }
}
