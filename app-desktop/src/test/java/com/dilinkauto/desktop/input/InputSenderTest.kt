package com.dilinkauto.desktop.input

import com.dilinkauto.protocol.Channel
import com.dilinkauto.protocol.ControlMsg
import com.dilinkauto.protocol.InputMsg
import com.dilinkauto.protocol.LaunchAppMessage
import com.dilinkauto.protocol.TouchEvent
import com.dilinkauto.protocol.TouchMoveBatch
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [InputSender] 单测：用记录型 transport 断言协议帧的通道/类型/载荷。
 *
 * 不依赖网络——[InputSender] 只做"输入语义 → 协议帧"，连接生命周期由会话层负责，
 * 因此这里可以直接校验字节，无需起 FakePhone。
 */
class InputSenderTest {

    private class Recorder {
        val frames = mutableListOf<Triple<Byte, Byte, ByteArray>>()
        fun transport(): (Byte, Byte, ByteArray) -> Unit = { ch, type, payload ->
            frames.add(Triple(ch, type, payload))
        }
        val last get() = frames.last()
    }

    private fun senderWith(recorder: Recorder) = InputSender(clock = { 123456789L }).apply {
        transport = recorder.transport()
    }

    @Test
    fun down_sendsTouchDownOnInputChannel() {
        val rec = Recorder()
        val sender = senderWith(rec)

        assertTrue(sender.down(0.25f, 0.75f))

        val (channel, type, payload) = rec.last
        assertEquals(Channel.INPUT, channel)
        assertEquals(InputMsg.TOUCH_DOWN, type)
        val e = TouchEvent.decode(payload)
        assertEquals(0.25f, e.x, 0.0001f)
        assertEquals(0.75f, e.y, 0.0001f)
        assertEquals(InputMsg.TOUCH_DOWN, e.action)
        assertEquals(0, e.pointerId)
        assertEquals(123456789L, e.timestamp)
    }

    @Test
    fun moveWithoutDown_isIgnored() {
        val rec = Recorder()
        val sender = senderWith(rec)

        assertFalse(sender.move(0.5f, 0.5f))
        assertTrue(rec.frames.isEmpty())
    }

    @Test
    fun moveAfterDown_usesMoveBatch() {
        val rec = Recorder()
        val sender = senderWith(rec)

        sender.down(0.1f, 0.1f)
        assertTrue(sender.move(0.4f, 0.6f))

        val (channel, type, payload) = rec.last
        assertEquals(Channel.INPUT, channel)
        assertEquals(InputMsg.TOUCH_MOVE_BATCH, type)
        val batch = TouchMoveBatch.decode(payload)
        assertEquals(1, batch.pointers.size)
        assertEquals(0.4f, batch.pointers[0].x, 0.0001f)
        assertEquals(0.6f, batch.pointers[0].y, 0.0001f)
        assertEquals(InputMsg.TOUCH_MOVE, batch.pointers[0].action)
    }

    @Test
    fun upAfterDown_sendsTouchUpThenIgnoresRepeats() {
        val rec = Recorder()
        val sender = senderWith(rec)

        sender.down(0.2f, 0.2f)
        assertTrue(sender.up(0.3f, 0.3f))
        assertEquals(InputMsg.TOUCH_UP, rec.last.second)

        // 再次 UP / MOVE 都应被忽略（没有按下的指针）
        assertFalse(sender.up(0.3f, 0.3f))
        assertFalse(sender.move(0.3f, 0.3f))
        assertEquals(2, rec.frames.size)
    }

    @Test
    fun reset_clearsPressedState() {
        val rec = Recorder()
        val sender = senderWith(rec)

        sender.down(0.2f, 0.2f)
        sender.reset()
        assertFalse(sender.move(0.3f, 0.3f))
    }

    @Test
    fun coordinates_areClampedToUnitRange() {
        val rec = Recorder()
        val sender = senderWith(rec)

        sender.down(-5f, 9f)
        val e = TouchEvent.decode(rec.last.third)
        assertEquals(0f, e.x, 0.0001f)
        assertEquals(1f, e.y, 0.0001f)
    }

    @Test
    fun navCommands_goOnControlChannelOfInputPort() {
        val rec = Recorder()
        val sender = senderWith(rec)

        assertTrue(sender.goHome())
        assertEquals(Channel.CONTROL to ControlMsg.GO_HOME, rec.last.first to rec.last.second)

        assertTrue(sender.goBack())
        assertEquals(Channel.CONTROL to ControlMsg.GO_BACK, rec.last.first to rec.last.second)

        assertTrue(sender.goRecent())
        assertEquals(Channel.CONTROL to ControlMsg.GO_RECENT, rec.last.first to rec.last.second)
    }

    @Test
    fun launchApp_carriesPackageName() {
        val rec = Recorder()
        val sender = senderWith(rec)

        assertTrue(sender.launchApp("com.autonavi.minimap"))

        val (channel, type, payload) = rec.last
        assertEquals(Channel.CONTROL, channel)
        assertEquals(ControlMsg.LAUNCH_APP, type)
        assertEquals("com.autonavi.minimap", LaunchAppMessage.decode(payload).packageName)
    }

    @Test
    fun setDisplayPower_sendsSingleBytePayloadOnControlChannel() {
        val rec = Recorder()
        val sender = senderWith(rec)

        assertTrue(sender.setDisplayPower(true))
        assertEquals(Channel.CONTROL to ControlMsg.SET_DISPLAY_POWER, rec.last.first to rec.last.second)
        assertArrayEquals(byteArrayOf(1), rec.last.third)

        assertTrue(sender.setDisplayPower(false))
        assertArrayEquals(byteArrayOf(0), rec.last.third)
    }

    @Test
    fun withoutTransport_nothingIsSentAndCallsReportFalse() {
        val sender = InputSender()
        assertFalse(sender.isConnected)
        assertFalse(sender.down(0.5f, 0.5f))
        assertFalse(sender.move(0.5f, 0.5f))
        assertFalse(sender.up(0.5f, 0.5f))
        assertFalse(sender.goHome())
        assertFalse(sender.launchApp("com.example"))
        assertFalse(sender.setDisplayPower(false))

        // 断开后 MOVE/UP 不应产生副作用：重新接入通道时 pressed 仍为 false
        val rec = Recorder()
        sender.transport = rec.transport()
        assertTrue(sender.isConnected)
        assertFalse(sender.move(0.5f, 0.5f))
        assertTrue(rec.frames.isEmpty())
        assertTrue(sender.down(0.5f, 0.5f))
    }
}
