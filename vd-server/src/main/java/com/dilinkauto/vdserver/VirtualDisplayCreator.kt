package com.dilinkauto.vdserver

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.Surface
import java.lang.reflect.Method

/**
 * Creates the shell-UID VirtualDisplay via framework reflection.
 *
 * Two strategies, in order:
 *  1. [DisplayManagerGlobal] — the hidden singleton that accepts a
 *     [VirtualDisplayConfig] built via its Builder. This is the path that
 *     lets us set the surface, the trust flag (0x6c49), and the
 *     display-id-to-mirror; it works on the AOSP shell runtime.
 *  2. [DisplayManager] fallback — construct the (also hidden) constructor
 *     via [FakeContext], force `mDisplayIdToMirror` to 0, and use the public
 *     [DisplayManager.createVirtualDisplay] with the same trust-flag bits.
 *
 * After creation, applies the iPad-like letterbox style (Android 12L+) so
 * portrait apps render at a sensible 16:10 aspect ratio inside the car's
 * landscape viewport, and disables the screen-off timeout / lift / proximity
 * wake so the phone panel doesn't sleep while the car is driving.
 *
 * Extracted from [PipelineServer] to keep the reflection body out of the
 * process lifecycle. The returned [VirtualDisplay] is owned by the caller
 * (released in cleanup).
 */
internal class VirtualDisplayCreator(
    private val displayWidth: Int,
    private val displayHeight: Int,
    private val dpi: Int,
    private val execShell: (String) -> Unit,
    private val execShellOutput: (String) -> String?,
    private val log: (String) -> Unit,
    private val err: (String) -> Unit
) {
    var displayId: Int = -1
        private set

    fun create(surface: Surface): VirtualDisplay? {
        var vd: VirtualDisplay? = createViaDisplayManagerGlobal(surface)
        if (vd == null) vd = createViaDisplayManager(surface)
        if (vd == null) return null
        configureDisplayEnvironment()
        return vd
    }

    private fun createViaDisplayManagerGlobal(surface: Surface): VirtualDisplay? {
        try {
            log("DisplayManagerGlobal...")
            val dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val dmg = dmgClass.getDeclaredMethod("getInstance").apply { isAccessible = true }.invoke(null)
            val cfgClass = Class.forName("android.hardware.display.VirtualDisplayConfig")
            val bldClass = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
            val bldCtor = bldClass.getDeclaredConstructor(String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            bldCtor.isAccessible = true
            val bld = bldCtor.newInstance("DiLinkAutoVD", displayWidth, displayHeight, dpi)
            bldClass.getDeclaredMethod("setSurface", Surface::class.java).apply { isAccessible = true; invoke(bld, surface) }
            bldClass.getDeclaredMethod("setFlags", Int::class.javaPrimitiveType).apply { isAccessible = true; invoke(bld, 0x6c49) }
            val cfg = bldClass.getDeclaredMethod("build").apply { isAccessible = true }.invoke(bld)
            val cbClass = Class.forName("android.hardware.display.IVirtualDisplayCallback")
            val createVd: Method = try {
                dmgClass.getDeclaredMethod("createVirtualDisplay", cfgClass, cbClass, android.os.Handler::class.java, String::class.java)
            } catch (_: NoSuchMethodException) {
                dmgClass.getDeclaredMethod("createVirtualDisplay", cfgClass, cbClass, String::class.java)
            }
            createVd.isAccessible = true
            val vd = (if (createVd.parameterCount == 4) createVd.invoke(dmg, cfg, null, null, "com.android.shell")
                else createVd.invoke(dmg, cfg, null, "com.android.shell")) as? VirtualDisplay
            if (vd != null) {
                displayId = try { vd.display.displayId } catch (_: Exception) { findDisplayId("DiLinkAutoVD") }
                log("VD via DisplayManagerGlobal: id=$displayId")
            }
            return vd
        } catch (e: Exception) { err("DisplayManagerGlobal: ${e.message}"); return null }
    }

    private fun createViaDisplayManager(surface: Surface): VirtualDisplay? {
        try {
            log("DisplayManager...")
            val ctor = DisplayManager::class.java.getDeclaredConstructor(android.content.Context::class.java)
            ctor.isAccessible = true; val dm = ctor.newInstance(FakeContext.get())
            val flags = (DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or (1 shl 6) or (1 shl 10) or (1 shl 11) or (1 shl 13) or (1 shl 14))
            val vd = dm.createVirtualDisplay("DiLinkAutoVD", displayWidth, displayHeight, dpi, surface, flags)
            displayId = try { vd.display.displayId } catch (_: Exception) { findDisplayId("DiLinkAutoVD") }
            log("VD via DisplayManager: id=$displayId")
            return vd
        } catch (e: Exception) { err("DisplayManager: ${e.message}"); return null }
    }

    /** Apply the letterbox style + disable screen-off / wake gestures for the session. */
    private fun configureDisplayEnvironment() {
        try {
            execShell("cmd window set-letterbox-style --aspectRatio 1.6 --cornerRadius 24 --horizontalPositionMultiplier 0.5")
            log("Configured iPad-like letterbox style: aspectRatio=1.6, cornerRadius=24")
        } catch (_: Exception) {}
        try {
            execShell("settings put system screen_off_timeout 2147483647"); log("Screen timeout disabled")
            execShell("settings put system lift_wakeup_enabled 0")
            execShell("settings put system proximity_wakeup_enabled 0")
        } catch (_: Exception) {}
    }

    private fun findDisplayId(name: String): Int {
        val out = execShellOutput("dumpsys display 2>/dev/null | grep -A 5 '$name' | grep 'mDisplayId=' | head -1")?.trim() ?: return -1
        return Regex("mDisplayId=(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }
}
