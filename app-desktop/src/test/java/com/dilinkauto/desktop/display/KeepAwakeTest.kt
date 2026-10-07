package com.dilinkauto.desktop.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * KeepAwake 单测。
 *
 * 真实 API 按线程生效、只能靠肉眼观察屏幕是否熄灭来验证，
 * 因此这里注入假驱动，只验证"调用参数对不对、失败怎么降级"这两件能确定的事。
 */
class KeepAwakeTest {

    /** 记录每次调用的 flags，并可按需返回失败或抛异常。 */
    private class RecordingDriver(
        private val result: Long = 1L,
        private val fail: Boolean = false,
    ) : KeepAwake.Driver {
        val calls = CopyOnWriteArrayList<Long>()

        override fun setExecutionState(flags: Long): Long {
            calls.add(flags)
            if (fail) throw IllegalStateException("模拟系统调用失败")
            return result
        }
    }

    @Test
    fun `enable 传入 持续+系统+显示器 三个标志`() {
        val driver = RecordingDriver()
        val keepAwake = KeepAwake(driver)
        try {
            assertTrue(keepAwake.enable())
            assertTrue(keepAwake.isActive)
            assertEquals(0x80000003L, driver.calls.single())
        } finally {
            keepAwake.close()
        }
    }

    @Test
    fun `disable 只传 ES_CONTINUOUS 并清掉激活状态`() {
        val driver = RecordingDriver()
        val keepAwake = KeepAwake(driver)
        try {
            keepAwake.enable()
            assertTrue(keepAwake.disable())
            assertFalse(keepAwake.isActive)
            assertEquals(0x80000000L, driver.calls.last())
        } finally {
            keepAwake.close()
        }
    }

    @Test
    fun `系统调用返回 0 视为失败，不置为激活`() {
        val driver = RecordingDriver(result = 0L)
        val keepAwake = KeepAwake(driver)
        try {
            assertFalse(keepAwake.enable())
            assertFalse(keepAwake.isActive)
        } finally {
            keepAwake.close()
        }
    }

    @Test
    fun `驱动抛异常时降级为失败，不影响调用方`() {
        val driver = RecordingDriver(fail = true)
        val keepAwake = KeepAwake(driver)
        try {
            assertFalse(keepAwake.enable())
            assertFalse(keepAwake.isActive)
        } finally {
            keepAwake.close()
        }
    }

    @Test
    fun `空驱动表示本机不支持`() {
        val keepAwake = KeepAwake(KeepAwake.NoopDriver)
        try {
            assertFalse("空驱动应报告不支持", keepAwake.isSupported)
            assertFalse(keepAwake.enable())
            assertFalse(keepAwake.isActive)
        } finally {
            keepAwake.close()
        }
    }

    @Test
    fun `可用驱动报告为支持`() {
        val keepAwake = KeepAwake(RecordingDriver())
        try {
            assertTrue(keepAwake.isSupported)
        } finally {
            keepAwake.close()
        }
    }

    @Test
    fun `close 会释放常亮`() {
        val driver = RecordingDriver()
        val keepAwake = KeepAwake(driver)
        keepAwake.enable()
        keepAwake.close()

        assertEquals(0x80000000L, driver.calls.last()) // 最后一步是 disable
        assertFalse(keepAwake.isActive)
    }

    @Test
    fun `close 之后不再接受调用`() {
        val keepAwake = KeepAwake(RecordingDriver())
        keepAwake.close()
        assertFalse(keepAwake.enable())
    }

    @Test
    fun `两次 enable 后 disable 一次即视为关闭`() {
        val driver = RecordingDriver()
        val keepAwake = KeepAwake(driver)
        try {
            keepAwake.enable()
            keepAwake.enable()
            keepAwake.disable()
            assertFalse(keepAwake.isActive)
        } finally {
            keepAwake.close()
        }
    }
}
