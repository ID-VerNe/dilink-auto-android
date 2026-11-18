package com.dilinkauto.server.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 首连等 Surface 28.7s 的归因插桩与记账分账守卫（issue #229）。
 *
 * 现场（`.tmp/logs-20261118/client.log` 首连段 L64-228）：壳层冷启动停在 HOME 磁贴页，
 * SurfaceView 保持 INVISIBLE、surfaceCreated 只能等用户点应用磁贴（`sendCommandToVd 0x10`）
 * 触发；28.6s 里 9 个 IDR 在停播期被新 IDR 覆盖丢弃，全部混进 in-stream 的 `keys_drop`。
 * 重连 0.2s 只是用户点得快（壳层常已在 APP 页）——不是代码路径差异。
 *
 * 本 PR 修的是可诊断性（纯日志 + 记账拆分，不改任何行为）：
 *  - A. 归因插桩：镜像页可见性上报进 service，三处日志带 `mirrorVisible=`；
 *  - C. 记账分离：停播期作废的缓存 IDR 与 in-stream 丢帧分账（行为级红测见
 *    [VideoDecoderIntakeAccountingTest] 的 `停播期覆盖丢弃的 IDR 必须与 in-stream 丢帧分账`，
 *    base 上即红）。
 *
 * 这里用结构性守卫（读源码断言调用形态）钉住插桩点不被误删，范式同
 * [CachedKeyFrameGenerationTest]。
 */
class FirstConnectSurfaceAttributionTest {

    // ─── A. 归因插桩 ───

    @Test
    fun `MirrorScreen 的 update 块必须上报壳层可见性`() {
        val src = sourceOf(MIRROR_PATH)
        val update = src.indexOf("update = { container ->")
        assertTrue("找不到 AndroidView update 块", update >= 0)
        val report = src.indexOf("service.onMirrorScreenVisibilityChanged(visible)", update)
        val modifier = src.indexOf("modifier = Modifier", update)
        assertTrue(
            "update 块里必须在切可见性的同时上报壳层页面状态，否则归因链断在 UI 层（issue #229）",
            report in update until modifier,
        )
    }

    @Test
    fun `CarConnectionService 必须转发可见性上报给会话编排层`() {
        val src = sourceOf(SERVICE_PATH)
        assertTrue(
            "service facade 少了转发，MirrorScreen 的上报到不了 coordinator",
            src.contains(
                "fun onMirrorScreenVisibilityChanged(visible: Boolean) = sessionCoordinator.onMirrorScreenVisibilityChanged(visible)"
            ),
        )
    }

    @Test
    fun `coordinator 必须有可见性入口且只在字段声明处复位为 false`() {
        val src = sourceOf(COORDINATOR_PATH)
        assertTrue(
            "缺少可见性入口方法",
            src.contains("fun onMirrorScreenVisibilityChanged(visible: Boolean)"),
        )
        assertEquals(
            "mirrorScreenVisible = false 只允许出现在字段初始值 —— 重连时壳层常仍在 APP 页" +
                "（重连 0.2s 的对照组），任何复位点都会把真 true 改成假 false（issue #229）",
            1,
            Regex("""mirrorScreenVisible = false""").findAll(src).count(),
        )
    }

    @Test
    fun `waiting for surface 日志必须带 mirrorVisible 归因`() {
        val src = sourceOf(COORDINATOR_PATH)
        val wait = src.indexOf("[Video] waiting for surface")
        assertTrue("找不到等待日志", wait >= 0)
        val attribution = src.indexOf("mirrorVisible=\$mirrorScreenVisible", wait)
        assertTrue(
            "等待日志必须带上壳层页面状态：首连 28.7s 的真相是用户停在 HOME 没点磁贴，" +
                "不带它回签者无法从日志归因（issue #229），实际距离=${attribution - wait}",
            attribution in wait until (wait + 600),
        )
    }

    @Test
    fun `surface 回调日志三处都必须带 mirrorVisible 便于配对用户点击`() {
        val src = sourceOf(COORDINATOR_PATH)
        assertEquals(
            "surface created / surface destroyed 日志必须与 waiting 日志一样带 mirrorVisible" +
                "（回签者靠它确认 surfaceCreated 是用户点击触发的，issue #229）",
            3,
            Regex("mirrorVisible=\\\$mirrorScreenVisible").findAll(src).count(),
        )
    }

    // ─── C. 记账分离 ───

    @Test
    fun `缓存 IDR 的四个作废点必须全部走停播期分项`() {
        val src = sourceOf(DECODER_PATH)
        // 四个作废点：start() 跨代 Discarded、新 CONFIG 清缓存、停播期覆盖替换、stop() 清缓存。
        assertEquals(
            "必须恰好四个作废点走 onKeyDroppedBeforeStart（多=误改 in-stream 丢帧，少=分账漏点）",
            4,
            Regex("""intake\.onKeyDroppedBeforeStart\(\)""").findAll(src).count(),
        )
        assertEquals(
            "两个 clear() 作废点（新 CONFIG / stop）必须保持「clear 即分项作废」形态",
            2,
            Regex("""if \(cachedKeyFrameStore\.clear\(\)\) intake\.onKeyDroppedBeforeStart\(\)""").findAll(src).count(),
        )
        assertTrue(
            "停播期覆盖替换必须走停播期分项（首连 28.7s 里 9 个 IDR 全走这里）",
            src.contains("cachedKeyFrameStore.replace(codecGeneration, data) != null"),
        )
    }

    @Test
    fun `Fed 汇总行必须带 keys_prestart_drop 分项`() {
        val src = sourceOf(DECODER_PATH)
        assertTrue(
            "汇总行少了停播期分项，现场无法回答「IDR 丢在 start 之前还是码流里」（issue #229）",
            src.contains("keys_prestart_drop=\${snap.keyDroppedBeforeStart}"),
        )
    }

    /** Gradle 测试工作目录不保证是仓库根，向上找到含源文件的目录（同 CachedKeyFrameGenerationTest）。 */
    private fun sourceOf(relativePath: String): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relativePath)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        error("找不到 $relativePath —— 测试工作目录：${File(".").absolutePath}")
    }

    private companion object {
        const val MIRROR_PATH =
            "app-server/src/main/java/com/dilinkauto/server/ui/screen/MirrorScreen.kt"
        const val SERVICE_PATH =
            "app-server/src/main/java/com/dilinkauto/server/service/CarConnectionService.kt"
        const val COORDINATOR_PATH =
            "app-server/src/main/java/com/dilinkauto/server/service/CarSessionCoordinator.kt"
        const val DECODER_PATH =
            "app-server/src/main/java/com/dilinkauto/server/decoder/VideoDecoder.kt"
    }
}
