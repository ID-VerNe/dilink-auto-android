package com.dilinkauto.server.service

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 空调反射读写层的回归网（issue #278-PA）。
 *
 * 仿 [com.dilinkauto.server.probe.BydVehicleProbeTest]：fake 设备类照抄 SDK 签名
 * （`static getInstance(Context)` / `getTemprature(int)` / `setAcTemperature(int,int,int,int)`），
 * 经 `classLookup` 接缝注入，让 JVM 上真实跑反射主路径——方法名/参数写错会立刻
 * 变 `NoSuchMethodException`。
 *
 * 不可接受行为 = 抛异常（车机进程崩溃）与**写失败静默**（UX 表 S4：乐观更新冒充成功）。
 */
class AcSettingsProviderTest {

    private class Harness(
        device: Class<*>? = FakeAcDevice::class.java,
        context: Context? = android.content.ContextWrapper(null),
    ) {
        val sink = MutableStateFlowHolder()
        val logs = mutableListOf<String>()
        val unconditionalLogs = mutableListOf<String>()
        var writeFailedCount = 0
        val provider = AcSettingsProvider(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            context = context,
            sink = sink.flow,
            onWriteFailed = { writeFailedCount++ },
            log = { logs += it },
            logUnconditional = { unconditionalLogs += it },
            classLookup = { name ->
                if (name.endsWith("BYDAutoAcDevice") && device != null) device else null
            },
            sleepMs = { /* 单测不真睡眠 */ },
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )
    }

    /** `MutableStateFlow<AcUiState?>` 的持有者（Kotlin 类型推断辅助）。 */
    private class MutableStateFlowHolder {
        val flow = kotlinx.coroutines.flow.MutableStateFlow<AcUiState?>(null)
        val value: AcUiState? get() = flow.value
    }

    private fun newHarness(device: Class<*>? = FakeAcDevice::class.java): Harness {
        AcSettingsProviderTestHarness.device = null
        return Harness(device = device)
    }

    @Test
    fun `read path fills state via reflection`() {
        val h = newHarness()
        h.provider.readAllNow(reason = "test")

        val state = h.sink.value
        assertTrue("读链路必须灌入温度：${h.unconditionalLogs}", state != null)
        assertEquals(22, state?.temperatureC)
        assertEquals(true, state?.started)
        assertTrue("读成功走常规日志：${h.logs}", h.logs.any { it.contains("read t=22 started=true") })
    }

    @Test
    fun `getInstance must receive the permission wrapper`() {
        val h = newHarness()
        h.provider.readAllNow(reason = "test")

        assertTrue(
            "设备实例化必须经 BydPermissionContext（#188），fake 直接记录收到的类型",
            AcSettingsProviderTestHarness.device?.contextIsWrapper == true,
        )
    }

    @Test
    fun `missing class keeps chip hidden with one reason line`() {
        val h = newHarness(device = null)
        h.provider.readAllNow(reason = "test")

        assertNull("类缺失时 state 必须保持 null（chip 不渲染）", h.sink.value)
        assertTrue(
            "不留死入口但要留一行原因：${h.unconditionalLogs}",
            h.unconditionalLogs.any { it.contains("class missing") },
        )
    }

    @Test
    fun `server-side denial is reported not thrown`() {
        val h = newHarness(device = ServerDeniedDevice::class.java)
        h.provider.readAllNow(reason = "test")

        assertNull("getInstance 抛 SecurityException（服务端校验）时不得崩溃", h.sink.value)
        assertTrue(
            "服务端拒绝必须留一行证据：${h.unconditionalLogs}",
            h.unconditionalLogs.any { it.contains("init failed") && it.contains("SecurityException") },
        )
    }

    @Test
    fun `first read of sentinel keeps chip hidden`() {
        // fake 在首次 readAllNow 里才创建，改用「读数即哨兵」的设备类。
        val cold = newHarness(device = ColdSentinelDevice::class.java)
        cold.provider.readAllNow(reason = "test")

        assertNull("首次读就是哨兵 → state 保持 null（chip 不渲染，不留死入口）", cold.sink.value)
        assertTrue(
            "首次读失败必须留一行原因：${cold.unconditionalLogs}",
            cold.unconditionalLogs.any { it.contains("no valid temperature") },
        )
    }

    @Test
    fun `sentinel temperature keeps chip hidden`() {
        val h = newHarness()
        h.provider.readAllNow(reason = "test") // 先建立有效 state（fake 亦在此创建）
        assertEquals(22, h.sink.value?.temperatureC)
        AcSettingsProviderTestHarness.device?.temperature = 0 // 再把读数换回哨兵

        h.provider.readAllNow(reason = "test")

        assertEquals(
            "无效读数（AC_TEMP_INVALID=0）不得上屏，保留旧值不清屏",
            22,
            h.sink.value?.temperatureC,
        )
        assertTrue(
            "读数变无效留一行原因：${h.logs}",
            h.logs.any { it.contains("read invalid") },
        )
    }

    @Test
    fun `adjust writes area1 source0 unit1 and confirms by readback`() {
        val h = newHarness()
        h.provider.readAllNow(reason = "test")
        runBlocking { h.provider.adjustTemperatureNow(+1) }

        val fake = AcSettingsProviderTestHarness.device!!
        assertEquals(
            "写调用必须逐参对齐 #277 §2 矩阵：area=1主驾, value=23, source=0 UI_KEY, unit=1℃",
            listOf(FakeAcDevice.AcWriteCall(type = 1, value = 23, source = 0, unit = 1)),
            fake.writes,
        )
        assertEquals(23, h.sink.value?.temperatureC)
        assertEquals("写成功必须确认 0 次 UI 失败提示", 0, h.writeFailedCount)
        assertTrue(
            "写成功确认行（实车验收 grep 目标）：${h.unconditionalLogs}",
            h.unconditionalLogs.any { it.contains("write t=23 ret=0 confirmed=23") },
        )
    }

    @Test
    fun `rapid repeated steps serialize through the write mutex`() {
        val h = newHarness()
        h.provider.readAllNow(reason = "test") // t=22

        // 连点三次 +（writeMutex 串行）：必须依次 22→23→24→25，而不是乱序/丢失/重复。
        runBlocking {
            repeat(3) { h.provider.adjustTemperatureNow(+1) }
        }

        val fake = AcSettingsProviderTestHarness.device!!
        assertEquals(
            "三次连点 = 依次三条写，每条基于前一条的结果：${fake.writes}",
            listOf(23, 24, 25).map { FakeAcDevice.AcWriteCall(1, it, 0, 1) },
            fake.writes,
        )
        assertEquals(25, h.sink.value?.temperatureC)
        assertEquals(0, h.writeFailedCount)
    }

    @Test
    fun `nonzero return code rolls back and notifies`() {
        val h = newHarness()
        h.provider.readAllNow(reason = "test")
        AcSettingsProviderTestHarness.device?.setTempResult = -2147482648 // AC_COMMAND_FAILED

        runBlocking { h.provider.adjustTemperatureNow(+1) }

        assertEquals(
            "乐观更新必须回滚到写前值（S4：写失败绝不静默）",
            22,
            h.sink.value?.temperatureC,
        )
        assertEquals("UI 必须收到失败事件（toast）", 1, h.writeFailedCount)
        assertTrue(
            "失败必须留 ret= 证据行：${h.unconditionalLogs}",
            h.unconditionalLogs.any { it.contains("write FAILED") && it.contains("ret=-2147482648") },
        )
    }

    @Test
    fun `write exception rolls back and notifies`() {
        val h = newHarness()
        h.provider.readAllNow(reason = "test")
        AcSettingsProviderTestHarness.device?.setTempThrows = RuntimeException("binder died")

        runBlocking { h.provider.adjustTemperatureNow(+1) }

        assertEquals(22, h.sink.value?.temperatureC)
        assertEquals(1, h.writeFailedCount)
        assertTrue(
            h.unconditionalLogs.any { it.contains("write FAILED") && it.contains("binder died") },
        )
    }

    @Test
    fun `readback mismatch then match within retry confirms`() {
        val h = newHarness()
        h.provider.readAllNow(reason = "test")
        // fake：写入生效但不立即体现在回读上（第一次回读返回旧值，第二次返回新值）。
        AcSettingsProviderTestHarness.device?.readBackOverrides = mutableListOf(22)

        runBlocking { h.provider.adjustTemperatureNow(+1) }

        assertEquals(
            "回读重试内追上目标值 → 确认成功，不回滚",
            23,
            h.sink.value?.temperatureC,
        )
        assertEquals(0, h.writeFailedCount)
    }

    @Test
    fun `readback never matching rolls back and notifies`() {
        val h = newHarness()
        h.provider.readAllNow(reason = "test")
        // 回读始终钉在旧值（写「成功」但车上没生效）。
        AcSettingsProviderTestHarness.device?.readBackPin = true

        runBlocking { h.provider.adjustTemperatureNow(+1) }

        assertEquals(22, h.sink.value?.temperatureC)
        assertEquals(1, h.writeFailedCount)
        assertTrue(
            "确认超时必须留原因行：${h.unconditionalLogs}",
            h.unconditionalLogs.any { it.contains("write FAILED") && it.contains("confirm-timeout") },
        )
    }

    @Test
    fun `boundary step never sends an out-of-range write`() {
        val h = newHarness()
        h.provider.readAllNow(reason = "test") // 先建立 fake 与有效 state（t=22）
        AcSettingsProviderTestHarness.device?.temperature = 33
        h.provider.readAllNow(reason = "test") // 再读到上界
        assertEquals(33, h.sink.value?.temperatureC)

        runBlocking { h.provider.adjustTemperatureNow(+1) }

        val fake = AcSettingsProviderTestHarness.device!!
        assertTrue(
            "上界再 + 不得发写（UI 置灰兜底，越界写会得到 INVALID_VALUE）：${fake.writes}",
            fake.writes.isEmpty(),
        )
        assertEquals("state 也不得被乐观更新污染", 33, h.sink.value?.temperatureC)
    }

    @Test
    fun `adjust without state is a no-op`() {
        val h = newHarness(device = null) // 初始化失败 → state 为 null
        runBlocking { h.provider.adjustTemperatureNow(+1) }

        assertNull(h.sink.value)
        assertEquals(0, h.writeFailedCount)
        assertTrue("无数据时无写调用", AcSettingsProviderTestHarness.device?.writes.isNullOrEmpty())
    }

    @Test
    fun `started sentinel maps to unknown not off`() {
        val h = newHarness()
        h.provider.readAllNow(reason = "test") // 先建立 fake 与有效 state
        AcSettingsProviderTestHarness.device?.startState = 2 // 非 1/0 的哨兵

        h.provider.readAllNow(reason = "test")

        assertEquals(
            "getAcStartState 哨兵值 → started 未知（null），不得误判成「关」",
            null,
            h.sink.value?.started,
        )
        assertEquals(22, h.sink.value?.temperatureC)
    }
}

/**
 * 假设备：签名照抄 SDK（`BYDAutoAcDevice.java:91-215`），让 JVM 真实跑反射。
 * [contextIsWrapper] 直接钉住「getInstance 必须收到 wrapper」这条写链路契约。
 */
internal class FakeAcDevice {
    var temperature: Int = 22
    var startState: Int = 1
    var setTempResult: Int = 0
    var setTempThrows: Throwable? = null

    /** 回读覆盖队列：非空时按序弹出，弹完走真实值（模拟回读延迟生效）。 */
    var readBackOverrides: MutableList<Int>? = null

    /** true = 回读永远钉在 [temperatureBeforeWrite]（写「成功」但车上没生效）。 */
    var readBackPin = false
    private var temperatureBeforeWrite = 22

    val writes = mutableListOf<AcWriteCall>()
    var contextIsWrapper: Boolean = false

    fun getTemprature(area: Int): Int {
        readBackOverrides?.let { q ->
            if (q.isNotEmpty()) return q.removeAt(0)
        }
        if (readBackPin) return temperatureBeforeWrite
        return temperature
    }

    fun getAcStartState(): Int = startState

    fun setAcTemperature(type: Int, value: Int, tempSource: Int, unit: Int): Int {
        // 逐参记录（area/source/unit 是 #277 §2 矩阵契约的一部分，测试直接断言四元组）。
        writes += AcWriteCall(type = type, value = value, source = tempSource, unit = unit)
        temperatureBeforeWrite = temperature
        setTempThrows?.let { throw it }
        if (setTempResult == 0) temperature = value
        return setTempResult
    }

    /** 一次 `setAcTemperature` 调用的完整参数（与 SDK 签名一一对应）。 */
    data class AcWriteCall(val type: Int, val value: Int, val source: Int, val unit: Int)

    companion object {
        @JvmStatic
        fun getInstance(context: Context): FakeAcDevice =
            FakeAcDevice().apply {
                contextIsWrapper = context is BydPermissionContext
            }.also { AcSettingsProviderTestHarness.device = it }
    }
}

/** 模拟「客户端权限被绕过、服务端仍然校验」的结局（#277 §4.1 的实测场景）。 */
internal class ServerDeniedDevice {
    companion object {
        @JvmStatic
        fun getInstance(context: Context): ServerDeniedDevice =
            throw SecurityException("server denied")
    }
}

/** 模拟「首次读就是哨兵」（读链路无有效值 → chip 不渲染，不留死入口）。 */
internal class ColdSentinelDevice {
    fun getTemprature(area: Int): Int = 0
    fun getAcStartState(): Int = 1

    companion object {
        @JvmStatic
        fun getInstance(context: Context): ColdSentinelDevice = ColdSentinelDevice()
    }
}

/** [FakeAcDevice.getInstance] 与测试用例之间的取货点（Kotlin companion 里不便静态持有）。 */
internal object AcSettingsProviderTestHarness {
    var device: FakeAcDevice? = null
}
