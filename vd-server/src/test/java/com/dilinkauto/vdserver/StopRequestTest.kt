package com.dilinkauto.vdserver

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files

/**
 * [consumeStopRequest] 契约测试（停止哨兵协议见
 * [com.dilinkauto.protocol.VdDeploy.gracefulStopCommand] 与其类头）。
 *
 * 用临时目录注入路径：vd-server 的单测跑纯 JVM，不能碰 /sdcard。watchdog 的
 * 线程集成不在单测范围内——它只依赖本函数的返回值与"消费即删除"语义。
 */
class StopRequestTest {

    private val dir: File = Files.createTempDirectory("stop-request-test").toFile()
    private val path: String = File(dir, "stop-request").absolutePath

    @After
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun `absent file is not a stop request`() {
        assertFalse(consumeStopRequest(path))
    }

    @Test
    fun `existing file is consumed and deleted`() {
        val f = File(path)
        f.writeText("")

        assertTrue("存在即受理", consumeStopRequest(path))
        assertFalse("受理必须消费掉文件（外部据此观察已受理）", f.exists())
        assertFalse("二次调用为空", consumeStopRequest(path))
    }

    /**
     * 删除失败也要返回 true：信号已"受理"，残留由引擎启动时的清理兜底。
     * Windows 上打开着的文件 delete 会失败（Unix 上 unlink 仍成功）——
     * 两种平台都必须保持"存在即 true"的契约。
     */
    @Test
    fun `consumed even when delete fails`() {
        val f = File(path)
        f.writeText("")
        FileInputStream(f).use {
            assertTrue(consumeStopRequest(path))
        }
    }
}
