package com.dilinkauto.vdserver

/**
 * 虚拟显示（VD）租约的**纯 JVM** 判定层（issue #202-A / #254）。
 *
 * 这一层只做两件不依赖 Android 的事，便于脱机单测：
 *  1. [VdLeasePolicy.decide]：手上的旧 VD 能不能直接复用，还是要重建；
 *  2. [VdLeaseTimeline]：把「请求建屏 → VD 就绪 → 首帧」的时间线钉成可断言的契约，
 *     用来证明「VD 在首帧前就已就绪」（#254 的预建口径）。
 *
 * 为什么不把复用判定塞进 [PipelineServer]：`createVirtualDisplay()` 全仓只有启动序列
 * 一个调用点（`PipelineServer.kt:461`），当前**一会话一进程**，进程内不存在第二个租约，
 * 所以复用分支在生产路径上不会命中。判定函数放在这里，是为了让 #202-A 的契约
 * （参数一致 → 复用；尺寸/DPI 变化 / display 已消失 / Surface 已脱离 → 重建）先以
 * 可执行规格的形式固定下来，等引擎常驻（见 issue #202 的「明确不做」）落地时直接接上，
 * 而不是届时空手重写一遍判定逻辑。
 */
internal data class VdLeaseParams(val width: Int, val height: Int, val dpi: Int) {
    override fun toString(): String = "${width}x${height}@${dpi}dpi"
}

/**
 * 已持有租约的快照——复用判定的唯一输入。
 *
 * @param alive display 是否仍然存在（跨进程场景要靠 `dumpsys display` 确认；
 *   本进程自己持有的 VD 在 [VirtualDisplayLease.release] 之前一律视为存活）。
 * @param surfaceAttached 输出 Surface 是否仍然有效（Surface 已释放时复用只会得到黑帧）。
 */
internal data class VdLeaseSnapshot(
    val params: VdLeaseParams,
    val displayId: Int,
    val alive: Boolean,
    val surfaceAttached: Boolean,
)

/** 不能复用时的重建原因，直接进日志，现场不必猜。 */
internal enum class VdLeaseRebuildReason {
    /** 手上没有租约（每个新会话的常态）。 */
    NO_LEASE,

    /** 旧 display 已经不存在（进程重启 / 框架释放）。 */
    DISPLAY_GONE,

    /** 旧 display 的输出 Surface 已脱离（编码器重建等）。 */
    SURFACE_DETACHED,

    /** 视口尺寸变了（旋转 / 分屏）。 */
    SIZE_CHANGED,

    /** DPI 变了（用户设置 / 自动标定）。 */
    DPI_CHANGED,
}

internal data class VdLeaseDecision(
    val reuse: Boolean,
    val reason: VdLeaseRebuildReason?,
) {
    companion object {
        val REUSE = VdLeaseDecision(reuse = true, reason = null)

        fun rebuild(reason: VdLeaseRebuildReason) = VdLeaseDecision(reuse = false, reason = reason)
    }
}

internal object VdLeasePolicy {

    /**
     * 复用还是重建。
     *
     * 顺序是刻意的：先判「还有没有 display / Surface 可复用」，再比参数 —— 一个已经
     * 消失的 display 即使参数一致也不能复用，否则调用方会把黑帧当成成功。
     */
    fun decide(request: VdLeaseParams, existing: VdLeaseSnapshot?): VdLeaseDecision {
        if (existing == null) return VdLeaseDecision.rebuild(VdLeaseRebuildReason.NO_LEASE)
        if (!existing.alive) return VdLeaseDecision.rebuild(VdLeaseRebuildReason.DISPLAY_GONE)
        if (!existing.surfaceAttached) {
            return VdLeaseDecision.rebuild(VdLeaseRebuildReason.SURFACE_DETACHED)
        }
        if (existing.params.width != request.width || existing.params.height != request.height) {
            return VdLeaseDecision.rebuild(VdLeaseRebuildReason.SIZE_CHANGED)
        }
        if (existing.params.dpi != request.dpi) {
            return VdLeaseDecision.rebuild(VdLeaseRebuildReason.DPI_CHANGED)
        }
        return VdLeaseDecision.REUSE
    }
}

/**
 * 「请求建屏 → VD 就绪 → 首帧」的时间线（单调时钟纳秒）。
 *
 * issue #254 的验收口径是「新 VD 就绪前画面不能黑」：只要 [displayReadyNanos] 不晚于
 * [firstFrameNanos]，就说明编码管线是在 VD 建好之后才开始出帧的（预建成立）；
 * 反过来则说明首帧先于建屏，属于调用顺序回归。
 */
internal data class VdLeaseTimeline(
    val acquireStartedNanos: Long,
    val displayReadyNanos: Long,
    val firstFrameNanos: Long?,
) {
    /** 请求建屏到 VD 就绪的耗时。 */
    val displayReadyAfterMs: Long
        get() = (displayReadyNanos - acquireStartedNanos) / 1_000_000

    /** VD 就绪到首帧的耗时；还没出帧时为 null。 */
    val firstFrameAfterMs: Long?
        get() = firstFrameNanos?.let { (it - displayReadyNanos) / 1_000_000 }

    /** 首帧（或尚无首帧）时 VD 是否已就绪 = 预建成立的判定。 */
    val readyBeforeFirstFrame: Boolean
        get() = firstFrameNanos == null || displayReadyNanos <= firstFrameNanos
}
