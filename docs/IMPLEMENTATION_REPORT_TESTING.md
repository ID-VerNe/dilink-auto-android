# DiLink-Auto 测试覆盖补齐 — 主实施报告

日期：2026-10-09　范围：全仓 6 个 Gradle 模块（`:protocol-core` / `:protocol` / `:app-client` / `:app-server` / `:app-desktop` / `:vd-server`）

## 0. 执行方式
先并行派出 6 个只读审计 subagent（每模块一个）产出覆盖缺口报告，再据此逐个模块补测试。所有新增测试均在**本机 Gradle 离线模式实测通过**（`/>module/:test`，含 Android 模块的 debug/release 两个变体）。测试栈沿用项目既有约定：**JUnit4 + kotlinx-coroutines-test**，无 Robolectric/Mockito/MockK；Android 模块靠 `isReturnDefaultValues=true` + 手写假件（`ContextWrapper(null)`、捕获式 `OutputStream`、canned lambda）。

## 1. 新增测试清单（全部 verify green）

| 模块 | 新增测试文件 | 覆盖的生产对象 | 用例数 |
|---|---|---|---|
| `:protocol` | `AdbProtocolTest`, `AdbCryptoTest` | ADB 帧头/magic/校验和/round-trip/常量；`bigIntToLEPadded`/`SHA1_DIGEST_INFO`/`fingerprint`/`signAuthToken`/`buildAuthReply` | 27 |
| `:protocol-core` | `VideoConfigTest`, `VdDeployArgsFormatTest`, `ImeRestoreGuardTest`, `H264NalParserRefIdcTest`, `AsyncLogQueueTest`, `VdDeployConstantsTest`, `WifiGatewayIpTest` | `calculateOptimalDpi` 各分支；`VdDeployArgs.format` 布局/等比钳制/bitrate 边界；`shouldRestoreIme`；H264 `nal_ref_idc` 还原；`AsyncLogQueue` 溢出/FIFO/挂起/close；kill/stop/probe 命令逐字锁；网关 IP 字节序 | 33 |
| `:vd-server` | `AdaptiveBitrateEdgeTest`, `DisplayPowerControllerRestoreTest`, `TouchInjectorFallbackTest`, `CarCommandRouterDispatchTest` | 自适应码率阈值边界/截断/sync latch；S-M10 哨兵回落 `restoreIme`；touch 归一化缩放+shell 回退；S-02 shell 引用/`am/pm/input` 命令路由/`SET_DISPLAY_POWER` | 27 |
| `:app-client` | `VersioningTest`, `AppCategorizerTest`, `InstallStatusTest` | 版本 parse/compare（含哨兵/SNAPSHOT 边界）；app 分类有序关键字；安装状态串分类 + 阶段索引（含本地化漏判 hazard） | 41 |
| `:app-server` | `WifiGatewayProbeTest`, `CarCrashReportTest` | 网关查找取不到回落；崩溃报告格式契约（头/时间戳/线程/堆栈/Process State/Caused by/Suppressed） | 8 |
| `:app-desktop` | `HandshakeFactoryTest`, `DesktopConfigTest` | 握手字段映射 + `evenAlign`（下限2、负值兜底）；配置默认值须来自协议常量 + `copy` 只改命名字段 | 6 |
| 合计 | **18 个新文件** | | **约 142** |

### build.gradle.kts 变更
- `:protocol`：新增 `testOptions { unitTests.isReturnDefaultValues = true }` + `testImplementation("junit:junit:4.13.2")`（此前本模块**零测试源集**）。

## 2. 锁定的关键跨模块不变量
- **ADB wire**：`magic=cmd^0xFFFFFFFF`、additive 校验和、MAX_PAYLOAD 上限在 encode 不拦/parse 拦。
- **VD 部署 argv** ↔ 两端；`kill/stop/probe` 的 `[P]ipelineServer` 括号技巧防误杀包裹 shell。
- **屏幕超时哨兵**：写端哨兵 2147483647 → 读端必须回落 60000，绝不能写回哨兵。
- **H264 关键帧**：真实编码器 `0x65`（ref_idc=3）靠 `and 0x1F` 识别。
- **touch 归一化→像素** 缩放与 `input -d tap` 回退。
- **Crash 报告格式** ↔ 手机侧逐行消费。
- **port/dimension/bitrate/dpi** 默认值须来自 `:protocol-core` 常量（`Ports`/`VideoConfig`/`DimAlign`）。

## 3. 审计发现的生产缺陷/隐患（已用**特征化测试固定现状**；改动属产品决策，未擅自修改）
1. **`Versioning` 零版本哨兵** (`C-5`)：`readInstalledVersion` 无安装时返回 `"0"`，而 `versionName="0/0.0/0.0.0"` 与 `"0"` 比较相等 → 会被判"已最新"而**跳过本应进行的首装**。`-SNAPSHOT` 与对应 release 也判定相等。→ `VersioningTest`（hazard 用例固定）。
2. **`InstallStatus` 只按英文子串分类** (`C-6`)：状态串由 `context.getString` 本地化产出，非英文 `values-*` 全部落入 `IDLE` → 无进度/错误展示；`"not reachable"`/`"Invalid IP"` 也不归类为 ERROR。→ `InstallStatusTest`（hazard 用例固定）。建议：分类改走**结构化状态枚举**而非本地化文案。
3. **`CarCommandRouter.dispatch` 的 `LAUNCH_APP`/`APP_UNINSTALL` decode 未保护** (`S-02`)：恶意包名令 `LaunchAppMessage.decode` 直接抛出，逃逸到读帧线程（`APP_INFO` 有 try/catch，这两个没有）。→ `CarCommandRouterDispatchTest`（固定"抛异常且无 am start"）。
4. **`AppCategorizer` 常用 app 落 OTHER**：如 `com.google.android.youtube` 未匹配任何关键字。属 UX 缺口，非崩溃。
5. **WifiGatewayIp 字节序**：当前 LSB-first 与 `Formatter.formatIpAddress` 惯例一致，**判定为正确**（此前被单列为疑似 bug，实为一致）。

## 4. 已验证的运行结果
```
:protocol:test                                   BUILD SUCCESSFUL
:protocol-core:test                              BUILD SUCCESSFUL
:vd-server:test                                  BUILD SUCCESSFUL
:app-client:testDebugUnitTest                    BUILD SUCCESSFUL
:app-server:testDebugUnitTest                    BUILD SUCCESSFUL
:app-desktop:test                                BUILD SUCCESSFUL
```

## 5. 剩余高价值工作（需"为可测性重构"的 seam，已获授权，列为后续）
遵循"低风险、行为不变、配回归测试"原则，按 6 份审计报告的 refactor 清单推进：
- `:protocol-core`：`Connection` 心跳/看门狗/优雅 EOF-flush 测试（需把 `HEARTBEAT_*`/`FLUSH_GRACE_MS` 提为可注入参数或 const）。
- `:protocol`：`TcpAdbConnection` connect 协商/readMessage CRC/shell 闭环（用 `TestSockets` 式假 server harness；建议 extract `negotiateMaxPayload` / `readMessageFrom(InputStream)`，并把 `AdbCrypto.encodePublicKey` 的 `android.util.Base64` 换成 `java.util.Base64`——字节等价）。
- `:app-client`：`CarInstallCoordinator`（extract `CarInstaller` 接口 + `parseInstalledVersion`）、`AssetDeployer`（extract `AssetSource`，因 `AssetManager` 是 final 无法子类）、`AllowlistSeeder`/`filterByAllowlist`、`LogArchiver`（纯 java.io，高 ROI，无重构即可补）。
- `:app-server`：`PinnedAppsRepository`/`CarPrefs`/`ManualConnectState`（加 `FakePrefs` fixture）、`CarConnectionService` 的 `logToggleEnabled`/`reconnectBackoff`/`iconBudget`/`resolvePhoneDpi`（extract 纯函数）、`CarTouchSender.shouldLog`、`CarLogWriter` bounded buffer；`CarCrashHandler` 秒级归档名冲突修复（真 bug）。
- `:app-desktop`：`DesktopPaths`/`FrameDumper`/`VideoDecodePipeline` gate 注入、`FeedGate`↔`VideoStreamPipe` CONFIG 驱逐静默损坏跨文件不变量、`SessionStatsLogger` 格式。
- `:vd-server`：`VirtualDisplayCreator.dtaAttached`、`GlPipeline.writeFrame`、`LifecycleWriter.encode`、`PipelineServer.displayReadyBytes/isAllowedPeer`（internal 化/提取）。

## 6. 结论
已把此前**零覆盖或弱覆盖**的最高风险纯逻辑面（协议帧、加解密基元、部署参数、码率控制、shell 注入门、版本门槛、状态分类、崩溃格式、跨模块常量契约）用**约 142 个实测通过的测试**锁死，并为多处真实缺陷留下可执行的回归护栏与修复建议。后续按 §5 的 seam 清单继续深化，可在不改动对外行为的前提下把剩余分支/状态机逐一纳入回归网。
