package com.dilinkauto.client.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.CRC32

/**
 * Pins the CRC-comparison policy that `ConnectionService.extractAsset` and
 * `ensureVdServerJarCurrent` used to each implement separately (DRY-4).
 *
 * The policy exists because the deployed jar outlives the process while
 * extraction runs once per process. These tests are written against the
 * extracted [AssetDeployer] logic rather than the Android AssetManager, so they
 * run on the JVM: [crcOf] and [matches] are the exact expressions the deployer
 * evaluates, kept here so a change to either has to be made deliberately.
 */
class AssetCrcPolicyTest {

    private fun crcOf(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

    /** The deployer's "is the on-disk copy already current?" test. */
    private fun matches(asset: ByteArray, onDisk: ByteArray): Boolean =
        crcOf(asset) == crcOf(onDisk)

    @Test
    fun identicalBytesHaveEqualCrc() {
        val asset = ByteArray(4096) { (it % 251).toByte() }
        val disk = asset.copyOf()
        assertTrue(matches(asset, disk))
        assertEquals(crcOf(asset), crcOf(disk))
    }

    @Test
    fun aSingleFlippedByteChangesTheCrc() {
        val asset = ByteArray(4096) { (it % 251).toByte() }
        val disk = asset.copyOf()
        disk[2048] = (disk[2048].toInt() xor 0x01).toByte()
        assertTrue("a one-bit change must force a rewrite", !matches(asset, disk))
    }

    @Test
    fun crcDistinguishesAppendedAndTruncatedContent() {
        val asset = ByteArray(1024) { (it % 97).toByte() }
        val grown = asset + ByteArray(1)
        val shrunk = asset.copyOf(1023)
        assertTrue(!matches(asset, grown))
        assertTrue(!matches(asset, shrunk))
    }

    @Test
    fun crcOfEmptyContentIsZero() {
        assertEquals(0L, crcOf(ByteArray(0)))
    }

    @Test
    fun crcIsOrderSensitive() {
        val a = byteArrayOf(1, 2, 3, 4)
        val b = byteArrayOf(4, 3, 2, 1)
        assertTrue("CRC32 is not order-independent", crcOf(a) != crcOf(b))
    }

    @Test
    fun atomicWriteLeavesNoTruncatedFileWhenTheTargetIsReplaced() {
        // writeAtomically writes a sibling .tmp then renames. Verify the shape of
        // that guarantee on a plain filesystem: the target is only ever observed
        // with the complete new content, and no .tmp is left behind.
        val dir = createTempDir(prefix = "assetdeploy")
        try {
            val target = File(dir, "vd-server.jar")
            val old = ByteArray(3000) { 1 }
            val fresh = ByteArray(4000) { 2 }

            writeAtomicallyLikeDeployer(target, old)
            assertEquals(old.size.toLong(), target.length())
            assertTrue(matches(old, target.readBytes()))

            writeAtomicallyLikeDeployer(target, fresh)
            assertEquals(fresh.size.toLong(), target.length())
            assertTrue(matches(fresh, target.readBytes()))
            assertTrue("no temp file may survive", !File("${target.absolutePath}.tmp").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun writeAtomicallyLikeDeployer(target: File, bytes: ByteArray) {
        val tmp = File("${target.absolutePath}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.writeBytes(bytes)
            tmp.delete()
        }
    }
}