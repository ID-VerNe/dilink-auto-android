package com.dilinkauto.vdserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #239 的红基线：这些断言在**未打补丁的 base**（main @ f737d7ad）上必须先红。
 *
 * 现场症状（issue 正文）：
 * `[teardown-move] task=886 moved-to=0 displayId=0 bounds=Rect(634, 0 - 1293, 1054)` ——
 * 已经搬到物理屏（display 0），回读的 bounds 却还是旧 VD 的 1926x1054 坐标系。
 *
 * 夹具取自同设备 2026-11-19 的**实时** `dumpsys activity activities`（task 886 块逐字摘录）。
 * 块内有两份互相矛盾的配置：
 *  - `mLastReportedConfigurations.mGlobalConfig` 里 `mBounds=Rect(634, 0 - 1293, 1054)`
 *    —— 搬迁前 VD 的**上次上报**快照（与日志里的假数据逐字一致）；
 *  - `CurrentConfiguration` 里 `mBounds=Rect(0, 0 - 1368, 3192)` —— 物理屏当前配置。
 * base 上 [ActivityDumpParser.extractBounds] 取块内**首个** `mBounds=`，命中前者。
 *
 * 另有两条日志归属用例：`findTaskSection` 按 dump 中 display 段的出现顺序取第一个命中，
 * VD 残留段排在物理屏段之前时，base 会把残留段的 bounds 当成目标屏的取证结果。
 */
class Issue239RedFirstTest {

    /** task 886 在物理屏（Display #0）上的真实任务块（`taskBlock` 的输出形态，逐字摘录）。 */
    private val task886OnPhysical = """
  * Task{6f81f2e #886 type=standard A=10240:com.bilibili.app.in U=0 visible=false visibleRequested=false mode=fullscreen translucent=true sz=1}
    mLastNonFullscreenBounds=Rect(697, 39 - 1230, 1015)
    isSleeping=false
    * Hist  #0: ActivityRecord{e11cd30 u0 com.bilibili.app.in/tv.danmaku.bili.MainActivityV2 t886}
      packageName=com.bilibili.app.in processName=com.bilibili.app.in
      launchedFromUid=2000 launchedFromPackage=com.android.shell launchedFromFeature=null userId=0
      app=null
      Intent { flg=0x10000000 cmp=com.bilibili.app.in/tv.danmaku.bili.MainActivityV2 }
      rootOfTask=true task=Task{6f81f2e #886 type=standard A=10240:com.bilibili.app.in}
      taskAffinity=10240:com.bilibili.app.in
      mActivityComponent=com.bilibili.app.in/tv.danmaku.bili.MainActivityV2
      stateNotNeeded=false componentSpecified=true mActivityType=standard
      compat={560dpi} theme=0x7f13007a
      mLastReportedConfigurations:
        mGlobalConfig={1.0 460mcc2mnc [zh_CN_#Hans,zh_CN] ldltr sw451dp w451dp h721dp 234dpi xlrg long port -touch -keyb/v/h -nav/h winConfig={ mBounds=Rect(634, 0 - 1293, 1054) mAppBounds=Rect(634, 0 - 1293, 1054) mMaxBounds=Rect(0, 0 - 1926, 1054) mDisplayRotation=ROTATION_0 mWindowingMode=fullscreen mActivityType=undefined mAlwaysOnTop=undefined mRotation=ROTATION_0} as.5 s.686 fontWeightAdjustment=163}
        mOverrideConfig={0.0 ?mcc0mnc [zh_CN_#Hans,zh_CN] ldltr sw721dp w1317dp h721dp 234dpi xlrg long land ?uimode ?night -touch -keyb/v/h -nav/h winConfig={ mBounds=Rect(0, 0 - 1926, 1054) mAppBounds=Rect(0, 0 - 1926, 1054) mMaxBounds=Rect(0, 0 - 1926, 1054) mDisplayRotation=ROTATION_0 mWindowingMode=fullscreen mActivityType=standard mAlwaysOnTop=undefined mRotation=ROTATION_0} s.14 ?fontWeightAdjustment}
      mLastReportedActivityWindowInfo=ActivityWindowInfo{isEmbedded=false, taskBounds=Rect(0, 0 - 1926, 1054), taskFragmentBounds=Rect(0, 0 - 1926, 1054)}
      CurrentConfiguration={1.0 460mcc2mnc [zh_CN_#Hans,zh_CN] ldltr sw391dp w391dp h855dp 560dpi nrml long hdr widecg port finger -keyb/v/h -nav/h winConfig={ mBounds=Rect(0, 0 - 1368, 3192) mAppBounds=Rect(0, 112 - 1368, 3104) mMaxBounds=Rect(0, 0 - 1368, 3192) mDisplayRotation=ROTATION_0 mWindowingMode=fullscreen mActivityType=standard mAlwaysOnTop=undefined mRotation=ROTATION_0} as.5 s.57 fontWeightAdjustment=163}
      RequestedOverrideConfiguration={0.0 ?mcc0mnc [zh_CN_#Hans,zh_CN] ldltr ?swdp ?wdp ?hdp ?density ?lsize ?long ?ldr ?wideColorGamut ?orien ?uimode ?night ?touch ?keyb/?/? ?nav/? winConfig={ mBounds=Rect(0, 0 - 0, 0) mAppBounds=null mMaxBounds=Rect(0, 0 - 0, 0) mDisplayRotation=undefined mWindowingMode=undefined mActivityType=standard mAlwaysOnTop=undefined mRotation=undefined} ?fontWeightAdjustment}
      ResolvedOverrideConfiguration={0.0 ?mcc0mnc [zh_CN_#Hans,zh_CN] ldltr sw391dp w391dp h855dp ?density ?lsize ?long ?ldr ?wideColorGamut port ?uimode ?night ?touch ?keyb/?/? ?nav/? winConfig={ mBounds=Rect(0, 0 - 0, 0) mAppBounds=Rect(0, 112 - 1368, 3104) mMaxBounds=Rect(0, 0 - 0, 0) mDisplayRotation=undefined mWindowingMode=undefined mActivityType=standard mAlwaysOnTop=undefined mRotation=undefined} s.57 ?fontWeightAdjustment}
"""

    /** 旧 VD（Display #6）里搬迁前的 task 886 残留段：整块仍停在 1926x1054 坐标系。 */
    private val residualVdSection = """
Display #6 (activities from top to bottom):
  * Task{6f81f2e #886 type=standard A=10240:com.bilibili.app.in U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}
    mLastNonFullscreenBounds=Rect(697, 39 - 1230, 1015)
    isSleeping=false
    * Hist  #0: ActivityRecord{e11cd30 u0 com.bilibili.app.in/tv.danmaku.bili.MainActivityV2 t886}
      packageName=com.bilibili.app.in processName=com.bilibili.app.in
      stateNotNeeded=false componentSpecified=true mActivityType=standard
      compat={560dpi} theme=0x7f13007a
      mLastReportedConfigurations:
        mGlobalConfig={1.0 460mcc2mnc [zh_CN] ldltr sw451dp w451dp h721dp 234dpi nrml long port -touch -keyb/v/h -nav/h winConfig={ mBounds=Rect(634, 0 - 1293, 1054) mAppBounds=Rect(634, 0 - 1293, 1054) mMaxBounds=Rect(0, 0 - 1926, 1054) mDisplayRotation=ROTATION_0 mWindowingMode=fullscreen mActivityType=undefined mAlwaysOnTop=undefined mRotation=ROTATION_0} as.5 s.686 fontWeightAdjustment=163}
      CurrentConfiguration={1.0 460mcc2mnc [zh_CN] ldltr sw451dp w451dp h721dp 234dpi nrml long port -touch -keyb/v/h -nav/h winConfig={ mBounds=Rect(634, 0 - 1293, 1054) mAppBounds=Rect(634, 0 - 1293, 1054) mMaxBounds=Rect(0, 0 - 1926, 1054) mDisplayRotation=ROTATION_0 mWindowingMode=fullscreen mActivityType=standard mAlwaysOnTop=undefined mRotation=ROTATION_0} as.5 s.686 fontWeightAdjustment=163}
"""

    /** 目标物理屏段（Display #0）：task 886 已经在这里。 */
    private val targetDisplayWithTask = "Display #0 (activities from top to bottom):\n$task886OnPhysical"

    /** 目标物理屏段里只有 launcher，没有 task 886。 */
    private val targetDisplayWithoutTask = """
Display #0 (activities from top to bottom):
  * Task{297ee62 #1 type=home U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}
    * ActivityRecord{2140e60 u0 com.meizu.flyme.launcher/com.android.launcher3.uioverrides.QuickstepLauncher t793}
"""

    /** 搬迁前的源 dump：task 886 还在 Display #6（VD）上。 */
    private val sourceDump = """
Display #6 (activities from top to bottom):
  * Task{6f81f2e #886 type=standard A=10240:com.bilibili.app.in U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}
    * ActivityRecord{e11cd30 u0 com.bilibili.app.in/tv.danmaku.bili.MainActivityV2 t886}
"""

    /** 捕获 stdout（[PipeLog.log] 直接写 System.out），用于断言 `[teardown-move]` 取证日志。 */
    private fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val buffer = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(buffer, true))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return buffer.toString()
    }

    /** 最小 Rig：`moveAllTasksToDisplay` 只用到 dumpsys / wm size 与 API 门槛。 */
    private class Rig(dumps: List<String>, val execs: MutableList<String>) {
        private val queue = java.util.concurrent.ConcurrentLinkedQueue(dumps)
        val router = CarCommandRouter(
            exec = { execs += it },
            execOut = { cmd ->
                when {
                    cmd.startsWith("dumpsys activity activities") -> queue.poll()
                    cmd.startsWith("wm size") -> "Physical size: 1368x3192"
                    else -> null
                }
            },
            displayId = { 6 },
            onStackEmpty = { },
            setDisplayPower = { },
            requestSyncFrame = { },
            repairLayerTree = { },
            sdkInt = { 29 },
        )
    }

    @Test
    fun `extract bounds must read the current configuration instead of the last reported snapshot`() {
        // issue #239：块内首个 `mBounds=` 属于 mLastReportedConfigurations 的搬迁前快照
        // （Rect(634, 0 - 1293, 1054)，与现场日志逐字一致）；任务当前配置在 CurrentConfiguration。
        assertEquals(
            "搬迁后必须读任务当前的物理屏 bounds，而不是上次上报的旧 VD 快照",
            "Rect(0, 0 - 1368, 3192)",
            ActivityDumpParser.extractBounds(task886OnPhysical)
        )
    }

    @Test
    fun `teardown move logs the target display bounds when a residual vd section comes first`() {
        // issue #239：VD 残留段（Display #6）排在物理屏段之前，且仍带 task 886 的完整块。
        // 搬迁后的归属必须是 display 0，bounds 必须来自目标段。
        val execs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val r = Rig(listOf(sourceDump, residualVdSection + targetDisplayWithTask), execs)

        val log = captureStdout { r.router.moveAllTasksToDisplay(6, 0) }

        assertTrue("必须把 task 886 从 VD 搬回物理屏", execs.contains("am display move-stack 886 0"))
        assertTrue(
            "归属必须是目标 display（不得把残留段当结果）: $log",
            log.contains("[teardown-move] task=886 moved-to=0 displayId=0")
        )
        assertTrue(
            "bounds 必须来自目标 display 段: $log",
            log.contains("bounds=Rect(0, 0 - 1368, 3192)")
        )
    }

    @Test
    fun `teardown move logs unknown instead of falling back to a residual display`() {
        // issue #239：目标 display 段里读不到该 task 时，必须记 <unknown>，
        // 不得回落到旧 VD 残留段的 bounds（这正是现场假数据的来源之一）。
        val execs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val r = Rig(listOf(sourceDump, residualVdSection + targetDisplayWithoutTask), execs)

        val log = captureStdout { r.router.moveAllTasksToDisplay(6, 0) }

        assertTrue("归属未知时必须记 <unknown>: $log", log.contains("displayId=<unknown>"))
        assertTrue("bounds 未知时必须记 <unknown>: $log", log.contains("bounds=<unknown>"))
        assertFalse(
            "不得回落到残留段的 bounds: $log",
            log.contains("bounds=Rect(634, 0 - 1293, 1054)")
        )
    }
}
