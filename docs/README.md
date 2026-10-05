# DiLink-Auto 文档

本目录是 DiLink-Auto 的开发者文档入口。面向用户的中文介绍请看仓库根目录的 [README.md](../README.md)。

本 fork 面向中国手机 ROM + BYD DiLink 车机使用场景,并在 **比亚迪 秦PLUS DM-i 2023款 冠军版 55KM 领先型**(DiLink 4.0 低配,骁龙 439 / Android 9 / 1280x800)上做了针对性性能调优。文档以英文为主,与根目录中文 README 互补。

## 文档索引

| 文档 | 读者 | 内容 |
|------|------|------|
| [Setup Guide](./setup.md) | 用户 | 安装步骤、权限引导、故障排查、DiLink 4.0 低配车机注意事项 |
| [Architecture](./architecture.md) | 开发者 | 模块设计、直连 VD 架构、连接流程、设计决策 |
| [Protocol Specification](./protocol.md) | 开发者 | 线格式、消息类型(`VD_PORTS_BOUND`、`dpiOverride`)、端口分配 |
| [Client (Phone) App](./client.md) | 开发者 | ConnectionService 编排器、VD 部署、车机自更新、应用允许列表 |
| [Server (Car) App](./server.md) | 开发者 | 状态机、SurfaceView 解码器、三键导航栏、DPI 覆盖 |
| [Progress Tracker](./progress.md) | 贡献者 | 功能状态、里程碑、fork 后的技术演进 |

## 与 upstream 的差异

本 fork 相对 [andersonlucasg3/dilink-auto-android](https://github.com/andersonlucasg3/dilink-auto-android) 的主要技术路线(详见各文档):

- **直连 VD 架构**:VD Server 直接绑定 9638/9639 端口与车机对话,手机端不做视频/触摸中继。
- **DiLink 4.0 低配车机适配**:编码尺寸 cap 1920x1080、码率 4Mbps、24fps、TextureView → SurfaceView、线程优先级、图标缓存清理等九项优化。
- **API 28 车机兼容**:移除 API 29+ 的硬件解码器 picker,minSdk 提到 26。
- **导航栏重设计**:精简为 Eject / Home / Back 三键;通知转发、最近任务栏、时钟、网络信息全部移除。
- **应用允许列表 + 应用置顶**:手机端选择推送到车机的应用,车机端长按置顶。
- **车机端启动 DPI 覆盖**:解决 1280x800 默认 DPI 偏小。
- **简体中文**:client 和 server 都加了 `values-zh-rCN` / `values-zh`。

## 已移除的功能(相对 upstream)

- 通知转发(手机通知 → 车机屏):导航栏通知按钮、通知列表、`NotificationService`、`FOCUSED_APP` / `APP_SHORTCUTS` / `NOTIFICATION_*` 协议消息全部删除。
- 最近任务栏、时钟、网络信息。

## 未实现

- 音频流传输、媒体控制、导航小部件。

## 下载

不提供具体版本号(代码版本与 Release tag 不一致,以 [Releases](https://github.com/ID-VerNe/dilink-auto-android/releases/latest) 为准)。当前版本: **0.18.0-dev-13** (VD 泄漏修复 + 清理幂等 + 黑屏自愈)。
