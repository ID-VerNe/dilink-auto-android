package com.dilinkauto.client.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Versioning（parseVersion / compareVersions）单测：车端 APK 安装门槛。
 * 比较错 → 要么车机永远跑旧引擎，要么每次点击都重推 20MB。
 */
class VersioningTest {

    @Test
    fun `parse release version`() {
        val p = parseVersion("0.17.0")
        assertEquals("0.17.0", p.base); assertFalse(p.isDev); assertEquals(0, p.devNum)
    }

    @Test
    fun `parse bare dev suffix`() {
        val p = parseVersion("0.17.0-dev")
        assertEquals("0.17.0", p.base); assertTrue(p.isDev); assertEquals(0, p.devNum)
    }

    @Test
    fun `parse numbered dev suffix`() {
        val p = parseVersion("0.17.0-dev-02")
        assertEquals("0.17.0", p.base); assertTrue(p.isDev); assertEquals(2, p.devNum)
    }

    @Test
    fun `dev number is decimal not octal`() {
        assertEquals(10, parseVersion("1.0.0-dev-010").devNum)
    }

    @Test
    fun `snapshot suffix is not a dev build`() {
        val p = parseVersion("0.17.0-SNAPSHOT")
        assertEquals("0.17.0-SNAPSHOT", p.base); assertFalse(p.isDev)
    }

    @Test
    fun `non numeric dev number is not a dev build`() {
        val p = parseVersion("1.0.0-dev-abc")
        assertEquals("1.0.0-dev-abc", p.base); assertFalse(p.isDev)
    }

    @Test
    fun `greedy base takes the last dev marker`() {
        val p = parseVersion("1.0.0-dev-dev-3")
        assertEquals("1.0.0-dev", p.base); assertTrue(p.isDev); assertEquals(3, p.devNum)
    }

    @Test
    fun `uppercase dev suffix is not recognized`() {
        val p = parseVersion("1.0.0-DEV-3")
        assertEquals("1.0.0-DEV-3", p.base); assertFalse(p.isDev)
    }

    @Test
    fun `compare orders numerically not lexically`() {
        assertTrue(compareVersions("0.9.0", "0.10.0") < 0)
    }

    @Test
    fun `compare treats missing components as zero`() {
        assertEquals(0, compareVersions("1.2", "1.2.0"))
        assertEquals(0, compareVersions("1", "1.0.0"))
    }

    @Test
    fun `compare coerces non numeric components to zero`() {
        assertEquals(0, compareVersions("1.2.beta", "1.2.0"))
    }

    @Test
    fun `compare ignores leading zeros`() {
        assertEquals(0, compareVersions("01.002.3", "1.2.3"))
    }

    @Test
    fun `release beats dev of the same base`() {
        assertTrue(compareVersions("0.17.0", "0.17.0-dev-99") > 0)
    }

    @Test
    fun `dev builds order by number`() {
        assertTrue(compareVersions("0.17.0-dev-02", "0.17.0-dev-01") > 0)
        assertTrue(compareVersions("0.17.0-dev", "0.17.0-dev-01") < 0)
    }

    @Test
    fun `dev of a newer base beats an older release`() {
        assertTrue(compareVersions("0.18.0-dev", "0.17.0") > 0)
    }

    @Test
    fun `empty string behaves like zero base`() {
        assertEquals(0, compareVersions("", "0.0.0"))
        assertTrue(compareVersions("", "0.0.1") < 0)
    }

    @Test
    fun `compare is irreflexive and antisymmetric`() {
        val samples = listOf("0.17.0", "0.17.0-dev", "0.17.0-dev-02", "1.2.beta", "")
        for (v in samples) assertEquals(0, compareVersions(v, v))
        for (a in samples) for (b in samples) {
            assertEquals(
                Integer.signum(compareVersions(a, b)),
                -Integer.signum(compareVersions(b, a))
            )
        }
    }

    @Test
    fun `hazard a snapshot compares equal to its release`() {
        // 固定现状：-SNAPSHOT 的最后一段 coerce 成 0，与 release 判定相等
        assertEquals(0, compareVersions("0.17.0-SNAPSHOT", "0.17.0"))
    }

    @Test
    fun `hazard a plain zero version never installs over the not installed sentinel`() {
        // CarAppInstaller.readInstalledVersion 无安装时返回 "0"；versionName 为 "0"/"0.0"/"0.0.0" 会被判为"已最新"
        assertEquals(0, compareVersions("0.0.0", "0"))
        assertEquals(0, compareVersions("0", "0"))
    }
}
