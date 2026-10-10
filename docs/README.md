# DiLink-Auto 文档

本目录是 DiLink-Auto 的开发者文档入口。面向用户的中文介绍请看仓库根目录的 [README.md](../README.md)。

本 fork 面向中国手机 ROM + BYD DiLink 车机使用场景,并在 **比亚迪 秦PLUS DM-i 2023款 冠军版 55KM 领先型**(DiLink 4.0 低配,骁龙 439 / Android 9 / 1280x800)上做了针对性性能调优。语言分工:核心六篇(Setup / Architecture / Protocol / Client / Server / Progress)是英文,审计与测试报告是中文;本页与根目录中文 README 一为开发者视角、一为用户视角,互补而不重复。

## 文档索引

| 文档 | 读者 | 内容 |
|------|------|------|
| [Setup Guide](./setup.md) | 用户 | 安装步骤、权限引导、故障排查、DiLink 4.0 低配车机注意事项 |
| [Architecture](./architecture.md) | 开发者 | 模块设计、直连 VD 架构、连接流程、设计决策 |
| [Protocol Specification](./protocol.md) | 开发者 | 线格式、消息类型(`VD_PORTS_BOUND`、`dpiOverride`)、端口分配 |
| [Client (Phone) App](./client.md) | 开发者 | ConnectionService 编排器、VD 部署、车机自更新、应用允许列表 |
| [Server (Car) App](./server.md) | 开发者 | 状态机、SurfaceView 解码器、三键导航栏、DPI 覆盖 |
| [Progress Tracker](./progress.md) | 贡献者 | 功能状态、里程碑、fork 后的技术演进 |
| [测试覆盖补齐实施报告](./IMPLEMENTATION_REPORT_TESTING.md) | 贡献者 | 全 6 模块测试补齐与 seam 批次(24 个新文件 / 183 用例)、锁定的跨模块不变量、可测性 seam 落地记录、未修隐患清单 |
| [全项目安全审计](./audit-project-2026-10-09.md) | 开发者 | 5 agent 并行的全仓审计:威胁模型、六类系统根因(`S-A`~`S-F`)、CRITICAL/HIGH 明细与修复状态 |
| [SRP / DRY 审计](./audit-srp-dry.md) | 开发者 | 第二轮 SRP/DRY 审计(~90 项,2026-10-08) |
| [SRP / DRY 审计 Round 3](./audit-srp-dry-round3.md) | 开发者 | 第三轮 49 项的修复 pass(R3-SRP-01 / R3-SRP-02 为部分落地);`R3-*` 编号在代码注释中被多处引用 |
| [UI / UX 审计](./audit-ui-ux.md) | 开发者 | 15 项无障碍 / 一致性问题及其修复 pass(2026-10-08) |
| [桌面接收端审计](./audit-desktop.md) | 开发者 | `:app-desktop` 的代码审计与修复状态(15 项 `WIN-*`) |
| [功能提案:双模接收端](./FEATURE_DUAL_MODE_RECEIVER.md) | 开发者 | 接收端「模式」按钮提案(车机反向控制 / 代理转发);设计 + 可测核心已落地,上层接线与真机验证待办 |

> Windows 接收端(`app-desktop/`)暂未单独成文档:模块说明、链路与打包命令见根目录 [README.md](../README.md) 的「Windows 接收端」一节,链路与设计决策见 [architecture.md](./architecture.md)。

## 与 upstream 的差异

本 fork 相对 [andersonlucasg3/dilink-auto-android](https://github.com/andersonlucasg3/dilink-auto-android) 的主要技术路线(详见各文档):

- **直连 VD 架构**:VD Server 直接绑定 9638/9639 端口与接收端对话,手机端不做视频/触摸中继。
- **接收端可替换**:同一份协议跑在车机(`app-server`)或 Windows(`app-desktop`);纯 JVM 的部分抽到 `protocol-core`,三端共用。
- **DiLink 4.0 低配车机适配**:编码尺寸 cap 1920x1080、码率 4Mbps、24fps、TextureView → SurfaceView、线程优先级、图标缓存清理等九项优化。
- **API 28 车机兼容**:移除 API 29+ 的硬件解码器 picker,minSdk 提到 26。
- **导航栏重设计**:精简为 Eject / Home / Back 三键;通知转发、最近任务栏、时钟、网络信息全部移除。
- **应用允许列表 + 应用置顶**:手机端选择推送到车机的应用,车机端长按置顶。
- **车机端启动 DPI 覆盖**:解决 1280x800 默认 DPI 偏小。
- **持续黑屏自愈**:判据在 `protocol-core`(`BlackScreenDetector`),车机端重握手、桌面端自动重连(有次数上限)。
- **全模块单元测试**:6 个模块均有 `src/test`(此前 `vd-server` / `protocol` 为零覆盖),JUnit4 + kotlinx-coroutines-test,578 个 `@Test` 用例;其中 2026-10 的补齐与 seam 批次新增 24 个文件、183 个用例;清单与锁定的跨模块不变量见 [IMPLEMENTATION_REPORT_TESTING.md](./IMPLEMENTATION_REPORT_TESTING.md)。
- **安全加固**:协议不再无条件信任对端(畸形帧不再杀进程、payload 上限 128MB → 16MB)、VD 通道鉴权、`VdDeploy` shell 引用、桌面端部署入参校验、release keystore 移出仓库;报告见 [audit-project-2026-10-09.md](./audit-project-2026-10-09.md)。
- **简体中文**:client 和 server 都加了 `values-zh-rCN` / `values-zh`。

## 已移除的功能(相对 upstream)

- 通知转发(手机通知 → 车机屏):导航栏通知按钮、通知列表、`NotificationService`、`FOCUSED_APP` / `APP_SHORTCUTS` / `NOTIFICATION_*` 协议消息全部删除。
- 最近任务栏、时钟、网络信息。

## 未实现

- 音频流传输、媒体控制、导航小部件。

## 已知隐患(测试/审计发现,尚未修复)

以下条目都已用**特征化测试固定现状**(改动属产品决策,未擅自修改),源码与报告一致:

| 编号 | 位置 | 现象 |
|------|------|------|
| C-5 | `app-client` `Versioning` / `CarAppInstaller` | 车机未安装时 `readInstalledVersion` 返回 `"0"`;若内嵌 `versionName` 也是 `0` / `0.0` / `0.0.0` 形式,比较结果判为"已最新",**跳过本应进行的首装**。当前 `0.18.0-dev-13` 不触发 |
| C-6 | `app-client` `InstallStatus.parse` | 状态串由 `context.getString` 本地化产出,分类却只按英文子串;中文等非英文 locale 全部落入 `IDLE`(无进度、无错误),且 `"not reachable"` / `"Invalid IP"` 也不归类为 ERROR。建议改走结构化状态枚举 |
| S-02 | `vd-server` `CarCommandRouter.dispatch` | `LAUNCH_APP` / `APP_UNINSTALL` 的 decode 未加保护(包名已有 `shellQuote` + 解码器形状校验),恶意包名仍能让读帧线程抛异常;`APP_INFO` 分支有 try/catch |
| — | 解码器重启竞争 | 偶发花屏,下一个关键帧(~1s)恢复,见根 README「已知限制」 |

## 下载

不提供具体版本号(代码版本与 Release tag 不一致,以 [Releases](https://github.com/ID-VerNe/dilink-auto-android/releases/latest) 为准)。当前代码版本:`versionName = 0.18.0-dev-13` / `versionCode = 58`(`gradle.properties`),主要变化为 VD 泄漏修复 + 清理幂等 + 黑屏自愈,之后的测试补齐与安全加固尚未改版本号。
