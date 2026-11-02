package com.dilinkauto.protocol

/**
 * 车机主屏天气显示的纯格式化 / 过期判定 / 退避逻辑（issue #41）。
 *
 * 与 [ClockFormat] / [AltitudeFormat] 同格放在 protocol-core —— 「把天气数据渲染成
 * `晴 6°C`」、「判定缓存是否过期（>60 分钟）」、「算失败退避序列（30s→2m→5m，上限 15m）」
 * 是纯函数，在 JVM 上用 JUnit 钉死，无需实机网络。
 */
object WeatherFormat {

    /** 缓存「新鲜」上限：距上次成功 ≤60 分钟视为有效（issue #41 追评一）。 */
    const val STALE_THRESHOLD_MS = 60L * 60_000L

    /** 正常刷新周期（毫秒）：15 分钟。 */
    const val REFRESH_INTERVAL_MS = 15L * 60_000L

    /** 退避序列上限（毫秒）：15 分钟，与正常刷新周期对齐，失败再多也不比正常更慢地重试。 */
    private const val MAX_BACKOFF_MS = 15L * 60_000L

    /**
     * 把天气数据渲染成 chip 文本。
     *
     * - `data == null` → `—`（从未成功 / 解析失败，issue #41 验收标准 3）。
     * - 否则 `现象 温度°C`（如 `晴 6°C`）。
     * - `isStale` 时末尾加 ` *`，让用户知道数据可能过时（断网保留旧值的可见标记）。
     *
     * 城市名 v1 不进 chip（InfoBar 空间有限；定位城市不对另开 issue）。
     */
    fun formatWeather(data: WeatherData?, isStale: Boolean): String {
        if (data == null) return "—"
        val base = "${data.condition} ${data.temperature}°C"
        return if (isStale) "$base *" else base
    }

    /**
     * 缓存是否过期（距上次成功 > [STALE_THRESHOLD_MS]）。
     *
     * @param updatedAtMs 上次成功拉取的服务端时间戳（`data.weather.updated_at`）。
     * @param nowMs 当前基准时间，仅用于让单测固定时间，不读系统时钟。
     */
    fun isStale(updatedAtMs: Long, nowMs: Long): Boolean {
        val age = nowMs - updatedAtMs
        // 负年龄（时钟回拨或服务器时间在未来）→ 视为未过期。
        return age > STALE_THRESHOLD_MS
    }

    /**
     * 失败退避序列（毫秒）：30s → 2m → 5m → 15m（封顶），之后恒为 15m。
     *
     * - `consecutiveFailures <= 1`（首次失败）→ 30_000ms。
     * - `== 2` → 120_000ms（2m）。
     * - `== 3` → 300_000ms（5m）。
     * - `>= 4` → 900_000ms（15m，封顶）。
     *
     * 不形成请求风暴（issue #41 验收标准隐含），上限与正常刷新周期对齐。
     */
    fun nextBackoffMs(consecutiveFailures: Int): Long = when {
        consecutiveFailures <= 1 -> 30_000L
        consecutiveFailures == 2 -> 120_000L
        consecutiveFailures == 3 -> 300_000L
        else -> MAX_BACKOFF_MS
    }
}
