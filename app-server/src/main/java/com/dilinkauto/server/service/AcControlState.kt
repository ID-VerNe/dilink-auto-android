package com.dilinkauto.server.service

/**
 * 空调控制的 UI 状态（issue #278-PA）。
 *
 * - [temperatureC]：主驾区设定温度（℃），恒在 [AcControlState.TEMP_MIN_C] 到 [AcControlState.TEMP_MAX_C] 之间；
 * - [started]：空调是否开启（`BYDAutoAcDevice.getAcStartState()`，1/0）；
 *   `null` = 读不到开关状态（只显温度，不显「开启/关闭」）。
 */
data class AcUiState(
    val temperatureC: Int,
    val started: Boolean?,
)

/**
 * 空调控制的纯函数与边界常量（issue #278-PA，全单测钉死）。
 *
 * 常量值逐条对照 SDK stub 源（`byd_sdk_33/.../android/hardware/bydauto/ac/BYDAutoAcDevice.java`）：
 * - 温度边界 `AC_TEMP_IN_CELSIUS_MIN/MAX = 17/33`（:50-51）；
 * - 主驾温区 `AC_TEMPERATURE_MAIN = 1`（:43）；
 * - 写来源恒传 `AC_CTRL_SOURCE_UI_KEY = 0`（:26）；
 * - 温度单位恒传 `AC_TEMPERATURE_UNIT_OC = 1`（:47，℃）；
 * - 写返回码 `AC_COMMAND_SUCCESS = 0`（:18），失败为负值（:15-19）。
 *
 * 调用模式对照车况助手未混淆源（`floating/CarControlPage.java:166-176`）：
 * `getTemprature(AC_TEMPERATURE_MAIN)` 读当前值 → `setAcTemperature(area, t±1, UI_KEY, ℃)`。
 */
object AcControlState {
    const val TEMP_MIN_C = 17
    const val TEMP_MAX_C = 33

    /** 主驾温区 `AC_TEMPERATURE_MAIN = 1`（BYDAutoAcDevice.java:43；温度读写都只走主驾，issue 明确不做分区）。 */
    const val TEMP_AREA_MAIN = 1

    /** `getAcStartState()` 的开/关取值（`AC_POWER_ON/OFF = 1/0`，BYDAutoAcDevice.java:38-39）。 */
    const val AC_START_ON = 1
    const val AC_START_OFF = 0

    /** SDK `setAcTemperature` 的成功返回码；其余（BUSY/TIMEOUT/INVALID_VALUE/FAILED）都按失败处理。 */
    const val COMMAND_SUCCESS = 0

    /** 温度钳制（写前必过，防止把越界值发给车机）。 */
    fun clampTemp(t: Int): Int = t.coerceIn(TEMP_MIN_C, TEMP_MAX_C)

    /** 温度行「+」是否可点（Nielsen #5：到 33 置灰，约束优于报错，不发 toast）。 */
    fun canStepUp(t: Int): Boolean = t < TEMP_MAX_C

    /** 温度行「−」是否可点（到 17 置灰）。 */
    fun canStepDown(t: Int): Boolean = t > TEMP_MIN_C

    /**
     * 读回的温度是否有效（读链路可能回哨兵/越界值——探针 #195 见过 8191/65535 这类无效值；
     * 无效读数不得当温度上屏，也不得触发写链路）。
     */
    fun isValidTemp(t: Int): Boolean = t in TEMP_MIN_C..TEMP_MAX_C

    /**
     * chip 文本的结构化判定：[AcChipLabel.Hidden] = 不渲染 chip（无有效数据，不留死入口，
     * issue #278 UX 表 S2）；[AcChipLabel.Off] = 空调关（文本由 UI 层本地化，如「关」/“Off”）；
     * [AcChipLabel.Temp] = 当前设定温度（`24°`，与语言无关，UI 直接拼）。
     */
    fun chipLabel(state: AcUiState?): AcChipLabel {
        val t = state?.temperatureC ?: return AcChipLabel.Hidden
        if (!isValidTemp(t)) return AcChipLabel.Hidden
        return if (state.started == false) AcChipLabel.Off else AcChipLabel.Temp(t)
    }

    /** chip / 弹出块的温度文本：`24°`（≤4 字符，满足左栏 76dp 定宽 chip 约束，issue #189/#54）。 */
    fun formatChipTemp(t: Int): String = "$t°"

    /** `getAcStartState()` 原始返回值 → 三态（true 开 / false 关 / null 读不到）。 */
    fun parseStartState(raw: Int?): Boolean? = when (raw) {
        AC_START_ON -> true
        AC_START_OFF -> false
        else -> null
    }
}

/** 空调 chip 的渲染判定结果，文本本地化归 UI 层（纯函数不做字符串拼接以外的语言假设）。 */
sealed interface AcChipLabel {
    /** 当前设定温度（`24°`，文本与语言无关，见 [AcControlState.formatChipTemp]）。 */
    data class Temp(val value: Int) : AcChipLabel

    /** 空调关闭（「关」/“Off”，本地化文本由 UI 层出）。 */
    object Off : AcChipLabel

    /** 无有效数据：整个 chip 不渲染（不留死入口）。 */
    object Hidden : AcChipLabel
}
