package com.dilinkauto.server.decoder

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * issue #267：首帧 / SurfaceView 归因插桩的存在性守卫。
 *
 * 这些 `[Trace]` 行是下一次上车判定的唯一依据：没有它们就无法区分
 * 「主窗口没画出来」与「HOME 页本来就不需要 surface」。删掉任何一条，
 * 本单会退回「只能看 waiting for surface 秒数」的瞎猜状态。
 */
class FirstFrameTraceTest {

    private fun src(rel: String) = File(rel).readText()

    @Test
    fun `MainActivity 必须保留首帧时间线与主线程 watchdog`() {
        val s = src("src/main/java/com/dilinkauto/server/MainActivity.kt")
        assertTrue("缺少 decor 首次 draw 打点", s.contains("decor first draw"))
        assertTrue("缺少 Choreographer 首帧打点", s.contains("choreographer first frame"))
        assertTrue("缺少主线程阻塞打点", s.contains("[MainThread] blocked"))
        assertTrue("缺少 onCreate 时间线", s.contains("[FirstFrame]"))
        assertTrue("watchdog 必须只在 DEBUG 启用", s.contains("if (!BuildConfig.DEBUG)"))
    }

    @Test
    fun `MirrorScreen 必须在主线程即时记录 surface 回调与可见性`() {
        val s = src("src/main/java/com/dilinkauto/server/ui/screen/MirrorScreen.kt")
        assertTrue("缺少 surfaceCreated 同步打点", s.contains("[SurfaceView] surfaceCreated"))
        assertTrue("缺少 surfaceDestroyed 同步打点", s.contains("[SurfaceView] surfaceDestroyed"))
        assertTrue("缺少 attach 打点", s.contains("[SurfaceView] attached"))
        assertTrue("缺少 visibility 打点", s.contains("[SurfaceView] visibility="))
    }

    @Test
    fun `CarShell 必须记录 HOME 与 APP 的切换时刻`() {
        val s = src("src/main/java/com/dilinkauto/server/ui/screen/CarShell.kt")
        assertTrue("缺少切页打点", s.contains("[Screen] now="))
        assertTrue("切页打点必须挂在 currentScreen 上", s.contains("LaunchedEffect(currentScreen)"))
    }
}
