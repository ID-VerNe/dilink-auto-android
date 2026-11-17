package com.dilinkauto.server.probe

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.dilinkauto.server.BuildConfig
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy as JavaProxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * 车机侧 BYDAuto 车辆数据探针：issue #188 立单，issue #195 增补对照实验。
 *
 * 为什么要在车机里跑：我们自己的车机端包（`com.dilinkauto.server`，正式签名）拿不到
 * signature 级的 `android.permission.BYDAUTO_*`；但 SDK 的权限检查发生在**客户端 getter
 * 第一行**（`mContext.enforceCallingOrSelfPermission(...)`），[BydPermissionContext] 把这层
 * 拦掉之后，服务端会不会二次校验，就是本探针要回答的问题。
 *
 * #195 的现场数据是 `engine speed[0..5]=ok:8191`，而 `statistic soc=55.0`、`ac temp=27`
 * 正常。只靠这一行无法区分三种根因，所以本轮补了五组对照：
 * 1. `ctxStored=`：`getInstance` 之后 SDK 存的还是不是我们那个 wrapper；
 * 2. `permSelf=` / `permReal=`：wrapper 与真实 ApplicationContext 的权限判定并列；
 * 3. `realCtxCall=`：用真实 context 重新实例化并调 `getEngineSpeed()`；
 * 4. `engine coolant=` / `oil=` / `speedWarn=`：同 `BYDAUTO_ENGINE_GET` 权限的其它 getter；
 * 5. `listener=`：事件通道（上一轮误判为「抽象类不可 Proxy」，见 #188 v2 的更正）；
 * 最后 `verdict-legacy=` 由 [decideSpeedVerdict] 判定；#188 v2 起主判据是
 * `verdict=`（[decideSubscribeVerdict]，raw FID + 订阅对照），避免人工读日志把根因看反。
 *
 * #188 v2（第三轮）推翻了上一轮的两个前提：
 * 1. **事件通道不是死路**。#195 判 `listener=skip(abstract-class)` 是因为拿抽象类去 Proxy；
 *    实际注册入口收的是**接口** `android.hardware.IBYDAutoListener`，
 *    `BYDAutoEngineDevice.registerListener(AbsBYDAutoEngineListener)` 内部就是
 *    `super.registerListener((IBYDAutoListener) l)`（`temp.java:287-293`），所以
 *    `java.lang.reflect.Proxy` 可直接订阅，不需要 DexMaker；
 * 2. **原始 FID 读取要自己走**。typed getter 首行有客户端权限检查，且历史上 `HelperDaemon`
 *    与 `FidDaemon` 都把发动机归到 `dev=1003`（正确是 `1012`），所以「三条通道都返回 8191」
 *    很可能只是同一个错误的复制。本轮用 `AbsBYDAutoDevice.get(dev, fid)` 直接读 1012 / 1003，
 *    并用 AC 主温（`dev=1000`, 已知 26）自证这条反射链能读到非哨兵值。
 *
 * 输出约定：一行一个 `[BydProbe] key=value`，经 `CarLogWriter` 以 `CAR_LOG` 帧回传手机，
 * 手机端落进 `client.log`。得到结论后本类连同调用点一起删除（见 issue #188 的清理条件）。
 *
 * 设计约束：
 * - 全反射，不依赖 `framework.jar`（`byd_api/readme.txt:3` 明确它不能当普通 jar 依赖）；
 * - 任何异常都必须就地吞掉并记录，绝不能让车机端崩溃；
 * - **必须在 IO 线程执行**：`sleepMs` 内部是阻塞 `Thread.sleep`，调用方不能是
 *   `Dispatchers.Main`（车机会卡 UI）；
 * - **必须可中止**：探针全程没有挂起点，取消协程不会自动打断阻塞循环，所以每一拍前都要
 *   问一次 [shouldAbort]；否则断线后旧探针会跑完并继续往下一个会话回传；
 * - 全程控制在 15 秒内：6 拍 × 500ms + 若干一次性的反射调用；
 * - 反射入口可注入，单测可以在 JVM 上覆盖各条对照路径。
 */
internal class BydVehicleProbe(
    private val context: Context?,
    private val sink: (String) -> Unit,
    /** 类查找接缝：生产走 [Class.forName]，单测注入假的类。 */
    private val classLookup: (String) -> Class<*>? = { name -> runCatching { Class.forName(name) }.getOrNull() },
    /** 采样间隔的接缝，单测传 0 避免真睡眠。 */
    private val sleepMs: (Long) -> Unit = { Thread.sleep(it) },
    /** 中止判定接缝：会话在采样途中被取消时返回 true，生产侧接协程 `isActive`。 */
    private val shouldAbort: () -> Boolean = { false },
    /**
     * #195 对照 3 用的「真实 context」。生产取 ApplicationContext，和传进 `getInstance`
     * 的 wrapper 区分开；单测在 mockable android.jar 上拿不到 applicationContext，
     * 会回落到调用方给的 context。
     */
    private val realContext: Context? = context?.let { runCatching { it.applicationContext }.getOrNull() } ?: context,
    /** 墙钟接缝（毫秒单调时钟）：单测注入假时钟验证总时长守卫，避免真等 15 秒。 */
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** 单次会话最多采几拍转速（500ms 一拍 → 约 3 秒）。 */
    private val speedSamples = 6

    /** 探针总时长上限。某次反射卡住时不能把整段车机会话拖死。 */
    private val deadlineMs = 15_000L

    private var startedAtMs = 0L
    private var deadlineHit = false

    fun run() {
        startedAtMs = clockMs()
        emit("start pkg=${context?.packageName ?: "-"} uid=${runCatching { context?.applicationInfo?.uid }.getOrNull() ?: -1} sdk=${Build.VERSION.SDK_INT} debug=${com.dilinkauto.server.BuildConfig.DEBUG}")
        emit("note rpm 8191/65535 = invalid sentinel; speed raw unit varies by model")
        if (abortedAt("start")) {
            finishAfterStop(EngineEvidence())
            return
        }
        reportPermission()
        val evidence = EngineEvidence()
        // 每个 probe 返回 false 表示会话已结束（断线或超时）；断线不写 `verdict`/`done`，
        // 超时则写 `verdict` + `done` 并注明 deadline，避免把「没跑完」误读成「读不到」。
        if (!probeEngine(evidence)) {
            finishAfterStop(evidence)
            return
        }
        if (!probeStatistic()) {
            finishAfterStop(evidence)
            return
        }
        if (!probeSpeed()) {
            finishAfterStop(evidence)
            return
        }
        if (!probeAc(evidence)) {
            finishAfterStop(evidence)
            return
        }
        emitVerdict(evidence)
        emit("done")
    }

    /** 超时是受控收尾：把已收集的证据落成 `verdict` 再写 `done`；断线仍然直接中止。 */
    private fun finishAfterStop(evidence: EngineEvidence) {
        if (!deadlineHit) return
        emitVerdict(evidence)
        emit("done")
    }

    /**
     * 会话已结束或超过总时长就停下，并把停在哪一步记下来
     * （避免把「没跑完」误读成「读不到」）。
     */
    private fun abortedAt(where: String): Boolean {
        if (clockMs() - startedAtMs > deadlineMs) {
            deadlineHit = true
            emit("deadline reached — skip remaining at $where")
            return true
        }
        if (!shouldAbort()) return false
        emit("aborted at $where")
        return true
    }

    /**
     * 权限现状。三条并列，缺一就会误判：
     * - `perm`：原始 service context 的 `checkSelfPermission`（保持 #188 的既有格式）；
     * - `permSelf`：wrapper 的 `checkSelfPermission`（没被 override，仍走 base）与
     *   `checkCallingOrSelfPermission`（被 override，SDK 实际调用的是这个入口）；
     * - `permReal`：真实 ApplicationContext 的 `checkSelfPermission`。
     */
    private fun reportPermission() {
        val ctx = context ?: return emit("perm engine_get=no-context")
        emit("perm $ENGINE_GET_PERMISSION=${granted(ctx)}")
        val wrapper = BydPermissionContext(ctx)
        emit("permSelf=${granted(wrapper)} checkCallingOrSelf=${callingOrSelf(wrapper)}")
        emit("permReal=${granted(realContext ?: ctx)}")
    }

    private fun granted(ctx: Context): String = runCatching {
        if (ctx.checkSelfPermission(ENGINE_GET_PERMISSION) == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED"
    }.getOrElse { "err:${describe(it)}" }

    private fun callingOrSelf(ctx: Context): String = runCatching {
        if (ctx.checkCallingOrSelfPermission(ENGINE_GET_PERMISSION) == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED"
    }.getOrElse { "err:${describe(it)}" }

    /** 主角：发动机转速。`getEngineSpeed()` → `super.get(1012, 0x14400012)`。 */
    private fun probeEngine(evidence: EngineEvidence): Boolean {
        val device = obtainDevice(ENGINE_DEVICE, "engine")
        // 对照 1：SDK 内部存的 context 是否还是我们的 wrapper（被换掉则 wrapper 失效）。
        evidence.storedContext = probeStoredContext(device)
        if (device == null) {
            evidence.listener = probeSubscribe(null, evidence)
            return true
        }
        for (i in 0 until speedSamples) {
            if (abortedAt("engine speed[$i]")) return false
            val raw = callInt(device, "getEngineSpeed")
            emit("engine speed[$i]=$raw")
            // 只用第一拍参与判定，避免 6 个重复样本掩盖其它 getter 的差异。
            if (i == 0) evidence.readings += parseOkInt(raw)
            if (i != speedSamples - 1) sleepMs(SAMPLE_INTERVAL_MS)
        }
        if (abortedAt("engine power")) return false
        emit("engine power=${callInt(device, "getEnginePower")}")

        // 对照 4：同权限的其它 getter。全哨兵 → 不是单个 feature 的问题。
        if (abortedAt("engine coolant")) return false
        val coolant = callInt(device, "getEngineCoolantLevel")
        emit("engine coolant=$coolant")
        evidence.readings += parseOkInt(coolant)

        if (abortedAt("engine oil")) return false
        val oil = callInt(device, "getOilLevel")
        emit("oil=$oil")
        evidence.readings += parseOkInt(oil)

        if (abortedAt("engine speed warn")) return false
        val warn = callInt(device, "getEngineSpeedWarningValue")
        emit("speedWarn=$warn")
        evidence.readings += parseOkInt(warn)

        // 对照 5：事件通道（#188 v2 起改成真订阅，见 [probeSubscribe]）。
        if (abortedAt("raw lanes")) return false
        evidence.rawEngine = rawGetInt(device, DEV_ENGINE, FID_ENGINE_SPEED)
        evidence.rawLegacy = rawGetInt(device, DEV_ENGINE_LEGACY, FID_ENGINE_SPEED)
        emit("raw[1012]=${evidence.rawEngine}")
        emit("raw[1003]=${evidence.rawLegacy}")

        if (abortedAt("subscribe")) return false
        evidence.listener = probeSubscribe(device, evidence)

        // 对照 3 必须放在最后：`getInstance(realContext)` 可能返回同一单例并覆盖它的
        // `mContext`，之后任何 getter 都会落到真实 context 的权限检查上；先跑会把
        // coolant/oil/speedWarn 变成 SecurityException，整轮证据作废。
        if (abortedAt("engine real ctx")) return false
        evidence.realContextCall = probeRealContextCall()
        return true
    }

    /**
     * #195 对照 1：反射遍历设备对象及其父类的非静态 `Context` 字段，逐个读值并判断是否
     * 还是 [BydPermissionContext]。
     *
     * 结论取三态（[CtxStored]）：全部是 wrapper = [CtxStored.WRAPPER]；全部不是 =
     * [CtxStored.NOT_WRAPPER]；混着出现 = [CtxStored.MIXED]（父类/无关字段会干扰，
     * 不能拿「任一是 wrapper」冒充成功）；没有可读字段 = [CtxStored.UNKNOWN]。
     */
    private fun probeStoredContext(device: Any?): CtxStored {
        if (device == null) {
            emit("ctxStored=skip(no-instance)")
            return CtxStored.UNKNOWN
        }
        val fields = mutableListOf<Field>()
        var cls: Class<*>? = device.javaClass
        while (cls != null && cls != Any::class.java) {
            for (field in cls.declaredFields) {
                if (!Modifier.isStatic(field.modifiers) && Context::class.java.isAssignableFrom(field.type)) {
                    fields += field
                }
            }
            cls = cls.superclass
        }
        if (fields.isEmpty()) {
            emit("ctxStored=skip(no-context-field)")
            return CtxStored.UNKNOWN
        }
        var readable = 0
        var wrapperFields = 0
        for (field in fields) {
            val read = runCatching {
                field.isAccessible = true
                field.get(device)
            }
            if (read.isFailure) {
                // Android 9+ 的 hidden-API 限制会让 setAccessible 抛 InaccessibleObjectException /
                // NoSuchFieldException；原样记录类型与消息，别让「读不到」被当成「不是 wrapper」。
                emit("ctxStored=err field=${field.name} ${describe(read.exceptionOrNull()!!)}")
                continue
            }
            readable++
            val value = read.getOrNull()
            val isWrapper = value is BydPermissionContext
            if (isWrapper) wrapperFields++
            emit("ctxStored=wrapper=$isWrapper field=${field.name} ctx=${value?.javaClass?.simpleName ?: "null"}")
        }
        val state = when {
            readable == 0 -> {
                // 字段存在但一个都读不到：反射被拦，无法判断归属（含 hidden-API 限制）。
                emit("ctxStored=skip(unreadable-field) hint=hidden-api-or-reflection")
                CtxStored.UNKNOWN
            }
            wrapperFields == readable -> CtxStored.WRAPPER
            wrapperFields == 0 -> CtxStored.NOT_WRAPPER
            else -> CtxStored.MIXED
        }
        emit("ctxStored=state=${state.tag} readable=$readable wrapperFields=$wrapperFields")
        return state
    }

    /** #195 对照 3：用真实 context（非 wrapper）重新实例化并调用转速 getter。 */
    private fun probeRealContextCall(): RealContextCall {
        val real = realContext
        if (real == null) {
            emit("realCtxCall=skip(no-context)")
            return RealContextCall.SKIPPED
        }
        val cls = classLookup(ENGINE_DEVICE)
        if (cls == null) {
            emit("realCtxCall=skip(no-class)")
            return RealContextCall.SKIPPED
        }
        return try {
            val instance = cls.getMethod("getInstance", Context::class.java).invoke(null, real)
            if (instance == null) {
                emit("realCtxCall=skip(null-instance)")
                RealContextCall.SKIPPED
            } else {
                val raw = instance.javaClass.getMethod("getEngineSpeed").invoke(instance)
                val value = raw as? Int
                if (value != null && value in BYD_SENTINEL_VALUES) {
                    emit("realCtxCall=ok:$raw(sentinel)")
                    RealContextCall.OK_SENTINEL
                } else {
                    emit("realCtxCall=ok:$raw")
                    RealContextCall.OK_REAL
                }
            }
        } catch (e: Throwable) {
            val root = rootCause(e)
            emit("realCtxCall=err:${describe(e)}")
            if (root is SecurityException) RealContextCall.DENIED else RealContextCall.ERROR
        }
    }

    /**
     * #188 v2 对照 5：**真订阅**，并对比订阅前后的原始读数。
     *
     * 上一轮判 `listener=skip(abstract-class)` 的推理是错的：需要 Proxy 的不是
     * `AbsBYDAutoEngineListener`（抽象类），而是它实现的接口 `android.hardware.IBYDAutoListener`
     * （3 个方法）。`BYDAutoEngineDevice.registerListener(AbsBYDAutoEngineListener)` 内部执行
     * `super.registerListener((IBYDAutoListener) l)`（`temp.java:287-293`），说明设备侧只认接口，
     * 所以 `java.lang.reflect.Proxy` 可以直接订阅 —— 不需要 DexMaker，也不需要编译期依赖。
     *
     * 三步：只订阅转速 FID → 读一次；全量订阅 → 再读一次；注销（必须同一 proxy 实例）。
     */
    private fun probeSubscribe(device: Any?, evidence: EngineEvidence): ListenerState {
        if (device == null) {
            emit("listener=skip(no-instance)")
            return ListenerState.NOT_ATTEMPTED
        }
        val iface = classLookup(LISTENER_INTERFACE)
        if (iface == null || !iface.isInterface) {
            emit("listener=skip(no-interface($LISTENER_INTERFACE))")
            // 接口缺失时把「抽象类不可 Proxy」的旧结论原样留证，别让两轮日志对不上。
            val abs = classLookup(LISTENER_CLASS)
            if (abs != null && Modifier.isAbstract(abs.modifiers)) emit("listener=skip(abstract-class)")
            return ListenerState.SKIP_ABSTRACT
        }
        val proxy = runCatching { newListenerProxy(iface, evidence) }.getOrElse {
            emit("listener=err:proxy(${describe(it)})")
            return ListenerState.ERROR
        }

        if (!registerListener(device, iface, proxy, intArrayOf(FID_ENGINE_SPEED))) {
            return ListenerState.ERROR
        }
        emit("sub=registered featureIds=1")
        sleepMs(SUBSCRIBE_WAIT_MS)
        evidence.afterSubscribe = rawGetInt(device, DEV_ENGINE, FID_ENGINE_SPEED)
        emit("sub-after=${evidence.afterSubscribe}")

        // 全量订阅：排除「单 FID 订阅没下发」造成的假阴性。
        if (registerListener(device, iface, proxy, null)) {
            emit("sub-all=registered")
            sleepMs(SUBSCRIBE_WAIT_MS)
            evidence.afterSubscribeAll = rawGetInt(device, DEV_ENGINE, FID_ENGINE_SPEED)
            emit("sub-all-after=${evidence.afterSubscribeAll}")
        }

        runCatching {
            val base = classLookup(ABS_DEVICE) ?: error("no $ABS_DEVICE")
            base.getMethod("unregisterListener", iface).invoke(device, proxy)
            emit("unsub=ok")
        }.onFailure { emit("unsub=err:${describe(it)}") }

        return if (evidence.hasRealCallback()) ListenerState.REGISTERED_REAL_VALUE else ListenerState.REGISTERED
    }

    /** 反射 `AbsBYDAutoDevice.registerListener(IBYDAutoListener[, int[]])`；子类只声明了 typed 重载。 */
    private fun registerListener(device: Any, iface: Class<*>, proxy: Any, featureIds: IntArray?): Boolean =
        runCatching {
            val base = classLookup(ABS_DEVICE) ?: error("no $ABS_DEVICE")
            if (featureIds == null) {
                base.getMethod("registerListener", iface).invoke(device, proxy)
            } else {
                base.getMethod("registerListener", iface, IntArray::class.java).invoke(device, proxy, featureIds)
            }
            true
        }.getOrElse {
            emit("listener=err:register(${describe(it)})")
            false
        }

    /**
     * 造一个 `IBYDAutoListener` 代理。回调**可能在 binder 线程**，所以 `EngineEvidence`
     * 里的事件容器必须是并发安全且只在锁内累积（见 [EngineEvidence.recordCallback]）。
     *
     * 两个必须踩住的坑，踩错任何一个这一轮的订阅实验都会直接作废：
     * 1. **`Object` 方法也会进 handler**。SDK 用
     *    `ConcurrentHashMap<IBYDAutoListener, ArraySet<Integer>>` 存听众
     *    （`IBYDAutoListenerIdsMap.mListenerIdMap`），注册时必然调 `hashCode()`/`equals()`；
     *    handler 一律 `return null` 会在拆箱成 `int` 时 NPE，`registerListener` 直接抛异常，
     *    日志只剩 `listener=err:register(...)`。所以这三个方法必须给出真值。
     * 2. **事件值藏在两个不同形状里**：`onDataChanged(IBYDAutoEvent)` 走 getter，
     *    而 `onDataEventChanged(int, BYDAutoEventValue)` 的值在**公有字段**里
     *    （`intValue` / `doubleValue`），第二个参数不是 null 就一定要读 —— 只记 `evt`
     *    等于把「读到了转速」记成「没有数据」，判定会给出与事实相反的 `no-data`。
     */
    private fun newListenerProxy(iface: Class<*>, evidence: EngineEvidence): Any {
        // 接口在 boot classpath 时 `classLoader` 是 null；用我们自己的 loader 定义代理类更稳。
        val loader = iface.classLoader ?: BydVehicleProbe::class.java.classLoader
        return JavaProxy.newProxyInstance(loader, arrayOf(iface)) { proxySelf, method, args ->
            when {
                method.declaringClass == Any::class.java -> when (method.name) {
                    "toString" -> "BydProbeListenerProxy"
                    "hashCode" -> System.identityHashCode(proxySelf)
                    // 剩下只有 equals：代理之间按引用相等，否则 ConcurrentHashMap 会找不到自己。
                    else -> proxySelf === args?.getOrNull(0)
                }
                method.name == "onDataChanged" -> {
                    val event = args?.getOrNull(0)
                    evidence.recordCallback(
                        dev = readInt(event, "getDeviceType"),
                        evt = readInt(event, "getEventType"),
                        value = readInt(event, "getValue"),
                        dbl = readDouble(event, "getDoubleValue"),
                        emit = ::emit,
                    )
                    null
                }
                // `onDataEventChanged(int, BYDAutoEventValue)`：第一个参数是 eventType，值在第二个参数里。
                method.name == "onDataEventChanged" -> {
                    val event = args?.getOrNull(1)
                    evidence.recordCallback(
                        dev = null,
                        evt = args?.getOrNull(0) as? Int,
                        value = readIntField(event, "intValue"),
                        dbl = readDoubleField(event, "doubleValue"),
                        emit = ::emit,
                    )
                    null
                }
                method.name == "onError" -> {
                    emit("cb=err ${args?.getOrNull(1)}")
                    null
                }
                else -> null
            }
        }
    }

    private fun readCall(target: Any?, method: String): Any? =
        runCatching { target?.javaClass?.getMethod(method)?.invoke(target) }.getOrNull()

    private fun readInt(target: Any?, method: String): Int? = readCall(target, method) as? Int

    private fun readDouble(target: Any?, method: String): Double? = readCall(target, method) as? Double

    /** 直读 `BYDAutoEventValue.intValue`；`INVAILD_INT`（-999999999）是 SDK 的「无值」哨兵。 */
    private fun readIntField(target: Any?, field: String): Int? {
        val raw = runCatching { target?.javaClass?.getField(field)?.get(target) }.getOrNull() as? Int
        return if (raw == null || raw == BYD_EVENT_INVALID_INT) null else raw
    }

    /** 直读 `BYDAutoEventValue.doubleValue`；`INVALID_DOUBLE`（-9.99999999E8）是「无值」。 */
    private fun readDoubleField(target: Any?, field: String): Double? {
        val raw = runCatching { target?.javaClass?.getField(field)?.get(target) }.getOrNull() as? Double
        return if (raw == null || raw <= BYD_EVENT_INVALID_DOUBLE_THRESHOLD) null else raw
    }

    /**
     * 直接调 `AbsBYDAutoDevice.get(dev, fid)`，绕开 typed getter 首行的客户端权限检查。
     * 返回 `ok:<int>` / `err:<异常>`，与其它 lane 的格式保持一致。
     */
    private fun rawGetInt(device: Any?, dev: Int, fid: Int): String {
        if (device == null) return "skip(no-instance)"
        val base = classLookup(ABS_DEVICE) ?: return "skip(no-class)"
        return runCatching {
            val m = base.getMethod("get", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            "ok:${m.invoke(device, dev, fid)}"
        }.getOrElse { "err:${describe(it)}" }
    }

    /** 把判定所需的全部对照结果汇总成一行，方便明天上车直接 grep `verdict=`。 */
    private fun emitVerdict(evidence: EngineEvidence) {
        val snapshot = evidence.snapshot()
        val sentinel = snapshot.engineReadings.count { it != null && it in BYD_SENTINEL_VALUES }
        emit(
            "verdict-legacy=${decideSpeedVerdict(snapshot)} ctxStored=${snapshot.storedContext.tag} " +
                "realCtx=${snapshot.realContextCall.tag} sentinel=$sentinel/${snapshot.engineReadings.size} " +
                "listener=${snapshot.listener.tag}",
        )
        // #188 v2 的主判据：raw lane + 订阅前后对比。上一轮 `verdict-legacy` 依赖的
        // `realCtxCall` 已被证明不是有效对照（`getInstance` 是进程级单例），判读看这一行。
        val sub = evidence.subscribeSnapshot()
        emit(
            "verdict=${decideSubscribeVerdict(sub)} raw[1012]=${sub.rawEngine} raw[1003]=${sub.rawLegacy} " +
                "raw[ctl]=${sub.rawControl} anchored=${isLaneAnchored(sub.rawControl)} " +
                "sub-after=${sub.afterSubscribe} sub-all-after=${sub.afterSubscribeAll} " +
                "cb=${sub.callbackValues.size}/${evidence.callbackTotal.get()}",
        )
    }

    /** 对照组：TripLogger 在这条路径上读到过真实值（SOC 86.0），用来区分「全挂」还是「只有 engine 不行」。 */
    private fun probeStatistic(): Boolean {
        if (abortedAt("statistic")) return false
        val device = obtainDevice(STATISTIC_DEVICE, "statistic") ?: return true
        emit("statistic soc=${callDouble(device, "getElecPercentageValue")}")
        return true
    }

    private fun probeSpeed(): Boolean {
        if (abortedAt("speed")) return false
        val device = obtainDevice(SPEED_DEVICE, "speed") ?: return true
        // 标签写 raw：`getCurrentSpeed()` 的原始单位随车型/固件而异，别写成 km/h 误导判读。
        emit("speed raw=${callDouble(device, "getCurrentSpeed")}")
        return true
    }

    /**
     * 更高门槛的对照：AC 设备初始化要求 `BYDAUTO_AC_COMMON`，比 engine 的 `_GET` 更严。
     *
     * #188 v2 起同时充当 `raw` lane 的自证：`get(1000, AC_TEMP_MAIN)` 在实车 dump 里读到 26，
     * 如果这条也返回哨兵，说明 `raw` lane 本身写错了，不能拿它否定发动机字段。
     *
     * 设备拿不到时**不要**让整轮作废：`laneState` 会退化成 `unanchored`（判据见
     * [LaneSelfCheck]），结论仍可用，只是标注为证据较弱。
     */
    private fun probeAc(evidence: EngineEvidence): Boolean {
        if (abortedAt("ac")) return false
        val device = obtainDevice(AC_DEVICE, "ac") ?: return true
        emit("ac temp(area=1)=${callInt(device, "getTemprature", 1)}")
        evidence.rawControl = rawGetInt(device, DEV_AC, FID_AC_TEMP_MAIN)
        emit("raw[ctl]=${evidence.rawControl}")
        return true
    }

    /**
     * `Class.forName` → `getInstance(Context)`。实例化失败时把异常链记下来，
     * 这是「服务端是否校验」最直接的证据。
     */
    private fun obtainDevice(className: String, label: String): Any? {
        val cls = classLookup(className)
        if (cls == null) {
            emit("dev $label class=missing($className)")
            return null
        }
        emit("dev $label class=ok")
        val ctx = context
        if (ctx == null) {
            emit("dev $label instance=no-context")
            return null
        }
        return try {
            val factory = cls.getMethod("getInstance", Context::class.java)
            val instance = factory.invoke(null, BydPermissionContext(ctx))
            // getInstance 可能返回 null：那与「实例化成功但 getter 全挂」是两件事，必须区分。
            emit("dev $label instance=${if (instance == null) "null" else "ok"}")
            instance
        } catch (e: Throwable) {
            emit("dev $label instance=err ${describe(e)}")
            null
        }
    }

    private fun callInt(target: Any, method: String, arg: Int? = null): String =
        invoke(target, method, arg, Int::class.javaPrimitiveType)

    private fun callDouble(target: Any, method: String): String =
        invoke(target, method, null, null)

    private fun invoke(target: Any, method: String, arg: Int?, argType: Class<*>?): String = try {
        val m = if (arg == null) target.javaClass.getMethod(method) else target.javaClass.getMethod(method, argType)
        val value = if (arg == null) m.invoke(target) else m.invoke(target, arg)
        "ok:$value"
    } catch (e: Throwable) {
        "err:${describe(e)}"
    }

    /** 只取 `ok:` 后面的整数值参与判定；`err:` 与不可解析的值都按「读数缺失」处理。 */
    private fun parseOkInt(raw: String): Int? = raw.removePrefix("ok:").toIntOrNull()

    /** 把反射调用里的异常剥到最内层（`InvocationTargetException.cause` 才是真相）。 */
    private fun describe(e: Throwable): String {
        val root = rootCause(e)
        val msg = root.message?.replace('\n', ' ')?.take(160) ?: "-"
        return "${root.javaClass.simpleName}:$msg"
    }

    private fun rootCause(e: Throwable): Throwable {
        var root = e
        while (root.cause != null && root.cause !== root) root = root.cause!!
        return root
    }

    /** 同时写 logcat 与手机端回传通道；任何一路失败都不影响另一路。 */
    private fun emit(line: String) {
        runCatching { Log.i(TAG, line) }
        runCatching { sink("$TAG $line") }
    }

    /** 判定用的可变累积器，跑完再拍成不可变的 [BydSpeedEvidence] 交给纯函数。 */
    private class EngineEvidence {
        var storedContext: CtxStored = CtxStored.UNKNOWN
        var realContextCall: RealContextCall = RealContextCall.SKIPPED
        var listener: ListenerState = ListenerState.NOT_ATTEMPTED
        val readings = mutableListOf<Int?>()

        // ---- #188 v2：raw lane + 订阅 ----
        var rawEngine: String? = null
        var rawLegacy: String? = null
        var rawControl: String? = null
        var afterSubscribe: String? = null
        var afterSubscribeAll: String? = null

        /** 事件回调值。可能在 binder 线程写入，所以用并发容器，不在锁里做 IO。 */
        private val callbacks = CopyOnWriteArrayList<Int>()
        private val callbackLines = AtomicInteger()
        val callbackTotal = AtomicInteger()

        /**
         * 记录一条事件回调。日志行数封顶（[MAX_CALLBACK_LINES]），否则转速事件会把
         * `client.log` 刷爆；总共收到多少条走 [callbackTotal] 单独统计。
         *
         * `value` 来自 `IBYDAutoEvent.getValue()` 或 `BYDAutoEventValue.intValue`；
         * `dbl` 是两者的 double 版本。两条通道只要任一条给出有效整数就计入。
         *
         * **必须做归属过滤**：`sub-all` 是全量订阅，空调/车速/统计的事件都会打到同一个
         * listener 上。若不过滤，`26`（AC 温度）、`55`（SOC）都落在转速的 `1..8000` 区间，
         * 会被 [isRealRpm] 当成转速，把结论刷成假的 `event-only`。
         *
         * 归属判定：
         * - `dev` 非空（`onDataChanged` 带 `getDeviceType()`）→ 必须等于 [DEV_ENGINE]；
         * - `dev` 为空（`onDataEventChanged` 只给一个 int）→ 该 int 按 SDK 的
         *   `IBYDAutoListenerIdsMap` 语义是 **featureId**，只有等于 [FID_ENGINE_SPEED] 才算转速；
         * - 两者都拿不到 → 不计入，只留日志。
         */
        fun recordCallback(dev: Int?, evt: Int?, value: Int?, dbl: Double?, emit: (String) -> Unit) {
            callbackTotal.incrementAndGet()
            val asInt = value ?: dbl?.roundToInt()
            val attribution = when {
                dev != null -> if (dev == DEV_ENGINE) "engine" else "dev=$dev"
                evt != null -> if (evt == FID_ENGINE_SPEED) "engine-fid" else "fid=0x${evt.toUInt().toString(16)}"
                else -> "unknown"
            }
            val attributed = attribution == "engine" || attribution == "engine-fid"
            if (callbackLines.getAndIncrement() < MAX_CALLBACK_LINES) {
                emit("cb dev=$dev evt=$evt val=$value dbl=$dbl -> $asInt [$attribution]")
            }
            if (asInt != null && attributed) callbacks += asInt
        }

        /**
         * 事件通道里是否出现过**真实**转速。口径必须与判定函数一致（[isRealRpm]）：
         * 只看 `>0 && !in 哨兵` 会把 9000+ 的越界回调也算成真值，让 `event-only` 误报。
         */
        fun hasRealCallback(): Boolean = callbacks.any(::isRealRpm)

        fun snapshot(): BydSpeedEvidence =
            BydSpeedEvidence(storedContext, realContextCall, readings.toList(), listener)

        /**
         * #188 v2 快照。`listener` 在这里**重新**按回调结果升级成 [ListenerState.REGISTERED_REAL_VALUE]：
         * `probeSubscribe` 收尾后就返回，之后（比如 `probeRealContextCall` 那一段）仍可能来回调，
         * 若在 `probeSubscribe` 里就把状态钉死，这些晚到的值会被判成「没收到」。
         */
        fun subscribeSnapshot(): BydSubscribeEvidence = BydSubscribeEvidence(
            rawEngine = parseProbeInt(rawEngine),
            rawLegacy = parseProbeInt(rawLegacy),
            rawControl = parseProbeInt(rawControl),
            afterSubscribe = parseProbeInt(afterSubscribe),
            afterSubscribeAll = parseProbeInt(afterSubscribeAll),
            callbackValues = callbacks.toList(),
            listener = effectiveListener(),
        )

        /** 注册成功且有真实回调时升级为 [ListenerState.REGISTERED_REAL_VALUE]。 */
        fun effectiveListener(): ListenerState =
            if (listener == ListenerState.REGISTERED && hasRealCallback()) ListenerState.REGISTERED_REAL_VALUE else listener
    }

    companion object {
        const val TAG = "[BydProbe]"

        /**
         * 探针总开关。**只在 debug 包里自动打开**：本轮实验结束、结论回填后连同本文件
         * 与调用点一起删除（清理条件见 issue #188）。
         *
         * 为什么不能写死 `true`：这个探针挂在 `CarSessionCoordinator.beginConnect` 上，
         * 每次连接都会跑约 3 秒，并且会**订阅并注销**车机的发动机事件通道。正式包里
         * 让用户车机跑临时诊断代码是不可接受的，所以用 [BuildConfig.DEBUG] 卡住 ——
         * release 包里 `ENABLED` 恒为 false，效果等同删除，但代码还能给 debug 包复用。
         *
         * [BuildConfig.PROBE_FORCE] 是实车取证的唯一例外：车机上装的是正式签名包，
         * debug 包覆盖不上去（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`），卸载重装又会丢
         * 车机端配置，所以实车那一轮用 `-Pprobe.force=true` 单独构建。默认 false，
         * 发布流水线不带该参数，因此正式包不会跑探针。
         */
        val ENABLED: Boolean = BuildConfig.DEBUG || BuildConfig.PROBE_FORCE

        private const val SAMPLE_INTERVAL_MS = 500L
        private const val ENGINE_GET_PERMISSION = "android.permission.BYDAUTO_ENGINE_GET"
        private const val ENGINE_DEVICE = "android.hardware.bydauto.engine.BYDAutoEngineDevice"
        private const val LISTENER_CLASS = "android.hardware.bydauto.engine.AbsBYDAutoEngineListener"
        private const val STATISTIC_DEVICE = "android.hardware.bydauto.statistic.BYDAutoStatisticDevice"
        private const val SPEED_DEVICE = "android.hardware.bydauto.speed.BYDAutoSpeedDevice"
        private const val AC_DEVICE = "android.hardware.bydauto.ac.BYDAutoAcDevice"

        // ---- #188 v2（第三轮）：原始 FID 读取 + 订阅对照 ----
        /** `AbsBYDAutoDevice.get(int, int)` 所在的基类；typed getter 的权限检查就在它上一层。 */
        private const val ABS_DEVICE = "android.hardware.bydauto.AbsBYDAutoDevice"

        /** 注册入口真正接收的**接口**（不是 `AbsBYDAutoEngineListener` 抽象类）。 */
        private const val LISTENER_INTERFACE = "android.hardware.IBYDAutoListener"

        /** `BYDAutoConstants.BYDAUTO_DEVICE_ENGINE`；历史上两个 daemon 误用 1003。 */
        private const val DEV_ENGINE = 1012
        private const val DEV_ENGINE_LEGACY = 1003
        private const val DEV_AC = 1000

        /** `BYDAutoFeatureIds.ENGINE_SPEED`。 */
        private const val FID_ENGINE_SPEED = 0x14400012

        /** `BYDAutoFeatureIds.AC_TEMP_MAIN`，实车 dump 读到 26，作为 lane 自证。 */
        private const val FID_AC_TEMP_MAIN = 0x40400028

        /** 订阅后等一拍再读，给服务端下发订阅的时间。 */
        private const val SUBSCRIBE_WAIT_MS = 800L

        /** 回调行最多打几条，避免转速事件把 `client.log` 刷爆。 */
        private const val MAX_CALLBACK_LINES = 8

        /** `BYDAutoEventValue.INVAILD_INT`（SDK 原样拼写）：`intValue` 字段的「无值」哨兵。 */
        private const val BYD_EVENT_INVALID_INT = -999_999_999

        /** `BYDAutoEventValue.INVALID_DOUBLE` 是 -9.99999999E8；用阈值比较比等值安全（double 精度）。 */
        private const val BYD_EVENT_INVALID_DOUBLE_THRESHOLD = -1.0E8
    }
}

/** `realCtxCall` 的分类，写日志时用短 tag，判定时用枚举。 */
internal enum class RealContextCall(val tag: String) {
    /** 真实 context 也拿到了非哨兵值。 */
    OK_REAL("ok-real"),

    /** 真实 context 没抛异常，但拿到的仍是哨兵（8191 / 65535）。 */
    OK_SENTINEL("ok-sentinel"),

    /** 真实 context 被客户端权限检查挡住（`SecurityException`）。 */
    DENIED("denied"),

    /** 其它异常（类缺失、反射失败等）。 */
    ERROR("error"),

    /** 没有可用 context / 类，本项没跑。 */
    SKIPPED("skipped"),
}

/** `ctxStored` 的归属结论（三态 + 未知），单独成行便于 grep。 */
internal enum class CtxStored(val tag: String) {
    /** 所有可读的 Context 字段都还是我们传进去的 wrapper。 */
    WRAPPER("wrapper"),

    /** 所有可读字段都不是 wrapper（SDK 换成了别的 context）。 */
    NOT_WRAPPER("not-wrapper"),

    /** 多个 Context 字段里有的时 wrapper、有的不是：父类/无关字段干扰，不能下强结论。 */
    MIXED("mixed"),

    /** 没有实例、没有 Context 字段，或反射被 hidden-API 拦住。 */
    UNKNOWN("unknown"),
}

/** 事件通道（listener）的尝试结果。 */
internal enum class ListenerState(val tag: String) {
    NOT_ATTEMPTED("not-attempted"),
    SKIP_ABSTRACT("skip(abstract-class)"),

    /**
     * 注册成功，但本轮**没有**采集到回调值（抽象类造不出子类，也没有采集窗口）。
     * 注册不抛异常本身不构成「必须走事件通道」的证据。
     */
    REGISTERED("registered"),

    /** 只有真正回调出非哨兵值时才允许判成 `listener-required`。 */
    REGISTERED_REAL_VALUE("real-value"),

    ERROR("error"),
}

/**
 * #195 判定所需的证据快照。字段顺序与探针输出顺序一致，便于回看日志。
 *
 * @param storedContext `ctxStored` 的归属结论；[CtxStored.MIXED]/[CtxStored.UNKNOWN] 都会让判定退化。
 * @param realContextCall 真实 context 的对照调用分类。
 * @param engineReadings 同 `BYDAUTO_ENGINE_GET` 权限的 getter 读数（转速首拍 + 冷却液 + 机油 + 超速警告）；
 *   `null` 表示该 getter 抛异常。
 * @param listener 事件通道的尝试结果。
 */
internal data class BydSpeedEvidence(
    val storedContext: CtxStored,
    val realContextCall: RealContextCall,
    val engineReadings: List<Int?>,
    val listener: ListenerState,
)

/** 车型上报的无效哨兵值：8191（转速无效）、65535（链路错误 / 从未取到）。 */
internal val BYD_SENTINEL_VALUES = setOf(8191, 65535)

internal const val VERDICT_SERVER_PERMISSION = "server-permission"
internal const val VERDICT_CONTEXT_SWAPPED = "context-swapped"
internal const val VERDICT_LISTENER_REQUIRED = "listener-required"
internal const val VERDICT_UNCLEAR = "unclear"

/**
 * #195 的定性规则（纯函数，单测钉死）。
 *
 * 口径故意收窄 —— 只回答「是不是服务端把数据挡掉了」，回答不了「数据未就绪/未使能」：
 * 1. 读数缺失 / 有 getter 抛异常 / 出现非哨兵值 → `unclear`（证据链不完整或未复现）；
 * 2. 只有事件通道真的回调出非哨兵值 → `listener-required`（同步 getter 不可信）；
 * 3. `ctxStored=mixed` → `unclear`：wrapper 归属有歧义，任何强结论都不可信；
 * 4. 只有 `realCtxCall=denied`（客户端被 `SecurityException` 挡住）**且** ctx 归属是
 *    wrapper/unknown 时才给 `server-permission`；`ok-sentinel` 无法排除「引擎未就绪」，
 *    一律 `unclear`；
 * 5. 全部可读字段都不是 wrapper 且真实 context 不被拒 → `context-swapped`；
 * 6. 其余 → `unclear`。
 */
internal fun decideSpeedVerdict(evidence: BydSpeedEvidence): String {
    val readings = evidence.engineReadings
    // 没有读数或某个 getter 抛异常：证据链断了，不能定性。
    if (readings.isEmpty() || readings.any { it == null }) return VERDICT_UNCLEAR
    // 出现非哨兵值：本轮的哨兵现象没有复现（或混合），不下结论。
    if (readings.any { it != null && it !in BYD_SENTINEL_VALUES }) return VERDICT_UNCLEAR

    if (evidence.listener == ListenerState.REGISTERED_REAL_VALUE) return VERDICT_LISTENER_REQUIRED
    if (evidence.storedContext == CtxStored.MIXED) return VERDICT_UNCLEAR

    if (evidence.realContextCall == RealContextCall.DENIED) {
        return when (evidence.storedContext) {
            // 客户端检查只被 wrapper 绕过，binder 之后仍拿哨兵 → 只能是服务端拒绝。
            CtxStored.WRAPPER, CtxStored.UNKNOWN -> VERDICT_SERVER_PERMISSION
            // ctx 归属与「真实 context 被拒」互相矛盾，不下结论。
            CtxStored.NOT_WRAPPER, CtxStored.MIXED -> VERDICT_UNCLEAR
        }
    }
    if (evidence.storedContext == CtxStored.NOT_WRAPPER) return VERDICT_CONTEXT_SWAPPED
    return VERDICT_UNCLEAR
}

/**
 * #188 v2 的证据快照：只装「原始 FID 读取 + 订阅前后 + 事件回调」这三类新证据。
 *
 * 各字段为 `null` 表示那条 lane **抛了异常**（不是「读到 0」）；两者必须区分，
 * 否则「反射写错」会被读成「车上没数据」。
 */
internal data class BydSubscribeEvidence(
    /** `AbsBYDAutoDevice.get(1012, ENGINE_SPEED)`，正确设备号。 */
    val rawEngine: Int?,
    /** `get(1003, ENGINE_SPEED)`，复现 `HelperDaemon` / `FidDaemon` 的错误设备号。 */
    val rawLegacy: Int?,
    /** `get(1000, AC_TEMP_MAIN)`，lane 自证：这条链必须能读到非哨兵值。 */
    val rawControl: Int?,
    /** 只订阅转速 FID 之后再读一次。 */
    val afterSubscribe: Int?,
    /** 全量订阅之后再读一次。 */
    val afterSubscribeAll: Int?,
    /** 事件通道收到的原始值（`onDataChanged.getValue`）。 */
    val callbackValues: List<Int>,
    val listener: ListenerState,
)

internal const val SUBSCRIBE_HAS_VALUE = "subscribed-has-value"
internal const val SUBSCRIBE_EVENT_ONLY = "event-only"
internal const val SUBSCRIBE_NO_DATA = "no-data"

/** 读数全无效、且对照 lane 也没通：**不能**判「本车没这个字段」，只能记「测不到」。 */
internal const val SUBSCRIBE_NO_DATA_UNANCHORED = "no-data-unanchored"
internal const val SUBSCRIBE_LANE_BROKEN = "lane-broken"

/** 转速的物理有效区间来自 `ENGINE_SPEED_MIN/MAX = 0/8000`（`temp.java:44-45`）。 */
internal fun isRealRpm(value: Int): Boolean =
    value > 0 && value <= 8000 && value !in BYD_SENTINEL_VALUES

/**
 * 对照 lane 是否自证通过：AC 主温能读到**非哨兵**值。
 *
 * 这里只排除哨兵（8191 / 65535），**不**套转速的 `1..8000` 区间 —— AC 主温的量程是
 * 17..33℃（`BYDAutoAcDevice.java:50-51`），室外温度还能到 -40℃，拿转速区间去检定会在
 * 冬天把正常的对照判成「反射链坏了」。
 */
internal fun isLaneAnchored(control: Int?): Boolean =
    control != null && control !in BYD_SENTINEL_VALUES

/**
 * #188 v2 的定性规则（纯函数，单测钉死）。顺序即优先级：
 *
 * 1. 没订阅成功 → `unclear`：这一轮根本没测到订阅，不能替订阅下结论；
 * 2. 任一 lane 出现真实转速 → `subscribed-has-value`；只有回调有 → `event-only`。
 *    **放在对照检查之前**：读到值本身就是自证的，对照挂了不影响「能读到」这个事实；
 * 3. 一条读数都没拿到（全抛异常）→ `lane-broken`：反射链写错了，不是车上没数据；
 * 4. 有读数但全是 0/哨兵，且对照 lane 自证通过 → `no-data`（字段在本车确实没数据）；
 * 5. 同上但对照 lane 也没通（AC 设备缺失 / 读到哨兵）→ `no-data-unanchored`：
 *    结论仍可用，但证据弱一档，上车要复核；
 * 6. 其余 → `unclear`。
 */
internal fun decideSubscribeVerdict(evidence: BydSubscribeEvidence): String {
    if (evidence.listener != ListenerState.REGISTERED &&
        evidence.listener != ListenerState.REGISTERED_REAL_VALUE
    ) {
        return VERDICT_UNCLEAR
    }

    val visible = listOfNotNull(
        evidence.rawEngine,
        evidence.rawLegacy,
        evidence.afterSubscribe,
        evidence.afterSubscribeAll,
    )
    if (visible.any(::isRealRpm)) return SUBSCRIBE_HAS_VALUE
    if (evidence.callbackValues.any(::isRealRpm)) return SUBSCRIBE_EVENT_ONLY

    // 全部 lane 都抛异常（null）：反射链本身有问题，跟「车上没数据」是两码事。
    val anyLaneRead = visible.isNotEmpty()
    if (!anyLaneRead) {
        return if (isLaneAnchored(evidence.rawControl)) SUBSCRIBE_LANE_BROKEN else VERDICT_UNCLEAR
    }
    return if (isLaneAnchored(evidence.rawControl)) SUBSCRIBE_NO_DATA else SUBSCRIBE_NO_DATA_UNANCHORED
}

/** 解析 `ok:<int>`；`err:` / `skip(...)` 一律 `null`（= 该 lane 没拿到数）。 */
internal fun parseProbeInt(raw: String?): Int? =
    raw?.removePrefix("ok:")?.trim()?.toIntOrNull()

/**
 * 是否应该对这条权限放行（只放 BYDAUTO_ 前缀的客户端检查）。
 *
 * 抽成顶层纯函数的唯一目的：`BYDAUTO_PREFIX` 写错一个字母，所有 getter 会抛
 * `SecurityException`，而实车上看起来和「服务端拒绝」一模一样 —— 必须有单测钉住。
 */
internal fun shouldGrantBydPermission(permission: String?): Boolean =
    permission != null && permission.startsWith(BYDAUTO_PERMISSION_PREFIX)

private const val BYDAUTO_PERMISSION_PREFIX = "android.permission.BYDAUTO_"

/**
 * 绕过 BYDAUTO 客户端权限检查的 ContextWrapper（借自 `BYD-dev/TripLogger` 验证过的做法）。
 *
 * 只影响**客户端**的 `checkPermission` / `enforce*`；服务端 binder 侧的校验不受影响，
 * 这正是探针要区分的东西 —— 如果调用仍然抛 `SecurityException`，说明校验在服务端。
 *
 * 四个入口都要覆盖（`checkPermission` / `checkCallingOrSelfPermission` / `enforce*`），
 * 只拦一个的话 SDK 换个调用点就失效。注意 `checkSelfPermission` 没有 override：
 * SDK 实际用的是 `enforceCallingOrSelfPermission`，`checkSelfPermission` 直通 base，
 * 所以 `permSelf` 与 `permReal` 可能相同，真正的差异在 `permSelf` 行里的 `checkCallingOrSelf`。
 *
 * 参数声明为不可空：SDK stub 上这些参数标了 `@NonNull`，Kotlin 不接受可空覆盖签名
 * （`'checkPermission' overrides nothing`）。放行判定本身在 [shouldGrantBydPermission]
 * 里对 null 做了保护，供非反射调用方复用。
 */
internal class BydPermissionContext(base: Context) : ContextWrapper(base) {
    override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
        if (shouldGrantBydPermission(permission)) PackageManager.PERMISSION_GRANTED
        else super.checkPermission(permission, pid, uid)

    override fun checkCallingOrSelfPermission(permission: String): Int =
        if (shouldGrantBydPermission(permission)) PackageManager.PERMISSION_GRANTED
        else super.checkCallingOrSelfPermission(permission)

    override fun enforcePermission(permission: String, pid: Int, uid: Int, message: String?) {
        if (!shouldGrantBydPermission(permission)) {
            super.enforcePermission(permission, pid, uid, message)
        }
    }

    override fun enforceCallingOrSelfPermission(permission: String, message: String?) {
        if (!shouldGrantBydPermission(permission)) {
            super.enforceCallingOrSelfPermission(permission, message)
        }
    }
}
