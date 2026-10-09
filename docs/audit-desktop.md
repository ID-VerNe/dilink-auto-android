# Windows 接收端代码审计报告 — DiLink-Auto

- **审计时间**：2026-10-09
- **审计对象**：`:app-desktop`（Windows 接收端，即本 fork 新增的桌面镜像客户端）
- **代码基线**：`main` @ `da8284f`（工作区仅 `docs/audit-srp-dry-round3.md` 有未提交改动，与本模块无关）
- **范围**：`app-desktop` 全部 49 个源文件（main 33 个 / 3,111 行 + test 16 个 / 2,005 行，合计 5,116 行）+ 其复用的 `:protocol-core` 关键路径（`Connection` / `NioReader` / `FrameCodec` / `Messages` / `VdDeploy` / `VdDeploySequence` / `H264NalParser` / `VideoConfig` / `VdDeployArgs` / `DimAlign` / `LogLine` / `Ports` / `AppPrefs`）
- **不在范围**：手机端 `app-client`、车机端 `app-server`、`vd-server`（仅在需要对照语义时引用）
- **方法**：全文精读；依赖行为以字节码 / 官方文档核对（`javacv 1.5.10` 反汇编、`compose 1.6.2` 互操作规范）；`./gradlew :app-desktop:test :protocol-core:test --rerun-tasks` 强制重跑取证；跨端语义对照（手机侧 `ConnectionService.handleHandshake`、`VdDimensions`、`VdDeploySequence`）
- **边界**：**只读审计，未改动任何代码**。发现编号 `WIN-01…WIN-15`，与 `R3-*`（SRP/DRY）、`UX-*`（UI/UX）编号不混用

---

## 1. 结论摘要

| 严重度 | 数量 | 条目 |
| --- | --- | --- |
| 🔴 HIGH | 2 | WIN-01（切页被视频面板遮住）、WIN-04（「应用并重连」可能直接关窗退出） |
| 🟠 MED | 5 | WIN-02（probe 的 ADB 部署分支 100% 失效）、WIN-03（退出清理竞态）、WIN-05（解码图像实例复用引发的绘制竞争）、WIN-06（等待 VD 无超时/失败无提示）、WIN-07（画面冻结无自愈） |
| 🟡 LOW | 8 | WIN-08…WIN-15（DPI 语义漂移、命令注入面、死代码、显示器枚举、UI 状态不实、文档缺口、视口宽高比、测试缺口） |

**总评**：模块分层干净（协议 / 编排 / 解码 / 渲染 / 部署各归其位，SRP、DRY 前两轮审计的结论在桌面上同样成立），协议复用零漂移，186 个单测全绿。**问题集中在"会话生命周期与 UI 层的胶水"**：三处竞态/失配（WIN-01/03/04）都发生在 `DesktopApp.closeSession()` 与 Compose 组合/进程退出之间，而这两块恰好是当前测试覆盖不到的地方（WIN-15）。建议修复顺序：**WIN-01 → WIN-04 → WIN-02 → WIN-05 → WIN-06/07 → 其余**。

## 2. 模块概览（审计对象）

| 分组 | 文件 | 职责 |
| --- | --- | --- |
| 入口/编排 | `DesktopMain.kt` / `DesktopApp.kt` / `DesktopConnectionService.kt` / `DesktopConfig.kt` / `HandshakeFactory.kt` / `ProbeRunner.kt` / `SessionStatsLogger.kt` | 参数解析、会话代（`Session`）管理、三连接状态机 |
| 视频 | `video/VideoDecodePipeline.kt` / `VideoStreamPipe.kt` / `FeedGate.kt` / `HardwareDecodeWatchdog.kt` / `FrameDumper.kt` | FFmpeg 软/硬解、背压、等 IDR、硬解回退 |
| 呈现/UI | `ui/DesktopWindow.kt` / `SwingVideoView.kt` / `AppGridView.kt` / `DisplaySettings.kt` / `Letterbox.kt` / `Palette.kt` / `UiPrimitives.kt` | Compose 外壳 + Swing 画布、letterbox、坐标换算 |
| 应用目录 | `apps/AppCatalog.kt` / `AppIconStore.kt` | `APP_LIST` → `StateFlow`、PNG 图标缓存 |
| 输入 | `input/InputSender.kt` | 鼠标手势 → `TouchEvent`，导航/电源命令 |
| 部署 | `deploy/AdbDeploy.kt` / `AdbDeployer.kt` / `AdbRunner.kt` / `ProcessAdbRunner.kt` | 无 Shizuku 时用 `adb.exe` 部署 VD server |
| 配置/日志/平台 | `config/*` / `log/DesktopLog.kt` / `display/KeepAwake.kt` / `display/Screens.kt` | `config.json` 读写、日志、防息屏、显示器枚举 |

## 3. 发现

| # | 严重度 | 类别 | 位置 | 现状 | 建议 | 原因 |
| --- | --- | --- | --- | --- | --- | --- |
| WIN-01 | 🔴 HIGH | UI / 平台行为 | `app-desktop/.../ui/DesktopWindow.kt:109-168`（`SwingPanel` `:121-127`，页面 `when(view)` `:130-167`，同处一个 `Box`） | 视频面板用 Swing `SwingPanel` 承载，与 Compose 的「应用」「显示」页写在**同一个 `Box`** 里；Compose 1.6.2 的既定行为是 **SwingPanel 内的 AWT 组件永远浮在 Compose 内容之上**（除非显式开启实验性 `compose.interop.blending`）。仓库全局搜索确认**未设置**该 flag。后果：一旦有活动会话（`session != null` 就会挂上 `fillMaxSize` 的视频面板），点「应用」/「显示」页时网格与设置**被视频面板盖住**，只有未连接时才正常 | 二选一：① 启动时 `System.setProperty("compose.interop.blending", "true")`（1.6.2 支持，实验性、有性能代价，需回归）；② 更小改动——切页时把 Swing 组件藏起来：`videoView.isVisible = (view == DesktopView.MIRROR)`（保持挂载、避开摘挂抖动），或仅在 MIRROR 视图内组合 `SwingPanel` | 这是 JetBrains 官方文档明示的互操作限制（"the Swing component within SwingPanel will always be layered above the Compose Multiplatform component"）。**需一次运行时目视复核**：连上手机后点「应用」页，若能看到网格则本条作废 |
| WIN-04 | 🔴 HIGH | 并发 / 生命周期 | `DesktopApp.kt:220-226`（协程收尾写 `sessionEnded.value = true`）+ `DesktopApp.kt:234-246`（`closeSession()` 顺序）+ `ui/DesktopWindow.kt:64,78-80`（`LaunchedEffect(ended) { onClose() }`） | 「应用并重连」→ `restart()` → `openSession()` → `closeSession()`。而 `closeSession()` **先** `service.stop()`（令旧会话协程立即恢复并写 `sessionEnded.value = true`），**再** `pipeline.stop()`（join 解码线程，典型数毫秒、最坏 2s），**最后**才把 `_session.value` 置 null。在这段窗口里，UI 仍收集着旧会话的 `sessionEnded`，一旦观测到 `true` 就执行 `onClose()` → `exitApplication()` → **用户点「重连」，应用直接退出**（时序相关、非必现） | 会话收尾加"代际守卫"：`closeSession()` 里先 `_session.value = null`（或置 `replacing = true`），协程收尾仅当 `_session.value === session` 时才写 `sessionEnded`；更稳妥的是把自动关窗的判据从"会话结束"改为"会话结束 **且** 未被替换" | 同一段代码在**正常断连**时是设计意图（不留空窗口），在**主动重连**时是误伤——两者共用了一条无区分的信号。修法只需一个标志位 |
| WIN-02 | 🟠 MED | 资源 / 部署 | `ProbeRunner.kt:71-79`（`finally { deployer.close() }`）+ `AdbDeployer.kt:41-89` / `:92` + `ProcessAdbRunner.kt:34-45` | probe 模式的 ADB 部署分支：`deploy()` 返回时 VD server **刚刚启动**（其存活依赖本地 `adb shell` 进程挂着，见 `AdbRunner.kt:16-18` 的硬约束注释），而 `finally` 立刻 `close() → killAll() → process.destroy()` → 本地 adb 进程死 → 设备侧 `exec app_process` 被回收 → 引擎**在绑定 9638/9639 之前就被杀掉**。该分支 100% 不可用，会话随后静默停在 `WAITING_VD` | 把 `close()` 从部署调用点移到**会话真正结束之后**（例如 `runBlocking { service.runSession() }` 返回后、`scope.cancel()` 之前），或让 `ProbeRunner` 复用 `DesktopApp` 的会话级持有语义 | 注释写的是"等会话结束后释放"，代码却在部署返回瞬间释放——意图与实现不符。与 `.plan/windows-client-plan.md` 的待办一致：这条路径从未真机验证过 |
| WIN-03 | 🟠 MED | 资源 / 退出时序 | `DesktopMain.kt:99-101` + `DesktopApp.kt:148-154` | `app.stop()` 只是 `lifecycle.execute { … }`（**异步提交**），下一行就是 `exitProcess(0)`。收尾任务里的 `closeSession()`（含 `pipeline.stop()` 的 join，最坏 2s）与 `adbDeployer.close()` 可能在 JVM 退出前**被截断**：`adb.exe` 子进程在 Windows 上不随父进程退出，可能短暂存活；日志里的"已退出"在部分运行中名不副实 | 让 `stop()` 变成可等待的：`lifecycle.submit { … }.get()`，或 `lifecycle.shutdown(); lifecycle.awaitTermination(...)` 之后再 `exitProcess`；同时给 `ProcessAdbRunner` 加 JVM shutdown hook 兜底 `killAll()` | 影响有上限（JVM 退出会由 OS 关闭控制 socket，手机侧 `ConnectionService` 收到 EOF 后照常 `cleanupSession()` 并回收 VD），但"显式清理"这一契约本身不该靠竞态兑现 |
| WIN-05 | 🟠 MED | 并发 / 绘制 | `video/VideoDecodePipeline.kt:129,180-183` + `ui/SwingVideoView.kt:27-29,62-66` | `Java2DFrameConverter` 在整个解码线程生命周期只 new 一次，**`getBufferedImage(frame)` 复用同一个 `BufferedImage` 实例**（javap 反汇编 javacv 1.5.10 证实：仅当尺寸/type 变化才重新分配）。而 `onImage` → `setFrame(image)` 只做 `@Volatile 引用交换 + repaint()`，实际绘制在 EDT 异步发生——即**解码线程正在改写的那张图，正是 EDT 正在读的那张图**（数据竞争 → 撕裂；`SwingVideoView` 注释里"volatile 引用交换，绘制只在 EDT 上发生"的保护实际失效，因为引用永远不变） | 在**解码线程**完成分离：用 ping-pong 双 `BufferedImage`（`Java2DFrameConverter.copy(frame, target)` 静态方法写入指定缓冲），或每帧 `cloneBufferedImage`/新建后投递（分配代价约 2.7MB/帧 @720p24 → 不建议） | 复用图像是 JavaCV 的设计（省 GC），但本项目把它直接投给了**异步重绘**的 Swing 组件——投递语义必须是"移交所有权"或"拷贝"。当前是共享可变状态 |
| WIN-06 | 🟠 MED | 健壮性 / UX | `DesktopConnectionService.kt:98-99`（`vdPortsBound.await()` 无超时）+ `DesktopApp.kt:253-264`（`AdbDeployer.deploy` 的布尔返回值被丢弃） | 握手被接受后，若手机侧 VD 部署失败（无 Shizuku + PC 未开 `dev_mode` / `adb connect` 失败 / VD 启动异常），手机**不会**回告失败（对照 `app-client`：只在成功时发 `VD_PORTS_BOUND`，见 `ConnectionService.kt:409-419`），而控制口心跳会一直把 TCP 保活 → 接收端**永久停在 `WAITING_VD`**，UI 只有"正在连接 …"，日志之外无任何提示、无超时、无重试 | ① 给 `vdPortsBound.await()` 加超时（如 20–30s，与手机侧部署耗时上限匹配），超时后 `endSession("VD 未就绪")` 并在 UI/日志给出可操作提示；② `deployVdServer` 检查 `deploy()` 返回值，失败即终止会话并提示 | "静默卡住"正是 Phase 1a 已经踩过一次并修掉的坑（连接建立超时），这条路径（等 VD）当时没一起补 |
| WIN-07 | 🟠 MED | 健壮性 / 功能对齐 | `video/VideoDecodePipeline.kt`（无黑屏/冻结检测）vs 车机端 `app-server` 的 `VideoDecoder.onSustainedBlackScreen` + `CarConnectionService.rehandshakeForBlackScreen` | 手机侧编码器若卡死但 TCP 仍通，桌面端会**永远停在最后一帧**（冻结或黑屏），无检测、无重建、无重握手。车机端 v0.18.0-dev-13 已有"持续 5s 微小关键帧 → 重握手"的自愈，桌面端未继承 | 把车机的黑屏判定上移到 `:protocol-core`（与 `H264NalParser` 同一模式，车机/桌面共用），桌面端在 `onImage` 路径做同样的持续判定，触发后走"重建管道 → 等 IDR"，必要时通知手机重握手 | 同一硬件链路的同类故障，两端恢复能力不对等；桌面端已具备 `decoderRebuilds` 计数与重建机制，只缺**触发条件** |
| WIN-08 | 🟡 LOW | 语义漂移 | `config/DesktopSettings.kt:62,78`（`MAX_DPI = 640`，注释称"与手机侧 `VideoConfig` 可接受范围一致"）+ `deploy/AdbDeploy.kt:67-69`（`resolveDpi` 直通不夹紧） | 协议侧 `dpiOverride` 的合法区间是 `[120, 480]`（`VdDeployArgs.DPI_OVERRIDE_MIN/MAX`，手机侧 `VdDimensions.kt:49-50` 会 `coerceDpiOverride`）。桌面端允许 0–640 且 ADB 部署路径**不做夹紧**：同一 UI 值在 Shizuku 路径落到 480、在 ADB 路径原样 640；另外 `dpiOverride=0`（自动）时 ADB 路径直接用 `screenDpi=160`，与手机自动标定（如 1920x1080 → 240）不一致 | 桌面端改用 `VdDeployArgs.coerceDpiOverride` 并让 UI 区间与协议区间对齐（0 或 120–480）；ADB 路径的"自动"建议复用 `VideoConfig.calculateOptimalDpi` | 区间定义在 protocol-core 里已单点化，桌面端却各自写了一份 640 —— 正是前两轮审计反复收敛的那类漂移 |
| WIN-09 | 🟡 LOW | 安全（防御性） | `protocol-core/.../VdDeploy.kt:113`（`CLASSPATH=$jarPath …` 未加引号）+ `deploy/AdbDeploy.kt:45-65` | `jarPath` 取自**对端**握手响应 `vdServerJarPath`，被原样拼进设备侧 shell 命令行。当前手机端固定回常量（`ConnectionService.kt:428-443`，`VdDeploy.JAR_PATH`），实际不可控；但一旦对端是旧版/第三方实现，就是一个设备侧命令注入面（执行身份为 `shell`） | 拼接前做 shell 引用（单引号包裹 + 内嵌引号转义），或校验路径前缀白名单（`/sdcard/`）。**手机端同样在 `commandLine` 单点，改一处即可两端生效** | 部署路径本来就以 `shell` 身份执行 `pkill`，风险等级不高；但"对端可控字符串进 shell"应当显式防御，成本一行 |
| WIN-10 | 🟡 LOW | 死代码 | `apps/AppCatalog.kt:64-68` | `clear()` 生产路径**从未调用**（会话切换靠 new 一个新 `AppCatalog`），只有 `AppCatalogTest.clear_emptiesList` 在用 | 删除 `clear()` 与对应测试，或（若想保留）在 `openSession()` 里显式复用同一实例并调用 `clear()` | 与项目既往"死代码跨模块清理"的口径一致 |
| WIN-11 | 🟡 LOW | 功能完整度 | `ui/DesktopWindow.kt:72`（`val screens = remember { Screens.list() }`） | 显示器列表只在首次组合时枚举一次；拔插外接屏后「显示」页不会更新（`Screens.list()` 本身实现是健壮的，只是调用时机） | 每次进入 DISPLAY 视图时重算（`remember(view) { … }`），或加一个"刷新"动作 | 多显示器切换是 Phase 5a 的新功能，尚未真机验证；低成本即可消掉这个不一致 |
| WIN-12 | 🟡 LOW | UI 状态一致性 | `ui/DesktopWindow.kt:156-159` + `input/InputSender.kt:77-84` | 「点亮手机物理屏」开关先在本地翻转 `phoneScreenOn`，再调 `session.setDisplayPower(on)`；而后者在输入口未连上时会返回 `false`（命令被丢弃），UI 仍显示已切换 | 用返回值回写：`if (session?.setDisplayPower(on) != true) phoneScreenOn = !on`，或在无输入连接时禁用该开关 | 显示值与实际状态漂移，正是"属性 setter 是唯一写入路径"这条既有约定要防的情况（对照 `CarPrefs` 的 KDoc） |
| WIN-13 | 🟡 LOW | 文档 | `README.md`（模块表 / 项目结构）、`docs/README.md`、`docs/architecture.md` | 三处文档**完全没有** Windows 接收端：根 README 的模块表只有 `protocol` / `app-client` / `app-server` / `vd-server`，项目结构图也没有 `app-desktop` 与 `protocol-core`；架构文档中没有"手机 ↔ PC"的链路描述 | 补模块表两行（`protocol-core`、`app-desktop`）、项目结构树、以及桌面端链路（9637/9638/9639 + `connectionMethod` 分流 + ADB 部署）小节；`docs/architecture.md` 至少加一段"接收端可替换"的说明 | 一个已经能 `jpackage` 出 exe 的一等模块，在对外文档里不存在——新用户不知道有这个能力 |
| WIN-14 | 🟡 LOW | 布局 / 观感 | `ui/DesktopWindow.kt:70`（窗口尺寸 = `viewportWidth × viewportHeight` dp）+ `:223`（`RAIL_WIDTH = 96.dp`） | 握手视口不扣导航栏：窗口 1280×720 时视频区实际 1184×720（≈1.64:1），而手机按 16:9 编码 → 画面上下各留约 27px 黑边（触摸映射由 `Letterbox` 兜住，**不影响操作正确性**，只浪费约 8% 面积） | 视口按"窗口宽 − 导航栏宽"上报，或把导航栏做成覆盖层（video 区保持 16:9） | 纯观感问题；若要动，注意与 WIN-01 的修复方向可能重叠（导航栏形态变化会影响 Swing 面板布局） |
| WIN-15 | 🟡 LOW | 测试覆盖 | `app-desktop/src/test/**` | 单测 106 个、质量不错（真实 IO 的集成测试、注入时钟/驱动/runner 都到位），但**三处空白**：① `DesktopApp` 会话代 / `restart` / `stop` 语义（WIN-03/WIN-04 正藏在这里，0 覆盖）；② `ProcessAdbRunner` 的进程持有与 `killAll`（WIN-02 藏在这里，只用 FakeRunner 测了命令序列）；③ UI 层（`DesktopWindow` 组装、切页可见性）；另 `VideoDecodePipeline` 无冒烟测试（真实 FFmpeg 解一帧即可覆盖"等 IDR/重建"链路） | 补齐上述 4 类；优先做 ① 与 ②——它们不需要真实设备，用假时钟 + 假 adb 即可锁定钩子顺序 | 现在是"注入点设计得好，但没人用测试钉住语义"：依赖注入已经为可测性铺好了路，成本很低 |

## 4. 已核对为正常的要点（不作为发现）

避免下轮重复排查，以下内容本次做了核对并确认无问题：

| 关注点 | 核对方式 | 结论 |
| --- | --- | --- |
| 传输层帧顺序（5f 修复） | 精读 `Connection.kt:44-59,117-127,265-274` | 发送侧 `Dispatchers.IO.limitedParallelism(1)` + 接收侧 INPUT/CONTROL 内联 → 触摸与命令严格按调用顺序；仅 DATA 异步（重活） |
| 背压与丢帧策略 | `VideoStreamPipe.kt:30-40`、`FeedGate.kt`、`VideoStreamPipeTest` | 队列满丢最旧块（直播优先），重建重放 CONFIG + 等 IDR 的语义正确且被单测钉住 |
| 硬解回退 | `HardwareDecodeWatchdog`（7 个单测）、`VideoDecodePipeline.fallbackToSoftware` | "起来但零帧"判定 + 单次触发 + 重建走软解，路径自洽；仅"启动即抛异常"与"超时"两路都收敛到同一状态位 |
| 图像泄漏 / FFmpeg 资源 | `decodeLoop` 的 `finally`（`grabber.stop/release` + `pipe.close`） | 每轮重建都释放；`stop()` 关管道 → 读端 EOF → 线程退出（join ≤2s） |
| `Java2DFrameConverter` 构造参数（maximumSize=0） | 代码注释 + `FFmpegFrameGrabber` 语义 | 双参构造禁用 seek 模拟，直播流不会永久阻塞 `start()`（真机踩坑结论，保留正确） |
| `NioReader` 缓冲区增长 / EOF | 精读 + protocol-core 既有测试 | `growIfNeeded` 只对单个读单元扩容、`compact/put` 语义正确；`awaitReadable` 的关闭判定齐全 |
| `KeepAwake` 线程语义 | 8 个单测 + API 语义 | 固定专用线程调用、`close()` 先 disable 再 shutdown、失败降级为 `NoopDriver` —— 正确 |
| 配置读写 | `JsonConfig`（8 单测）+ `DesktopSettingsStore` + `DesktopSettingsTest` | 类型容错、区间夹紧、损坏回退默认值、写失败不阻断启动 —— 与既有约定一致 |
| 输入编码 | `InputSender`（10 单测）+ `LetterboxTest`（7） | 通道分工（INPUT 触摸 / CONTROL 命令）与手机侧 `readTouchAndCommands` 对齐；坐标 clamp 与黑边界定正确 |
| 图标缓存语义 | `AppIconStore`（6 单测） | 空 PNG 沿用旧图（手机 hash 抑制）、解码失败不清旧图 —— 与手机端 `AppListBuilder` 行为吻合 |
| 视图切换保活设计（`SwingPanel` 常驻） | 代码注释 + 组合结构 | 意图正确（避免摘挂抖动），但受平台限制反噬 → 见 WIN-01 |

## 5. 验证（已执行 / 未执行）

**已执行**

| 检查 | 方式 | 结果 |
| --- | --- | --- |
| 源码全量精读 | main 33 文件 + test 16 文件（含全部单测） | 全部发现均带 `文件:行` 与现状代码 |
| 依赖行为核对（关键） | `javap -p -c` 反汇编 `javacv-1.5.10.jar` 的 `Java2DFrameConverter` | 证实 `getBufferedImage(Frame)` 复用实例字段 `bufferedImage`（仅尺寸/type 变化才重建）→ WIN-05 成立 |
| 平台行为核对 | 对照 JetBrains 官方 Swing 互操作文档 + `compose 1.6.2` 变更说明；仓库全局搜索确认未设 `compose.interop.blending` | WIN-01 成立（文档原文：SwingPanel 内组件恒在 Compose 内容之上，需实验性 flag 才可叠加） |
| 测试基线 | `./gradlew :app-desktop:test :protocol-core:test --rerun-tasks` | **186 全绿**（app-desktop 106 / protocol-core 80，failures=0 / errors=0）。与 UI/UX 修复 pass 记录的数字一致 |
| 跨端语义对照 | 手机侧 `ConnectionService.handleHandshake` / `VdDimensions` / `AppListBuilder`；共享 `VdDeploySequence` | 协议字段、通道分工、部署序列无漂移；DPI 区间存在漂移（WIN-08） |
| 部署命令形状 | `AdbDeployTest`（8 断言逐条钉文本）+ 人工复核 `exec app_process` 前台约束 | 与车机路径一致，无回归 |

**未验证（受环境限制，不转化为发现）**

- WIN-01 的运行时目视确认（需连上手机后点「应用」页看一眼，1 分钟）
- WIN-04 的触发概率（时序相关，建议"应用并重连"连点 10 次观察）
- ADB 部署闭环（`adb.exe` + 真机）：与 `.plan/windows-client-plan.md` 待办一致，**从未真机验证**——WIN-02 正因此未被发现
- D3D11VA 硬解的实际启用与回退、多显示器/全屏、本机常亮、手机物理屏开关（`.plan` 待办清单内的真机项，本次仅做静态审计）

## 6. 建议的修复顺序

1. **WIN-01**（用户在 1 分钟内可目视确认；若作废则直接划掉）
2. **WIN-04**（一个标志位即可消除误关窗）
3. **WIN-02 + WIN-03**（同属"部署/退出生命周期"，一次改到位：恢复 probe 的会话级持有 + 让 `stop()` 可等待）
4. **WIN-05**（ping-pong 缓冲，消除绘制竞争）
5. **WIN-06 + WIN-07**（超时与自愈：把"静默卡住"和"永久冻结"变成有反馈的状态）
6. WIN-08…WIN-15（低风险，可与文档同步一轮收口）
7. **WIN-15 的 ①②**建议紧跟 1–3 之后做——它们正是这次三条 HIGH/MED 缺陷的回归防线

---

*审计轮为只读产出（未改动代码）；修复记录见下章。*

---

# 修复状态（2026-10-09 修复 pass）

15 项全部处理：**WIN-01…WIN-14 已修复**；**WIN-15** 的 ①②④ 已补齐（③ UI 层自动化的取舍见下方「附带决定」）。

验证基线：**291 个单测全绿**（protocol-core 95 / app-desktop 122 / app-server 16 / vd-server 34 / app-client 24），`./gradlew test :app-client:assembleDebug :app-server:assembleDebug` 通过。

> 口径说明（与审计轮的 282 对比）：protocol-core **+15**（BlackScreenDetector 11 个用例自 app-server 迁入 + 2 个新增 + shellQuote 2 个）；app-desktop **+16**（4 个新测试类 15 个用例 + AdbDeploy 2 个 − 死代码用例 1 个）；app-server **−22**（BlackScreenDetector 用例迁出，按 debug/release 双 variant 口径少 22）。Android 模块仍按 debug+release 双跑统计。

## HIGH

| # | 状态 | 修复位置 | 做法 |
| --- | --- | --- | --- |
| WIN-01 | ✅ | `ui/DesktopWindow.kt` | 视频面板**只在「镜像」页组合**：`else if (view == DesktopView.MIRROR) { key(current.generation) { SwingPanel(…) } }`。报告备选①（`isVisible=false`）被**字节码取证否决**：解包 `ui-desktop-1.6.2.jar` 确认 `background` 参数画在 `SwingPanel` 自建的**内层 JPanel**（默认 `isOpaque=true`）上，藏 `isVisible` 治不了遮挡；而"只在当前页组合"依赖的反复摘挂是安全的（`Container.addImpl` 在 add 时自动 reparent、`FocusSwitcher` 无全局监听器，均以字节码核对）。不组合期间 `setFrame` 只空转，回页立刻显示最近一帧 |
| WIN-04 | ✅ | `DesktopApp.kt`（`closeSession()` + 会话协程收尾） | 双保险：`closeSession()` **首行**即 `_session.value = null`（UI 立刻停止收集旧代）；协程收尾加代际守卫 `if (_session.value === session) sessionEnded.value = true` —— 只有**未被顶替**的那一代才发结束信号。回归防线：`DesktopAppTest` 两条（正常断连要发 / 被顶替不得发） |

## MEDIUM

| # | 状态 | 修复位置 | 做法 |
| --- | --- | --- | --- |
| WIN-02 | ✅ | `ProbeRunner.kt` | `AdbDeployer` 改为**会话级持有**（实例字段），`finally { deployer.close() }` 从部署调用点移到 `run()` 的会话收尾处 —— 本地 `adb shell` 进程活到会话结束，设备侧引擎不再被提前回收（意图与实现对齐） |
| WIN-03 | ✅ | `DesktopApp.stop()` + `deploy/ProcessAdbRunner.kt` | `stop()` 改为**可等待**：`lifecycle.submit{…}.get(5s)` 之后才 `shutdown()`，`DesktopMain` 的 `exitProcess` 不再截断收尾；`ProcessAdbRunner` 增加 JVM shutdown hook 兜底（`killAllAndAwait` —— `destroy()` 是异步的，不等就"等于没做"）。回归防线：`ProcessAdbRunnerTest` 5 用例（真实进程：持有 / 一次清空 / 超时不持有 / 输出排空 / 无效路径） |
| WIN-05 | ✅ | `ui/SwingVideoView.kt`（帧交接重写）+ `video/VideoDecodePipeline.kt`（契约） | 三条线程各碰各的图：`setFrame`（解码线程）在锁内**立刻拷贝**进自有 `latest`；EDT 取帧时从 `latest` 拷进 EDT 独占的 `paintBuffer`，绘制在锁外进行。`blitInto` 尺寸/类型相同时复用缓冲（≈一次逐行 blit，避开每帧 2.7MB 的分配）。`onImage` 的**借用语义**（"回调返回前必须完成消费"）写入 KDoc。回归防线：`SwingVideoViewTest` 4 用例（源图改写不影响绘制 / 新帧刷新 / 无帧为 null / 尺寸变化重建） |
| WIN-06 | ✅ | `DesktopConnectionService.kt`（`awaitVdPortsBound`）+ `DesktopApp.kt` / `ProbeRunner.kt`（`deployVdServer`） | 等 `VD_PORTS_BOUND` 加 **30s 超时**，并转成普通 `IOException`（`TimeoutCancellationException` 是 `CancellationException` 子类，直接抛出去上层就收不到"会话结束"了）；`deploy()` 返回值不再被丢弃；`dev_mode` 闸门与部署失败一律**抛错终止会话**。回归防线：`DesktopAppTest` 的 dev_mode 用例 |
| WIN-07 | ✅ | 新增 `protocol-core/.../BlackScreenDetector.kt` + `VideoDecodePipeline` 集成 + `DesktopApp.onSustainedBlackScreen` | 判定器**上移 protocol-core**（与 `H264NalParser` 同模式）：`SystemClock.elapsedRealtime()` 改为 `clock: () -> Long` 注入，车机端与桌面端共用一份实现（车机 3s / 桌面 10s 窗口只是 `setSustainWindow` 参数）。桌面端命中后 `restart()` 重建整代会话 —— 手机侧 `handleHandshake` 每次都会拆旧 VD 再建新的，正是编码器/VD 卡死需要的动作。**过触发防护**：进程级重连预算 2 次（检测器的"只升级一次"闩锁按会话重置，只靠它会变成重连风暴），用尽后只记日志、把决定权交回「显示」页 |

## LOW

| # | 状态 | 修复位置 | 做法 |
| --- | --- | --- | --- |
| WIN-08 | ✅ | `config/DesktopSettings.kt` + `deploy/AdbDeploy.kt` + `DesktopApp.restart()` | 三处统一走 `VdDeployArgs.coerceDpiOverride`（删除桌面端自写的 `MAX_DPI=640`）；ADB 路径的"自动 DPI"改用 `VideoConfig.calculateOptimalDpi`（与手机 `VdDimensions` 同函数同结果）；`restart()` 写盘前也夹紧。测试锁定：夹紧边界（9999→480、50→120）与自动标定（1280x720→160、1920x1080 未报 DPI→233） |
| WIN-09 | ✅ | `protocol-core/.../VdDeploy.kt` | 新增 `shellQuote`（单引号包裹 + 内嵌引号 `'\''` 转义），`commandLine()` 的 `jarPath` 与 `logPath` 全部引用。三条部署路径（车机 USB/TCP、桌面 `adb shell`、手机 Shizuku `sh -c`）最终都由 shell 解析命令行 —— 单点修改两端生效；测试对**整条命令行**做精确断言 |
| WIN-10 | ✅ | `apps/AppCatalog.kt` / `AppIconStore.kt` | 删除两个从未被生产调用的 `clear()`（含对应测试），注释说明"跨会话清缓存靠新建实例" |
| WIN-11 | ✅ | `ui/DesktopWindow.kt` | `remember(view) { if (view == DISPLAY) Screens.list() else emptyList() }` —— 每次进入「显示」页重新枚举显示器 |
| WIN-12 | ✅ | `ui/DesktopWindow.kt` + `ui/DisplaySettings.kt` | `setDisplayPower` 返回值回写：只有命令真的进了输入通道才翻转开关；未连上时用 `phoneScreenHint` 说明原因（"输入口尚未连上，请稍后重试"）。另将 DPI 提示文案改挂协议区间（`VdDeployArgs.DPI_OVERRIDE_MIN/MAX`），不再写死数字 |
| WIN-13 | ✅ | `README.md` / `docs/README.md` / `docs/architecture.md` | 根 README 补「Windows 接收端」小节（链路 / 部署分流 / 解码 / 界面 / 自愈 / 两条命令），结构树与模块表补 `protocol-core`、`app-desktop`；docs/README 补索引行与「与 upstream 的差异」两条；architecture 改 six-module，新增「Receiver is replaceable」链路图与两张组件表 |
| WIN-14 | ✅ | `ui/DesktopWindow.kt` | 窗口尺寸改为 `viewportWidth + RAIL_WIDTH` —— 视频区与握手视口同比例，黑边消除 |
| WIN-15 | ◑ | `app-desktop/src/test`（新增 4 类 + 既有更新） | ① `DesktopAppTest`（5 用例，真实 TCP：正常断连发结束信号 / 被顶替不发 / `stop` 返回即收尾完成 / `restart` 夹紧 DPI 并落盘 / dev_mode 部署拒绝终止会话）② `ProcessAdbRunnerTest`（5 用例，真实进程）④ `VideoDecodePipelineSmokeTest`（真实 FFmpeg 端到端：现场编码 H.264 → CONFIG/IDR/P 帧 → `onImage` 出图）。**③（`DesktopWindow` UI 自动化）未做** —— 取舍见下。为可测性加了两个 `internal` 观测点（`ProcessAdbRunner.heldProcesses()`、`SwingVideoView.frameForPaintingForTest()`）|

## 修复 pass 中的附带决定

| 决定 | 原因 |
| --- | --- |
| `BlackScreenDetector` **迁入** protocol-core 而非桌面端复制 | 与 `H264NalParser` 同一 DRY 模式：两端的判定必须只有一份。迁移带入 13 个用例；车机端只改时钟来源（`clock = { SystemClock.elapsedRealtime() }`） |
| 桌面端黑屏窗口 **10s**（车机端 3s） | 桌面命中后的动作是**重连**（中断画面数秒）；且手机**合法地**停在暗色/全黑界面同样持续输出极小 I 帧，长窗口让这类场景基本不命中 |
| 黑屏重连采用**进程级预算 2 次** | 检测器的"只升级一次"闩锁按**会话**重置 —— 只靠它，手机合法显示暗色画面时会一路重连下去。预算用尽后只记日志，把决定权交回用户 |
| `stop()` 的调用约束写入 KDoc | `stop()` 会在 lifecycle 线程上**自等**（不得从该线程调用）；`stop()` 之后实例已终结（`restart`/`setKeepAwake` 会 RejectedExecution）。当前唯一调用点是 `DesktopMain` 窗口关闭后，只调一次 |
| WIN-15 ③（`DesktopWindow` UI 自动化）**不做** | 需引入 Compose UI 测试依赖，且 `Window` + `SwingPanel` 在测试环境的组合行为与真实平台差异大，脆弱性高于收益；WIN-01 的判据保持"真机目视 + 代码结构"（视频面板只有"镜像页"一条组合路径） |
| WIN-15 ④ 用**持续投喂**而非"喂完即等" | 修复 pass 中实测：raw h264 的 `grabber.start()`（流信息探测）在"喂完即止"的小数据量下不会完成 —— 探测需要的"参数就绪"要解码器真的解出画面，且 packet 攒批需要足够字节。测试改为循环重放码流直到出帧，这也正是生产时序：手机**不停**推流，没有"喂完"这一时刻 |

## 验证边界

**已在本地验证**：`./gradlew test :app-client:assembleDebug :app-server:assembleDebug` 通过；291 单测全绿（较修复前净 +9，口径变化见开头说明）；`DesktopAppTest` 用真实 TCP（127.0.0.1）锁定会话代语义（含"旧代收尾后仍不发结束信号"的负向断言）；`VideoDecodePipelineSmokeTest` 用真实 FFmpeg 解出现场编码的 H.264。

**无法在本地验证**（判据仍是"连上手机后能出画面 / 能触摸 / 能旋转 / 能恢复"）：

- WIN-01 的运行时目视：连上手机后切「应用」「显示」页不被视频面板遮挡（1 分钟）；
- WIN-02 的 ADB 部署闭环（`adb.exe` + 真机）—— 与 `.plan/windows-client-plan.md` 待办一致，**从未真机验证**（正因如此它当时才会被漏掉）；
- WIN-04 的连点压力：「应用并重连」连点 10 次观察是否误关窗；
- WIN-07 的黑屏自愈实际触发率与误触发率（合法暗色界面是否会命中 10s 窗口）；
- D3D11VA 硬解、多显示器、`SwingPanel` 反复摘挂在各 Windows 版本下的表现。

建议随下一次 BYD 真机回归一并复测 WIN-01/02/04/07。
