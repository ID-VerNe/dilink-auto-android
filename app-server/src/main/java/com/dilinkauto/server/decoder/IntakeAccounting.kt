package com.dilinkauto.server.decoder

/**
 * 收帧记账的唯一入口（issue #205）。
 *
 * 背景：`keys_recv / keys_fed / keys_drop` 原来是三个各自 `++` 的散字段 —— 接收入口、
 * 喂帧线程、队列驱逐各记各的，冷启动补喂缓存 IDR、`stop()` 清队列、`!configFed` 早退
 * 这些分支干脆不记。现场 `keys_recv=123 keys_fed=118 keys_drop=0`：差 5 无处可去，
 * 既不算喂入也不算丢弃，于是「丢了几个 IDR」永远算不出来。
 *
 * 这里把它收敛成一台账：每帧只能走 [onReceived] 入账一次，再由 [onFed] / [onDropped]
 * 之一销账一次（入口直接丢的用 [onRejected] 一步完成）。不变式：
 *
 * ```
 * received == fed + dropped + pending          // 全量帧
 * keyReceived == keyFed + keyDropped + keyDroppedBeforeStart + keyPending  // 关键帧
 * ```
 *
 * 关键帧侧比全量侧多一个分项 `keyDroppedBeforeStart`（issue #229）：停播期（解码器
 * 尚未 start，典型如首连等 Surface 期间）被作废/覆盖的缓存 IDR 与码流内的丢帧语义
 * 完全不同 —— 前者是「等 Surface 时的正常新陈代谢」，后者才意味着码流丢帧。混在
 * 同一个 `keyDropped` 里，现场无法回答「首连 28.7 秒里的 9 个 IDR 丢在哪一程」。
 * 全量侧不拆：`dropped` 的不变式保持原样，拆分只发生在关键帧侧。
 *
 * `pending` 是「已接收、尚未喂入也未丢弃」的帧数（队列里 + 缓存待补喂的 IDR）。
 * 它由本类显式维护，不由队列长度推导 —— 两者刻意分开，才能真正发现「帧被清掉了
 * 但没记账」（[violationText] 会报），而不是让队列长度把漏记掩盖过去。
 *
 * 线程模型：网络线程入账、喂帧线程销账，所有读写都在 `synchronized(this)` 内。
 */
internal class IntakeAccounting {

    /** 某一时刻的四项快照（含关键帧分项），用于日志与不变式校验。 */
    data class Snapshot(
        val received: Long,
        val fed: Long,
        val dropped: Long,
        val pending: Long,
        val keyReceived: Long,
        val keyFed: Long,
        val keyDropped: Long,
        /** 停播期（start 之前）作废/覆盖的缓存 IDR 数，与 in-stream 的 [keyDropped] 分账（issue #229）。 */
        val keyDroppedBeforeStart: Long,
        val keyPending: Long,
    ) {
        /** 全量帧是否平账。 */
        val streamBalanced: Boolean get() = received == fed + dropped + pending

        /** 关键帧是否平账（停播期分项计入）。 */
        val keyBalanced: Boolean
            get() = keyReceived == keyFed + keyDropped + keyDroppedBeforeStart + keyPending

        /** 全量帧与关键帧同时平账。 */
        val balanced: Boolean get() = streamBalanced && keyBalanced
    }

    private var received = 0L
    private var fed = 0L
    private var dropped = 0L
    private var pending = 0L

    private var keyReceived = 0L
    private var keyFed = 0L
    private var keyDropped = 0L
    private var keyDroppedBeforeStart = 0L
    private var keyPending = 0L

    /** 会话边界清零：`VideoDecoder.start()` 调用，保证一个会话一套账。 */
    @Synchronized
    fun reset() {
        received = 0L
        fed = 0L
        dropped = 0L
        pending = 0L
        keyReceived = 0L
        keyFed = 0L
        keyDropped = 0L
        keyDroppedBeforeStart = 0L
        keyPending = 0L
    }

    /** 接收一帧：进入「待喂/待丢」账。 */
    @Synchronized
    fun onReceived(isKey: Boolean) {
        received++
        pending++
        if (isKey) {
            keyReceived++
            keyPending++
        }
    }

    /** 成功喂给解码器一帧。 */
    @Synchronized
    fun onFed(isKey: Boolean) {
        fed++
        pending--
        if (isKey) {
            keyFed++
            keyPending--
        }
    }

    /** 丢弃一帧（它此前已经 [onReceived] 入账，例如队列驱逐、stop 清队列、喂入失败）。 */
    @Synchronized
    fun onDropped(isKey: Boolean) {
        dropped++
        pending--
        if (isKey) {
            keyDropped++
            keyPending--
        }
    }

    /**
     * 停播期作废一个缓存 IDR（issue #229）。
     *
     * 只记关键帧侧的细分桶（`dropped` 总账同 [onDropped]），调用方是 VideoDecoder 里
     * 四个「缓存 IDR 从未进入活着的解码器」的作废点：start 跨代作废、新 CONFIG 清缓存、
     * 停播期覆盖替换、stop 清缓存。它们全部发生在 start 之前或跨代，与码流内的丢帧
     * （[onDropped]）语义不同 —— 首连等 Surface 期间的 IDR 覆盖全走这里，现场一眼可分。
     */
    @Synchronized
    fun onKeyDroppedBeforeStart() {
        dropped++
        pending--
        keyDroppedBeforeStart++
        keyPending--
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        received = received,
        fed = fed,
        dropped = dropped,
        pending = pending,
        keyReceived = keyReceived,
        keyFed = keyFed,
        keyDropped = keyDropped,
        keyDroppedBeforeStart = keyDroppedBeforeStart,
        keyPending = keyPending,
    )

    /**
     * 不变式违例描述（`null` = 平账）。
     *
     * 除了等式本身，还单独判 `pending < 0`：同一帧被既记喂入又记丢弃时，等式两边
     * 会被负数 pending「抹平」，只有这个下限检查能抓到重复销账。
     */
    @Synchronized
    fun violationText(): String? {
        val s = snapshot()
        if (s.pending >= 0L && s.keyPending >= 0L && s.balanced) return null
        return "recv=${s.received} fed=${s.fed} drop=${s.dropped} pending=${s.pending} " +
            "keys recv=${s.keyReceived} fed=${s.keyFed} drop=${s.keyDropped} " +
            "prestart_drop=${s.keyDroppedBeforeStart} pending=${s.keyPending}"
    }
}
