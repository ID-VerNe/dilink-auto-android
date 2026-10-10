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
        // 线程安全列表：quickSwitch / fillLaunchedApp 在后台线程里追加，
        // 测试线程同时读取（轮询断言），普通 ArrayList 会偶发 CME。
        val execs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val execOuts = java.util.concurrent.CopyOnWriteArrayList<String>()
        val powers = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        var displayId = 7
        var launchResolve: String? = null
        var appInfoResolve: String? = null
        var recentsDump: String? = null
        var activitiesDump: String? = null

        val router = CarCommandRouter(
            exec = { execs += it },
            execOut = { cmd ->
                execOuts += cmd
                when {
                    cmd.startsWith("dumpsys activity recents") -> recentsDump
                    cmd.startsWith("dumpsys activity activities") -> activitiesDump
                    cmd.startsWith("wm size") -> "Physical size: 2340x1316"
                    cmd.contains("resolve-activity") && cmd.contains("android.intent.action.MAIN") -> launchResolve
                    cmd.contains("resolve-activity") && cmd.contains("APPLICATION_DETAILS_SETTINGS") -> appInfoResolve
                    cmd.startsWith("am start") -> "Starting: Intent { ... }"
                    else -> null // 其余 dumpsys 等
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
    fun `go recent quick switches to the most recent other task via am start task`() {
        // 「最近」的语义（2026-10-10 实机拍板）：快速切换到上一个任务。
        // 不再注入 APP_SWITCH（keyevent 187）—— 它硬编码默认显示器，实测只会
        // toggle 物理屏的 recents，对 VD 无效（见 CarCommandRouter.quickSwitch 注释）。
        // 实机样本：twitter(2048) 是当前 VD 顶层、控制端(2040) 必须跳过、
        // 小红书(2047) 是切换目标。
        val r = Rig()
        r.recentsDump = """
  * RecentTaskInfo #0: 
    id=2048 userId=0 hasTask=true lastActiveTime=849748141
    baseIntent=Intent { act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] flg=0x10200000 cmp=com.twitter.android/com.x.android.main.MainActivity }
  * RecentTaskInfo #1: 
    id=2040 userId=0 hasTask=true lastActiveTime=849625054
    baseIntent=Intent { act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] flg=0x10200000 cmp=com.dilinkauto.client/.MainActivity }
  * RecentTaskInfo #2: 
    id=2047 userId=0 hasTask=true lastActiveTime=848968398
    baseIntent=Intent { act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] flg=0x10200000 cmp=com.xingin.xhs/.index.v2.IndexActivityV2 }
"""
        r.activitiesDump = """
Display #7 (activities from top to bottom):
  * Task{a11 #2048 type=standard A=10216:com.twitter.android}
    * ActivityRecord{751a1f5 u0 com.twitter.android/com.x.android.main.MainActivity t2048}
"""
        r.router.dispatch(control(ControlMsg.GO_RECENT, ByteArray(0)))

        // quickSwitch 异步执行（两个 dumpsys 各几百毫秒，不能阻塞 TouchReader）——
        // 有界等待目标命令落地。
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline &&
            r.execOuts.none { it.startsWith("am start --display 7 --task") }
        ) Thread.sleep(10)

        assertTrue(
            r.execOuts.contains(
                "am start --display 7 --task 2047 -n 'com.xingin.xhs/.index.v2.IndexActivityV2'"
            )
        )
        // 旧行为的回归钉子：不得再往（物理屏硬编码的）APP_SWITCH 注入任何 keyevent。
        assertTrue("GO_RECENT 不得再注入 keyevent 187", r.execs.isEmpty())
    }

    @Test
    fun `go recent issues nothing when every candidate is filtered out`() {
        // 只剩自家控制端与 launcher → 无可切换目标 → 什么都不发（只记日志）。
        val r = Rig()
        r.recentsDump = """
  * RecentTaskInfo #0: 
    id=2040 userId=0 hasTask=true lastActiveTime=849625054
    baseIntent=Intent { cmp=com.dilinkauto.client/.MainActivity }
  * RecentTaskInfo #1: 
    id=3000 userId=0 hasTask=true lastActiveTime=849625000
    baseIntent=Intent { cmp=com.google.android.apps.nexuslauncher/.NexusLauncherActivity }
"""
        r.activitiesDump = "Display #7 (activities from top to bottom):\n"
        r.router.dispatch(control(ControlMsg.GO_RECENT, ByteArray(0)))

        // 等两个 dumpsys 都跑过（= 选择逻辑已走完），再给潜在命令一个落地窗口。
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline &&
            r.execOuts.count { it.startsWith("dumpsys activity") } < 2
        ) Thread.sleep(10)
        Thread.sleep(100)

        assertTrue("无可切换目标时不得发 am start", r.execOuts.none { it.startsWith("am start") })
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
    fun `launch app resizes the launched task to fill the virtual display`() {
        // 2026-10-10 实机：VD 上 app 默认落成 freeform 小窗（853,51-1487,1220），
        // 启动参数 --windowingMode 1 被框架忽略；铺满 = 启动后
        // `am task resize <taskId> 0 0 <W> <H>`（实测铺满 0,0-2340,1316）。
        val r = Rig()
        r.launchResolve = "com.foo.bar/.MainActivity"
        r.activitiesDump = """
Display #7 (activities from top to bottom):
  * Task{a11 #77 type=standard A=1000:com.foo.bar}
    * ActivityRecord{751a1f5 u0 com.foo.bar/.MainActivity t77}
"""
        r.router.dispatch(control(ControlMsg.LAUNCH_APP, "com.foo.bar".toByteArray()))

        // 铺满是异步的（后台线程）——有界等待目标命令落地。
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline &&
            r.execOuts.none { it.startsWith("am task resize") }
        ) Thread.sleep(10)

        assertTrue(r.execOuts.contains("am task resize 77 0 0 2340 1316"))
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
