package com.dilinkauto.protocol

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 车机主屏海拔显示的纯判定 / 平滑 / 格式化逻辑（issue #40）。
 *
 * 与 [ClockFormat] 同格放在 protocol-core —— 「判定一个 GPS 定位是否新鲜有效」、
 * 「对海拔做指数滑动平均」、「把整数米渲染成 `1234m` / `-12m` / `—`」这三件事是纯函数，
 * 在 JVM 上就能用 JUnit 钉死，无需实车 GPS。
 *
 * 不读 Android API（[android.location.Location]、[android.content.pm.PackageManager]
 * 由调用方传入判定结果），单测用固定数值覆盖。
 */
object AltitudeFormat {

    /**
     * 一个有效定位的最大年龄（毫秒）。超过即视为过期缓存，拒收（issue #40 追评一）：
     * 车机进入地库/隧道失锁后，`LocationManager` 可能仍持有最后一次缓存定位，
     * 拿过期值冒充实时会导致海拔「卡住不动」。
     */
    const val MAX_LOCATION_AGE_MS = 10_000L

    /** EMA 默认平滑系数：新样本权重 0.2，历史权重 0.8。GPS 抖动 ±10–20m 经此平滑后数字不再每帧跳。 */
    const val DEFAULT_EMA_ALPHA = 0.2f

    /**
     * 判定一个定位是否可用于海拔显示。
     *
     * @param altitude 海拔米数；`null` 表示该定位 `hasAltitude() == false`（无卫星高度解算）。
     * @param ageMs 该定位距现在的毫秒数（由调用方算 `nowMs - location.time` 后传入）；
     *   `null` 表示调用方无法判定年龄，按过期处理（保守拒收）。
     * @return true 当且仅当海拔非 null 且年龄在 [MAX_LOCATION_AGE_MS] 内。
     */
    fun acceptLocation(altitude: Double?, ageMs: Long?): Boolean {
        if (altitude == null || ageMs == null) return false
        // ageMs 可能为负（时钟回拨或定位时间在未来），按 0 处理（视为「刚好现在」），不因负值拒收。
        val age = if (ageMs < 0) 0L else ageMs
        return age <= MAX_LOCATION_AGE_MS
    }

    /**
     * 指数滑动平均（EMA）。
     *
     * - `prev == null`（首个样本或失锁后首个恢复样本）：直接返回 [sample]，不做加权，避免从 0 拉起。
     * - 否则：`prev * (1 - alpha) + sample * alpha`。
     *
     * @param prev 上一次平滑后的值；`null` 表示无历史。
     * @param sample 本次原始海拔（米，WGS84 椭球高）。
     * @param alpha 新样本权重，越大越跟手、越小越平滑；默认 [DEFAULT_EMA_ALPHA]。
     */
    fun emaSmooth(prev: Double?, sample: Double, alpha: Float = DEFAULT_EMA_ALPHA): Double {
        if (prev == null) return sample
        val a = alpha.coerceIn(0f, 1f)
        return prev * (1.0 - a) + sample * a
    }

    /**
     * 把平滑后的海拔渲染成整数米字符串。
     *
     * - `null` → `—`（无有效数据，issue #40 验收标准 2）。
     * - 否则四舍五入到整数米：`1234.5` → `1235`，`-12.4` → `-12`，`0` → `0`。
     * - 负海拔（低于海平面）允许，渲染为 `-12m`。
     *
     * 四舍五入用 [roundToInt]：`0.5` 进位（Kotlin `roundToInt` 对 .5 取偶数银行舍入，
     * 但海拔 ±0.5m 的差异在 GPS ±10–20m 精度下无意义，不做特殊处理）。
     */
    fun formatAltitude(meters: Double?): String {
        if (meters == null) return "—"
        val rounded = meters.roundToInt()
        return "${rounded}m"
    }

    /**
     * 便捷重载：直接接收已取整的海拔（null → `—`）。供 UI 层缓存了整数海拔时使用。
     */
    fun formatAltitude(meters: Int?): String =
        if (meters == null) "—" else "${meters}m"
}
