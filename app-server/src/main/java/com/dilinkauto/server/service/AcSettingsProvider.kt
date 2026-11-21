package com.dilinkauto.server.service

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 空调设置的反射读写层（issue #278-PA：温度档先行）。
 *
 * 把「反射拿设备实例 + 读设定温度 + 写温度 + 写后回读确认 + 失败回滚」从
 * [CarConnectionService] 拆出单独成类（SRP，仿 [AltitudeProvider] 形态）：
 * 服务只持有 `acState` 出口与 [start]/[stop] 生命周期调用。
 *
 * **写链路契约（#277 §2 矩阵 + #188 wrapper）**：
 * - 全反射（`Class.forName`，无编译期依赖，不引 `framework.jar`）；
 * - `getInstance` 必须包 [BydPermissionContext]（绕客户端权限检查；服务端是否
 *   二次校验由 PA 实车首次实测，#277 §4.1）；
 * - 写签名恒为 `setAcTemperature(area=1主驾, t, source=0 UI_KEY, unit=1 ℃)`，
 *   返回码 0 = 成功，负值（BUSY/TIMEOUT/INVALID_VALUE/FAILED）一律按失败处理；
 * - **写后确认不走 listener**：`AbsBYDAutoAcListener` 是抽象类而非接口
 *   （`AbsBYDAutoAcListener.java:15`），`java.lang.reflect.Proxy` 造不出子类
 *   （`BydVehicleProbe.kt` 对 engine listener 已有同类结论），PA 用**写后回读**
 *   `getTemprature(1)` 确认（读链路 #195 已实车验证 `ac temp(area=1)=ok:27`）。
 *
 * **失效模式防线（issue #278 UX 表）**：
 * - 写失败静默（S4）：返回码非 0 / 抛异常 / 回读不一致 → **回滚显示 + [onWriteFailed]**
 *   （UI toast「设置未生效，请重试」），绝不让乐观更新冒充成功；
 * - 越界写：写前必过 [AcControlState.clampTemp]；边界步进（17 再 −、33 再 +）直接
 *   不发写（UI 已置灰，这里兜底）；
 * - 初始化失败不留死入口（S2）：读不到有效温度 → `acState` 保持 null → chip 不渲染，
 *   `logUnconditional` 一行说明原因。
 *
 * 读时机（PA 不做后台轮询，与车况助手「listener 驱动刷新」对齐的最小替代）：
 * `start()` 启动读一次、[refresh]（弹出块打开时）、写后回读。
 *
 * 线程模型：反射是阻塞 binder 调用，全部走 [ioDispatcher]（调用方不得用 Main）；
 * 连点 ± 用 [writeMutex] 串行，保证「连点三次 + = 依次写 +1」而不是乱序覆盖。
 * 回读重试的等待走 [sleepMs] 接缝（默认真 `Thread.sleep`，单测注入 no-op）。
 */
internal class AcSettingsProvider(
    private val scope: CoroutineScope,
    private val context: Context?,
    private val sink: MutableStateFlow<AcUiState?>,
    /** 写失败（显示已回滚）通知出口——UI 据此 toast（S4：写失败绝不静默）。 */
    private val onWriteFailed: () -> Unit,
    /** 常规诊断日志（受 LOG_TOGGLE 控制，release 默认关）。 */
    private val log: (String) -> Unit = {},
    /** 关键行（初始化失败/写失败/确认成功）：不受开关控制，release 也能 grep（#200/#194 先例）。 */
    private val logUnconditional: (String) -> Unit = {},
    /** 类查找接缝：生产走 `Class.forName`，单测注入假类。 */
    private val classLookup: (String) -> Class<*>? = { name -> runCatching { Class.forName(name) }.getOrNull() },
    /** 回读重试间隔接缝：单测注入 no-op 避免真睡眠。 */
    private val sleepMs: (Long) -> Unit = { Thread.sleep(it) },
    /** IO 调度器接缝。 */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** 只读出口，供 UI `collectAsState`。 */
    val state: StateFlow<AcUiState?> = sink.asStateFlow()

    private var readJob: Job? = null

    /** 连点串行：同一时刻只允许一条写链路在飞（后到的 adjust 排队，基于内存最新值算目标）。 */
    private val writeMutex = Mutex()

    @Volatile private var device: Any? = null

    /** 最近一次 `setAcTemperature` 反射抛异常的详情（writeMutex 串行内写读，仅随失败行输出）。 */
    private var lastWriteError: String? = null

    fun start() {
        readJob = scope.launch(ioDispatcher) { readAllNow(reason = "start") }
    }

    fun stop() {
        readJob?.cancel()
        readJob = null
    }

    /** 弹出块打开时全量读一次初始状态（车况助手 initAutoData 模式）。 */
    fun refresh() {
        scope.launch(ioDispatcher) { readAllNow(reason = "refresh") }
    }

    /**
     * 温度步进（±1）：UI 唯一写入口。
     *
     * 乐观更新 → 写 → 回读确认 → 不一致回滚 + [onWriteFailed]。无有效数据（state 为
     * null，chip 本来就不渲染）或目标越界时直接不发写。
     */
    fun adjustTemperature(delta: Int) {
        scope.launch(ioDispatcher) { adjustTemperatureNow(delta) }
    }

    /** 读的同步核（阻塞反射，只能在 IO 线程调；拆出来供单测直接覆盖）。 */
    internal fun readAllNow(reason: String) {
        val dev = obtainDevice() ?: return
        val temp = invokeGetTemp(dev)
        if (temp == null || !AcControlState.isValidTemp(temp)) {
            // 读失败但有旧值：保留旧值不清屏（避免一次读抖就把 chip 抹掉，同 Altitude 处理）；
            // 首次读失败：sink 保持 null → chip 不渲染 + 一行原因（不留死入口，S2）。
            if (sink.value == null) {
                logUnconditional("init failed: no valid temperature (reason=$reason raw=$temp)")
            } else {
                log("read invalid raw=$temp reason=$reason — keep last shown value")
            }
            return
        }
        val started = AcControlState.parseStartState(invokeGetStartState(dev))
        sink.value = AcUiState(temperatureC = temp, started = started)
        log("read t=$temp started=$started reason=$reason")
    }

    /** 写的同步核（阻塞反射 + 回读重试，只能在 IO 线程调；[writeMutex] 保证连点串行）。 */
    internal suspend fun adjustTemperatureNow(delta: Int) = writeMutex.withLock {
        val current = sink.value ?: return@withLock
        val currentTemp = current.temperatureC
        val target = AcControlState.clampTemp(currentTemp + delta)
        if (target == currentTemp) {
            // 边界步进（17 再 − / 33 再 +）：UI 已置灰，这里兜底不发无效写。
            log("step ignored: t=$currentTemp delta=$delta at bound")
            return@withLock
        }

        // 乐观更新（车况助手反例：写后不回读也不看返回值；这里写失败必回滚）。
        sink.value = current.copy(temperatureC = target)

        val dev = obtainDevice()
        if (dev == null) {
            rollback(current, reason = "no-device")
            return@withLock
        }
        val ret = invokeSetTemp(dev, target)
        if (ret == null) {
            // 异常详情随失败行无条件输出（#191/#197 先例：实车 release 只有 unconditional 行可 grep）。
            rollback(current, reason = "write-exception ${lastWriteError.orEmpty()}".trimEnd())
            return@withLock
        }
        if (ret != AcControlState.COMMAND_SUCCESS) {
            rollback(current, reason = "ret=$ret")
            return@withLock
        }

        // 写后回读确认（最多 2 次；首次不 match 多半是车上 CAN 还没生效）。
        for (attempt in 0 until CONFIRM_ATTEMPTS) {
            if (attempt > 0) sleepMs(CONFIRM_RETRY_MS)
            val readBack = invokeGetTemp(dev)
            if (readBack != null && readBack == target) {
                sink.value = current.copy(temperatureC = readBack)
                logUnconditional("write t=$target ret=$ret confirmed=$readBack")
                return@withLock
            }
            log("confirm mismatch read=$readBack target=$target attempt=$attempt")
        }
        rollback(current, reason = "confirm-timeout")
    }

    private fun rollback(previous: AcUiState, reason: String) {
        sink.value = previous
        logUnconditional("write FAILED reason=$reason rolledBackToT=${previous.temperatureC}")
        onWriteFailed()
    }

    /**
     * `Class.forName` → `getInstance(BydPermissionContext)`，成功后缓存实例。
     * 失败（无类/无 context/抛异常/返回 null）返回 null 并留一行原因日志——
     * 这是「服务端是否校验」最直接的证据（探针 #188 同型）。
     */
    private fun obtainDevice(): Any? {
        device?.let { return it }
        val ctx = context ?: run {
            logUnconditional("init failed: no context")
            return null
        }
        val cls = classLookup(AC_DEVICE_CLASS) ?: run {
            logUnconditional("init failed: class missing ($AC_DEVICE_CLASS)")
            return null
        }
        return try {
            val instance = cls.getMethod("getInstance", Context::class.java)
                .invoke(null, BydPermissionContext(ctx))
            if (instance == null) {
                logUnconditional("init failed: getInstance returned null")
                null
            } else {
                device = instance
                log("device ready (via BydPermissionContext)")
                instance
            }
        } catch (t: Throwable) {
            logUnconditional("init failed: ${describe(t)}")
            null
        }
    }

    private fun invokeGetTemp(dev: Any): Int? = runCatching {
        dev.javaClass
            .getMethod("getTemprature", Int::class.javaPrimitiveType)
            .invoke(dev, AcControlState.TEMP_AREA_MAIN) as? Int
    }.getOrElse {
        log("getTemprature failed: ${describe(it)}")
        null
    }

    private fun invokeGetStartState(dev: Any): Int? = runCatching {
        dev.javaClass.getMethod("getAcStartState").invoke(dev) as? Int
    }.getOrElse {
        log("getAcStartState failed: ${describe(it)}")
        null
    }

    private fun invokeSetTemp(dev: Any, target: Int): Int? = runCatching {
        dev.javaClass
            .getMethod(
                "setAcTemperature",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            )
            .invoke(
                dev,
                AcControlState.TEMP_AREA_MAIN, target,
                SET_SOURCE_UI_KEY, TEMP_UNIT_CELSIUS,
            ) as? Int
    }.getOrElse {
        lastWriteError = describe(it)
        log("setAcTemperature failed: $lastWriteError")
        null
    }

    /** 剥到最内层异常（`InvocationTargetException.cause` 才是真相），同探针。 */
    private fun describe(e: Throwable): String {
        var root = e
        while (root.cause != null && root.cause !== root) root = root.cause!!
        val msg = root.message?.replace('\n', ' ')?.take(160) ?: "-"
        return "${root.javaClass.simpleName}:$msg"
    }

    private companion object {
        const val TAG = "AcSettings"

        /** SDK `BYDAutoAcDevice` 全限定名（与探针 #188 同源）。 */
        const val AC_DEVICE_CLASS = "android.hardware.bydauto.ac.BYDAutoAcDevice"

        /** 写来源恒传 `AC_CTRL_SOURCE_UI_KEY = 0`（所有写操作第一参数，#277 §2）。 */
        const val SET_SOURCE_UI_KEY = 0

        /** 温度单位恒传 `AC_TEMPERATURE_UNIT_OC = 1`（℃）。 */
        const val TEMP_UNIT_CELSIUS = 1

        /** 回读确认尝试次数（首次立即读，不 match 再等一轮重读一次）。 */
        const val CONFIRM_ATTEMPTS = 2

        /** 回读重试间隔（毫秒）。 */
        const val CONFIRM_RETRY_MS = 400L
    }
}
