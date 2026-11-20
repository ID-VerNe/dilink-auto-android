package com.dilinkauto.vdserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.ClosedChannelException

/**
 * 钉住 issue #221 第 2 条：`VideoSend` 写失败日志不得只剩 `null`。
 *
 * 现场 `vd-server.log:3176`：`VideoSend: write failed, session should reconnect: null`，
 * 同一时刻车机端是 `User disconnect — will not auto-reconnect`。本测试锁住：
 *  1. 用户/本端关闭通道必须归类为正常收尾，不再声称「session should reconnect」；
 *  2. 真实写失败仍保留「session should reconnect」语义；
 *  3. 文案始终带异常类名，message 为空时显式标注并附 cause 链，不出现裸 `null`。
 */
class VideoSendFailureTest {

    @Test
    fun `closed channel is classified as expected close`() {
        val d = VideoSendFailure.describe(ClosedChannelException())
        assertTrue("本端关闭通道属于正常收尾：${d.text}", d.expectedClose)
        assertTrue("文案必须带异常类名：${d.text}", d.text.contains("ClosedChannelException"))
        assertFalse("正常收尾不得再写 session should reconnect：${d.text}", d.text.contains("should reconnect"))
    }

    @Test
    fun `asynchronous close and eof are expected closes`() {
        assertTrue(VideoSendFailure.describe(AsynchronousCloseException()).expectedClose)
        assertTrue(VideoSendFailure.describe(EOFException()).expectedClose)
    }

    @Test
    fun `peer reset messages are expected closes`() {
        assertTrue(
            "用户断开时对端先关 socket，Broken pipe 不是链路故障",
            VideoSendFailure.describe(IOException("Broken pipe")).expectedClose,
        )
        assertTrue(
            VideoSendFailure.describe(IOException("Connection reset by peer")).expectedClose,
        )
    }

    @Test
    fun `real io failure keeps reconnect semantics`() {
        val d = VideoSendFailure.describe(
            IOException("Write timed out: 1023 bytes remaining, send buffer full for 10000ms"),
        )
        assertFalse("写超时是真实故障：${d.text}", d.expectedClose)
        assertTrue("必须保留重连语义：${d.text}", d.text.contains("session should reconnect"))
        assertTrue("必须带异常类名：${d.text}", d.text.contains("IOException"))
    }

    @Test
    fun `null message prints class name and cause chain instead of null`() {
        // 注意不能用 RuntimeException(cause)：它会把 message 设成 cause.toString()，
        // 现场那种 message 真的为 null 的形态要用无参构造 + initCause 复刻。
        val error = RuntimeException().apply { initCause(IOException()) }
        val d = VideoSendFailure.describe(error)
        assertFalse(d.expectedClose)
        assertTrue("message 为空时必须显式标注：${d.text}", d.text.contains("no message"))
        assertTrue("必须带顶层异常类名：${d.text}", d.text.contains("RuntimeException"))
        assertTrue("必须带 cause 类名：${d.text}", d.text.contains("IOException"))
        assertFalse("日志里不得出现裸 null：${d.text}", d.text.contains("null"))
    }

    @Test
    fun `description carries message when present`() {
        assertEquals(
            "RuntimeException: boom <- IOException: inner",
            VideoSendFailure.detail(RuntimeException("boom", IOException("inner"))),
        )
    }
}
