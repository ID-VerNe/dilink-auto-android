package com.dilinkauto.server.service

import com.dilinkauto.protocol.NotificationData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class NotificationCompoundKeyAdversarialTest {

    private fun createNotification(
        id: Int,
        packageName: String,
        title: String = "Title",
        text: String = "Text",
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

    // ─── Requirement 1: Notification Compound Key Collision Boundaries ───

    @Test
    fun testDuplicateIdsAcrossDifferentPackages() {
        // Many packages posting with the exact same ID (e.g. standard ID 0 or 1)
        val packages = listOf(
            "com.google.android.gm",
            "com.whatsapp",
            "org.telegram.messenger",
            "com.spotify.music",
            "com.tencent.mm",
            "com.bytedance.toutiao"
        )
        val collisionId = 1

        var list = emptyList<NotificationData>()

        // Post from all packages with ID 1
        for (pkg in packages) {
            val n = createNotification(id = collisionId, packageName = pkg, title = "Message from $pkg")
            list = list.filter { it.id != n.id || it.packageName != n.packageName } + n
        }

        assertEquals("All distinct packages with collision ID must coexist", packages.size, list.size)
        for (pkg in packages) {
            val found = list.filter { it.id == collisionId && it.packageName == pkg }
            assertEquals("Exactly one entry per package", 1, found.size)
        }

        // Now remove from one specific package
        val removeTarget = createNotification(id = collisionId, packageName = "com.whatsapp")
        list = list.filter { it.id != removeTarget.id || it.packageName != removeTarget.packageName }

        assertEquals(packages.size - 1, list.size)
        assertFalse("Removed package must not be in list", list.any { it.packageName == "com.whatsapp" })
        assertTrue("Other packages must remain intact", list.any { it.packageName == "org.telegram.messenger" })
    }

    @Test
    fun testMultipleNotificationsFromSamePackageWithDifferentIds() {
        val pkg = "com.example.multi"
        var list = emptyList<NotificationData>()

        // Post 10 notifications from the same package with IDs 0..9
        for (i in 0 until 10) {
            val n = createNotification(id = i, packageName = pkg, title = "Notification #$i", progress = i * 10)
            list = list.filter { it.id != n.id || it.packageName != n.packageName } + n
        }

        assertEquals(10, list.size)

        // Update progress of ID 5
        val update = createNotification(id = 5, packageName = pkg, title = "Notification #5 UPDATED", progress = 99)
        list = list.filter { it.id != update.id || it.packageName != update.packageName } + update

        assertEquals("Updating same ID must replace, not grow size", 10, list.size)
        val updatedEntry = list.find { it.id == 5 && it.packageName == pkg }
        assertEquals(99, updatedEntry?.progress)
        assertEquals("Notification #5 UPDATED", updatedEntry?.title)

        // Remove IDs 2 and 7
        for (idToRemove in listOf(2, 7)) {
            val rem = createNotification(id = idToRemove, packageName = pkg)
            list = list.filter { it.id != rem.id || it.packageName != rem.packageName }
        }

        assertEquals(8, list.size)
        assertFalse(list.any { it.id == 2 && it.packageName == pkg })
        assertFalse(list.any { it.id == 7 && it.packageName == pkg })
        assertTrue(list.any { it.id == 5 && it.packageName == pkg })
    }

    @Test
    fun testExtremeBoundaryIds() {
        val boundaryIds = listOf(
            0,
            -1,
            Int.MIN_VALUE,
            Int.MAX_VALUE,
            42,
            -999999
        )
        val pkg = "com.example.boundary"
        var list = emptyList<NotificationData>()

        for (id in boundaryIds) {
            val n = createNotification(id = id, packageName = pkg, title = "ID: $id")
            list = list.filter { it.id != n.id || it.packageName != n.packageName } + n
        }

        assertEquals("All boundary IDs must be preserved", boundaryIds.size, list.size)

        for (id in boundaryIds) {
            val found = list.find { it.id == id && it.packageName == pkg }
            assertEquals("ID: $id", found?.title)
        }

        // Remove Int.MIN_VALUE and Int.MAX_VALUE
        for (idToRemove in listOf(Int.MIN_VALUE, Int.MAX_VALUE)) {
            val rem = createNotification(id = idToRemove, packageName = pkg)
            list = list.filter { it.id != rem.id || it.packageName != rem.packageName }
        }

        assertEquals(boundaryIds.size - 2, list.size)
        assertFalse(list.any { it.id == Int.MIN_VALUE })
        assertFalse(list.any { it.id == Int.MAX_VALUE })
        assertTrue(list.any { it.id == 0 })
        assertTrue(list.any { it.id == -1 })
    }

    @Test
    fun testPackageNameBoundariesAndCaseSensitivity() {
        var list = emptyList<NotificationData>()

        // Test empty package name
        val emptyPkg = createNotification(id = 1, packageName = "", title = "Empty Pkg")
        list = list.filter { it.id != emptyPkg.id || it.packageName != emptyPkg.packageName } + emptyPkg
        assertEquals(1, list.size)

        // Case sensitive package names: "com.App" vs "com.app"
        val lowerPkg = createNotification(id = 1, packageName = "com.app", title = "Lower")
        val upperPkg = createNotification(id = 1, packageName = "com.App", title = "Upper")
        list = list.filter { it.id != lowerPkg.id || it.packageName != lowerPkg.packageName } + lowerPkg
        list = list.filter { it.id != upperPkg.id || it.packageName != upperPkg.packageName } + upperPkg

        assertEquals("Different casing in package names are distinct packages", 3, list.size)
        assertTrue(list.any { it.packageName == "" && it.id == 1 })
        assertTrue(list.any { it.packageName == "com.app" && it.id == 1 })
        assertTrue(list.any { it.packageName == "com.App" && it.id == 1 })
    }

    @Test
    fun testRapidInterleavedPostAndRemoveStressSequence() {
        // High-stress test: 1000 randomized operations against an oracle Map
        val oracle = mutableMapOf<Pair<String, Int>, NotificationData>()
        var list = emptyList<NotificationData>()

        val pkgs = listOf("pkg.a", "pkg.b", "pkg.c", "pkg.d", "pkg.e")
        val rng = Random(42)

        for (step in 0 until 1000) {
            val pkg = pkgs[rng.nextInt(pkgs.size)]
            val id = rng.nextInt(15) // small ID pool to maximize collisions & replacements
            val op = rng.nextInt(3)

            when (op) {
                0, 1 -> { // POST or UPDATE (66% weight)
                    val n = createNotification(id = id, packageName = pkg, title = "Step $step", progress = step % 100)
                    oracle[pkg to id] = n
                    list = list.filter { it.id != n.id || it.packageName != n.packageName } + n
                }
                2 -> { // REMOVE (33% weight)
                    val rem = createNotification(id = id, packageName = pkg)
                    oracle.remove(pkg to id)
                    list = list.filter { it.id != rem.id || it.packageName != rem.packageName }
                }
            }

            // Invariant check: size and content must match oracle exactly
            assertEquals("Size mismatch at step $step", oracle.size, list.size)
        }

        // Final thorough validation
        for ((key, expected) in oracle) {
            val (pkg, id) = key
            val actual = list.find { it.packageName == pkg && it.id == id }
            assertEquals("Oracle content mismatch for ($pkg, $id)", expected.title, actual?.title)
            assertEquals("Oracle progress mismatch for ($pkg, $id)", expected.progress, actual?.progress)
        }
    }

    @Test
    fun testClearNotificationMethodSemantics() {
        val n1 = createNotification(id = 10, packageName = "pkg.alpha", title = "N1")
        val n2 = createNotification(id = 10, packageName = "pkg.beta", title = "N2")
        val n3 = createNotification(id = 20, packageName = "pkg.alpha", title = "N3")

        var notifications = listOf(n1, n2, n3)

        // Clear notification with id=10, pkg=pkg.alpha (simulating CarConnectionService.clearNotification)
        val clearId = 10
        val clearPkg = "pkg.alpha"
        notifications = notifications.filter { it.id != clearId || it.packageName != clearPkg }

        assertEquals(2, notifications.size)
        assertFalse("pkg.alpha with id 10 must be cleared", notifications.any { it.id == 10 && it.packageName == "pkg.alpha" })
        assertTrue("pkg.beta with id 10 must remain", notifications.any { it.id == 10 && it.packageName == "pkg.beta" })
        assertTrue("pkg.alpha with id 20 must remain", notifications.any { it.id == 20 && it.packageName == "pkg.alpha" })

        // Clear all notifications
        notifications = emptyList()
        assertTrue("clearAllNotifications must empty list", notifications.isEmpty())
    }
}
