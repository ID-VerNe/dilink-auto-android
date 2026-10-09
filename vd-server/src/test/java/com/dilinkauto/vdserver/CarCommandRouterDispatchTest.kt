package com.dilinkauto.vdserver

import com.dilinkauto.protocol.Channel
import com.dilinkauto.protocol.ControlMsg
import com.dilinkauto.protocol.FrameCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CarCommandRouter.dispatch —— 车机可控命令的路由与 shell 引用（S-02）。
 *
 * 全 lambda 注入，无需 Android。这是"对端可控字符串进 shell-UID 的 am/pm/input"的调用点，
 * 此前零覆盖：shell 引用、resolve 输出校验、SET_DISPLAY_POWER 载荷语义都在这里。
 */
class CarCommandRouterDispatchTest {

    private fun control(type: Byte, payload: ByteArray) = FrameCodec.Frame(Channel.CONTROL, type, payload)

    private class Rig {
        val execs = mutableListOf<String>()
        val execOuts = mutableListOf<String>()
        val powers = mutableListOf<Boolean>()
        var displayId = 7
        var launchResolve: String? = null
        var appInfoResolve: String? = null

        val router = CarCommandRouter(
            exec = { execs += it },
            execOut = { cmd ->
                execOuts += cmd
                when {
                    cmd.contains("resolve-activity") && cmd.contains("android.intent.action.MAIN") -> launchResolve
                    cmd.contains("resolve-activity") && cmd.contains("APPLICATION_DETAILS_SETTINGS") -> appInfoResolve
                    cmd.startsWith("am start") -> "Starting: Intent { ... }"
                    else -> null // dumpsys 等
                }
            },
            displayId = { displayId },
            onStackEmpty = { },
            setDisplayPower = { powers += it },
        )
    }

    @Test
    fun `go home emits keyevent three on the virtual display`() {
        val r = Rig()
        r.router.dispatch(control(ControlMsg.GO_HOME, ByteArray(0)))
        assertTrue(r.execs.contains("input -d 7 keyevent 3"))
    }

    @Test
    fun `go back emits keyevent four`() {
        val r = Rig()
        r.router.dispatch(control(ControlMsg.GO_BACK, ByteArray(0)))
        assertTrue(r.execs.contains("input -d 7 keyevent 4"))
    }

    @Test
    fun `go recent emits keyevent one eighty seven`() {
        val r = Rig()
        r.router.dispatch(control(ControlMsg.GO_RECENT, ByteArray(0)))
        assertTrue(r.execs.contains("input -d 7 keyevent 187"))
    }

    @Test
    fun `display id is read at dispatch time not cached`() {
        val r = Rig(); r.displayId = -1
        r.router.dispatch(control(ControlMsg.GO_HOME, ByteArray(0)))
        assertTrue(r.execs.contains("input -d -1 keyevent 3"))
    }

    @Test
    fun `app uninstall shell quotes the package`() {
        val r = Rig()
        r.router.dispatch(control(ControlMsg.APP_UNINSTALL, "com.foo.bar".toByteArray()))
        assertTrue(r.execs.contains("pm uninstall 'com.foo.bar'"))
    }

    @Test
    fun `set display power payload semantics`() {
        val one = Rig(); one.router.dispatch(control(ControlMsg.SET_DISPLAY_POWER, byteArrayOf(1)))
        assertEquals(listOf(true), one.powers)

        val zero = Rig(); zero.router.dispatch(control(ControlMsg.SET_DISPLAY_POWER, byteArrayOf(0)))
        assertEquals(listOf(false), zero.powers)

        val empty = Rig(); empty.router.dispatch(control(ControlMsg.SET_DISPLAY_POWER, ByteArray(0)))
        assertEquals(listOf(false), empty.powers)

        val two = Rig(); two.router.dispatch(control(ControlMsg.SET_DISPLAY_POWER, byteArrayOf(2)))
        assertEquals(listOf(true), two.powers)
    }

    @Test
    fun `launch app with resolvable component uses explicit intent and shell quotes both`() {
        val r = Rig()
        r.launchResolve = "com.foo.bar/.MainActivity"
        r.router.dispatch(control(ControlMsg.LAUNCH_APP, "com.foo.bar".toByteArray()))
        // resolve 调用把 pkg 引起来
        assertTrue(r.execOuts.any { it.contains("android.intent.category.LAUNCHER 'com.foo.bar'") })
        // am start -n 用组件名并整体引用
        assertTrue(r.execOuts.any { it == "am start --display 7 -n 'com.foo.bar/.MainActivity'" })
    }

    @Test
    fun `launch app falls back to implicit intent when resolve emits non component text`() {
        val r = Rig()
        r.launchResolve = "No activity found"
        r.router.dispatch(control(ControlMsg.LAUNCH_APP, "com.foo.bar".toByteArray()))
        assertTrue(r.execOuts.any {
            it.startsWith("am start --display 7 -a android.intent.action.MAIN") &&
                it.contains("android.intent.category.LAUNCHER 'com.foo.bar'") && !it.contains(" -n ")
        })
    }

    @Test
    fun `launch app rejects resolve output that is not a valid component`() {
        val r = Rig()
        r.launchResolve = "com.foo.bar/-weird" // 有 '/' 无空格 → 被当成组件候选，但 requireComponentName 拒绝
        r.router.dispatch(control(ControlMsg.LAUNCH_APP, "com.foo.bar".toByteArray()))
        assertTrue("非法组件名不得触发 am start", r.execOuts.none { it.startsWith("am start") })
    }

    @Test
    fun `app info uses resolved activity and quotes the data uri`() {
        val r = Rig()
        r.appInfoResolve = "com.android.settings/.Settings"
        r.router.dispatch(control(ControlMsg.APP_INFO, "com.foo.bar".toByteArray()))
        // -n 不引用（$s 来自系统 resolve），-d 引用
        assertTrue(r.execs.contains("am start --display 7 -n com.android.settings/.Settings -d 'package:com.foo.bar'"))
    }

    @Test
    fun `app info falls back to detail settings action when resolve is empty`() {
        val r = Rig()
        r.appInfoResolve = ""
        r.router.dispatch(control(ControlMsg.APP_INFO, "com.foo.bar".toByteArray()))
        assertTrue(r.execs.contains("am start --display 7 -a android.settings.APPLICATION_DETAILS_SETTINGS -d 'package:com.foo.bar'"))
    }

    @Test
    fun `a malicious launch app payload throws and nothing is issued`() {
        // 现状：LAUNCH_APP 的 decode 未在 dispatch 内保护，恶意包名直接抛出去（APP_INFO 有 try/catch）。
        // 本测试只锁定"抛异常且没有 am start"的当前行为，作为 hazard 文档。
        val r = Rig()
        var threw = false
        try {
            r.router.dispatch(control(ControlMsg.LAUNCH_APP, "com.foo;x".toByteArray()))
        } catch (e: Exception) {
            threw = true
        }
        assertTrue(threw)
        assertTrue(r.execOuts.none { it.startsWith("am start") })
        assertTrue(r.execs.isEmpty())
    }
}
