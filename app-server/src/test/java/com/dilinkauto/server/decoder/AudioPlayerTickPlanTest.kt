package com.dilinkauto.server.decoder

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 钉住音频主循环「本拍该处理几帧 / 下一拍基准推进到哪」的判定（issue #179）。
 *
 * 背景：原来的 `calculateNextTick` 一旦发现 `now - nextTick > frameNanos` 就把基准
 * 直接重置成 `now`，落后的整拍被永久丢掉。主循环每拍固定约 6 ms 开销，于是每秒少约
 * 10 拍：消费速率只有 36.5 帧/秒，而发送侧是 46.875 帧/秒，jitter 队列长期 15~16/16。
 *
 * 判定抽成纯函数 [audioTickPlan] 后，用这张表把语义钉死：
 * 1. 准时 / 负 lag → 本拍 1 帧，基准前进 1 帧；
 * 2. 落后不足上限 → 本拍补上落后的帧数（`floor(lag / frameNanos) + 1`）；
 * 3. 落后超过 [AUDIO_MAX_FRAMES_PER_TICK]（线程被长停）→ 重置为 1 帧、基准取 `now + frameNanos`；
 * 4. `frameNanos <= 0` → 兜底 1 帧（防御除零）。
 */
class AudioPlayerTickPlanTest {

    private val frameNanos = 21_333_333L
    private val base = 1_000_000_000L

    private data class TickCase(
        val name: String,
        val currentNextTick: Long,
        val now: Long,
        val frameNanos: Long,
        val expectedFrames: Int,
        val expectedNextTick: Long,
    )

    private val cases = listOf(
        // ── 1. 准时 / 负 lag ──
        TickCase("准时（now == nextTick）→ 1 帧", base, base, frameNanos, 1, base + frameNanos),
        TickCase("now 略早于 nextTick（负 lag）→ 1 帧", base, base - 5_000_000L, frameNanos, 1, base + frameNanos),

        // ── 2. 落后但未到上限 → 补齐落后帧 ──
        // 25_600_000 / 21_333_333 = 1 → 2 帧
        TickCase("落后 1.2 拍 → 2 帧", base, base + 25_600_000L, frameNanos, 2, base + 2 * frameNanos),
        TickCase("落后 5 拍 → 6 帧", base, base + 5 * frameNanos, frameNanos, 6, base + 6 * frameNanos),

        // ── 3. 落后超过上限 → 重置（20 × 21.333ms ≈ 426ms）──
        TickCase(
            "落后 20 拍（约 426ms）→ 重置为 1 帧",
            base, base + 20 * frameNanos, frameNanos, 1, base + 20 * frameNanos + frameNanos,
        ),

        // ── 4. 防御除零 ──
        TickCase("frameNanos = 0 → 兜底 1 帧、基准取 now", base, base + 999L, 0L, 1, base + 999L),
    )

    @Test
    fun `tick plan decision table`() {
        for (c in cases) {
            val plan = audioTickPlan(c.currentNextTick, c.now, c.frameNanos)
            assertEquals("case: ${c.name} framesDue", c.expectedFrames, plan.framesDue)
            assertEquals("case: ${c.name} nextTick", c.expectedNextTick, plan.nextTickNanos)
        }
    }
}
