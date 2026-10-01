package com.dilinkauto.server.service

import com.dilinkauto.protocol.NotificationData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationFilterTest {

    private fun createNotification(
        id: Int,
        packageName: String,
        title: String,
        text: String = "Test text",
        progress: Int = 0
    ): NotificationData {
        return NotificationData(
            id = id,
            packageName = packageName,
            appName = packageName.substringAfterLast('.'),
            title = title,
            text = text,
            timestamp = System.currentTimeMillis(),
            progress = progress
        )
    }

    @Test
    fun testNotificationPost_replacesSamePackageAndId() {
        val initialList = listOf(
            createNotification(id = 1, packageName = "com.media.player", title = "Track 1", progress = 10),
            createNotification(id = 2, packageName = "com.chat.app", title = "Message from Alice")
        )

        val updatedPost = createNotification(id = 1, packageName = "com.media.player", title = "Track 1", progress = 50)

        // Applying the filter logic used in DataMsg.NOTIFICATION_POST
        val result = initialList.filter { it.id != updatedPost.id || it.packageName != updatedPost.packageName } + updatedPost

        assertEquals(2, result.size)
        val playerNotif = result.find { it.packageName == "com.media.player" && it.id == 1 }
        assertEquals(50, playerNotif?.progress)
    }

    @Test
    fun testNotificationPost_doesNotOverwriteDifferentPackageWithSameId() {
        // App A and App B both use notification ID = 1
        val notifAppA = createNotification(id = 1, packageName = "com.example.appA", title = "App A Notif")
        val notifAppB = createNotification(id = 1, packageName = "com.example.appB", title = "App B Notif")

        var list = listOf(notifAppA)

        // Post App B notification
        list = list.filter { it.id != notifAppB.id || it.packageName != notifAppB.packageName } + notifAppB

        assertEquals("Both notifications with same ID but different packages must be preserved", 2, list.size)
        assertTrue(list.any { it.packageName == "com.example.appA" && it.id == 1 })
        assertTrue(list.any { it.packageName == "com.example.appB" && it.id == 1 })
    }

    @Test
    fun testNotificationRemove_removesOnlyMatchingPackageAndId() {
        val notifAppA = createNotification(id = 1, packageName = "com.example.appA", title = "App A Notif")
        val notifAppB = createNotification(id = 1, packageName = "com.example.appB", title = "App B Notif")
        val notifAppC = createNotification(id = 2, packageName = "com.example.appA", title = "App A Notif 2")

        val list = listOf(notifAppA, notifAppB, notifAppC)

        val removeTarget = createNotification(id = 1, packageName = "com.example.appA", title = "")

        // Applying the filter logic used in DataMsg.NOTIFICATION_REMOVE
        val filtered = list.filter { it.id != removeTarget.id || it.packageName != removeTarget.packageName }

        assertEquals(2, filtered.size)
        assertFalse("App A with ID 1 should be removed", filtered.any { it.packageName == "com.example.appA" && it.id == 1 })
        assertTrue("App B with ID 1 must be kept", filtered.any { it.packageName == "com.example.appB" && it.id == 1 })
        assertTrue("App A with ID 2 must be kept", filtered.any { it.packageName == "com.example.appA" && it.id == 2 })
    }

    @Test
    fun testCompoundKeyFilter_truthTable() {
        val target = createNotification(id = 100, packageName = "pkg.target", title = "Target")

        // Case 1: Same ID, Same package -> filter out (returns false)
        val sameBoth = createNotification(id = 100, packageName = "pkg.target", title = "Same")
        assertFalse(sameBoth.id != target.id || sameBoth.packageName != target.packageName)

        // Case 2: Same ID, Different package -> keep (returns true)
        val sameIdDiffPkg = createNotification(id = 100, packageName = "pkg.other", title = "Other")
        assertTrue(sameIdDiffPkg.id != target.id || sameIdDiffPkg.packageName != target.packageName)

        // Case 3: Different ID, Same package -> keep (returns true)
        val diffIdSamePkg = createNotification(id = 200, packageName = "pkg.target", title = "Diff ID")
        assertTrue(diffIdSamePkg.id != target.id || diffIdSamePkg.packageName != target.packageName)

        // Case 4: Different ID, Different package -> keep (returns true)
        val diffBoth = createNotification(id = 200, packageName = "pkg.other", title = "Both Diff")
        assertTrue(diffBoth.id != target.id || diffBoth.packageName != target.packageName)
    }
}
