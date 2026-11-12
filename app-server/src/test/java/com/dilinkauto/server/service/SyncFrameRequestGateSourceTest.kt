package com.dilinkauto.server.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 源码级钉子：两端 IDR 请求闸门必须是同一份实现、且不存在死区（issue #75）。
 *
 * 缺陷形态就是「两端各写一个模块内私有阈值」——车机 500ms、手机 1000ms，于是
 * 「距上次授予 500–999ms」的补帧请求被车机放行、又被手机静默吞掉，车机只能干等
 * 一个自然 GOP（3s）。本文件把「车机放行 ⇒ 手机不吞」做成可执行断言，并在**未修复的
 * base** 上以与症状一致的签名变红（`dead zone replay at the observed 747ms gap`）。
 *
 * 刻意只读源码文本、不引用被修复后才存在的符号，因此本文件在 base 上也能编译并按
 * 断言变红——这正是 `docs/PR_GUIDE.md`「基线验证」要的「不打补丁就红」。
 */
class SyncFrameRequestGateSourceTest {

    private val carPath = "app-server/src/main/java/com/dilinkauto/server/service/CarConnectionService.kt"
    private val phonePath = "vd-server/src/main/java/com/dilinkauto/vdserver/GlPipeline.kt"
    private val corePath = "protocol-core/src/main/java/com/dilinkauto/protocol/VideoConfig.kt"

    private val car = sourceOf(carPath)
    private val phone = sourceOf(phonePath)
    private val core = sourceOf(corePath)

    /** 不允许再出现的模块内私有阈值常量（车机 ms / 手机 nanos 两种旧的书写方式）。 */
    private val moduleLocalGateConst =
        Regex("""const val\s+\w*(SYNC|IDR)\w*(INTERVAL|NANOS)\w*\s*=""")

    @Test
    fun `car applies the shared gate helper`() {
        assertTrue(
            "车机端必须调用 VideoConfig.syncFrameRequestAllowed（单一来源），当前源码未匹配到",
            car.contains("VideoConfig.syncFrameRequestAllowed"),
        )
    }

    @Test
    fun `phone applies the shared gate helper`() {
        assertTrue(
            "手机端必须调用 VideoConfig.syncFrameRequestAllowed（单一来源），当前源码未匹配到",
            phone.contains("VideoConfig.syncFrameRequestAllowed"),
        )
    }

    @Test
    fun `neither end declares a module local interval constant`() {
        for ((name, src) in listOf("CarConnectionService.kt" to car, "GlPipeline.kt" to phone)) {
            val hit = moduleLocalGateConst.find(src)
            assertFalse(
                "$name 又长出了模块内私有阈值常量 ${hit?.value}（issue #75 的缺陷形态：两端阈值漂移）",
                hit != null,
            )
        }
    }

    /**
     * 死区重放：解析两端**当前源码里实际生效的** IDR 请求间隔，用现场真实数字
     * （第 1 次请求已被服务，747ms 后第 2 次缺口到来）驱动两个闸门，断言绝不出现
     * 「车机放行、手机吞掉」。
     *
     * base 上：车机 500ms 放行、手机 1000ms 吞掉 → 747ms 与 999ms 两条断言失败。
     * 修复后：两端都取自 VideoConfig 的同一个常量（1000ms）→ 车机也拒 → 无死区。
     */
    @Test
    fun `dead zone replay at the observed 747ms gap is empty`() {
        val carMs = effectiveIntervalMs("CarConnectionService.kt", car, carLiteral, scale = 1L)
        val phoneMs = effectiveIntervalMs("GlPipeline.kt", phone, phoneLiteral, scale = 1_000_000L)

        // 现场：距上次授予 500/747/999ms 的补帧请求，两端必须同时拒绝或同时放行。
        for (gapMs in listOf(500L, 747L, 999L)) {
            val carAllows = carMs != null && gapMs >= carMs
            val phoneAllows = phoneMs != null && gapMs >= phoneMs
            assertFalse(
                "gap=${gapMs}ms 出现死区：车机闸门=${carMs}ms 放行=$carAllows，手机闸门=${phoneMs}ms 授予=$phoneAllows" +
                    " —— 请求注定被吞，车机要干等一个自然 GOP",
                carAllows && !phoneAllows,
            )
        }
        // 顺带钉住「两侧解析出的就是同一个数」，防止靠两个恰好相等的私有值蒙混过关。
        assertEquals("两端实际生效的 IDR 请求间隔必须相同", carMs, phoneMs)
    }

    private val carLiteral = Regex("""const val\s+\w*SYNC\w*INTERVAL\w*\s*=\s*([\d_]+)L""")

    /** 手机侧常量以纳秒书写，除以该系数换算成毫秒。 */
    private val phoneLiteral = Regex("""const val\s+\w*SYNC\w*NANOS\w*\s*=\s*([\d_]+)L""")

    private val sharedConstant =
        Regex("""const val\s+SYNC_FRAME_REQUEST_MIN_INTERVAL_MS\s*=\s*([\d_]+)L""")

    /**
     * 该端实际生效的 IDR 请求间隔（ms）：
     * - 若源码引用了共享常量，则取 :protocol-core 里该常量的字面量（=「两端引用同一常量」
     *   的可执行证明，而不是在测试里另抄一个 1000）；
     * - 否则回落到该端自己的私有字面量（base 形态）。
     */
    private fun effectiveIntervalMs(fileName: String, src: String, literal: Regex, scale: Long): Long? {
        if (src.contains("VideoConfig.SYNC_FRAME_REQUEST_MIN_INTERVAL_MS")) {
            val m = sharedConstant.find(core)
                ?: error("$fileName 引用了共享常量，但 :protocol-core 的 VideoConfig.kt 里找不到它的字面量")
            return m.groupValues[1].replace("_", "").toLong()
        }
        val m = literal.find(src) ?: return null
        return m.groupValues[1].replace("_", "").toLong() / scale
    }

    /** Gradle 测试工作目录不保证是仓库根，向上找到含源文件的目录。 */
    private fun sourceOf(relativePath: String): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relativePath)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        error("找不到 $relativePath —— 测试工作目录：${File(".").absolutePath}")
    }
}
