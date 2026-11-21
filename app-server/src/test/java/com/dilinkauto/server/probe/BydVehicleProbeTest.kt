package com.dilinkauto.server.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #188 探针的回归网。
 *
 * 探针跑在车机服务进程里，唯一不可接受的行为是**抛异常**（会把连接流程带崩）；
 * 其次是日志格式被改坏（用户在手机上靠 `[BydProbe]` 前缀 grep）。
 *
 * 残留的不覆盖点：`app-server` 没有 Robolectric，`ContextWrapper(null)` 的父类方法在
 * mockable android.jar 上返回默认值（`0` 恰好等于 `PERMISSION_GRANTED`），所以
 * [BydPermissionContext] 四个 override 的「未放行分支」在 JVM 上不可区分。判定逻辑
 * 本身由 [shouldGrantBydPermission] 的单测钉死，override 的接线靠代码评审兜底。
 */
class BydVehicleProbeTest {

    @Test
    fun `missing device class is reported and never throws`() {
        val lines = mutableListOf<String>()
        BydVehicleProbe(
            context = null,
            sink = { lines += it },
            classLookup = { null },
            sleepMs = { },
        ).run()

        assertTrue("必须记录 start：$lines", lines.any { it.contains("start pkg=") })
        assertTrue("必须记录 done：$lines", lines.any { it.contains("done") })
        assertTrue("类缺失要逐设备记下来：$lines", lines.count { it.contains("class=missing") } >= 4)
    }

    @Test
    fun `class present but no context is reported without throwing`() {
        val lines = mutableListOf<String>()
        BydVehicleProbe(
            context = null,
            sink = { lines += it },
            classLookup = { Any::class.java },
            sleepMs = { },
        ).run()

        assertTrue("类可用时必须先报 class=ok：$lines", lines.any { it.contains("dev engine class=ok") })
        assertTrue("没有 Context 时只报 no-context，不得抛异常：$lines", lines.any { it.contains("dev engine instance=no-context") })
    }

    /** 主路径：`getInstance` 成功 + getter 返回真实值，这套格式就是用户明天要读的。 */
    @Test
    fun `successful getInstance and getters render ok values`() {
        val lines = mutableListOf<String>()
        BydVehicleProbe(
            context = android.content.ContextWrapper(null),
            sink = { lines += it },
            classLookup = { FakeDevice::class.java },
            sleepMs = { },
        ).run()

        assertTrue("必须报实例化成功：$lines", lines.any { it.contains("dev engine instance=ok") })
        assertTrue("转速要按 6 拍输出：$lines", lines.count { it.contains("engine speed[") } == 6)
        assertTrue("转速取值格式：$lines", lines.any { it.contains("engine speed[0]=ok:1234") })
        assertTrue("SOC 取值格式：$lines", lines.any { it.contains("statistic soc=ok:86.0") })
        assertTrue("速度必须标 raw：$lines", lines.any { it.contains("speed raw=ok:55.0") })
        assertTrue("空调取值格式：$lines", lines.any { it.contains("ac temp(area=1)=ok:22") })
        assertTrue("必须记录 done：$lines", lines.any { it.contains("done") })
    }

    /** `getInstance` 返回 null 与「实例化成功但 getter 全挂」是两件事，不能让日志看起来一样。 */
    @Test
    fun `getInstance returning null is reported as null`() {
        val lines = mutableListOf<String>()
        BydVehicleProbe(
            context = android.content.ContextWrapper(null),
            sink = { lines += it },
            classLookup = { NullDevice::class.java },
            sleepMs = { },
        ).run()

        assertTrue("必须区分 null：$lines", lines.any { it.contains("dev engine instance=null") })
        assertFalse("不得误报成功：$lines", lines.any { it.contains("dev engine instance=ok") })
    }

    /** 服务端二次校验失败就长这样：异常剥到最内层 cause，一行不抛。 */
    @Test
    fun `getInstance throwing reports the innermost cause`() {
        val lines = mutableListOf<String>()
        BydVehicleProbe(
            context = android.content.ContextWrapper(null),
            sink = { lines += it },
            classLookup = { ThrowingDevice::class.java },
            sleepMs = { },
        ).run()

        val err = lines.firstOrNull { it.contains("dev engine instance=err") }
        assertTrue("必须记录实例化异常：$lines", err != null)
        assertTrue("异常类型与信息要可读：$err", err!!.contains("SecurityException") && err.contains("server denied"))
        assertTrue("异常之后仍要走完其它设备：$lines", lines.any { it.contains("dev ac instance=err") })
        assertTrue("必须记录 done：$lines", lines.any { it.contains("done") })
    }

    /**
     * 断线可中止：探针没有挂起点，取消协程不会打断阻塞循环，必须靠 [BydVehicleProbe] 自己
     * 每拍前问一次；否则旧探针会跑完并往下一个会话继续回传。
     */
    @Test
    fun `abort stops the sampling loop and says where it stopped`() {
        var sleeps = 0
        val lines = mutableListOf<String>()
        BydVehicleProbe(
            context = android.content.ContextWrapper(null),
            sink = { lines += it },
            classLookup = { FakeDevice::class.java },
            sleepMs = { sleeps++ },
            shouldAbort = { sleeps >= 2 },
        ).run()

        assertEquals("断线后不得继续采样：$lines", 2, lines.count { Regex("""engine speed\[\d+]=""").containsMatchIn(it) })
        assertTrue("必须写清停在哪一步：$lines", lines.any { it.contains("aborted at engine speed[2]") })
        assertFalse("中止后不得再打 done：$lines", lines.any { it.contains("done") })
        assertFalse("中止后不得再碰其它设备：$lines", lines.any { it.contains("dev statistic") })
    }

    /**
     * 前缀判定的假绿面：前缀写错一个字母，SDK 所有 getter 都会抛 `SecurityException`，
     * 实车日志看起来与「服务端拒绝」一模一样（这正是探针要回答的问题），必须先钉死。
     */
    @Test
    fun `only BYDAUTO permissions are granted by the wrapper`() {
        assertTrue(
            "engine getter 的权限必须放行，否则探针结论全部无效",
            shouldGrantBydPermission("android.permission.BYDAUTO_ENGINE_GET"),
        )
        assertTrue(
            "AC/statistic 等其它 BYDAUTO 权限也必须放行",
            shouldGrantBydPermission("android.permission.BYDAUTO_AC_COMMON"),
        )
        assertFalse(
            "非 BYDAUTO 权限一律不得放行，否则 wrapper 越权",
            shouldGrantBydPermission("android.permission.CAMERA"),
        )
        assertFalse("null 不得放行", shouldGrantBydPermission(null))
        assertFalse("空串不得放行", shouldGrantBydPermission(""))
    }

    @Test
    fun `every line carries the grep-able tag`() {
        val lines = mutableListOf<String>()
        BydVehicleProbe(
            context = null,
            sink = { lines += it },
            classLookup = { null },
            sleepMs = { },
        ).run()

        assertEquals("探针日志必须以 TAG 开头，否则手机端 grep 失效", "[BydProbe]", BydVehicleProbe.TAG)
        assertTrue("所有行都要带 TAG：$lines", lines.all { it.startsWith(BydVehicleProbe.TAG) })
    }

    /**
     * 探针总开关必须跟着 `BuildConfig.DEBUG` 走（或显式的 `-Pprobe.force=true`）。
     *
     * 这个探针挂在 `CarSessionCoordinator.beginConnect` 上，每次连接都会跑约 3 秒并
     * **订阅 + 注销**车机的发动机事件通道；写死 `true` 就意味着正式包里每次连接都在
     * 跑临时诊断代码。这条断言是那个错误的唯一门禁。
     */
    @Test
    fun `probe is only enabled in debug builds`() {
        assertEquals(
            "ENABLED 必须是 DEBUG 或 PROBE_FORCE，不得写死 true",
            com.dilinkauto.server.BuildConfig.DEBUG || com.dilinkauto.server.BuildConfig.PROBE_FORCE,
            BydVehicleProbe.ENABLED,
        )
    }

    /**
     * #195 对照 1：`getInstance` 之后 SDK 里存的是不是我们传入的 wrapper。
     * 如果被换成 ApplicationContext，wrapper 的作用范围就不覆盖 getter 的权限检查。
     */
    @Test
    fun `stored context field is reported as wrapper`() {
        val lines = runProbe(classLookup = { name -> deviceClassFor(name, StoredContextDevice::class.java) })

        assertTrue("必须逐字段打印并认出 wrapper：$lines", lines.any { it.contains("ctxStored=wrapper=true field=mContext") })
        assertTrue("必须给出聚合结论：$lines", lines.any { it.contains("ctxStored=state=wrapper") })
    }

    /** 反向对照：字段里存的不是 wrapper 时必须报 false，否则「context 被换掉」这条永远看不出来。 */
    @Test
    fun `stored context field that is not our wrapper is reported as false`() {
        val lines = runProbe(classLookup = { name -> deviceClassFor(name, SwappedContextDevice::class.java) })

        assertTrue("字段存在但不是 wrapper 时必须报 false：$lines", lines.any { it.contains("ctxStored=wrapper=false field=mContext") })
        assertTrue("聚合结论不能是 wrapper：$lines", lines.any { it.contains("ctxStored=state=not-wrapper") })
        assertFalse("不得误报成 wrapper：$lines", lines.any { it.contains("ctxStored=wrapper=true") })
    }

    /**
     * #195 对照 1 的假阳面：父类/无关 Context 字段混进来时，「任一字段是 wrapper」会
     * 让 context-swapped 分支永远不可达。必须单独报 mixed 并让判定退化为 unclear。
     */
    @Test
    fun `mixed context fields are reported as mixed`() {
        val lines = runProbe(classLookup = { name -> deviceClassFor(name, MixedContextDevice::class.java) })

        assertTrue("必须逐字段打印：$lines", lines.any { it.contains("ctxStored=wrapper=true field=mContext") })
        assertTrue("必须逐字段打印：$lines", lines.any { it.contains("ctxStored=wrapper=false field=mOther") })
        assertTrue("混合必须单独成态：$lines", lines.any { it.contains("ctxStored=state=mixed") })
        assertTrue("mixed 不得给出强结论：$lines", lines.any { it.contains("verdict=unclear") })
    }

    /**
     * #195 对照 2：wrapper 的 `checkSelfPermission` 不能代表 SDK 实际入口，
     * 必须把 `checkCallingOrSelfPermission` 的结果并列打出来。
     */
    @Test
    fun `permission contrast prints wrapper self check and sdk entry`() {
        val lines = runProbe()

        assertTrue("必须打印 wrapper 视角：$lines", lines.any { it.contains("permSelf=") && it.contains("checkCallingOrSelf=") })
        assertTrue("必须打印真实 context 视角：$lines", lines.any { it.contains("permReal=") })
    }

    /**
     * #195 对照 3：真实 ApplicationContext 重新实例化并调用 getter。
     * 这一行是「应用侧校验只被 wrapper 绕过」与「服务端拒绝」的分水岭。
     */
    @Test
    fun `real context call is classified as sentinel ok`() {
        val lines = runProbe(classLookup = { name -> deviceClassFor(name, StoredContextDevice::class.java) })

        assertTrue("真实 context 调用必须单独成行：$lines", lines.any { it.contains("realCtxCall=ok:8191(sentinel)") })
    }

    /** 真实 context 被客户端权限挡住时要把 SecurityException 打出来，而不是只写 err。 */
    @Test
    fun `real context call reports security exception`() {
        val lines = runProbe(
            classLookup = { name -> deviceClassFor(name, RealDeniedDevice::class.java) },
            realContext = android.content.ContextWrapper(null),
        )

        assertTrue("必须记录 SecurityException：$lines", lines.any { it.contains("realCtxCall=err:SecurityException:") })
    }

    /**
     * #195 对照 4：同权限的其它 getter。若冷却液/机油/超速警告也全是哨兵，
     * 说明不是单个 feature 的问题，更支持「服务端按 uid 拒绝」。
     */
    @Test
    fun `same permission getters are printed for comparison`() {
        val lines = runProbe(classLookup = { name -> deviceClassFor(name, StoredContextDevice::class.java) })

        assertTrue("必须读冷却液：$lines", lines.any { it.contains("engine coolant=ok:8191") })
        assertTrue("必须读机油：$lines", lines.any { it.contains("oil=ok:65535") })
        assertTrue("必须读超速警告：$lines", lines.any { it.contains("speedWarn=ok:8191") })
    }

    /** 第三轮的三条 lane 必须真的出现在日志里，否则手机上 grep 不到就等于没跑。 */
    @Test
    fun `raw lanes are emitted with the documented labels`() {
        val lines = runProbe()

        assertTrue("必须打正确 dev 的原始读数：$lines", lines.any { it.contains("raw[1012]=") })
        assertTrue("必须打旧 dev 对照：$lines", lines.any { it.contains("raw[1003]=") })
        assertTrue("必须打 lane 自证：$lines", lines.any { it.contains("raw[ctl]=") })
        assertTrue("必须给 v2 结论：$lines", lines.any { it.contains("verdict=") })
    }

    /**
     * #195 对照 5：`AbsBYDAutoEngineListener` 是抽象类，Proxy 不可用；
     * 找不到具体 Listener 时必须显式 skip，不能硬造一个假实现让结论失真。
     */
    @Test
    fun `abstract listener class is skipped explicitly`() {
        val lines = runProbe()

        assertTrue("抽象类必须报 skip：$lines", lines.any { it.contains("listener=skip(abstract-class)") })
    }

    // ------------------------------------------------------------------
    // #188 v3（#277/#280）：非 public get 姿势 + tick 同拍组合读 + energyMode
    // ------------------------------------------------------------------

    /**
     * P0 回归：`AbsBYDAutoDevice.get(int,int)` 不是 public（#277 §3），
     * `getMethod` 姿势会整片 `NoSuchMethodException`（上一轮实车三条 raw lane 同报的根因）。
     *
     * [FakeAbsDevice.get] 已改成 protected 复现真实可见性 —— 本用例就是那条教训的门禁：
     * 姿势一旦倒退，`raw[1012]` / tick / energy raw 行全部变 `err:NoSuchMethodException`。
     */
    @Test
    fun `non-public get is reachable through declared method lookup`() {
        val lines = runProbe(classLookup = { name -> deviceClassFor(name, HybridDevice::class.java) })

        assertTrue(
            "protected get 必须照样读到（err:NoSuchMethodException 即姿势倒退）：$lines",
            lines.any { it.contains("raw[1012]=ok:") },
        )
        assertTrue(
            "lane 自证也不能因姿势挂掉：$lines",
            lines.any { it.contains("raw[ctl]=ok:") },
        )
    }

    /**
     * #280 的生产模型预演：typed 发动机、GB FID、前/后电机在**同一拍**里取值，
     * 车上按 `tick[i]` 一行读出四个值的组合，直接对着车况助手同刻显示判读。
     */
    @Test
    fun `tick lines carry engine gb and motor readings together`() {
        val lines = runProbe(classLookup = { name -> deviceClassFor(name, HybridDevice::class.java) })

        assertEquals("tick 必须与转速同 6 拍：$lines", 6, lines.count { Regex("""tick\[\d+] eng=""").containsMatchIn(it) })
        assertTrue(
            "四路读数必须同拍成行：$lines",
            lines.any { it.contains("tick[0] eng=ok:1111 gb=ok:1100 mF=ok:3881 mR=ok:0") },
        )
    }

    /**
     * #280 EV 门控的三条 lane：typed `getEnergyMode()` + raw 双 FID 对照
     * （`ENERGY_MODE_INSTRUMENT` / `_50`），模式值 3 = HEV（门控主态）。
     */
    @Test
    fun `energy mode lanes are emitted for ev gating`() {
        val lines = runProbe(classLookup = { name -> deviceClassFor(name, HybridDevice::class.java) })

        assertTrue("typed energyMode 必须输出：$lines", lines.any { it.contains("energy mode=ok:3") })
        assertTrue("raw[1006,mode] 必须输出：$lines", lines.any { it.contains("raw[1006,mode]=ok:3") })
        assertTrue("raw[1006,mode50] 必须输出：$lines", lines.any { it.contains("raw[1006,mode50]=ok:3") })
    }

    /** 全哨兵 + wrapper 保留 + 真实 context 被 SecurityException 拒绝 → 服务端拒绝。 */
    @Test
    fun `sentinel readings with denied real context conclude server permission`() {
        val lines = runProbe(
            classLookup = { name -> deviceClassFor(name, RealDeniedDevice::class.java) },
            realContext = android.content.ContextWrapper(null),
        )

        // #188 v2 起旧判定改名为 `verdict-legacy=`（它依赖的 realCtxCall 不是有效对照），
        // 新主判据是 `verdict=`（raw lane + 订阅），两者都要能 grep 到。
        assertTrue("必须自动给出结论：$lines", lines.any { it.contains("verdict-legacy=server-permission") })
    }

    /**
     * 墙钟守卫：某次反射卡住时不能把整段车机会话拖死。到点要留一行 deadline 并收尾，
     * 不能静默消失（否则会被误读成「读不到」）。
     */
    @Test
    fun `wall clock budget stops the probe and still wraps up`() {
        var now = 0L
        val lines = runProbe(clockMs = { now += 5_000; now })

        assertTrue("到点必须留证据：$lines", lines.any { it.contains("deadline reached — skip remaining") })
        assertTrue("收尾要给结论：$lines", lines.any { it.contains("verdict=") })
        assertTrue("收尾要写 done：$lines", lines.any { it.contains("done") })
    }

    /**
     * 判定纯函数的分支表。#195 的结论只能靠日志自动化得出，
     * 人眼看「8191 + SecurityException」很容易把两种根因看反。
     */
    @Test
    fun `verdict rules cover every documented outcome`() {
        val allSentinel = listOf(8191, 8191, 65535, 8191)

        assertEquals(
            "真实 context 被 SecurityException 拒绝 + wrapper 保留 → 服务端拒绝",
            "server-permission",
            decideSpeedVerdict(BydSpeedEvidence(CtxStored.WRAPPER, RealContextCall.DENIED, allSentinel, ListenerState.SKIP_ABSTRACT)),
        )
        assertEquals(
            "ctx 归属读不到但真实 context 被拒 → 仍可归到服务端拒绝",
            "server-permission",
            decideSpeedVerdict(BydSpeedEvidence(CtxStored.UNKNOWN, RealContextCall.DENIED, allSentinel, ListenerState.SKIP_ABSTRACT)),
        )
        assertEquals(
            "真实 context 也拿哨兵但没被拒 → 无法排除引擎未就绪",
            "unclear",
            decideSpeedVerdict(BydSpeedEvidence(CtxStored.WRAPPER, RealContextCall.OK_SENTINEL, allSentinel, ListenerState.SKIP_ABSTRACT)),
        )
        assertEquals(
            "SDK 存的不是 wrapper 且真实 context 不被拒 → 先修 wrapper 传递",
            "context-swapped",
            decideSpeedVerdict(BydSpeedEvidence(CtxStored.NOT_WRAPPER, RealContextCall.OK_SENTINEL, allSentinel, ListenerState.SKIP_ABSTRACT)),
        )
        assertEquals(
            "Context 字段混合 → 归属有歧义，不下结论",
            "unclear",
            decideSpeedVerdict(BydSpeedEvidence(CtxStored.MIXED, RealContextCall.DENIED, allSentinel, ListenerState.SKIP_ABSTRACT)),
        )
        assertEquals(
            "仅注册 listener 不够，必须真的回调出真实值",
            "unclear",
            decideSpeedVerdict(BydSpeedEvidence(CtxStored.WRAPPER, RealContextCall.SKIPPED, allSentinel, ListenerState.REGISTERED)),
        )
        assertEquals(
            "事件通道真的回调出真实值 → 需要 listener",
            "listener-required",
            decideSpeedVerdict(BydSpeedEvidence(CtxStored.WRAPPER, RealContextCall.SKIPPED, allSentinel, ListenerState.REGISTERED_REAL_VALUE)),
        )
        assertEquals(
            "出现真实值 → 未复现",
            "unclear",
            decideSpeedVerdict(BydSpeedEvidence(CtxStored.WRAPPER, RealContextCall.DENIED, listOf(1234, 8191, 8191, 8191), ListenerState.SKIP_ABSTRACT)),
        )
        assertEquals(
            "读数缺失 → 证据不足",
            "unclear",
            decideSpeedVerdict(BydSpeedEvidence(CtxStored.WRAPPER, RealContextCall.DENIED, emptyList(), ListenerState.SKIP_ABSTRACT)),
        )
        assertEquals(
            "部分 getter 抛异常 → 证据混杂",
            "unclear",
            decideSpeedVerdict(BydSpeedEvidence(CtxStored.WRAPPER, RealContextCall.DENIED, listOf(8191, null, 8191, 8191), ListenerState.SKIP_ABSTRACT)),
        )
        assertEquals(
            "既不知道 ctx 归属、也没有真实 context 对照 → 不能下结论",
            "unclear",
            decideSpeedVerdict(BydSpeedEvidence(CtxStored.UNKNOWN, RealContextCall.SKIPPED, allSentinel, ListenerState.SKIP_ABSTRACT)),
        )
    }

    /**
     * 对照 lane 的判定不能套转速区间。
     *
     * AC 主温量程是 17..33℃（`BYDAutoAcDevice.java:50-51`），室外温度可到 -40℃。
     * 之前用 `1..8000` 检对照，等于「碰巧对了」：夏天 26 能过，冬天 -5 或 0 会被判成
     * 「反射链坏了」，整轮订阅实验白跑。
     */
    @Test
    fun `lane anchor only excludes sentinels not the rpm range`() {
        assertTrue("26℃ 必须算自证通过", isLaneAnchored(26))
        assertTrue("0℃ 也是合法温度，不能当失败", isLaneAnchored(0))
        assertTrue("室外 -5℃ 同样合法", isLaneAnchored(-5))
        assertTrue("高温 45℃ 合法", isLaneAnchored(45))
        assertFalse("8191 是哨兵，不能算通过", isLaneAnchored(8191))
        assertFalse("65535 是哨兵，不能算通过", isLaneAnchored(65535))
        assertFalse("拿不到对照值不算通过", isLaneAnchored(null))
    }

    /**
     * #188 v2 判定纯函数的分支表。
     *
     * 核心约束：`raw[ctl]`（AC 主温，实车 26℃）是 lane 自证的锚点，但它**只是**削弱证据，
     * 不能让整轮作废——AC 设备缺失时仍要能判 `no-data`，只是标注证据弱（`unanchored`）。
     */
    @Test
    fun `subscribe verdict rules cover every lane outcome`() {
        val registered = ListenerState.REGISTERED
        val sentinelEngine = BydSubscribeEvidence(
            rawEngine = 8191,
            rawLegacy = 8191,
            rawControl = 26,
            afterSubscribe = 8191,
            afterSubscribeAll = 8191,
            callbackValues = emptyList(),
            listener = registered,
        )
        assertEquals(
            "读数全哨兵但对照正常 → 本车无该字段",
            "no-data",
            decideSubscribeVerdict(sentinelEngine),
        )
        assertEquals(
            "原始 FID 直接读到真实转速 → 订阅不是必要条件",
            "subscribed-has-value",
            decideSubscribeVerdict(sentinelEngine.copy(rawEngine = 1650)),
        )
        assertEquals(
            "订阅之后才有真实转速 → 订阅是必要条件",
            "subscribed-has-value",
            decideSubscribeVerdict(sentinelEngine.copy(afterSubscribe = 1650)),
        )
        assertEquals(
            "只有事件回调有真实转速 → 必须走回调通道",
            "event-only",
            decideSubscribeVerdict(sentinelEngine.copy(callbackValues = listOf(0, 1650))),
        )
        assertEquals(
            "0 与负值都不算真实转速",
            "no-data",
            decideSubscribeVerdict(sentinelEngine.copy(afterSubscribe = 0, afterSubscribeAll = -1)),
        )
        assertEquals(
            "没订阅成功这一轮就没测到订阅 → 不能替订阅下结论",
            "unclear",
            decideSubscribeVerdict(sentinelEngine.copy(listener = ListenerState.SKIP_ABSTRACT)),
        )
        assertEquals(
            "所有读数都抛异常但对照正常 → 反射链有问题",
            "lane-broken",
            decideSubscribeVerdict(
                sentinelEngine.copy(
                    rawEngine = null,
                    rawLegacy = null,
                    afterSubscribe = null,
                    afterSubscribeAll = null,
                ),
            ),
        )
        assertEquals(
            "所有读数都抛异常且对照也没通 → 证据链断了",
            "unclear",
            decideSubscribeVerdict(
                sentinelEngine.copy(
                    rawEngine = null,
                    rawLegacy = null,
                    afterSubscribe = null,
                    afterSubscribeAll = null,
                    rawControl = null,
                ),
            ),
        )
        assertEquals(
            "对照 lane 读到哨兵 → 结论降级为证据弱，但仍算读不到数据",
            "no-data-unanchored",
            decideSubscribeVerdict(sentinelEngine.copy(rawControl = 8191)),
        )
        assertEquals(
            "AC 设备缺失（对照为 null）→ 同样降级，不能让整轮白跑",
            "no-data-unanchored",
            decideSubscribeVerdict(sentinelEngine.copy(rawControl = null)),
        )
        assertEquals(
            "对照挂了但确实读到转速 → 读到就是读到，不受对照影响",
            "subscribed-has-value",
            decideSubscribeVerdict(sentinelEngine.copy(rawControl = null, rawEngine = 1650)),
        )
        assertEquals(
            "对照挂了但回调有真实转速 → 事件通道依然成立",
            "event-only",
            decideSubscribeVerdict(sentinelEngine.copy(rawControl = null, callbackValues = listOf(1650))),
        )
    }

    /** `ok:<int>` 才解析成数字；`err:` / `skip(...)` 必须当成「没拿到数」而不是 0。 */
    @Test
    fun `probe int parser keeps failures distinct from zero`() {
        assertEquals(8191, parseProbeInt("ok:8191"))
        assertEquals(0, parseProbeInt("ok:0"))
        assertEquals(1650, parseProbeInt("ok:1650"))
        assertEquals(null, parseProbeInt("err:SecurityException:denied"))
        assertEquals(null, parseProbeInt("skip(no-instance)"))
        assertEquals(null, parseProbeInt(null))
    }

    // ------------------------------------------------------------------
    // 订阅反射链的端到端覆盖（P0：之前这一段是零测试）
    // ------------------------------------------------------------------

    /**
     * 订阅成功 + 回调给出真实转速 → `subscribed-has-value`。
     *
     * 这条用例把 `newListenerProxy` / `registerListener` / `recordCallback` /
     * `hasRealCallback` / 判定函数整条链路串起来。之前只有抽象类 skip 的用例，
     * 那一条恰好**绕开**了新路径（`IBYDAutoListener` 被映射成非接口的假类），
     * 所以订阅代码从没被真正执行过。
     */
    @Test
    fun `subscribing with engine feature id reports a real value`() {
        val lines = runProbe(
            classLookup = { name ->
                when {
                    name == LISTENER_INTERFACE_FQN -> FakeListenerInterface::class.java
                    name.endsWith("AbsBYDAutoEngineListener") -> AbstractEngineListener::class.java
                    else -> SubscribingDevice::class.java
                }
            },
        )

        assertTrue("必须报注册成功：$lines", lines.any { it.contains("sub=registered featureIds=1") })
        assertTrue("必须打回调行：$lines", lines.any { it.contains("cb dev=1012") })
        assertTrue("注销必须成功：$lines", lines.any { it.contains("unsub=ok") })
        assertTrue(
            "回调里有真实转速就必须判成 subscribed-has-value：$lines",
            lines.any { it.contains("verdict=subscribed-has-value") },
        )
        assertFalse("不得误判成没数据：$lines", lines.any { it.contains("verdict=no-data") })
    }

    /**
     * P0-1 回归：`onDataEventChanged(int, BYDAutoEventValue)` 的**值在第二个参数里**。
     *
     * 之前这条分支把值整个丢掉（`value = null, dbl = null`），SDK 若只走这条路，
     * 日志会显示「收到了回调但没有值」，判定给出 `no-data` —— 与事实正好相反。
     */
    @Test
    fun `event value carried by onDataEventChanged is recorded`() {
        val lines = runProbe(
            classLookup = { name ->
                when {
                    name == LISTENER_INTERFACE_FQN -> FakeListenerInterface::class.java
                    name.endsWith("AbsBYDAutoEngineListener") -> AbstractEngineListener::class.java
                    else -> EventValueDevice::class.java
                }
            },
        )

        assertTrue("必须从 intValue 字段读出 1650：$lines", lines.any { it.contains("-> 1650") })
        assertTrue(
            "只有 onDataEventChanged 有值时必须判 event-only：$lines",
            lines.any { it.contains("verdict=event-only") },
        )
    }

    /**
     * `BYDAutoEventValue.INVAILD_INT`（-999999999）是 SDK 的「无值」哨兵，
     * 不能当成一个巨大的负数转速，也不能因为「有值」就把回调计入。
     */
    @Test
    fun `invalid event value sentinel is not counted`() {
        val lines = runProbe(
            classLookup = { name ->
                when {
                    name == LISTENER_INTERFACE_FQN -> FakeListenerInterface::class.java
                    name.endsWith("AbsBYDAutoEngineListener") -> AbstractEngineListener::class.java
                    else -> InvalidEventValueDevice::class.java
                }
            },
        )

        assertTrue("必须留下回调日志：$lines", lines.any { it.contains("cb dev=1012") })
        assertFalse(
            "INVAILD_INT 不得被当成真实转速：$lines",
            lines.any { it.contains("verdict=event-only") || it.contains("verdict=subscribed-has-value") },
        )
    }

    /**
     * 全量订阅会把**其它设备**的事件也打到同一个 listener 上。
     * 空调 26℃、SOC 55 都落在转速的 `1..8000` 区间，不过滤就会刷出假的 `event-only`。
     */
    @Test
    fun `callback from other device is logged but not counted as rpm`() {
        val lines = runProbe(
            classLookup = { name ->
                when {
                    name == LISTENER_INTERFACE_FQN -> FakeListenerInterface::class.java
                    name.endsWith("AbsBYDAutoEngineListener") -> AbstractEngineListener::class.java
                    else -> ForeignEventDevice::class.java
                }
            },
        )

        assertTrue("非发动机回调也要留日志：$lines", lines.any { it.contains("cb dev=1000") })
        assertFalse(
            "空调 26℃ 不得被当成转速：$lines",
            lines.any { it.contains("verdict=event-only") || it.contains("verdict=subscribed-has-value") },
        )
    }

    /**
     * SDK 用 `ConcurrentHashMap<IBYDAutoListener, ArraySet<Integer>>` 存听众，
     * 注册时必然调 `hashCode()` / `equals()`。handler 一律返回 null 会在拆箱成 int 时
     * NPE，`registerListener` 直接抛异常——日志只剩 `listener=err:register(...)`，
     * 整轮订阅实验作废。
     */
    @Test
    fun `proxy answers object methods so map based registration works`() {
        val lines = runProbe(
            classLookup = { name ->
                when {
                    name == LISTENER_INTERFACE_FQN -> FakeListenerInterface::class.java
                    name.endsWith("AbsBYDAutoEngineListener") -> AbstractEngineListener::class.java
                    else -> MapBasedSubscribingDevice::class.java
                }
            },
        )

        assertFalse(
            "hashCode/equals 必须可用，注册不能抛异常：$lines",
            lines.any { it.contains("listener=err:register(") },
        )
        assertTrue("必须注册成功：$lines", lines.any { it.contains("sub=registered featureIds=1") })
    }

    /** 统一构造探针，默认把 listener 类映射为抽象类，模拟实车上的 `AbsBYDAutoEngineListener`。 */
    private fun runProbe(
        classLookup: (String) -> Class<*>? = { name -> deviceClassFor(name, FakeDevice::class.java) },
        context: android.content.Context? = android.content.ContextWrapper(null),
        realContext: android.content.Context? = null,
        clockMs: () -> Long = { 0L },
    ): List<String> {
        val lines = mutableListOf<String>()
        BydVehicleProbe(
            context = context,
            sink = { lines += it },
            classLookup = classLookup,
            sleepMs = { },
            // 生产默认取 applicationContext；mockable android.jar 上取不到，回落到 probe context。
            realContext = realContext ?: context,
            clockMs = clockMs,
        ).run()
        return lines
    }

    /** listener 抽象类走单独的类，设备类由各用例指定。 */
    private fun deviceClassFor(name: String, device: Class<*>): Class<*>? = when {
        name.endsWith("AbsBYDAutoEngineListener") -> AbstractEngineListener::class.java
        name == ABS_DEVICE_FQN -> FakeAbsDevice::class.java
        else -> device
    }

    private companion object {
        /** 与 `BydVehicleProbe` 里的常量保持一致（那边是 private，测试只能复制字面量）。 */
        const val LISTENER_INTERFACE_FQN = "android.hardware.IBYDAutoListener"
        const val ABS_DEVICE_FQN = "android.hardware.bydauto.AbsBYDAutoDevice"
    }
}

/**
 * 假设备：`static getInstance(Context)` 与 getter 签名都照抄 SDK，用来在 JVM 上跑通
 * 反射主路径（getter 名字/参数写错会立刻变成 `err:NoSuchMethodException`）。
 */
internal class FakeDevice {
    fun getEngineSpeed(): Int = 1234
    fun getEnginePower(): Int = 42
    fun getElecPercentageValue(): Double = 86.0
    fun getCurrentSpeed(): Double = 55.0
    fun getTemprature(area: Int): Int = 22
    fun getEnergyMode(): Int = 3

    companion object {
        @JvmStatic
        fun getInstance(context: android.content.Context): FakeDevice = FakeDevice()
    }
}

internal class NullDevice {
    companion object {
        @JvmStatic
        fun getInstance(context: android.content.Context): NullDevice? = null
    }
}

/** 模拟「客户端权限被绕过、服务端仍然校验」的结局。 */
internal class ThrowingDevice {
    companion object {
        @JvmStatic
        fun getInstance(context: android.content.Context): ThrowingDevice? =
            throw SecurityException("client check bypassed, server denied")
    }
}

/**
 * #195 假设备：像 SDH 一样把传入的 context 原样存进实例字段，
 * 并且所有同权限 getter 都返回哨兵值 —— 复现实车上的 `getEngineSpeed()=8191`。
 */
internal class StoredContextDevice(private val mContext: android.content.Context) {
    fun getEngineSpeed(): Int = 8191
    fun getEnginePower(): Int = 0
    fun getEngineCoolantLevel(): Int = 8191
    fun getOilLevel(): Int = 65535
    fun getEngineSpeedWarningValue(): Int = 8191
    fun getElecPercentageValue(): Double = 55.0
    fun getCurrentSpeed(): Double = 0.0
    fun getTemprature(area: Int): Int = 27

    companion object {
        @JvmStatic
        fun getInstance(context: android.content.Context): StoredContextDevice = StoredContextDevice(context)
    }
}

/** #195 假设备：`getInstance` 把 wrapper 换成别的 context，用来验证 `ctxStored=false` 分支。 */
internal class SwappedContextDevice(private val mContext: android.content.Context) {
    fun getEngineSpeed(): Int = 8191
    fun getEnginePower(): Int = 0
    fun getEngineCoolantLevel(): Int = 8191
    fun getOilLevel(): Int = 8191
    fun getEngineSpeedWarningValue(): Int = 8191

    companion object {
        @JvmStatic
        fun getInstance(context: android.content.Context): SwappedContextDevice =
            SwappedContextDevice(android.content.ContextWrapper(context))
    }
}

/**
 * #195 假设备：同时存在两个 Context 字段，一个保留 wrapper、一个被换成普通 Context，
 * 用来锁住「任一字段是 wrapper 就算成功」的假阳面。
 */
internal class MixedContextDevice(
    private val mContext: android.content.Context,
    private val mOther: android.content.Context,
) {
    fun getEngineSpeed(): Int = 8191
    fun getEnginePower(): Int = 0
    fun getEngineCoolantLevel(): Int = 8191
    fun getOilLevel(): Int = 8191
    fun getEngineSpeedWarningValue(): Int = 8191

    companion object {
        @JvmStatic
        fun getInstance(context: android.content.Context): MixedContextDevice =
            MixedContextDevice(context, android.content.ContextWrapper(context))
    }
}

/**
 * #195 假设备：只有 wrapper 才放行，真实 context 直接抛 `SecurityException` ——
 * 复现「客户端权限检查只被 wrapper 绕过」的那一半。
 */
internal class RealDeniedDevice(private val mContext: android.content.Context) {
    fun getEngineSpeed(): Int = 8191
    fun getEnginePower(): Int = 0
    fun getEngineCoolantLevel(): Int = 8191
    fun getOilLevel(): Int = 8191
    fun getEngineSpeedWarningValue(): Int = 8191

    companion object {
        @JvmStatic
        fun getInstance(context: android.content.Context): RealDeniedDevice? {
            if (context !is BydPermissionContext) throw SecurityException("real context denied")
            return RealDeniedDevice(context)
        }
    }
}

/** 模拟 `AbsBYDAutoEngineListener`：抽象类，`java.lang.reflect.Proxy` 无法代理。 */
internal abstract class AbstractEngineListener

/**
 * #188 v3 的混动工况假设备（#277 §7 / #280）：模拟实车「HEV 原地踩油门」——
 * typed 发动机 1111（#195 实车真值）、GB FID 1100（GB 优先 lane）、前电机 3881
 * （#277：车况助手同工况显示前电机 3881）、后电机 0、energyMode=3（HEV）。
 *
 * `get` 保持 protected（与真实 SDK 一致，见 [FakeAbsDevice]）——探针必须走
 * `getDeclaredMethod` + `setAccessible` 才读得到，`getMethod` 姿势一旦倒退，
 * tick/raw/energy 的 raw 行全部变 `err:NoSuchMethodException`，本假设备让它在单测里先红。
 */
internal class HybridDevice : FakeAbsDevice() {
    fun getEngineSpeed(): Int = 1111
    fun getEnginePower(): Int = -5
    fun getEngineCoolantLevel(): Int = 0
    fun getOilLevel(): Int = 12
    fun getEngineSpeedWarningValue(): Int = 1
    fun getElecPercentageValue(): Double = 38.0
    fun getCurrentSpeed(): Double = 0.0
    fun getTemprature(area: Int): Int = 27
    fun getEnergyMode(): Int = 3

    override fun get(device: Int, event: Int): Int = when {
        device == 1012 && event == 0x10D00008 -> 1100
        device == 1012 && event == 0x44100008 -> 3881
        device == 1012 && event == 0x25100008 -> 0
        else -> super.get(device, event)
    }

    companion object {
        @JvmStatic
        fun getInstance(context: android.content.Context): HybridDevice = HybridDevice()
    }
}

/**
 * 模拟真实接口 `android.hardware.IBYDAutoListener`（3 个方法）。
 *
 * 必须是 `interface`：`java.lang.reflect.Proxy` 只能代理接口，而
 * `BYDAutoEngineDevice.registerListener(AbsBYDAutoEngineListener)` 内部就是
 * `super.registerListener((IBYDAutoListener) l)`（`temp.java:287-293`）。
 *
 * 方法名必须与实车接口一致：探针按 `method.name` 分派，名字写错就静默走 `else`。
 */
internal interface FakeListenerInterface {
    fun onDataChanged(event: FakeEvent)
    fun onDataEventChanged(eventType: Int, eventValue: FakeEventValue)
    fun onError(errCode: Int, errMessage: String)
}

/** 模拟 `android.hardware.IBYDAutoEvent`：值通过 getter 暴露。 */
internal class FakeEvent(
    private val dev: Int,
    private val evt: Int,
    private val value: Int,
) {
    fun getDeviceType(): Int = dev
    fun getEventType(): Int = evt
    fun getValue(): Int = value
    fun getDoubleValue(): Double = value.toDouble()
}

/**
 * 模拟 `android.hardware.bydauto.BYDAutoEventValue`：值在**公有字段**里，不是 getter。
 *
 * 必须用 `@JvmField`：Kotlin 的 `val` 默认生成私有字段 + getter，而实车 SDK 里是
 * `public int intValue`（`BYDAutoEventValue.java:7-12`）。若用普通 `val`，探针的
 * `getField("intValue")` 在单测里永远拿不到值，测试会把「假类写错」伪装成「功能正常」。
 *
 * `INVAILD_INT` / `INVALID_DOUBLE` 沿用 SDK 的原样拼写（含 SDK 自己的拼写错误）。
 */
internal class FakeEventValue(
    @JvmField val intValue: Int = INVAILD_INT,
    @JvmField val doubleValue: Double = INVALID_DOUBLE,
) {
    companion object {
        const val INVAILD_INT = -999_999_999
        const val INVALID_DOUBLE = -9.99999999E8
    }
}

/**
 * 模拟 `android.hardware.bydauto.AbsBYDAutoDevice` 的公开面。
 *
 * 必须与真实 SDK 同构（`AbsBYDAutoDevice.java:72-105`）：`registerListener` 有**两个**
 * 重载（带/不带 `int[] featureIDs`）、`get(int,int)` 也在这个基类上。
 *
 * `get` 必须是 **protected**：真实 SDK 的 `get(int,int)` 不是 public（#277 §3：车况助手
 * 反射走 `getDeclaredMethod` + `setAccessible`），之前假类写成 `open`（public）掩盖了
 * `getMethod` 的姿势缺陷 —— 上一轮实车 `raw[1012]=err:NoSuchMethodException` 的单测
 * 假阳面就在这里。
 */
internal abstract class FakeAbsDevice {
    /**
     * `AbsBYDAutoDevice.get(device, event)`：AC 主温给 26（lane 自证），其余哨兵。
     * energyMode（1006）给 3（HEV，#280 的门控主态）。
     */
    protected open fun get(device: Int, event: Int): Int = when {
        device == 1000 && event == 0x40400028 -> 26
        device == 1006 && (event == 0x34200024 || event == 0x34200028) -> 3
        else -> 8191
    }

    open fun registerListener(listener: FakeListenerInterface) = onRegistered(listener, null)

    open fun registerListener(listener: FakeListenerInterface, featureIds: IntArray?) =
        onRegistered(listener, featureIds)

    open fun unregisterListener(listener: FakeListenerInterface) = Unit

    /** 注册被调用时做什么，由各假设备决定（模拟 binder 下发回调）。 */
    protected open fun onRegistered(listener: FakeListenerInterface, featureIds: IntArray?) = Unit
}

/**
 * 订阅后在 `registerListener` 里**同步**回调一次转速 1650。
 *
 * 实车上回调来自 binder 线程、时机不定；这里同步触发是为了让单测确定性地覆盖
 * 「注册 → 回调 → 记录 → 判定」整条链路。
 */
internal class SubscribingDevice : FakeAbsDevice() {
    fun getEngineSpeed(): Int = 8191
    fun getEnginePower(): Int = 0
    fun getEngineCoolantLevel(): Int = 8191
    fun getOilLevel(): Int = 8191
    fun getEngineSpeedWarningValue(): Int = 8191
    fun getElecPercentageValue(): Double = 55.0
    fun getCurrentSpeed(): Double = 0.0
    fun getTemprature(area: Int): Int = 26

    /** 正确 dev 才给真实转速，复现「订阅之后 raw lane 才有值」。可见性随基类（protected）。 */
    override fun get(device: Int, event: Int): Int = when {
        device == 1012 && event == 0x14400012 -> 1650
        else -> super.get(device, event)
    }

    override fun onRegistered(listener: FakeListenerInterface, featureIds: IntArray?) {
        listener.onDataChanged(FakeEvent(dev = 1012, evt = 0x14400012, value = 1650))
    }

    companion object {
        @JvmStatic
        fun getInstance(context: android.content.Context): SubscribingDevice = SubscribingDevice()
    }
}

/** 只走 `onDataEventChanged`（值在 `BYDAutoEventValue.intValue` 字段里）的假设备。 */
internal class EventValueDevice : FakeAbsDevice() {
    fun getEngineSpeed(): Int = 8191
    fun getEnginePower(): Int = 0
    fun getEngineCoolantLevel(): Int = 8191
    fun getOilLevel(): Int = 8191
    fun getEngineSpeedWarningValue(): Int = 8191
    fun getElecPercentageValue(): Double = 55.0
    fun getCurrentSpeed(): Double = 0.0
    fun getTemprature(area: Int): Int = 26

    override fun onRegistered(listener: FakeListenerInterface, featureIds: IntArray?) {
        listener.onDataEventChanged(0x14400012, FakeEventValue(intValue = 1650, doubleValue = 1650.0))
    }

    companion object {
        @JvmStatic
        fun getInstance(context: android.content.Context): EventValueDevice = EventValueDevice()
    }
}

/** 回调只带 SDK 的「无值」哨兵，用来验证哨兵不会被当成转速。 */
internal class InvalidEventValueDevice : FakeAbsDevice() {
    fun getEngineSpeed(): Int = 8191
    fun getEnginePower(): Int = 0
    fun getEngineCoolantLevel(): Int = 8191
    fun getOilLevel(): Int = 8191
    fun getEngineSpeedWarningValue(): Int = 8191
    fun getElecPercentageValue(): Double = 55.0
    fun getCurrentSpeed(): Double = 0.0
    fun getTemprature(area: Int): Int = 26

    override fun onRegistered(listener: FakeListenerInterface, featureIds: IntArray?) {
        listener.onDataChanged(FakeEvent(dev = 1012, evt = 0x14400012, value = FakeEventValue.INVAILD_INT))
    }

    companion object {
        @JvmStatic
        fun getInstance(context: android.content.Context): InvalidEventValueDevice = InvalidEventValueDevice()
    }
}

/**
 * `sub-all` 之后会收到**其它设备**的事件：空调 26℃、SOC 55。
 * 两者都落在转速的 `1..8000` 区间内，不加归属过滤就会刷出假的 `event-only`。
 */
internal class ForeignEventDevice : FakeAbsDevice() {
    fun getEngineSpeed(): Int = 8191
    fun getEnginePower(): Int = 0
    fun getEngineCoolantLevel(): Int = 8191
    fun getOilLevel(): Int = 8191
    fun getEngineSpeedWarningValue(): Int = 8191
    fun getElecPercentageValue(): Double = 55.0
    fun getCurrentSpeed(): Double = 0.0
    fun getTemprature(area: Int): Int = 26

    override fun onRegistered(listener: FakeListenerInterface, featureIds: IntArray?) {
        // 空调设备（dev=1000）的事件，值 26 正好落在转速区间内。
        listener.onDataChanged(FakeEvent(dev = 1000, evt = 0x40400028, value = 26))
    }

    companion object {
        @JvmStatic
        fun getInstance(context: android.content.Context): ForeignEventDevice = ForeignEventDevice()
    }
}

/**
 * 像 SDK 一样把 listener 放进 `HashMap` 存：注册过程会调 `hashCode()` / `equals()`。
 * 代理若对 `Object` 方法返回 null，这里会直接 NPE。
 */
internal class MapBasedSubscribingDevice : FakeAbsDevice() {
    private val listeners = HashMap<FakeListenerInterface, IntArray>()

    fun getEngineSpeed(): Int = 8191
    fun getEnginePower(): Int = 0
    fun getEngineCoolantLevel(): Int = 8191
    fun getOilLevel(): Int = 8191
    fun getEngineSpeedWarningValue(): Int = 8191
    fun getElecPercentageValue(): Double = 55.0
    fun getCurrentSpeed(): Double = 0.0
    fun getTemprature(area: Int): Int = 26

    override fun onRegistered(listener: FakeListenerInterface, featureIds: IntArray?) {
        // 复现 SDK 的 IBYDAutoListenerIdsMap：键的 hashCode/equals 必须可用。
        listeners[listener] = featureIds ?: intArrayOf()
        require(listeners.containsKey(listener)) { "listener lost in map — hashCode/equals broken" }
    }

    override fun unregisterListener(listener: FakeListenerInterface) {
        listeners.remove(listener)
    }

    companion object {
        @JvmStatic
        fun getInstance(context: android.content.Context): MapBasedSubscribingDevice = MapBasedSubscribingDevice()
    }
}
