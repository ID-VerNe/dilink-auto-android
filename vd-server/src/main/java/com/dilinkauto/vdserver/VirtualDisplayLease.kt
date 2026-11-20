package com.dilinkauto.vdserver

/**
 * VD 租约：一个会话（进程）只允许持有**一个**虚拟显示，建屏（预建）、复用/重建判定、
 * 归还与「建屏 → 首帧」时间线都收在这里（issue #202-A / #254）。
 *
 * 为什么要有这一层：
 *  - 建屏与归还在 [PipelineServer] 里原本散落成两处裸调用（`create()` / `release()`），
 *    中间没有任何地方记录「这个 display 是什么参数、活了多久、首帧在它之后就绪没有」，
 *    现场只能靠人肉对照日志（vd-server.log 无时间戳）。
 *  - 复用判定（[VdLeasePolicy]）需要有唯一的持有者来提供「现有租约快照」。把它挂在
 *    租约上，等引擎常驻之后（issue #202-A 的后续）无需改判定就能直接命中复用分支。
 *
 * 泛型 [T] 只是为了让单测注入字符串句柄，不依赖 Android 的 [android.hardware.display.VirtualDisplay]；
 * 生产路径的 [T] 就是 `VirtualDisplay`，`releaseRaw` 负责真正 `release()`。
 *
 * 线程模型：只允许建屏线程（启动序列的主线程）与收尾线程调用；字段用 `@Volatile`
 * 发布给读日志/判定的一方。
 */
internal class VirtualDisplayLease<T : Any>(
    /** 本会话的最终建屏参数（issue #202 B 已收敛到单一来源）。 */
    private val params: VdLeaseParams,
    private val log: (String) -> Unit,
    private val releaseRaw: (T) -> Unit,
    /** 单调时钟，单测注入假时钟。 */
    private val clock: () -> Long = { System.nanoTime() },
) {
    @Volatile
    private var handle: T? = null

    @Volatile
    private var currentParams: VdLeaseParams? = null

    @Volatile
    var displayId: Int = -1
        private set

    private var acquireStartedNanos = 0L
    private var displayReadyNanos = 0L
    private var firstFrameNanos: Long? = null

    /**
     * 取得租约：参数一致且旧 VD 仍存活时直接复用，否则（含首次）调用 [create] 建屏。
     *
     * @param request 本次建屏请求的参数，缺省为本会话参数。
     * @param create 真正的建屏动作；返回 null 表示建屏失败（本租约保持空）。
     * @param displayIdOf 建屏后从平台读回 displayId（[create] 成功后才会调用）。
     * @return 建好的（或复用的）句柄；null = 建屏失败。
     */
    fun acquire(
        create: () -> T?,
        displayIdOf: () -> Int,
        request: VdLeaseParams = params,
    ): T? {
        val started = clock()
        val decision = VdLeasePolicy.decide(request, snapshot())
        if (decision.reuse) {
            log("[vd-lease] reuse display=$displayId $request（参数一致，跳过重建与任务搬迁）")
            return handle
        }
        // 有旧租约却不能复用（尺寸/DPI 变化、display 已消失）：先归还，避免同进程里
        // 留下两个 VD —— 两个 VD 会同时参与合成，且旧的那个再也无人 release。
        if (handle != null) releaseInternal("rebuild: ${decision.reason}")
        acquireStartedNanos = started
        val created = create() ?: run {
            log("[vd-lease] create failed, no lease held ($request)")
            return null
        }
        handle = created
        currentParams = request
        displayId = displayIdOf()
        displayReadyNanos = clock()
        log(
            "[vd-lease] ready display=$displayId $request after ${displayReadyAfterMs()}ms" +
                " — VD 在首帧前就绪（issue #254 预建）"
        )
        return created
    }

    /**
     * 首帧已编码输出（编译管线第一帧）。
     *
     * 在同一次会话里幂等：只有第一次调用会落日志，避免每帧刷屏。
     */
    fun noteFirstFrame() {
        if (firstFrameNanos != null) return
        val now = clock()
        firstFrameNanos = now
        val t = timeline() ?: return
        log(
            "[vd-lease] first frame ${t.firstFrameAfterMs ?: 0}ms after VD ready" +
                " (readyBeforeFirstFrame=${t.readyBeforeFirstFrame})"
        )
    }

    /** 归还租约（收尾第 18/18 步）。幂等：重复调用不会再 release 一次。 */
    fun release() = releaseInternal("teardown")

    /** 当前时间线；没有持有租约时返回 null。 */
    fun timeline(): VdLeaseTimeline? {
        if (handle == null) return null
        return VdLeaseTimeline(
            acquireStartedNanos = acquireStartedNanos,
            displayReadyNanos = displayReadyNanos,
            firstFrameNanos = firstFrameNanos,
        )
    }

    fun displayReadyAfterMs(): Long =
        if (displayReadyNanos == 0L) 0L else (displayReadyNanos - acquireStartedNanos) / 1_000_000

    private fun snapshot(): VdLeaseSnapshot? {
        if (handle == null) return null
        // 本进程自己持有的 VD，在 release 之前一律视为存活；Surface 由编码管线
        // 持有到 GL teardown，租约存续期间同样视为有效。
        return VdLeaseSnapshot(
            params = currentParams ?: params,
            displayId = displayId,
            alive = true,
            surfaceAttached = true,
        )
    }

    private fun releaseInternal(reason: String) {
        val current = handle ?: return
        val livedMs = if (displayReadyNanos == 0L) 0L else (clock() - displayReadyNanos) / 1_000_000
        handle = null
        currentParams = null
        val releasedId = displayId
        displayId = -1
        releaseRaw(current)
        log("[vd-lease] released display=$releasedId ($reason), alive=${livedMs}ms")
    }
}
