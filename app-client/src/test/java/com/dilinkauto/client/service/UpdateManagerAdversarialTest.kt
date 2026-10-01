package com.dilinkauto.client.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UpdateManagerAdversarialTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ─── Requirement 2: UpdateManager.compareVersions Edge Cases ───

    @Test
    fun testCompareVersions_devQualifiers() {
        // Dev without number is treated as devNum = 0
        assertEquals(0, UpdateManager.compareVersions("0.17.0-dev", "0.17.0-dev"))
        assertTrue(UpdateManager.compareVersions("0.17.0-dev-01", "0.17.0-dev") > 0)
        assertTrue(UpdateManager.compareVersions("0.17.0-dev", "0.17.0-dev-01") < 0)

        // Multiple dev versions with different padding and magnitudes
        assertTrue(UpdateManager.compareVersions("0.17.0-dev-10", "0.17.0-dev-02") > 0)
        assertTrue(UpdateManager.compareVersions("0.17.0-dev-02", "0.17.0-dev-10") < 0)
        assertEquals(0, UpdateManager.compareVersions("0.17.0-dev-05", "0.17.0-dev-5"))

        // Dev of higher base version is newer than release of lower base version
        assertTrue(UpdateManager.compareVersions("0.18.0-dev-01", "0.17.0") > 0)
        assertTrue(UpdateManager.compareVersions("0.18.0-dev", "0.17.0") > 0)

        // Dev of lower base version is older than release of higher base version
        assertTrue(UpdateManager.compareVersions("0.17.0-dev-99", "0.18.0") < 0)
    }

    @Test
    fun testCompareVersions_patchBumpsAndMultiDigitComponents() {
        // Multi-digit version components (e.g. 10 > 9, 20 > 9)
        assertTrue(UpdateManager.compareVersions("0.17.10", "0.17.9") > 0)
        assertTrue(UpdateManager.compareVersions("0.17.9", "0.17.10") < 0)
        assertTrue(UpdateManager.compareVersions("0.20.0", "0.9.0") > 0)
        assertTrue(UpdateManager.compareVersions("10.0.0", "9.0.0") > 0)

        // Unequal component lengths: missing components default to 0
        assertEquals(0, UpdateManager.compareVersions("1.0", "1.0.0"))
        assertEquals(0, UpdateManager.compareVersions("1.0.0", "1.0"))
        assertTrue(UpdateManager.compareVersions("1.0.1", "1.0") > 0)
        assertTrue(UpdateManager.compareVersions("1.0", "1.0.1") < 0)
        assertTrue(UpdateManager.compareVersions("1.0.0.1", "1.0.0") > 0)
    }

    @Test
    fun testCompareVersions_majorAndMinorTransitions() {
        // Major transitions
        assertTrue(UpdateManager.compareVersions("1.0.0", "0.99.99") > 0)
        assertTrue(UpdateManager.compareVersions("2.0.0", "1.99.99") > 0)
        assertTrue(UpdateManager.compareVersions("0.99.99", "1.0.0") < 0)

        // Minor transitions
        assertTrue(UpdateManager.compareVersions("0.18.0", "0.17.99") > 0)
        assertTrue(UpdateManager.compareVersions("0.17.99", "0.18.0") < 0)
    }

    @Test
    fun testCompareVersions_snapshotAndOtherQualifiers() {
        // -SNAPSHOT is not a -dev qualifier; parseVersion treats it as base="0.17.0-SNAPSHOT"
        val parsedSnapshot = UpdateManager.parseVersion("0.17.0-SNAPSHOT")
        assertEquals("0.17.0-SNAPSHOT", parsedSnapshot.base)
        assertFalse("SNAPSHOT is not recognized as isDev", parsedSnapshot.isDev)

        // In compareVersions, "0.17.0-SNAPSHOT" splits into [0, 17, 0] because "0-SNAPSHOT".toIntOrNull() == null -> 0
        // Hence, compareVersions("0.17.0", "0.17.0-SNAPSHOT") evaluates components [0, 17, 0] vs [0, 17, 0]
        val cmp = UpdateManager.compareVersions("0.17.0", "0.17.0-SNAPSHOT")
        assertEquals("SNAPSHOT without -dev is treated with numeric prefix 0", 0, cmp)

        // Higher version with SNAPSHOT is still greater than lower release
        assertTrue(UpdateManager.compareVersions("0.18.0-SNAPSHOT", "0.17.0") > 0)
    }

    @Test
    fun testCompareVersions_invalidAndMalformedStrings() {
        // Empty strings do not crash, default to 0
        assertEquals(0, UpdateManager.compareVersions("", ""))
        assertTrue(UpdateManager.compareVersions("1.0.0", "") > 0)
        assertTrue(UpdateManager.compareVersions("", "1.0.0") < 0)

        // Completely non-numeric strings
        assertEquals(0, UpdateManager.compareVersions("invalid", "corrupt"))
        assertTrue(UpdateManager.compareVersions("1.0.0", "invalid") > 0)

        // Partially non-numeric strings
        assertTrue(UpdateManager.compareVersions("1.2.beta", "1.1.0") > 0)
        assertTrue(UpdateManager.compareVersions("1.2.3", "1.2.beta") > 0)

        // Non-digit dev suffixes (e.g. -dev-abc):
        // Regex "^(.*)-dev(?:-(\\d+))?$" requires digits after "-dev-".
        // Therefore, "-dev-abc" fails regex match, isDev is false, and base is the entire string.
        val parsedMalformedDev = UpdateManager.parseVersion("0.17.0-dev-abc")
        assertFalse("Non-digit qualifier after -dev- is not recognized as isDev", parsedMalformedDev.isDev)
        assertEquals("0.17.0-dev-abc", parsedMalformedDev.base)
        assertEquals(0, parsedMalformedDev.devNum)
    }

    // ─── Requirement 3: UpdateManager.stageApkForShizuku Behavior ───

    @Test
    fun testStageApkForShizuku_targetPathAlreadyExists() {
        // Source APK
        val srcFile = tempFolder.newFile("new_update.apk")
        val newContent = "NEW_UPDATE_PAYLOAD_V2".toByteArray()
        srcFile.writeBytes(newContent)

        // Existing target file with older/different contents
        val targetDir = tempFolder.newFolder("staged_target")
        val targetFile = File(targetDir, "update.apk")
        targetFile.writeBytes("OLD_STALE_PAYLOAD_V1".toByteArray())
        assertEquals("OLD_STALE_PAYLOAD_V1", targetFile.readText())

        // Execute stageApkForShizuku — must overwrite the existing file
        val staged = UpdateManager.stageApkForShizuku(srcFile, targetFile.absolutePath)

        assertTrue("Staging must succeed when target already exists", staged)
        assertTrue("Target file must exist", targetFile.exists())
        assertEquals("Target file must have been overwritten with new content",
            newContent.size.toLong(), targetFile.length())
        assertEquals("Target file content must match new payload",
            String(newContent), targetFile.readText())
    }

    @Test
    fun testStageApkForShizuku_sourceFileDoesNotExist() {
        val nonExistentSource = File(tempFolder.root, "does_not_exist.apk")
        val targetFile = File(tempFolder.root, "target.apk")

        // Must fail cleanly without throwing unhandled exceptions
        val result = UpdateManager.stageApkForShizuku(nonExistentSource, targetFile.absolutePath)
        assertFalse("Staging must return false when source does not exist", result)
        assertFalse("Target must not be created", targetFile.exists())
    }

    @Test
    fun testStageApkForShizuku_largePayloadIntegrity() {
        val srcFile = tempFolder.newFile("large_update.apk")
        // Create 2MB payload
        val chunkSize = 64 * 1024
        val chunk = ByteArray(chunkSize) { (it % 127).toByte() }
        srcFile.outputStream().use { out ->
            repeat(32) { out.write(chunk) }
        }
        val expectedLength = 32L * chunkSize
        assertEquals(expectedLength, srcFile.length())

        val targetFile = File(tempFolder.newFolder("large_target"), "update.apk")
        val staged = UpdateManager.stageApkForShizuku(srcFile, targetFile.absolutePath)

        assertTrue(staged)
        assertTrue(targetFile.exists())
        assertEquals("Target file size must match source exactly", expectedLength, targetFile.length())
    }

    @Test
    fun testStageApkForShizuku_targetIsDirectoryBehavior() {
        val srcFile = tempFolder.newFile("source.apk")
        val content = "SAMPLE_APK_CONTENT".toByteArray()
        srcFile.writeBytes(content)

        // Case 1: Target path is an EMPTY directory.
        // Kotlin File.copyTo(target, overwrite=true) calls target.delete() which deletes the empty directory
        // and creates the staged file in its place.
        val emptyDir = tempFolder.newFolder("target_empty_dir")
        val emptyResult = UpdateManager.stageApkForShizuku(srcFile, emptyDir.absolutePath)
        assertTrue("Overwriting empty directory deletes it and creates staged file", emptyResult)
        assertTrue("Empty dir was replaced by regular file", emptyDir.isFile)
        assertEquals(content.size.toLong(), emptyDir.length())

        // Case 2: Target path is a NON-EMPTY directory.
        // target.delete() fails because directory is not empty, direct copy throws FileAlreadyExistsException.
        // Staging falls back to ShizukuManager which returns false (unavailable in unit test env).
        val nonEmptyDir = tempFolder.newFolder("target_non_empty_dir")
        File(nonEmptyDir, "contained_file.txt").writeText("I make this directory non-empty")
        val nonEmptyResult = UpdateManager.stageApkForShizuku(srcFile, nonEmptyDir.absolutePath)
        assertFalse("Staging must return false when target is a non-empty directory", nonEmptyResult)
        assertTrue("Non-empty directory remains a directory", nonEmptyDir.isDirectory)
    }
}
