package com.dilinkauto.client

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import com.dilinkauto.protocol.AndroidPlatformHooks
import java.io.ByteArrayOutputStream

class ClientApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AndroidPlatformHooks.install()
        createNotificationChannels()
        ShizukuManager.init(this)
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        val serviceChannel = NotificationChannel(
            CHANNEL_SERVICE,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(serviceChannel)
    }

    companion object {
        const val CHANNEL_SERVICE = "dilinkauto_service"

        /** Loads an app icon at [size]×[size] and returns PNG bytes. No caching — phone only transmits. */
        fun loadIconPng(pm: PackageManager, packageName: String, size: Int): ByteArray {
            return try {
                val icon = pm.getApplicationIcon(packageName)
                val bitmap = IconRenderer.toBitmap(icon, size, rescale = true)
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 80, stream)
                stream.toByteArray()
            } catch (_: Exception) {
                ByteArray(0)
            }
        }
    }
}
