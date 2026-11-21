package com.dilinkauto.server.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 空调控制纯函数的回归网（issue #278-PA）。
 *
 * 常量值全部对照 SDK stub 源钉死（BYDAutoAcDevice.java:15-51）；温度边界表按
 * issue 回归建议覆盖 17/16/33/34、风量下限属 PB 不在本表。
 */
class AcControlStateTest {

    @Test
    fun `temperature bounds match SDK constants`() {
        assertEquals("下界必须等于 AC_TEMP_IN_CELSIUS_MIN（BYDAutoAcDevice.java:51）", 17, AcControlState.TEMP_MIN_C)
        assertEquals("上界必须等于 AC_TEMP_IN_CELSIUS_MAX（:50）", 33, AcControlState.TEMP_MAX_C)
        assertEquals("成功返回码必须等于 AC_COMMAND_SUCCESS（:18）", 0, AcControlState.COMMAND_SUCCESS)
        assertEquals("主驾温区必须等于 AC_TEMPERATURE_MAIN（:43）", 1, AcControlState.TEMP_AREA_MAIN)
    }

    @Test
    fun `clampTemp covers the boundary table`() {
        assertEquals("界内不动", 17, AcControlState.clampTemp(17))
        assertEquals("16 钳到下界", 17, AcControlState.clampTemp(16))
        assertEquals("界内不动", 33, AcControlState.clampTemp(33))
        assertEquals("34 钳到上界", 33, AcControlState.clampTemp(34))
        assertEquals("中段值不动", 24, AcControlState.clampTemp(24))
    }

    @Test
    fun `step gating grays out at bounds`() {
        assertFalse("33 再 + 必须不可点（置灰不发写）", AcControlState.canStepUp(33))
        assertTrue(AcControlState.canStepUp(32))
        assertFalse("17 再 − 必须不可点", AcControlState.canStepDown(17))
        assertTrue(AcControlState.canStepDown(18))
    }

    @Test
    fun `invalid reads are rejected by isValidTemp`() {
        assertTrue(AcControlState.isValidTemp(17))
        assertTrue(AcControlState.isValidTemp(33))
        // 读链路的哨兵/越界值（#195 见过 8191/65535；AC_TEMP_INVALID=0）不得当温度上屏。
        assertFalse(AcControlState.isValidTemp(0))
        assertFalse(AcControlState.isValidTemp(16))
        assertFalse(AcControlState.isValidTemp(34))
        assertFalse(AcControlState.isValidTemp(8191))
        assertFalse(AcControlState.isValidTemp(65535))
    }

    @Test
    fun `chipLabel renders temp, off and hidden three states`() {
        assertEquals(
            AcChipLabel.Temp(24),
            AcControlState.chipLabel(AcUiState(temperatureC = 24, started = true)),
        )
        assertEquals(
            "started 未知（读不到开关状态）时按温度显示，不显「关」",
            AcChipLabel.Temp(24),
            AcControlState.chipLabel(AcUiState(temperatureC = 24, started = null)),
        )
        assertEquals(
            "空调关闭显「关」",
            AcChipLabel.Off,
            AcControlState.chipLabel(AcUiState(temperatureC = 24, started = false)),
        )
        assertEquals("无数据不渲染 chip（不留死入口，S2）", AcChipLabel.Hidden, AcControlState.chipLabel(null))
        assertEquals(
            "无效读数同样不渲染",
            AcChipLabel.Hidden,
            AcControlState.chipLabel(AcUiState(temperatureC = 0, started = true)),
        )
    }

    @Test
    fun `chip temp text stays within the 4-char budget`() {
        assertEquals("24°", AcControlState.formatChipTemp(24))
        assertEquals("17°", AcControlState.formatChipTemp(17))
        assertEquals("33°", AcControlState.formatChipTemp(33))
    }

    @Test
    fun `parseStartState maps 1 and 0 only`() {
        assertEquals(true, AcControlState.parseStartState(1))
        assertEquals(false, AcControlState.parseStartState(0))
        assertEquals("其它取值（含哨兵/异常码）一律未知", null, AcControlState.parseStartState(2))
        assertEquals(null, AcControlState.parseStartState(-2147482648))
        assertEquals(null, AcControlState.parseStartState(null))
    }
}
