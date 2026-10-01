package com.dilinkauto.client.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UpdateManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testParseVersion_releaseVersion() {
        val parsed = UpdateManager.parseVersion("0.17.0")
        assertEquals("0.17.0", parsed.base)
        assertFalse(parsed.isDev)
        assertEquals(0, parsed.devNum)
    }

    @Test
    fun testParseVersion_devVersionWithNumber() {
        val parsed = UpdateManager.parseVersion("0.17.0-dev-02")
        assertEquals("0.17.0", parsed.base)
        assertTrue(parsed.isDev)
        assertEquals(2, parsed.devNum)
    }

    @Test
    fun testParseVersion_devVersionWithoutNumber() {
        val parsed = UpdateManager.parseVersion("0.17.0-dev")
        assertEquals("0.17.0", parsed.base)
        assertTrue(parsed.isDev)
        assertEquals(0, parsed.devNum)
    }

    @Test
    fun testCompareVersions_majorMinorPatch() {
        assertTrue(UpdateManager.compareVersions("0.18.0", "0.17.0") > 0)
        assertTrue(UpdateManager.compareVersions("0.17.1", "0.17.0") > 0)
        assertTrue(UpdateManager.compareVersions("1.0.0", "0.17.0") > 0)
        assertTrue(UpdateManager.compareVersions("0.17.0", "0.17.0") == 0)
        assertTrue(UpdateManager.compareVersions("0.16.9", "0.17.0") < 0)
    }

    @Test
    fun testCompareVersions_releaseVsDev() {
        // Release 0.17.0 is newer than dev 0.17.0-dev-01
        assertTrue(UpdateManager.compareVersions("0.17.0", "0.17.0-dev-01") > 0)
        assertTrue(UpdateManager.compareVersions("0.17.0-dev-01", "0.17.0") < 0)

        // Dev with higher base is newer than release with lower base
        assertTrue(UpdateManager.compareVersions("0.18.0-dev-01", "0.17.0") > 0)

        // Between dev builds of same base, higher devNum wins
        assertTrue(UpdateManager.compareVersions("0.17.0-dev-05", "0.17.0-dev-02") > 0)
        assertTrue(UpdateManager.compareVersions("0.17.0-dev-02", "0.17.0-dev-05") < 0)
        assertTrue(UpdateManager.compareVersions("0.17.0-dev-02", "0.17.0-dev-02") == 0)
    }

    @Test
    fun testStageApkForShizuku_directCopySuccess() {
        val srcFile = tempFolder.newFile("sample_update.apk")
        val content = "APK_MOCK_PAYLOAD_BYTES_123456789".toByteArray()
        srcFile.writeBytes(content)

        val dstDir = tempFolder.newFolder("target_dir")
        val dstFile = File(dstDir, "staged_update.apk")

        val success = UpdateManager.stageApkForShizuku(srcFile, dstFile.absolutePath)
        assertTrue("Staging should succeed via direct copy when destination is writable", success)
        assertTrue("Destination file should exist", dstFile.exists())
        assertEquals("File content should match source", content.size.toLong(), dstFile.length())
        assertEquals("File content should be identical", String(content), dstFile.readText())
    }
}
