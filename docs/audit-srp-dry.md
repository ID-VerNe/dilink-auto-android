# SRP / DRY 审计报告 — DiLink-Auto

- 审计时间：2026-10-08
- 修订时间：2026-10-08（第二轮：5 个并行 agent 逐条复核 ~90 处行号引用）
- 范围：6 个模块的全部 `src/main`（99 个 Kotlin 文件，15,423 行）+ `src/test`（29 个文件，3,610 行）
  ⚠️ 其中 **2 个模块零测试**（`vd-server` 无 `src/test`；`protocol` 的 `src/test` 为空）—— 明细见 §5
- 模块：`protocol-core`、`protocol`、`app-client`、`app-server`、`vd-server`、`app-desktop`（`settings.gradle.kts` 确认无其他 Kotlin 模块）
- 方法：全量文件行数统计 → 逐文件通读大文件 → grep 跨模块重复模式扫描 → 测试夹具对比 → **第二轮 5 agent 并行逐条复核**
- 说明：代码库此前已做过一轮 DRY/SRP pass（见 README §9），本报告只列**当前仍存在**的问题。

> ⚠️ **第一轮有 5 处结论不成立、约 25 处行号/表述不准、2 处覆盖遗漏，已全部更正并标注。**
> 两类成因：**(A) grep 模式命中即下结论、没读协议语义**；
> **(B) 派生层（§0/§3/§4/§5）没有回头核对正文** —— 尤以 §4 点名了**错误模块的测试**
> （会导致虚假安全感）最严重。详见 §6。
> **结构性判断与优先级排序基本成立，改造动作的相对次序不受影响。**

---

## 0. 结论摘要

| 维度 | 评级 | 说明 |
|---|---|---|
| SRP（职责单一） | ⚠️ 中等偏好 | 两个 Service 是真 god class（`CarConnectionService` 实为 54 函数 + 61 字段）；解码/日志/编排层有小幅职责混入，但抽取趋势健康 |
| DRY（重复） | ⚠️ 中等 | 三端（client/car/desktop）自然形成平行实现；**偶数对齐、握手构造、帧写出、会话编排**四处是实打实的重复 |
| 测试重复 | ✅ 基本良好 | 仅 `ConnectionTest` / `ConnectionStressTest` 共享夹具未抽取 |

**最该动的 5 件事：**

> ⚠️ 第二轮更正：第一轮标"按性价比排序"，但**排序键站不住** —— 它把工作量最大的 SRP-1（2-3 天）
> 排在第一位，而把 0.1-0.5 天的小项往后放。实际是**按严重度**排的，不是性价比。
> 真正的性价比最高的是阶段 A 里的 DRY-1/DRY-4/DRY-6（各 0.5 天，见 §4）。
> 同时第一轮**漏掉了 SRP-2** —— 它是 🔴高、§3 排名第 2 的第二个 god class。

1. `CarConnectionService`（1201 行，54 函数 + 61 字段）拆分 —— 最大单点复杂度与回归风险。
2. `ConnectionService`（866 行）拆分 `AssetDeployer` / `CarInstallCoordinator` / `ClientNotifier` —— 第二个 god class。
3. 偶数对齐统一到 `protocol-core`（现有 **5** 份实现、**3 种**互不相同的取整算法，其中 `navBarWidthPx` 是向上取偶）。
4. `GlPipeline` 复用 `FrameCodec` 的帧头编码/写出 + 码率抽 `AdaptiveBitrate`（同一文件，一并做）。
   **只改 `GlPipeline`** —— lifecycle 通道是裸字节协议，不在重复范围内（见 DRY-3 更正）。
5. `ConnectionService` 内 asset CRC 校验逻辑去重（`extractAsset` 与 `ensureVdServerJarCurrent`）。

---

## 1. SRP 审计

### 1.1 严重：God Class

#### SRP-1 `CarConnectionService` — 1,201 行，54 个函数 + 61 个字段
`app-server/src/main/java/com/dilinkauto/server/service/CarConnectionService.kt`

单个类同时承担以下**互相独立**的职责：

| # | 职责 | 代表成员 |
|---|---|---|
| 1 | WiFi 发现（网关轮询 + mDNS） | `startWifiTrack` L368 |
| 2 | 控制连接建立 + 握手 | `connectToPhone` L397、`connectVideoAndInput` L458 |
| 3 | USB 热插拔 / 权限 / USB ADB | `usbReceiver` L119、`onUsbDeviceAttached` L640、`connectUsbAdb` L658 |
| 4 | 开发模式 TCP ADB 轨道 | `startTcpAdbTrack` L526、`connectTcpAdb` L573 |
| 5 | 连接状态机 | `checkAndAdvance` L330 |
| 6 | VD server 部署编排 | `deployVdServer` L624、`deployVdServerDirect` L1126 |
| 7 | 视频/输入/控制/数据帧分发 | `handleControlFrame` L711、`handleVideoFrame` L762、`handleDataFrame` L783 |
| 8 | 应用列表 + 图标预解码 | `handleDataFrame` 内 `ServerApp.iconCache.prepareAll` L804 |
| 9 | 触摸/应用/媒体动作转发 | `launchApp` L878、`goRecent` L882、`sendMediaAction` L1022 |
| 10 | 旋转重握手 + 黑屏自愈重握手 | `onCarViewportChanged` L905、`rehandshakeForBlackScreen` L927、`rehandshakeOnExistingControl` L946 |
| 11 | 断线重连退避 | `handleDisconnect` L1031 |
| 12 | 前台通知 + WakeLock | `buildNotification` L1146、`acquireWakeLock` L1140 |
| 13 | 设备信息 / 崩溃报告转发 | `onCreate` L183 |
| 14 | 视口尺寸计算（含偶数对齐） | `getViewportSize` L1184（companion） |

**影响**：任何一条轨道（USB、TCP ADB、mDNS、黑屏、通知）的改动都要在 1200 行里定位；状态机依赖大量 `@Volatile` 标志（`wifiReady`/`usbReady`/`usbConnecting`/`tcpAdbConnecting`/`shizukuMode`/`handshakeDone`/`vdServerStarted`，全文共 14 处 `@Volatile`），任何一个初始化路径漏置都会让状态机静默卡死。

> ⚠️ **更正**：第一轮称「代码里已有两处注释记录了这类事故，L546、L594」。经核对，L546 是一个右花括号 `}`、
> L594 是空行（长度 0），**这两行都不存在注释**，行号是误引。
>
> **但报告的结论方向是对的，只是找错了证据位置。** 真正记录这类标志位耦合事故的注释在
> **L952-959**（`rehandshakeOnExistingControl` 开头）：它明确写出 `startWifiTrack` 的网关重试循环
> 以 `!handshakeDone && state == CONNECTING` 为条件，而下面的拆除动作恰好把这两个条件都置位，
> 于是会**竞态调用 `connectToPhone`，在重握手中途断开存活的 control 连接**。
> 这就是"标志位耦合 → 静默故障"的真实记录。
>
> 其他相关注释：L530-533（`usbReady` 未就地恢复会让状态机停在 CONNECTING、UI 显示"连接中"，
> 且下一条 L535 就是恢复动作 —— 二者必须成对看）、L1043-1044（为何 `adbController` 要跨断连存活）、
> L189-198（VD 不出画时的黑屏处置）、L308（"only reset if no ADB instance"）。
>
> **初始化路径实际有 5 条，不是 3 条**：
> `startConnection` L299-309（另在 L1071 之外无）、`handleDisconnect` L1056-1066 **+ L1071**、
> `rehandshakeOnExistingControl` **L973-975**（第 4 条完整重置路径）、
> `usbReceiver` USB 拔出 **L130**、TCP ADB 恢复 **L535** 与 `connectTcpAdb` **L689**。

**建议拆分**（保持对外 API 不变，Service 退化为组合根）：
- `WifiTrack` / `AdbTrack`（USB 与 TCP 各一）—— 各自拥有 ready 标志
- `CarSessionHandshake`（握手构造 + 重握手；`HandshakeFactory` 已是雏形）
- `CarFrameDispatcher`（4 个 handle*Frame）
- `CarNotifier`（通知 + WakeLock）
- `CarStateMachine`（`checkAndAdvance` + 状态流）

分阶段：先抽无状态协作对象（Notifier、FrameDispatcher），再抽有状态的轨道，最后收拢状态机。

#### SRP-2 `ConnectionService` — 866 行
`app-client/src/main/java/com/dilinkauto/client/service/ConnectionService.kt`

职责：asset 解包与 CRC 校验（`deployAssets` L128 → `ensureVdServerJarCurrent` 收于 L228）、网络回调与去抖（`registerNetworkCallback` L230-276，3 秒去抖在 L262-271）、包卸载广播（`registerPackageRemovedReceiver` L96）、IME 快照（`cacheDefaultIme` L79）、mDNS 注册 + 监听循环（`Discovery.registerService` L334 起）、握手 + VD 生命周期通道（`handleHandshake` L411-520）、Shizuku 部署（`startVdServerViaShizuku` L531-588）、app 列表/图标收发（`sendAppInfoData` L681）、车机 app 安装编排（`installCarApp` L606-704）、清理幂等 guard（`cleanupGuard` L724 / `resetCleanupGuard` L727-729 / `cleanupSession` L731-763，`stopEverything` L765-776 只是调用方）、通知/WakeLock（L780-808）。

**建议**：`AssetDeployer`（`deployAssets`/`extractAsset`/`ensureVdServerJarCurrent`/`writeAtomically` = L128-228，**101 行**）、`CarInstallCoordinator`（`installCarApp` L606-704）、`ClientNotifier`（L780-808）。握手处理可进一步拆出 `ClientHandshakeHandler`（对照桌面端 `DesktopConnectionService` 的干净分层）。

> ⚠️ **更正**：第一轮的区间有多处小幅偏差，已按函数真实闭合行修正 ——
> `handleHandshake` 411-**530**→**520**、`startVdServerViaShizuku` 531-**591**→**588**、
> `installCarApp` 606-**680**→**704**（原报**少算 24 行**）、asset 块 128-**229**→**228**、
> `stopEverything` 765-**779**→**776**。另：原文把 `stopEverything` 说成"清理幂等 guard"是**张冠李戴**——
> 幂等 guard 是 `cleanupGuard`/`cleanupSession`（L724/L731-763），`stopEverything` 只是它的调用方。

#### SRP-3 `VideoDecoder` — 472 行
`app-server/src/main/java/com/dilinkauto/server/decoder/VideoDecoder.kt`

一个类里混了：MediaCodec 生命周期 + feed/drain 线程、帧队列的丢弃/优先级策略、**黑屏检测器**（状态字段 L88-91 + L108-109 共 6 个、回调 `onSustainedBlackScreen` L106、检测主体 **L343-370**、重置 `resetBlackScreenState` L112-117）、Surface 切换门控（`switchSurface` L420 / `invalidateSurface` L438）、每 30 帧统计日志（L238）。

黑屏检测的 6 个状态字段 + 1 个回调 + 1 个重置函数构成完整闭环，**只依赖 `SystemClock.elapsedRealtime()` 和 `logW`，不碰 MediaCodec** —— 是干净的纯状态机，抽取无技术障碍。

> ⚠️ **更正**：第一轮给出的 `L113-165、L405-460` 两个区间都不准，且 `L405-460` 是**三种东西的混合**：
> - **L406-412**：帧队列满时的 P-frame 丢弃（不是 Surface 门控）
> - **L415-440**：`switchSurface` / `invalidateSurface`（Surface 门控）
> - **L442-459**：`stop()` 的完整 MediaCodec + feed 线程拆除（也不是 Surface 门控）
>
> 检测逻辑实际在 **L343-370**（L339 是函数头），状态字段与重置在 **L86-117**（L86 起是说明注释）。
> **结论（黑屏检测是可独立抽取的纯状态机）完全成立且证据比原文更强。**

**建议**：黑屏检测抽成 `BlackScreenDetector`（纯状态机，易单测）；队列策略抽成 `FrameQueue`。核心解码器只留 MediaCodec I/O。

### 1.2 中等：职责混入

#### SRP-4 `GlPipeline` — 214 行 ✅ **已修复 → 203 行**
`vd-server/src/main/java/com/dilinkauto/vdserver/GlPipeline.kt`
承担：EGL/GLES 初始化、shader 编译、渲染循环、编码器 drain、**自适应码率控制**（判定行 L163 降码率 / L170 升码率，配套 `applyBitrate` L199 / `requestSyncFrame` L200）、**socket 帧封装与写出**（`writeFrame` L181-184、`writeAll` L189-198）。后两项不是 GL 的职责，且与 `FrameCodec` 重复（见 DRY-3）。

> ⚠️ **更正**：第一轮写的 `L157-176` / `L180-200` 两个区间都不准，且与本文档 DRY-3 的 `L181-198` 自相矛盾。
> 已统一为 `L181-184` / `L189-198`。码率区间也修正为判定行 + helper 完整覆盖
> ——因为"抽 `AdaptiveBitrate`"这个建议要搬走的正是 L163/L170 加上 L199/L200。

#### SRP-5 `PipelineServer` — 523 行
`vd-server/.../PipelineServer.kt`
已拆分出 `GlPipeline`/`TouchInjector`/`DisplayPowerController`/`VirtualDisplayCreator`，剩余部分仍包含：进程生命周期、编码器配置、VD 创建编排（`createVirtualDisplay` L166-181，实为 15 行调度器，真正的 VD 构造在 `VirtualDisplayCreator.kt`）、socket bind/accept、**两个 reader 线程**（`startLifecycleReader` L272、`startTouchReader` L292）、LifeWriter（L408）、watchdog、cleanup 排序、shell 命令路由（`handleCarCommand` L351）、`launchApp` L370 / `checkStackEmpty` L389。行数偏大但主题集中在"进程与连接生命周期"，属可接受范围；若要继续拆，`CarCommandHandler`（`handleCarCommand` L351 / `launchApp` L370 / `checkStackEmpty` L389）是自然切点。

> ❌ **更正**：第一轮称剩余部分含"**三个 reader 线程**"，**不准确 —— 只有两个**。
> 本类的 KDoc L32 自己写的就是 "the lifecycle/touch reader threads"（两个）。
> 第三处 `Thread(` 命中是 watchdog（L308）/ StackCheck（L390）/ LifeWriter（L408）/ Pipeline（L123）/ ShutdownHook（L107），
> 都不是 reader。**成因与 §6 描述的失败模式一致：grep `Thread(` 数命中数。**

#### SRP-6 `FileLog` — 153 行
`app-client/.../FileLog.kt`
日志写入（队列 + 写线程）+ 轮转/清理（`rotate`）+ 打包（`zipLogs`）+ 启用态持久化（`loadEnabled`）+ 目录暴露。轮转与打包含大量文件系统逻辑，与"写一行日志"无关。建议抽 `LogArchiver`。

#### SRP-7 `AppListBuilder` — 131 行
`app-client/.../AppListBuilder.kt`
混合：`PackageManager` 查询/过滤、allowlist 播种（`seedDefaultAllowlist` + `DEFAULT_MAP_PACKAGES`）、图标 hash 抑制、wire 编码。分类逻辑已抽到 `AppCategorizer`（良好），allowlist 播种可继续外移。

#### SRP-8 `AdbDeployer.kt` 文件含 3 个类型
`app-desktop/.../deploy/AdbDeployer.kt`：`AdbRunner` 接口 + `AdbDeployer` + `ProcessAdbRunner`。可拆文件（`AdbRunner.kt` / `ProcessAdbRunner.kt`）以便按需加载与测试。

#### SRP-9 UI 大文件
- `app-server/.../CarLaunchScreen.kt` 590 行：品牌区（`BrandingSection` L199）、连接状态卡（`ConnectionStatusCard` L227-411）、**设置面板**（`SettingSection` L413-414）、WiFi ADB 说明卡（`WifiAdbSetupCard` L515-516）、"如何连接"步骤（`HowToConnect` L539-540）。设置与说明是独立主题，可抽 composable 文件。
- `app-client/.../OnboardingScreen.kt` 557 行：引导流程 + 车机安装步骤。**注意此文件属 app-client，与上面两个 app-server 文件不同模块**。
- `app-server/.../HomeScreen.kt` 391 行。

Compose 单文件多 composable 属常态，此处只标记 `CarLaunchScreen` 混入"设置"这一不同主题。

> 补充（执行时会踩坑）：设置面板**不是独立顶层 composable**，它嵌在 `ConnectionStatusCard`（L227-411）内部，
> 由该卡片在 L336（DPI）/ L361（码率）/ L387（FPS）三处内联调用。
> 抽取不是"把 `SettingSection` 挪到另一个文件"就完事 —— 要连带把 L336-407 的调用点一起拆出来，
> 否则会得到一个仍被父 composable 持有的跨文件组件。

### 1.3 轻微 / 可接受

- `Messages.kt`（492 行）：所有消息类型集中，属协议聚合，可接受。
- `DesktopApp.kt`（310 行）：组合根，职责边界在类注释里已明确声明，可接受。
- `Connection.kt`（363 行）：单连接抽象，职责清晰。

---

## 2. DRY 审计

### 2.1 高价值重复（建议修）

#### DRY-1 偶数对齐（viewport / encode dims）有 **5 份独立实现** ⚠️ ✅ **已修复**
H.264 要求偶数边长，同一语义散落五处，**且是三种不同算法**：

| 位置 | 写法 | 取整方向 |
|---|---|---|
| `app-desktop/.../HandshakeFactory.kt:26-27` | `if (v % 2 == 0) v else v - 1`，再 `coerceAtLeast(2)` | 向下，且保底 ≥2 |
| `app-server/.../CarConnectionService.kt:1189` | `x and 0x7FFFFFFE.toInt()` | 向下（清最低位） |
| `app-server/.../CarConnectionService.kt:1195` | `if (viewport % 2 != 0) targetPx + 1 else targetPx`（`navBarWidthPx`） | ⚠️ **向上** |
| `app-client/.../VdDimensions.kt:36,37,44,45` | `x and 0x7FFFFFFE.toInt()`（4 处） | 向下 |
| `protocol-core/.../VdDeployArgs.kt:40,41` | `x and 0x7FFFFFFE`（2 处） | 向下 |

> ❌ **更正**：第一轮称"4 份"，**漏了第 5 处** ——
> `CarConnectionService.navBarWidthPx` **L1195** 就在已引用的 L1189 **上方 7 行、同一 companion object 内**，
> 而它的唯一目的（见 L25-26 文档注释与 `PersistentNavBar.kt:26`）正是"为 H.264 编码器保证偶数视口"，
> 与其余四处语义完全同源。

三种算法**语义互不相同**，不只是"向下 vs 保底"的差别：
- `evenAlign(0)`→0→**`2`**；`0 and 0x7FFFFFFE`→**`0`**。对 0/1 输入结果不同。
- 负数更发散：`evenAlign(-1)`→-2→clamp 到 **`2`**；`-1 and 0x7FFFFFFE`→**`-2`**（仍是负数）。
- **`navBarWidthPx` 是向上取偶**（+1），与另外两种方向相反 —— 这是最危险的一处，
  因为它不会产生 0/1 边长这种容易被 H.264 拒绝的值，**错误更难被发现**。

应统一到 `protocol-core` 一个 `DimAlign.even(v)` / `evenMin2(v)`，并明确无符号边界与取整方向。

> 已正确复用、无需改动的反例：`app-desktop/.../deploy/AdbDeploy.kt:50-51` 已调用 `evenAlign`，
> 说明桌面端部署路径已经部分收敛到单一来源。

#### ✅ 修复记录 — DRY-4

（2026-10-08 补记：删除 `writeAtomically` 后，`ConnectionService` 实测降到 **810 行**，非 826；
`CarConnectionService` 因新增 `DimAlign` import 变为 1204 行。）

**新增** `app-client/.../service/AssetDeployer.kt`：单一实现
`读 asset → CRC32 → 比对目标 → writeAtomically`，返回 `sealed class Result`（`Current` / `Written` / `Failed`）。
`ensureCurrent` 直接建立在 `extract` 之上。

- `extractAsset` + `ensureVdServerJarCurrent` + `writeAtomically`（原 L128-229，共 101 行）→ 从 `ConnectionService` 移出，
  原地保留 5 行 `ensureVdServerJarCurrent` 委托 + 一行未知态告警
- `deployAssets` 改为调 `assetDeployer.extract(...)`（原 L135-136）
- `ConnectionService` **866 → 826 行**

**顺带修掉一个静默行为**：`ensureVdServerJarCurrent` 原本在**读取 asset 成功、写入失败**时
仍返回正的 `assetCrc`（`crc` 在 try 开头就被赋值，写失败不会改它），
于是日志会打出"看似已部署"的 CRC，而磁盘上其实是旧 jar。
现在写入失败会如实返回 `-1` 并告警"磁盘上的 jar 可能是旧的"。
已确认 `jarCrc` **仅用于日志**（`startVdServerViaShizuku` L573），不是启动门禁，所以语义修正无副作用。

**新增测试** `app-client/.../AssetCrcPolicyTest.kt`（6 例）—— 锁定 CRC 比对策略：
相同字节相等、**单比特翻转必触发重写**、追加/截断内容会被发现、`crcOf(empty) == 0`、
CRC32 对顺序敏感、以及 `writeAtomically` 在真实文件系统上"目标只会以完整新内容被观测、不留 .tmp"。

**验证**：`:app-client:compileDebugKotlin` BUILD SUCCESSFUL；`:app-client:testDebugUnitTest` 10 个测试
中仅 `CarIpLocatorTest.findCarAdb_returnsNullWhenNoControlConnectionAndNoInterfaces` 失败 ——
**该失败是本次改造之前就存在的网络依赖型基线失败**（详见 §6.5），与本次改动无关。

---

#### ✅ 修复记录 — DRY-1

**新增** `protocol-core/.../DimAlign.kt`，5 处调用点全部改为委托：

| 调用点 | 改为 |
|---|---|
| `VdDeployArgs.kt:40-41` | `DimAlign.even(...)` |
| `VdDimensions.kt:36,37,44,45` | `DimAlign.even(...)` |
| `CarConnectionService.kt:1189` | `DimAlign.even(...)` |
| `CarConnectionService.kt:1195`（`navBarWidthPx`） | `DimAlign.offsetForEvenRemainder(screenWidthPx, targetPx)` |
| `desktop/HandshakeFactory.kt:25`（`evenAlign`） | `DimAlign.evenMin2(value)` —— 保留函数与既有调用点，仅改为委托 |

**三种算法各自保留、未强行统一**，因为它们在 0/1 与负数输入上行为互不相同，
且 `AdbDeployTest` / `VdDeployCommandLineTest` 锁定了既有编码命令行。统一的是**实现位置**，不是**行为**。

**修复过程中发现并修掉一个新缺陷**：报告要求的"明确无符号边界"确实必要 ——
`0x7FFFFFFE` 是**无符号掩码**，作用于负 Int 时会清掉符号位，`even(-1)` 返回 **2147483646**（而非 -2）。
桌面端旧实现只是**侥幸**正确：Kotlin 的 `%` 对负操作数返回负值，`-1 % 2 == -1` → floor 到 -2 → clamp 到 2。
若把负数也路由进 `even()`，桌面端会静默变成 2147483646。
故 `evenMin2` 显式短路 `value <= 2 -> 2`，`even()` 的文档也标注了负数行为与"喂给编码器必须用 evenMin2"。

**新增测试** `protocol-core/.../DimAlignTest.kt`（9 个用例，锁定三变体行为 + `offsetForEvenRemainder`
"必须恰好 ±1 且只增不减"的穷举不变量 + 与旧 `evenAlign` 逐值一致）。
`:protocol-core:test` + `:app-desktop:test` 全绿（58 tests）。

#### DRY-2 握手构造有 **2 份实现**
- `app-desktop/.../HandshakeFactory.kt`：`object HandshakeFactory.build(config: DesktopConfig)`（L13-22）
- `app-server/.../service/HandshakeFactory.kt`：顶层 `internal fun buildHandshakeRequest(...)`（L18-39）

字段几乎相同，差异在"设备名/版本号来源"（桌面用 `config.deviceName` L14，车机硬编码 `"DiLink-${Build.MODEL}"` L29 + `PackageManager` 取版本）。两处具体差异（第一轮称"字段集合相同"，略偏）：
- 桌面端**不设 `appVersionName`**；车机端 L35 设了。
- 偶数对齐位置不同：桌面在 `build()` **内部**调 `evenAlign`（L15-16），
  车机端 `buildHandshakeRequest` **不做对齐**（L30-31 原样透传），依赖调用方传已对齐的尺寸
  （`CarConnectionService.getViewportSize` L1189）—— 这正是 DRY-1 里"对齐责任漂移"的实例。

> 注意：两者**不是结构对等的类型** —— 桌面端是 `object`，车机端是顶层 `internal fun`。
> 抽公共层时不能假设"两个同名工厂"，实际上一边是 object、一边是裸函数。

建议在 `protocol-core` 定义 `HandshakeRequest` 的默认构造/builder，两端只填差异字段。

#### DRY-3 帧头编码 + `writeAll` 在 `GlPipeline` 里重复实现 ⚠️（注释已过期） ✅ **已修复**
`FrameCodec`（protocol-core）已有：
- `encodeHeaderInto`（6 字节头布局，**private**）
- `writeFrameToChannel(channel, frame)`（public — 这才是可调用的入口）
- `writeAll(channel, buf)`（5s deadline + 100µs 退避）

`vd-server/.../GlPipeline.kt:181-198` 又手写了一份完全等价的 `writeFrame`（同样的 `fl shr 24/16/8` 头布局）+ `writeAll`（`WRITE_TIMEOUT_NS`/`WRITE_BACKOFF_NS` 在 L68-69，与 `FrameCodec.kt:212-213` 同值复制）。

`GlPipeline.kt:185-188` 的注释称"vd-server 是 shell 模块，不依赖 protocol 模块"——**该理由已过期**，且比第一轮所述更明显：
- `vd-server/build.gradle.kts:25` `implementation(project(":protocol"))`
- `protocol/build.gradle.kts:26` `api(project(":protocol-core"))`
- **`GlPipeline.kt:12` 本身就 `import com.dilinkauto.protocol.*`** —— 它已经在用 protocol 的类

因此可直接调用 `FrameCodec.writeFrameToChannel`。

> ❌ **删除一条错误结论**：第一轮称「同理 `PipelineServer.enqueueResponse`（L470-480）手工拼 lifecycle 帧头，
> 与 `FrameCodec` 布局重复」。**这条不成立，两个错**：
> 1. **行号错**：`enqueueResponse` 在 **L435-443**；L470-480 是 `moveTopApp` 里
>    `dumpsys activity activities` 的正则解析（L468 `Regex("ActivityRecord\{...")`、
>    L473 `execShell("am display move-stack ...")`）。
> 2. **不存在帧头**：lifecycle 通道**双向都是裸字节**，不是 FrameCodec 帧。
>    - 读侧（server→phone 的响应）L277 是 `r.readByteBlocking() and 0xFF == CMD_STOP` 单字节比较；
>      LifeWriter（L405-427）走 `ArrayBlockingQueue(16)` + `ch.write(buf)` + `Thread.sleep(1)` 自旋，
>      **连 `FrameCodec.writeAll` 都没用**，是"无限重试"策略，与 FrameCodec 的 5s deadline 策略本就不同。
>    - 写侧 L437-440 输出 `msgType` + 可选 `putInt(size)` + payload，
>      **没有 4 字节长度前缀、没有 channel 字节**。真实线格式是
>      `[msgType][可选 int32 payloadSize][payload]` —— 长度字段在**消息体内部**，不是帧头。
>    - 旁证（第一轮未引，更强）：`sendDisplayReady` L241 同样用
>      `ByteBuffer.allocate(6); put(MSG_DISPLAY_READY); putInt(displayId); put(flags)` 裸写，
>      手机侧 `VirtualDisplayClient.kt:99-103` 就按这个顺序读。
>
> 若照第一轮改动，会把刻意设计的裸字节协议误改为 FrameCodec 帧格式，
> 同时打断手机侧 `VirtualDisplayClient` 的读取循环（**L135-145**）与 `MSG_STACK_EMPTY`（L138）处理。

#### ✅ 修复记录 — DRY-3 + SRP-4（同一文件，同批完成）

**先建测试网**：按 §4 纪律，`vd-server` 原先**没有 `src/test` 目录**。
已在 `vd-server/build.gradle.kts` 加 `testImplementation("junit:junit:4.13.2")` 并新建测试源集 ——
**`:vd-server:testDebugUnitTest` 从 0 个测试变为 17 个**。

**DRY-3**（`GlPipeline` 帧写出）：
- `writeFrame`（原 L181-184 手写 6 字节头）→ 委托 `FrameCodec.writeFrameToChannel(ch, FrameCodec.Frame(Channel.VIDEO, msgType, payload))`
- **删除** 手写的 `writeAll`（原 L189-198）及本地 `WRITE_TIMEOUT_NS`/`WRITE_BACKOFF_NS`（原 L68-69）——
  deadline 与退避策略现在只有 `FrameCodec` 一处定义
- 删掉那条自相矛盾的注释（它声称本模块不依赖 protocol，而同一文件第 12 行就 import 了它）与随之失效的 `java.io.IOException` import
- `GlPipeline.kt` **214 → 203 行**

**SRP-4**（码率控制）：新增 `vd-server/.../AdaptiveBitrate.kt`，把判定行（原 L163/L170）与
`applyBitrate`/`requestSyncFrame`（原 L199/L200）搬进去，成为不依赖 EGL/MediaCodec/socket 的纯状态机。
`pipelineLoop` 改为喂 `AdaptiveBitrate.onFrameWritten(writeNanos)` 并在需要时 `applyBitrate` + `requestSyncFrame`。

**新增测试**：
- `AdaptiveBitrateTest`（10 例）—— 锁定"降码率单帧即触发 / 升码率需满 2s 干净间隔"的**非对称性**、
  不低于 `MIN_BITRATE`、sync frame 请求为一次性、天花板以下配置抬到地板
- `GlPipelineFrameFormatTest`（7 例）—— 独立于 `FrameCodec` 重推 6 字节头布局与
  `frameLength = 2 + payloadSize`（**不含 4 字节长度字段本身**），锁住 DRY-3 改动前后的线上格式一致

**过程中被自己的测试抓出 1 个 bug**：`AdaptiveBitrate` 的 `var currentBitrate: Int = ceiling`
里，`ceiling` 在属性初始化器中解析到的是**构造参数**而非已 clamp 的字段，导致"天花板低于地板时抬到地板"
失效。已改为 `this.ceiling` 并加注释说明原因 + 补测试。

**验证**：`:vd-server:testDebugUnitTest` 17/17 通过；`:vd-server:assembleDebug`、
`:app-server:assembleDebug`、`:app-desktop:test`、`app-desktop:assemble` 均 BUILD SUCCESSFUL。
>
> ⚠️ 另更正本报告自己的措辞：第一轮（及本文档上一版）说"会打断 `CMD_STOP` 解析"方向不准 ——
> `CMD_STOP` 由**手机写**（`VirtualDisplayClient.kt:172`）、在 server 侧 L277 解析，属 phone→server 方向；
> 而 `enqueueResponse` 是 server→phone。两者共用同一通道但方向相反，
> 被改坏的是**响应方向**（读取循环 + `MSG_STACK_EMPTY`），不是 `CMD_STOP`。

#### DRY-4 `ConnectionService` 内 asset CRC 校验逻辑重复 ✅ **已修复**
`extractAsset`（L172-189）与 `ensureVdServerJarCurrent`（L203-229）各自实现：读 asset → CRC32 → 若目标存在则读文件算 CRC 比对 → `writeAtomically`（L162）。CRC32 比对段（L175-180 与 L208-216）几乎逐句相同。应抽 `extractIfChanged(assetName, target): Long`（返回 CRC），`ensureVdServerJarCurrent` 直接复用。

#### DRY-5 会话编排有 **3 份平行实现**
"控制口连接 → 发握手 → 等 `VD_PORTS_BOUND` → 连 video/input → 断开收尾"：

| 端 | 实现 |
|---|---|
| 桌面 | `DesktopConnectionService.runSession` / `connectVideoAndInput`（最清晰，用 `CompletableDeferred` 表达信号） |
| 车机 | `CarConnectionService.connectToPhone` + `connectVideoAndInput`（回调 + `@Volatile` 标志） |
| 手机 | `ConnectionService.handleHandshake` + `VirtualDisplayClient` 生命周期通道 |

三端协议一致、流程一致（`VD_PORTS_BOUND` 在 `src/main` 出现 **14 处**：桌面 L23/29/51/174、车机 L340/442/727/739/745/902、手机 L454/455/457，另 `MessageType.kt:25` 定义），但状态表达方式各异（`CompletableDeferred` vs `@Volatile` 标志）。桌面版是抽公共抽象的天然蓝本。若短期不抽公共层，至少把"端口号/消息序列/超时"约束收敛到 protocol-core 的文档与常量。

> ⚠️ **更正**：第一轮称"`VD_PORTS_BOUND` 共出现 21 处"。21 是**含 `src/test`** 的总数
> （其中 7 处在 `DesktopConnectionServiceTest.kt`）。`src/main` 内是 **14 处**，
> 与本节列举的四个生产端一致。

#### DRY-6 通知 + WakeLock 在 client / car Service 中重复
`ConnectionService`（L780-808）与 `CarConnectionService`（L1140-1158）各有一份 `acquireWakeLock` / `buildNotification` / `updateNotification`。实测差异比第一轮列的多两项：
- channel id（`ClientApp.CHANNEL_SERVICE` vs `ServerApp.CHANNEL_SERVICE`）
- WakeLock tag（`"DiLinkAuto::ConnectionService"` vs `"DiLinkAuto::CarConnectionService"`）
- 通知 id（各自的 `NOTIFICATION_ID`）
- 手机端多一个 Stop action（L789-793）
- **手机端 `updateNotification` 有 `try/catch`（L804-807），车机端没有（L1155-1158）**

可抽一个最简 `ForegroundNotifier`（Android 侧可放 `protocol` 模块，或各自模块内部的同构小类）。

#### DRY-7 WiFi 网关 IP 查询有 3 个调用点
三处都调用 `WifiGatewayIp.format(dhcpInfo.gateway)` 且都包在 `try/catch` 里（✅ 属实）。
但"3 处都做 `getSystemService(WIFI_SERVICE) as WifiManager`"**不准确**：

| 调用点 | 取 WifiManager 的方式 | 行号 |
|---|---|---|
| `CarConnectionService.getWifiGatewayIp` | `applicationContext.getSystemService(WIFI_SERVICE) as WifiManager` | L1120-1121 |
| `ConnectionStatusScreen.ManualConnectBox` | `context.getSystemService(WIFI_SERVICE) as WifiManager` | L131-132 |
| `CarIpLocator` | **注入的** `@Volatile var wifiManager`（L119，由 `ConnectionService.kt:71` 在启动时赋值，L117 有注释说明是为避免静态 Service 引用） | L106-107 |

即真正重复的只是 **2 处**的「取 WifiManager + 兜异常」，`CarIpLocator` 已通过注入解耦。
`WifiGatewayIp` 已抽出了格式化，只差把那 2 处的取用收进去。

### 2.2 中等 / 低价值重复

#### DRY-8 日志格式在多端重复
- `FileLog`：`[$ts][$level][$tag] $msg`
- `CarLogWriter`：`[$ts][$level] $msg`
- `DesktopLog`：`$ts [$level] [$tag] $msg`

三端各写一遍时间戳格式化（`FileLog` 用 `SimpleDateFormat("HH:mm:ss.SSS")` L35、`CarLogWriter` 用 `DateTimeFormatter.ofPattern("HH:mm:ss.SSS")` L47、`DesktopLog` 用 `DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")` L67）。平台日志器分离是合理的（Android util.Log / 文件 / 控制台）。

注意：**三端时间戳精度不同**（桌面端带日期，手机/车机只有时分秒），
所以共享的只能是 `formatLine(ts, level, tag, msg)` 这类**纯拼接**函数，
时间格式化本身因精度需求不同不宜强行统一。

#### DRY-9 测试夹具 `createConnectedSockets()` 逐字重复 ✅ **已修复**
`protocol-core/.../ConnectionTest.kt:16-27` 与 `ConnectionStressTest.kt:18-29` 的 `createConnectedSockets()` **逐字节相同**（12 行）。抽到测试 `TestSockets.kt` 或公共基类。

> ✅ **已修复**：新增 `protocol-core/src/test/.../TestSockets.kt`（`internal object TestSockets.createConnectedSockets()`），
> 两个测试类各改为一行委托。夹具的 accept-then-close 顺序现在只有一处定义。

#### DRY-10 `Discovery` 只是 `Ports` 的转发壳
`protocol/src/main/java/com/dilinkauto/protocol/Discovery.kt:29-33` 把 `Ports.DEFAULT_PORT/VIDEO_PORT/INPUT_PORT/LIFECYCLE_PORT/ADB_PORT` 再声明一遍。

> ⚠️ **补正建议的力度**：第一轮建议"标注 `@Deprecated` 或直接让调用点用 `Ports`"，**但没注意到代码已经自我说明**。
> `Discovery.kt:27-28` 已写明：*"协议端口常量的唯一来源已移到平台无关的 :protocol-core（见 [Ports]），
> 这里保留同名别名以免改动三端的大量历史调用点，取值不重复定义。"* `Ports.kt:9-10` 有对称说明。
> 且 `Discovery.*` 在两文件之外确有 **26 处**调用点，**"大量历史调用点"属实**。
>
> 因此这更接近**已记录的有意决策**而非疏忽。真正该做的只是：要么保持现状并在报告里承认它是有意为之，
> 要么做一次**机械重命名**（26 处，不改语义）并保留 `Discovery` 的 `@Deprecated` 转发。
> **不建议**在 26 处调用点迁移前就先贴 `@Deprecated` —— 那会制造 26 个新告警却不带来任何收益。

#### DRY-11 图标"哈希未变则跳过"的抑制语义两端各写一遍
手机端 `AppListBuilder.lastSentIconHash`（L33 声明 / L56-72 比对，按 `lastUpdateTime` hash 决定是否发图标字节）与桌面端 `AppCatalog.onAppList` 的 `if (entries != _apps.value) _apps.value = entries`（L53-54，注释说明手机端会重发图标、相同数据不重复计算/存储）表达的是同一优化意图，但实现/判据不同（hash 缓存 vs 整列表引用比较）。不影响正确性，标记为概念重复。

#### DRY-12 `CarConnectionService` 内 USB 就绪标志重置逻辑重复 ✅ **已修复**
真正重复的只有 4 行 —— `usbAdb` 就绪判定块：
```kotlin
// startConnection L306-309      与    handleDisconnect L1059-1062
if (usbAdb?.isConnected != true) {
    usbReady = false
    if (usbAdb == null) usbConnecting = false
}
```

> ⚠️ **更正**：第一轮称两处**都**写了下面这一块并整体引用，**不准确**：
> ```kotlin
> if (adbController?.isConnected == true) usbReady = true   // ← 只在 handleDisconnect L1064-1066
> ```
> 这一行**只存在于 `handleDisconnect`**，`startConnection` 里没有（因为 `startConnection` 要重置状态，
> 不能保留 TCP ADB 就绪）。第一轮引用的整块代码因此并不存在于两处。

结论不变但**价值下调**：重复的是 4 行标志赋值，不是整段状态恢复逻辑。仍值得抽 `resetAdbReadiness()`，
因为这两处的 `if (usbAdb == null) usbConnecting = false` 注释含义已在两处漂移
（L308 有"only reset if no ADB instance (auth may be pending)"，L1061 无注释）。

#### ✅ 修复记录 — DRY-12 + DRY-13

**新增** `CarConnectionService.resetAdbReadiness()`（原 L307-310 与 L1057-1064 的共有部分）：

- `startConnection` 与 `handleDisconnect` 各改为一行调用
- 抽取时把**只在 `startConnection` 里的那条注释**（"auth may be pending"）保留下来并补全 ——
  这正是两处漂移的证据，现在注释和代码在一个地方，不会再各自退化
- KDoc 明确写出**两个刻意不处理的情况**：USB 仍连着时不清（跨重连保留物理设备状态）；
  不碰 TCP ADB controller（`handleDisconnect` 单独用 `adbController.isConnected` 恢复 `usbReady`，
  而 `startConnection` 若也做会撤销自己刚触发的重启）

> 📌 **行数反而增加了（如实记录）**：`CarConnectionService` 从 1204 → **1237 行**（+33）。
> 这不是倒退 —— 抽出 `resetAdbReadiness()` 消除了 4 行重复、为 `disconnectAllConnections`
> 增加了 `clearListeners` 分支，而两处调用点各减 3~4 行。
> 净增的是**文档与注释**：`resetAdbReadiness` 的 KDoc 写明了两个"刻意不处理"的情况
> （USB 仍连着时不清、为什么不碰 TCP ADB controller），`disconnectAllConnections` 增加了参数 KDoc，
> `rehandshakeOnExistingControl` 补了"control 留给重握手用"的说明。
> 函数数 54 → **55**（新增 `resetAdbReadiness`），`@Volatile` 仍为 **14**。
> **DRY-12/DRY-13 的收益是可维护性而非行数**，且按 §3 原本就只估 0.1 天/项。

**DRY-13**：`disconnectAllConnections` 增加 `clearListeners: Boolean = false` 参数，
把 `rehandshakeOnExistingControl`（原第 2 处内联副本，L993-998）的
`clearDisconnectListener` + `disconnect + null` 序列收敛进同一个函数。

> ⚠️ **有意保留的语义差异**：`rehandshakeOnExistingControl` **不拆 control 连接**
> （它复用现有 control 做重握手），而 `disconnectAllConnections` 会拆。
> 因此**没有**把前者改成调用后者 —— 强行统一会在重握手中途断掉正在用的 control。
> 只统一了 video/input 这两条腿与 listener 清理，`control` 的差异保留为显式参数化语义。
> 同时给该处补了注释说明"只拆 video/input，control 留给重握手用"。

> 📌 **补充（第二轮对抗性复核发现）**：字面上的 3 行构造只出现在 L1064-1066，
> 但**语义上还有第 3 处**同样的意图 —— `startTcpAdbTrack` **L529-538**：
> `if (adbController?.isConnected == true && lastAdbHost == phoneHost)` → L534-535 `if (!usbReady) { usbReady = true }`，
> 且它自己的注释 L530-533 明确是在解释为何要就地恢复（直接引用了 `startConnection` 的重置行为）。
> 另有 4 处 `usbReady = true` 写入点：L535、L599、L689、L1065。
> 所以准确的表述是：**字面重复 1 处、语义重复 2 处**（不是我先前写的"只有 4 行"）。

#### DRY-13 `disconnect + null` 序列有 **2 处**内联重复 ✅ **已修复（部分统一）**
`video/input` 的 `disconnect + null` 序列写了 2 遍：
- `disconnectAllConnections`（L492-499）—— video + input + **control**
- `rehandshakeOnExistingControl`（L966-969）—— video + input（**不含 control**，因为它复用现有 control 连接），
  且前置了 `clearDisconnectListener`（L966-967）

> ❌ **更正**：第一轮称「在 `disconnectAllConnections`、`rehandshakeOnExistingControl`（L965-968）、
> `handleDisconnect` 中**各写一遍**」，**不准确**。`handleDisconnect` 并未内联该序列，
> 它**调用**了 `disconnectAllConnections()`（**L1049**）；另有 L1018、L1104 也都是调用而非复制。
> 即只有 **2 处**内联，且两处覆盖的连接集合本就不同（control 的处理差异是语义性的，不是可参数化的冗余）。

因此「可参数化」的收益很小，**建议降级为纯备注**：真正值得做的是把
`clearDisconnectListener` + disconnect 的组合也收进 `disconnectAllConnections` 的一个参数变体里。

> ✅ **已按上述思路部分完成**：`disconnectAllConnections(clearListeners: Boolean = false)` 已加入该参数，
> listener 清理与 `disconnect + null` 序列收敛到单一实现。
> 但**有意未把 `rehandshakeOnExistingControl` 改成调用它** —— 该函数复用现有 control 连接，
> 而 `disconnectAllConnections` 会拆掉 control；强行统一会在重握手中途断掉正在用的连接。
> 两处 video/input 的公共部分已通过参数化表达，control 的语义差异保持显式。

### 2.3 已做得好的地方（不该动）

- `VdDeploy` / `VdDeployArgs` 把三端部署命令的单点来源收敛得很好（`killCommand` L50 / `stopCommand` L67 /
  `probeCommand` L76 / `launchCommand` L126；三端 16 个非测试文件共用）。其"偶数对齐"是 `and 0x7FFFFFFE`
  位掩码 —— ⚠️ **它不是 clamp，也不保证 ≥2**，与桌面端 `evenAlign` + `coerceAtLeast(2)` 语义不同（正是 DRY-1）。
- `AppCategorizer`（33 行）、`AppVersion`（44 行）、`NetUtil`（33 行）都是从大文件里正确抽出的协作对象。
- ⚠️ **更正**："`HandshakeFactory`（服务端）"**措辞有误导** —— app-server 里**没有 `HandshakeFactory` 这个类型**，
  `service/HandshakeFactory.kt`（39 行）只有顶层 `internal fun buildHandshakeRequest`（L18）。
  只有 desktop 有 `object HandshakeFactory`。原文把它当"抽出的协作对象"举例，但它是**函数**不是对象。
- `PipeLog`（`internal object PipeLog`，L15）与 `ShellExec`（**声明在 `PipeLog.kt` L29 内，没有独立的 ShellExec.kt**）
  消除了 vd-server 四个文件里的重复 log/err：`PipelineServer`、`GlPipeline`、`TouchInjector`、`DisplayPowerController`
  （这四个文件由 `PipeLog.kt:7` 自己列明）。
  - `PipeLog`（log/err 去重）调用点：`TouchInjector.kt:58`、`DisplayPowerController.kt:112`、
    `GlPipeline.kt:63-64`、`PipelineServer.kt:102-103`
  - `ShellExec` 调用点：`TouchInjector.kt:118`、`DisplayPowerController.kt:113`、`PipelineServer.kt:144`
  - ⚠️ 第一轮把 `ShellExec` 的行号当成了 log/err 去重的证据，且**漏掉了第 4 个文件 `GlPipeline.kt`**
    （列成了定义文件 `PipeLog.kt` 本身）。
- protocol-core 已把 `H264NalParser`（34 行）、`WifiGatewayIp`（27 行）、`ImeRestore`（49 行）等跨端逻辑下沉。
- `CarIpLocator.wifiManager` 用注入解耦，避免了静态 Service 引用（`CarIpLocator.kt:117-119`，全文无 `getSystemService`）——做法正确。

> 📌 **附带发现（非本报告范围，但影响判断）**：README §9 声称「大文件拆分（`CarConnectionService`
> 1237 → **1137** 行）」，实测当前是 **1201 行**。README 的基线数字已过期，
> 也就是说这个文件在"拆分完成"后又长回了 64 行 —— **SRP-1 判定因此比 README 描述的更严重**。
> README 的数字也需一并更正。

---

## 3. 问题清单（含优先级）

| ID | 类型 | 位置 | 严重度 | 预估工作量 |
|---|---|---|---|---|
| SRP-1 | God class | `CarConnectionService.kt` (**1232**) | 🔴 高 | 2-3 天 |
| SRP-2 | God class | `ConnectionService.kt` (**803**) | 🔴 高 | 1-2 天 |
| DRY-1 | 重复算法 | 偶数对齐 ×5 | ✅ **已完成** | — |
| DRY-2 | 重复 | 握手构造 ×2 | ✅ **已完成** | — |
| DRY-3 | 重复实现 | `GlPipeline` 帧写出 | ✅ **已完成** | — |
| DRY-4 | 重复逻辑 | asset CRC 校验 ×2 | ✅ **已完成** | — |
| DRY-6 | 重复 | 通知/WakeLock ×2 | ✅ **已完成** | — |
| DRY-7 | 重复 | WiFi gateway ×2 | ✅ **已完成** | — |
| DRY-8 | 重复 | 日志行拼接 ×3 | ✅ **已完成** | — |
| DRY-10 | 兼容壳 | `Discovery` vs `Ports` | ✅ **已完成** | — |
| DRY-5 | 平行实现 | 三端会话编排 | 🟠 中 | 2-4 天（大改） |
| SRP-3 | 职责混入 | `VideoDecoder` 黑屏检测 | 🟠 中 | 0.5 天 |
| DRY-6 | 重复 | 通知/WakeLock ×2 | 🟡 低中 | 0.5 天 |
| SRP-4 | 职责混入 | `GlPipeline` 码率/IO | 🟡 低中 | 0.5 天 |
| DRY-2 | 重复 | 握手构造 ×2 | 🟡 低中 | 0.5 天 |
| DRY-7 | 重复 | WiFi gateway ×2（3 个调用点，1 个已注入解耦） | 🟡 低 | 0.2 天 |
| DRY-8 | 重复 | 日志行拼接 ×3 | 🟡 低 | 0.3 天 |
| SRP-6 | 职责混入 | `FileLog` | 🟡 低 | 0.5 天 |
| SRP-7 | 职责混入 | `AppListBuilder` allowlist | 🟡 低 | 0.3 天 |
| SRP-9 | 大文件 | `CarLaunchScreen` / `OnboardingScreen` | 🟡 低 | 0.5 天 |
| SRP-5 | 大文件 | `PipelineServer` (523) | 🟢 极低 | 备注即可 |
| DRY-12 | 重复 | USB ready 重置（字面 ×1 / 语义 ×2） | ✅ 已完成 | — |
| DRY-13 | 重复 | disconnect 序列 ×2（语义性差异） | ✅ 已完成（部分） | — |
| DRY-9 | 测试重复 | `createConnectedSockets` ×2 | ✅ **已完成** | — |
| DRY-10 | 兼容壳 | `Discovery` vs `Ports`（已有意为之的注释） | 🟢 极低 | 0.1 天 |
| DRY-11 | 概念重复 | 图标 hash 抑制 | 🟢 极低 | 备注即可 |
| SRP-8 | 文件组织 | `AdbDeployer.kt` 3 类型 | 🟢 极低 | 0.1 天 |

> **第二轮补入两处遗漏**：**SRP-5**（`PipelineServer` 523 行）原先不在表内 ——
> 它超过 §5 自定的 450 行 god class 阈值，按报告自己的标准也应列出；
> 但其职责集中在"进程与连接生命周期"，本报告不建议动，故列为 🟢 极低/仅备注。
> **DRY-12** 的依据已修正（字面重复 1 处、语义重复 2 处，见 §2.2）。
>
> ⚠️ **阈值口径提醒**：§5 的 ">450 行且职责 >4" 阈值，`VideoDecoder`(472) 与 `PipelineServer`(523) 都已越过，
> 但只有前者被判为需处理。所以该阈值在本报告中**不是硬判据**，而是"需要人工判断"的触发器 ——
> 否则 §0"两个 Service 是真 god class"就是漏判。

---

## 4. 建议的落地顺序

**阶段 A — 低风险、高收益（2.5-3 天，不是第一轮说的 1-2 天）**

> ⚠️ **算术更正**：第一轮标"1-2 天"，但按 §3 自己的估算，阶段 A 的 9 项合计 **2.6 天**。
> 同时第一轮存在**优先级倒置** —— 🟡低中/0.5 天的 DRY-2 被整段漏掉，
> 而 🟢极低/0.1 天的 DRY-12/13 反而进了阶段 A。已重排。

0. **DRY-12 / DRY-13**（各 0.1 天，已完成）：`resetAdbReadiness()` 抽出两处共有的 4 行；
   `disconnectAllConnections(clearListeners)` 参数化，使 `rehandshakeOnExistingControl`
   可复用（原先它是第 2 处内联副本，见 §2.2 更正）。
1. **DRY-1** 统一偶数对齐到 protocol-core（0.5 天）。✅ 已完成保护：`AdbDeployTest`、`VdDeployCommandLineTest` 已锁定对齐结果。
   ⚠️ 务必覆盖 `navBarWidthPx` L1195 的**向上取偶**语义，不要机械改成向下。
2. **DRY-4** 抽 `extractIfChanged`，合并 `extractAsset`/`ensureVdServerJarCurrent`（0.5 天）。
3. **DRY-2** 在 protocol-core 定义 `HandshakeRequest` builder，两端只填差异字段（0.5 天）。
   ⚠️ 两端不是对等类型（desktop 是 `object`，car 是顶层 fun），且对齐责任目前落在 car 的调用方。
4. **DRY-6** 抽 `ForegroundNotifier`（0.5 天）。注意车机端 `updateNotification` 目前缺 `try/catch`，抽取时统一加上。
5. **SRP-4** `GlPipeline` 码率抽 `AdaptiveBitrate`（0.5 天）。**必须与 DRY-3 同批做**（同一文件、同一热路径）。
6. **DRY-3** 删掉 `GlPipeline` 内部 `writeAll`/帧头拼装，改用 `FrameCodec.writeFrameToChannel`（并入上项计）。
   **只改 `GlPipeline`** —— lifecycle 通道是裸字节协议，不在重复范围内（见 DRY-3 更正）。
7. **DRY-8** 抽共享 `formatLine` 行拼接（0.3 天）——只统一拼接，不统一时间戳精度。
8. **DRY-7** 收 `CarConnectionService` L1120 + `ConnectionStatusScreen` L131 两处（0.2 天）。
9. **DRY-12 / DRY-13 / DRY-9 / SRP-8** 清理（各 0.1 天）。DRY-10 需先做 26 处调用点迁移再谈 `@Deprecated`。

**阶段 B — 结构调整（3-4 天）**

10. **SRP-3** 从 `VideoDecoder` 抽 `BlackScreenDetector`（0.5 天）——搬 L86-117 + L343-370，**先补单测再搬**（vd-server 无测试，见下）。
11. **SRP-2** `ConnectionService` 抽 `AssetDeployer`(L128-228) / `CarInstallCoordinator`(L606-704) / `ClientNotifier`(L780-808)（1-2 天）。
12. **SRP-6** `FileLog` 抽 `LogArchiver`（0.5 天）。
13. **SRP-7** `AppListBuilder` allowlist 播种外移（0.3 天）。
14. **SRP-9** `CarLaunchScreen` 抽设置面板（0.5 天）——注意设置嵌在 `ConnectionStatusCard` L227-411 内，
    抽取须连带 L336-407 的三处调用点。

**阶段 C — 大手术（需要专门分支 + 回归验证）**

15. **SRP-1** `CarConnectionService` 按轨道拆分（2-3 天）。⚠️ 回归基线见下方"测试覆盖真相"。
16. **DRY-5** 评估把桌面端 `DesktopConnectionService` 的 Deferred 式编排抽成协议层共享的会话驱动（2-4 天）。

**仅备注、不排期**：SRP-5（职责集中，本报告不建议动）、DRY-11（概念重复，两端判据本就不同）。

---

### ⚠️ 测试覆盖真相（第一轮此处写错，是本节最重要的修正）

第一轮的点名保护措施**指向了错误的模块**，照做会得到虚假的安全感。实测：

| 第一轮说用这些保护 | 实际是什么 | 能保护 DRY-3/SRP-1/SRP-4 吗 |
|---|---|---|
| `AdversarialM2Test` | 在 **app-client**，只 import `VirtualDisplayClient` | ❌ 完全不碰 `CarConnectionService` |
| `ConnectionStressTest` | 在 **protocol-core**，零 `com.dilinkauto` import | ❌ 只测 `Connection`/NIO |
| `VideoStreamPipeTest` | **app-desktop** 自有类的测试，零跨模块 import | ❌ 不测 vd-server 的 `GlPipeline` |
| `FeedGateTest` | 同上，app-desktop 自有类 | ❌ 同上 |

真实情况：

- **唯一引用 `CarConnectionService` 的测试是 `app-server/src/test/.../MainActivityAdversarialTest.kt`**（6 处：L6/45/64/98/100/103）。
  SRP-1 拆分必须以它为回归基线，第一轮完全没提到它。
- **`vd-server/src/test` 目录不存在** —— `:vd-server:test` 解析出**零个测试**。
  所以 DRY-3（帧写出）和 SRP-4（码率）**当前没有任何自动化回归网**。
  这两项都在编码热路径上，改动前必须**先补测试**，否则无网可依。
- `AdbDeployTest` / `VdDeployCommandLineTest`（DRY-1 的保护）**属实**，可以依赖。
- `:app-client:assembleDebug` 作为提交门槛**属实**（README L223/L168/L205），
  且它会传递拉起 `:protocol-core:jar`、`:vd-server:bundleLibRuntimeToJarDebug`、`:app-server:assembleDebug`。

**修正后的验证纪律**：
1. 每次改动后跑 `./gradlew test` + `:app-client:assembleDebug`（README 要求的门槛）。
2. **动 DRY-1**：保留 `AdbDeployTest`、`VdDeployCommandLineTest` 断言不被削弱。✅ 现有覆盖
3. **动 DRY-3 / SRP-4**（vd-server 热路径）：**先建 `vd-server/src/test`**，
   至少覆盖 6 字节头布局与 `writeAll` 的 5s deadline 语义，再动代码。⚠️ 当前无覆盖
4. **动 SRP-1**（`CarConnectionService`）：以 `MainActivityAdversarialTest` 为基线。⚠️ 第一轮点错了测试
5. **动 SRP-3**（`BlackScreenDetector`）：黑屏检测当前无直接测试，抽取前先补纯逻辑单测。

---

## 5. 附：度量口径

- 行数按 `src/main` / `src/test` 下 `.kt` 逐行统计（已排除 `build/` 产物）。
  ⚠️ 在 Windows/PowerShell 下不要用 `Get-Content | Measure-Object -Line` —— 它会把空行/换行处理算成
  偏小的值（本次一度得到 1055 / 786 等错误数字）。用 `[System.IO.File]::ReadLines()` 逐行计数，
  与 `wc -l` 一致。**第一轮的 15,411 / 3,606 即为该误差所致的少计**（实际 15,423 / 3,610）。
- 分模块明细（第二轮补，便于复现）：

  | 模块 | main 文件 | main 行 | test 文件 | test 行 |
  |---|---|---|---|---|
  | `protocol-core` | 17 | 1,905 | 9 | 1,276 |
  | `protocol` | 4 | 505 | **0（目录存在但空）** | 0 |
  | `app-client` | 26 | 4,109 | 2 | 134 |
  | `app-server` | 20 | 4,516 | 2 | 192 |
  | `vd-server` | 7 | 1,382 | **0（无 src/test）** | 0 |
  | `app-desktop` | 25 | 3,006 | 16 | 2,008 |
  | **合计** | **99** | **15,423** | **29** | **3,610** |

  ⚠️ 头部的"全部 `src/test`"**有误导性**：6 个模块中 **2 个零测试**
  （`vd-server` 根本没有 `src/test` 目录；`protocol` 的 `src/test` 为空）。
  而 `vd-server` 正是帧写出与码率热路径所在模块 —— 见 §4「测试覆盖真相」。
- **成员数口径**（第二轮补，第一轮未说明方法）："54 函数 + 61 字段" =
  `fun` 声明行计数 54；类体层级（4 空格缩进）`val`/`var` 属性 61 个，不含 `companion object` 常量与函数内局部变量。
- "god class" 判定阈值：> 450 行**且**职责 > 4 个独立变更原因。
  ⚠️ 该阈值是**触发人工判断**的启发式，不是硬判据：`VideoDecoder`(472) 与 `PipelineServer`(523) 都越过 450，
  但只有前者被判需处理。不适用会导致 §0 漏判。
- 重复判定：语义一致的代码块 ≥ 2 处，且**修改一处而不同步另一处会导致缺陷**。
- 未读取 `.git` 历史，全部基于当前 `main` 工作树（HEAD = `22d3b7a`，无 tracked 文件改动）。

---

## 6. 修订记录（第二轮逐条复核）

第一轮报告的**结构性判断与优先级排序基本成立**。但经 5 个并行 agent 对全部 ~90 处行号引用逐条复核，
发现 **5 处结论不成立** + 约 25 处行号/表述不准 + 2 处覆盖遗漏。

它们的共同成因是两类：
**(A) 用 grep 模式匹配直接下结论，没有读该处的协议语义** —— 见 6.1。
**(B) 派生层（§0/§3/§4/§5）没有回头核对正文** —— 见 6.3，这是第一轮完全没自查过的层面。

### 6.1 已删除的错误结论（勿照做）

| # | 位置 | 错误内容 | 实际情况 |
|---|---|---|---|
| 1 | DRY-3 | `PipelineServer.enqueueResponse`(L470-480) 手工拼 lifecycle 帧头，与 `FrameCodec` 重复 | 行号错（实为 **L435-443**）；lifecycle **双向都是裸字节**，无长度前缀/channel 字节，真实线格式是 `[msgType][可选 int32 size][payload]`。照改会打断 `VirtualDisplayClient` 读取循环。**经对抗性复核确认删除正确** |
| 2 | SRP-1 | 「已有两处注释记录这类事故，L546、L594」 | L546 是 `}`，L594 是空行（长度 0）。**但报告方向是对的、证据找错了位置** —— 真正的记录在 **L952-959**（`startWifiTrack` 重试循环会在重握手中途断开存活 control 连接）。已替换为正确行号 |
| 3 | DRY-12 | 两处都写了 `usbAdb` 块 **+** `adbController` 块 | `adbController?.isConnected == true → usbReady = true` **只在 handleDisconnect L1064-1066**。字面重复降为 4 行 |
| 4 | DRY-13 | disconnect 序列在 3 处各写一遍 | `handleDisconnect` **调用** `disconnectAllConnections()`（L1049）。仅 **2 处**内联 |
| 5 | SRP-5 | `PipelineServer` 剩余部分含"**三个 reader 线程**" | **只有两个**（L272 lifecycle、L292 touch）。本类 KDoc L32 自己写的就是两个。第三处 `Thread(` 命中是 watchdog/StackCheck/LifeWriter/Pipeline/ShutdownHook —— **与成因 (A) 同型：grep `Thread(` 数命中数** |

### 6.2 行号与表述更正（共 25 处，摘录）

- **度量**：15,411 → **15,423**；3,606 → **3,610**（PowerShell 计数坑，见 §5）。
- **DRY-1**：**4 份 → 5 份**。漏了 `CarConnectionService.navBarWidthPx` **L1195**（就在已引用的 L1189 上方 7 行、
  同一 companion object），且它是**第三种算法：向上取偶（+1）**，方向与另两种相反 —— 不产生 0/1 边长，
  **因此错误最难被发现**。负数输入下三者也发散（`evenAlign(-1)`→`2` vs `-1 and 0x7FFFFFFE`→`-2`）。
- **DRY-5**：`VD_PORTS_BOUND` "21 处" → `src/main` 内 **14 处**（另 7 处在 `DesktopConnectionServiceTest.kt`，是测试）。
- **SRP-3**：检测主体 L339-368 → **L343-370**，状态块 L88-116 → **L86-117**；
  `L405-460` 实为**三段混合**（L406-412 队列丢弃 / L415-440 Surface 门控 / L442-459 `stop()` 拆除）。
- **SRP-5**：`createVirtualDisplay` L166-181 只是 15 行调度器，真逻辑在 `VirtualDisplayCreator.kt`。
- **SRP-2**：多个区间小幅偏差 —— `installCarApp` 606-**680**→**704**（原少算 24 行）、`handleHandshake` →**520**、
  `startVdServerViaShizuku` →**588**、`stopEverything` →**776**、asset 块 →**228**；
  且把 `stopEverything` 说成"幂等 guard"是张冠李戴（guard 是 `cleanupGuard` L724 / `cleanupSession` L731-763）。
- **SRP-1**：初始化路径"至少 3 条" → 实为 **5 条**（漏了 L130 USB 拔出、L973-975 第 4 条完整重置路径）。
- **SRP-4**：`L157-176`/`L180-200` → 码率判定行 L163/L170 + helper `applyBitrate` L199/`requestSyncFrame` L200；
  帧写出 → **L181-184 / L189-198**（原文与本文档 DRY-3 自相矛盾）。
- **SRP-9**：`OnboardingScreen` 属 **app-client**，与另两个 app-server 文件不同模块；
  设置面板**嵌在** `ConnectionStatusCard` L227-411 内，抽取须连带 L336-407 三处调用点。
- **DRY-2**：两端字段集**不完全相同**（desktop 不设 `appVersionName`）；对齐责任落在 car 的调用方；
  且两端**不是对等类型**（desktop `object` vs car 顶层 `internal fun`）。
- **DRY-6**：差异比原列多两项 —— WakeLock tag 不同；**车机端 `updateNotification` 缺 `try/catch`**。
- **DRY-7**：**2 处**做 `getSystemService`，非 3 处 —— `CarIpLocator` 已注入解耦。
- **DRY-8**：三端时间戳精度不同（桌面带日期），只能统一**行拼接**。
- **DRY-10**：垫片**已自我说明**（`Discovery.kt:27-28` + `Ports.kt:9-10`），且有 26 处调用点支撑 ——
  不应先贴 `@Deprecated`。**建议力度已下调。**
- **DRY-11**：`L56-72` → **L56-66**；`AppCatalog` 注释讲的是 Compose **重组抑制**（非"不重复计算/存储"），
  且两端是**因果链**（桌面侧正确性依赖手机侧抑制）而非简单重复。
- **§2.3**：app-server **没有 `HandshakeFactory` 类型**（只有顶层 fun）；
  `ShellExec` 声明在 `PipeLog.kt` L29 内、无独立文件；原列举**漏了第 4 个文件 `GlPipeline.kt`**，
  且把 `ShellExec` 行号当成了 log/err 去重证据；`VdDeployArgs` 的是**位掩码不是 clamp**。
- **DRY-3 自身措辞**：说"会打断 `CMD_STOP` 解析"方向不准 —— `CMD_STOP` 属 phone→server，
  `enqueueResponse` 是 server→phone，被改坏的是响应方向。

### 6.3 派生层的覆盖遗漏（第一轮完全没自查）

| # | 问题 | 已修 |
|---|---|---|
| 1 | **§4 验证纪律点名了错误模块的测试** —— `AdversarialM2Test` 在 app-client 且只 import `VirtualDisplayClient`；`ConnectionStressTest` 在 protocol-core 且零 `com.dilinkauto` import；`VideoStreamPipeTest`/`FeedGateTest` 是 app-desktop 自有类。**四个没有一个能保护 DRY-3/SRP-1/SRP-4** | 已改为「测试覆盖真相」表：唯一引用 `CarConnectionService` 的是 `MainActivityAdversarialTest`(app-server)；**`vd-server/src/test` 根本不存在**，`GlPipeline` 热路径零回归网 |
| 2 | **SRP-5 从未进入 §3 清单**（523 行，超过报告自定的 450 阈值） | 已补入，列为 🟢 极低/仅备注 |
| 3 | **§4 漏掉 7 个 §3 条目**：DRY-2、DRY-8、SRP-6、SRP-7、SRP-9、DRY-11、SRP-8 | 已全部排期；DRY-2 曾被漏掉而 DRY-12/13（0.1 天）却进了阶段 A —— 优先级倒置已修正 |
| 4 | **§4 阶段 A 算术矛盾**：标"1-2 天"，9 项按 §3 估算合计 **2.6 天** | 已改为 2.5-3 天并重排 |
| 5 | **§0 漏了 SRP-2**（🔴高、§3 排名第 2 的第二个 god class） | 已补入 |
| 6 | **§0"按性价比排序"站不住** —— 实为按严重度排，把工作量最大的 SRP-1 放第一 | 已去掉该表述并说明真实性价比最高项 |
| 7 | **450 行阈值被不一致地应用**（`VideoDecoder`/`PipelineServer` 都越过但只有一个被处理） | 已在 §5 声明它是"触发人工判断"的启发式而非硬判据 |
| 8 | 头部"全部 `src/test`"**误导** —— 6 模块中 2 个零测试 | 已在 §5 加分模块明细表并显式披露 |

### 6.6 修复进度总览

| ID | 状态 | 关键产出 |
|---|---|---|
| **DRY-1** | ✅ 已修复 | `DimAlign`（protocol-core），5 处调用点委托，3 变体行为各保留；新增 9 例测试 |
| **DRY-3** | ✅ 已修复 | `GlPipeline` 手写帧头/`writeAll`/常量全部删除，改用 `FrameCodec`；`GlPipeline` **214→203 行** |
| **SRP-4** | ✅ 已修复 | 新增 `AdaptiveBitrate` 纯状态机（与 DRY-3 同批，同一文件） |
| **DRY-4** | ✅ 已修复 | 新增 `AssetDeployer`，`ConnectionService` **866 → 810 行**；顺带修掉"写失败仍报成功 CRC"的静默行为 |
| **DRY-12** | ✅ 已修复 | 抽出 `resetAdbReadiness()`，两处共用，并把只在 1 处的注释补全 |
| **DRY-13** | ✅ 已修复（部分） | `disconnectAllConnections(clearListeners)` 参数化；**有意保留** control 连接的语义差异 |
| **DRY-2** | ✅ 已修复 | `protocol-core` 新增 `HandshakeRequest.Builder`；两端不再各自手搓 8 字段 |
| **DRY-6** | ✅ 已修复 | 新增 `protocol/ForegroundNotifier`；两端 Service 的通知/WakeLock 委托过去，**顺带补上车机端缺失的 try/catch** |
| **DRY-7** | ✅ 已修复 | 新增 `app-server/adb/WifiGatewayProbe`，收敛 2 处 `getSystemService` + try/catch |
| **DRY-8** | ✅ 已修复 | 新增 `protocol-core LogLine` + `LogLineTest`（6 例）；三端格式**有意保持不同** |
| **DRY-10** | ✅ 已修复 | 26 处调用点机械迁移到 `Ports`，`Discovery` 的 5 个别名按原计划标 `@Deprecated(WARNING)` |
| **DRY-9** | ✅ 已修复 | 新增 `TestSockets`，`ConnectionTest`/`ConnectionStressTest` 共用夹具 |
| — | 🆕 附带完成 | **`vd-server` 从 0 个测试变为 17 个**（新建 `src/test` + junit 依赖）——§4 要求的"先建网再动热路径" |
| — | 🆕 附带完成 | **修掉一个基线失败**：`CarIpLocatorTest` 加 `portProbeOverride` 测试缝 + 补 2 个分支用例；`./gradlew test` 现在全绿 |

**当前状态**：`./gradlew test` ✅ 全绿 + `:app-client:assembleDebug` ✅ BUILD SUCCESSFUL。

**阶段 A 全部完成**（DRY-1/2/3/4/6/7/8/9/10/12/13 + SRP-4）。

尚未处理：**DRY-11**（仅备注）、**DRY-5**（大改）、
**SRP-1、SRP-2**（两个 god class 的进一步拆分）、**SRP-3**（BlackScreenDetector）、
SRP-5（仅备注）、**SRP-6**（LogArchiver）、**SRP-7**（allowlist 外移）、
**SRP-8**（AdbDeployer 拆文件）、**SRP-9**（CarLaunchScreen 设置面板）。

### 6.5 已修掉的基线失败：`CarIpLocatorTest`（环境依赖型测试）

`:app-client:testDebugUnitTest` 的 `findCarAdb_returnsNullWhenNoControlConnectionAndNoInterfaces`
**在本次改造开始之前就失败**：

```
java.lang.AssertionError: findCarAdb must return null when no car is reachable
  expected null, but was:<192.168.3.206>
  at CarIpLocatorTest.kt:45
```

**根因**：该用例断言"找不到车机"，但 `findCarAdb` 会扫描**调用者真实的网络** ——
本地子网、`/proc/net/arp`、neighbor cache、并发 /24 扫描、网关。
开发机上只要有真实主机监听 5555（车机 / 另一台 ADB 设备）就会命中。
也就是说这个用例断言的是**测试机器的网络状态**，不是 `CarIpLocator` 的行为 ——
隔离 runner 会过，有设备的开发机会挂，属于典型的**非确定性测试**。

**修法**（不削弱断言强度）：
- 在 `CarIpLocator` 加 `portProbeOverride` 测试缝，生产路径 `null` → 走真实 `probePortBlocking`；
  5 处探测点（control remote / ARP / neighbor cache / 网关 / 并发扫描）统一改走 `probe(...)`
- 用例用 `{ _, _ -> false }` 固定"全部不可达"，从而真正断言 `CarIpLocator` 的遍历与返回逻辑
- 另**补 2 个用例**覆盖原先没有的分支：control 连接端口**打开**时必须**优先返回**该 IP；
  control 连接端口**关闭**时不得短路、须继续扫描

**现状**：`./gradlew test` 全绿（这是本次之前 `test` 任务失败的原因）。

### 6.4 逐条核对**确认无误**的部分（可放心依赖）

- **SRP-1 职责表 26 处行号引用全部精确命中**（含 14 个 `@Volatile`、54 函数 + 61 字段、L952-959 事故注释）。
- 全部文件行数：1201 / 866 / 472 / 214 / 523 / 153 / 131 / 590 / 557 / 391 / 492 / 310 / 363 —— 全部精确。
- **DRY-1 已引的 4 处全部命中**，且两种算法的语义发散分析成立。
- **DRY-3 主论断命中**（`GlPipeline` L12 已 import protocol —— 注释自相矛盾；L68-69 与 `FrameCodec` L212-213 同值）；
  **DRY-3 的删除经对抗性复核确认正确**，并补入第一轮未引的旁证（`sendDisplayReady` L241 /
  `VirtualDisplayClient` L99-103 同样是裸字节配对）。
- DRY-2 全部字段差异、DRY-4 全部 CRC 段、DRY-5 三端编排（`CompletableDeferred` L67-69）、
  DRY-6 五项差异（含通知 id 1001 vs 2001）、DRY-7 全部细节、DRY-9 两份夹具**逐字节相同**（12 行）、
  DRY-12/13 的更正版（对抗性复核通过）、§2.3 全部符号存在性。
- **§6 的四次"删除结论"本身也逐条被复核为正确** —— 这是本节最需要被验证的部分，已验证。
