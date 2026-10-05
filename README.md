# DiLink-Auto

把手机上的应用投到汽车车机屏幕上,完整触摸交互。开源,不依赖 Google 服务。

这是 [andersonlucasg3/dilink-auto-android](https://github.com/andersonlucasg3/dilink-auto-android) 的一个 fork,面向**中国手机 ROM + BYD DiLink 车机**的使用场景做了适配,并专门针对 **DiLink 4.0 低配车机**做了性能优化,使其在弱硬件上从"幻灯片"变成可用。

实测车型:**比亚迪 秦PLUS DM-i 2023款 冠军版 55KM 领先型**。该款车机是 DiLink 4.0 低配方案 —— 骁龙 439(8x Cortex-A53 全小核、Adreno 505)、4GB RAM、16GB eMMC、1280x800 屏、仅 2.4GHz WiFi、Android 9 / API 28、H.264 硬件解码上限 1080p。本 fork 的性能调优全部围绕这套硬件展开。

当前版本:**0.18.0-dev-13**。

原本要解决的矛盾是:手机(小米 HyperOS 国行版等)装不了 Android Auto,而车机只支持 Android Auto —— 中间没有桥。DiLink-Auto 对任何 Android 10+ 手机都通用,有没有 Google 服务都行。

---

## 这个 fork 相对 upstream 走了哪些路

下面是从 fork 点(`84df5cb`,upstream/main)到当前 `main` 的关键技术演进,按逻辑分组。每一条都对应实际 commit,不是规划。

### 1. 架构层:直连 VD 流式架构

手机端不再做视频/触摸中继。VD Server 直接绑定 `9638`(视频)、`9639`(输入)端口与车机对话,省掉了每帧 4 次 socket + 2 次进程上下文切换,变成 2 次 socket + 0 次上下文切换。手机端退化为纯编排器:握手、VD 生命周期(`VD_PORTS_BOUND`)、车机日志路由。涉及协议新增 `FrameCodec.readFrameBlocking()`、`MessageType.VD_PORTS_BOUND`,以及 `VirtualDisplayClient` 简化为只管生命周期。后续一系列 commit 修了直连架构下的生命周期通道竞争、部署竞争、栈导航、EGL/GL 纹理上下文等问题。

### 2. DiLink 4.0 低配车机适配(核心)

针对车机硬件(骁龙 439,8x Cortex-A53 全小核、Adreno 505、4GB RAM、16GB eMMC、1280x800、仅 2.4GHz WiFi、H.264 硬解上限 1080p)的九项修复:

- **编码尺寸 cap 到 1920x1080**:原来手机端为了修 IME 裁剪 bug,把 VD 尺寸放大到手机物理像素(1080x2340 → 编码 2340x1658),超过 439 的硬解上限,触发软解,8x A53 跑 >1080p H.264 是个位数帧率。修法是解耦 VD 尺寸和编码尺寸 —— VD 保持放大(保住 IME 裁剪修复),编码尺寸用车机上报尺寸并 clamp 到 1920x1080。`PipelineServer` 早已支持 `EW EH` 参数分离,只需改三条 deploy 路径(phone Shizuku、car USB/TCP ADB、car TCP ADB direct)的传参。
- **码率 8 Mbps → 4 Mbps**:2.4GHz 车内环境实测持续吞吐 5-15 Mbps,8 Mbps 没留余量。自适应回退也收紧:floor 1.5 Mbps、2 秒恢复窗、0.5 Mbps 步进(原来是 2 Mbps floor、5 秒、1 Mbps 步进,会反复在 8 和 2 之间震荡)。
- **帧率 30 → 24**:每帧预算 33ms → 42ms,WiFi/GPU/分配负载降 20%。1280x800 下 24fps 对车机镜像在视觉上够用。
- **TextureView → SurfaceView**:绕开 Adreno 505 每帧全屏 GL 合成(约 2-5ms/帧)。解码器跨导航 hide/show 时用一个 `outputSurfaceValid` 门控防止往已销毁 surface 渲染。
- **线程优先级**:A53 没有大核,调度提示有用。编码/解码/socket-drain 线程设 `THREAD_PRIORITY_URGENT_DISPLAY`,`LifeWriter` 设 `THREAD_PRIORITY_BACKGROUND`。
- **日志默认行为**:release 构建里 `carLogEnabled` 默认关(原来默认开,日志帧和视频抢 2.4GHz 带宽);`ConcurrentLinkedQueue.size()`(O(n))换成 `AtomicInteger`。
- **AppIconCache 断连清理**:原来 ~80-150 个 PNG 永久累积在 16GB eMMC,断连不清理(占 5-9MB 堆)。新增 `clear()` 在 `handleDisconnect` 调用;`prepareAll` 用 `Semaphore(4)` 并行解码,中间 bitmap 及时 recycle。
- **移除 `Debug.getPss()`**:每次连接都跑的 deprecated binder 调用,在 A53 上耗时 100-500ms,移到崩溃时才采集。
- **`@Immutable AppTileData`**:`AppInfo` 带 `ByteArray` 让 `AppTile` 不可跳过,任何 `appList` 重赋值都全量重组。改用排除 `iconPng` 的不可变包装,网格可跳过重组。

### 3. API 28 车机兼容

实测 BYD 车机(`k65v1_64_bsp`,Android 9 / API 28)上 `MediaCodecInfo.isHardwareAccelerated()`(API 29+)抛 `NoSuchMethodError` 直接杀进程。修法是删掉那个 speculative 的硬件解码器 picker,回到 `createDecoderByType`(`REGULAR_CODECS` 已经把硬件解码器排在前面,在 BYD 上会选到 `OMX.qcom.video.decoder.avc`)。顺手把 app-server `minSdk` 24 → 26,protocol 也跟着升到 26,重新启用 NewApi lint gate。`am display move-stack`(API 29+)在 API 26-28 上做了 `SDK_INT` 门控并打日志,而不是把 shell 失败当成功。

### 4. UI 与交互

- **导航栏重设计**:精简为 Eject(断连)/ Home / Back 三键。通知按钮、最近任务栏、时钟、网络信息全部移除,相关死代码跨四个模块清理(车机端 `NotificationScreen`、`RecentAppsState` 删除,手机端 `NotificationService` 删除,`FOCUSED_APP` / `APP_SHORTCUTS` / `NOTIFICATION_*` 消息类型从 protocol、`ConnectionService`、`VirtualDisplayClient`、`PipelineServer` 移除)。**通知功能在这个 fork 里已移除**,upstream README 里描述的通知相关特性不再适用。
- **应用置顶(pinning)**:长按应用图标弹出菜单支持 Pin to Top / Unpin,基于 SharedPreferences。
- **应用允许列表(Allowlist)**:新增 `AllowlistScreen`,用户选择哪些应用推送到车机,未选中的在 `sendAppList` 阶段就被过滤掉,缩小 wire payload 和车机端图标解码工作量。首次运行预置常见地图应用。切换后发 `ACTION_ALLOWLIST_UPDATED` 让运行中的 service 重发列表,车机网格实时更新。
- **竖屏应用优化**:动态 DPI 计算(`VideoConfig.calculateOptimalDpi`)让竖屏应用(高德、微信)在横屏 VD 里不被压成细条;IME 在会话断开时自动恢复(原来手机会残留 linkpc IME);多指触摸注入索引修复。
- **车机端启动 DPI 覆盖**:握手新增 `dpiOverride` 字段(0 = 自动,[120, 480] = 用户指定),车机端 `startup_dpi` 设置项驱动,横竖屏重握手都带上。解决 1280x800 默认 DPI 偏小的问题。
- **颜色 token 化、类型 flooring**:重构散落的 `Color.Gray` / `0xFF888888` 到 `onSurfaceVariant`,合并重复的 error 红,标签 12sp → 14sp。重命名 "WiFi Direct Mode" → "WiFi ADB Mode",移除车机端 `adb tcpip` 文本。
- **旋转黑屏修复**:旋转时 `onCarViewportChanged` 把状态置 CONNECTING 并销毁 VD 重部署,但流式布局的门控用了 `isConnected`(重部署 2 秒内为 false),导致 `CarShell` 翻回 `CarLaunchScreen`,`TextureView` 被移出组合,新 surface 无法重启解码器。门控改为同时接受 `state == CONNECTING && appList.isNotEmpty()`,流式布局(及其 video-wait overlay)在旋转期间保持不动。

### 5. 会话稳定性

活跃会话不会被重连尝试杀掉 —— 之前 WiFi 网关重试或手动连接会杀掉正在跑的 session。重连循环在连续 3 次 ADB 失败后停止。TCP ADB 在手机 IP 变化时重连,USB 不可用时自动回退到 TCP ADB。

### 6. PipelineServer 稳定化

从 `VirtualDisplayServer`(1287 行、9 线程、2 队列)重构为 `PipelineServer`(单线程、0 队列):帧时钟 → GL 渲染 → 编码器 drain → TCP 写,自然流控(TCP 卡住就阻塞下一帧 `eglSwapBuffers`)。修了 GL 纹理在临时 EGL 上下文里创建后被销毁、pipeline 线程绑到 texture 0 的 bug;修了 `app_process` 被非交互 shell 退出的 kill(用 `shellBackground` 保持 shell 流);修了物理面板恢复顺序(原来 shell 已关才调 `input keyevent 224`)。

### 7. 日志开关

Settings → Debug 加了诊断日志开关。off 时零磁盘写入。通过 `LOG_TOGGLE` data 消息同步给车机。默认行为:debug/pre-release 构建开,release 构建关;用户显式设置后持久化。

### 8. 简体中文

client 和 server 都加了 `values-zh-rCN` / `values-zh`。这是面向中国场景的基础适配。

### 9. 代码健康

最近一轮 4-agent 并行审计后的 DRY/SRP pass:跨模块共享常量(`AppPrefs`、`AppTargets`、`VdDeploy`、`VdDeployArgs`、`WifiGatewayIp`)、协作者提取(`ApkInstaller`、`AppListBuilder`、`AppVersion`、`CarLogWriter`、`CarTouchSender`、`HandshakeFactory`、`VdServerDeployer`、`PhoneDisplayRestorer`、`VdDimensions` 等)、大文件拆分(`CarConnectionService` 1237 → 1137 行)。DPI 范围 `120..480` 和 `app_process` argv tail 在 phone 和 car 之间共享同一份 `VdDeployArgs`。

### 10. VD 泄漏修复 + 清理幂等 + 黑屏自愈(v0.18.0-dev-13)

**根因**:每次重连都泄漏一个 VirtualDisplay。旧代码用 `pkill -9` 杀 VD 进程,跳过了 JVM shutdown hook,`PipelineServer.cleanup()` 从未执行 —— 导致 VD 不释放、物理面板保持关屏、`screen_off_timeout` 设置丢失(快照捕获的是自己写入的 `2147483647` 哨兵值)。连续几次重连后车机变黑屏。

**修复(跨 12 个文件)**:

- **`VdDeploy.stopCommand`**:两阶段停止(SIGTERM → 等 1 秒 → SIGKILL),合并成**一条 shell 命令**,确保协程取消不会落在两个信号之间导致进程半死。
- **`VdDeploy.PROCESS_PATTERN`**:`[P]ipelineServer` 括号技巧,避免 `pkill -f` 匹配到 wrapper shell 自身的 cmdline。
- **`VdDeploy.probeCommand` / `probeExitCodeCommand`**:`pkill -0` 存活探测(信号 0 = 仅检查,不发送信号)。
- **`ConnectionService.cleanupGuard`**:`AtomicBoolean` 幂等 guard,防止 7 个调用点重复执行 `cleanupSession()`(实测 1 次断连 → 9 次 "Force-waking physical display")。新会话开始时 `resetCleanupGuard()`。
- **`ShizukuManager.waitForVdServerExit()`**:轮询直到 VD 进程真正退出,再启动新引擎,防止两个实例争抢同一个 VirtualDisplay / DTA / 9638-9639 端口。
- **`VdServerDeployer.waitForVdServerExit()`**:车机端同样等待旧实例退出后再部署。
- **`PhoneDisplayRestorer`**:自建 process-lifetime `CoroutineScope`(不再是 Service scope,`onDestroy` 不会取消它)。`NonCancellable` 上下文,确保 `pkill` 后 `cmd display power-on` 不会被中断。`inFlight` 单飞 guard 合并并发请求。
- **`PipelineServer.cleanup()` 重排序**:先重置全局 window/rotation 状态,再移回前台 app,再恢复面板/IME,最后释放线程/GL/编码器/VD。
- **`VirtualDisplayCreator.create()` 拆分**:`create()` 不再调用 `configureDisplayEnvironment()`,新增 `configureEnvironment()` 方法 —— 必须在 `saveCurrentIme()` 快照**之后**调用,否则快照捕获的是自己的写入值。
- **`DisplayPowerController.restoreSetting()`**:写入前验证值合理性(非空、非 null/undefined、数字 > 0),防止写入哨兵值。
- **`VideoDecoder.onSustainedBlackScreen`**:持续黑屏检测 —— 仅在连续 5 秒以上微小关键帧后才触发,3 帧的短暂黑帧(启动/重建时的正常瞬态)不会触发重连风暴。
- **`CarConnectionService.rehandshakeForBlackScreen()`**:检测到持续黑屏后,通过重新握手让手机重建 VD。`blackScreenRecoveryInFlight` 闩锁确保每次会话最多触发一次。
- **`scripts/verify-blackscreen-fix.sh`**:自动化日志验证脚本,检查 VD 泄漏、cleanup 幂等、黑屏自愈、停机路径。

---

## 当前功能状态

**已可用:**
- H.264 视频镜像,24fps、4Mbps CBR、Main profile(针对 DiLink 4.0 低配车机调校;高配车机也兼容)
- 完整触摸输入(多点触控、双指缩放)
- 应用启动器:搜索、字母排序、64dp 图标、长按菜单(卸载、应用信息、置顶)
- 应用允许列表:只把选中的应用推到车机
- 车机端 DPI 覆盖:解决 1280x800 默认 DPI 偏小
- 横竖屏跟随:车机旋转时 VD 跟着重建,竖屏应用不再被压成细条
- Shizuku 支持:免 USB ADB 部署 VD server,`pm install` 静默自更新
- 车机应用自更新:手机检测到车机应用版本旧(versionCode 比对)就走 WiFi ADB 更新
- 流式传输时手机屏幕关闭(省电),断连后物理面板恢复
- 引导式权限授权
- 简体中文 + 8 种其他语言(英、葡、俄、白俄、法、哈、乌、乌兹)
- 直连 VD 架构:手机不做中继,视频/触摸直通
- VD 泄漏修复:两阶段优雅停机、清理幂等 guard、VD 退出等待 —— 每次重连不再泄漏 VirtualDisplay
- 黑屏自愈:车机端检测到持续 5 秒以上黑屏后自动重建 VD(重握手)

**已移除(相对 upstream):**
- 通知转发(手机通知 → 车机)—— 导航栏通知按钮、通知列表、相关协议消息全部删除
- 最近任务栏、时钟、网络信息

**未实现:**
- 音频流传输
- 媒体控制
- 导航小部件

**已知限制:**
- Samsung Auto Blocker 必须关掉才能用 USB ADB(设置 → 安全 → Auto Blocker → 关)。
- Samsung 电池管理需要显式豁免。
- Samsung Knox 首次访问虚拟显示器时可能弹安全提示。
- 手机热点需手动开启(Android 16 限制)。
- 偶发花屏 —— 解码器重启竞争,下一个关键帧(~1s)恢复。
- 流式延迟在负载下约 100-200ms。
- 部分应用不铺满屏幕(信箱化/仅竖屏)。DiLink-Auto 把横屏虚拟显示器镜像到车机,不支持横屏方向的应用会出现黑边 —— 这由应用自身决定,镜像侧无法解决。
- API 26-28 车机上,若 DisplayControl 反射失败,`cmd display power-on/off` shell 回退也是 API 29+,物理面板可能无法恢复(会打日志,不再静默)。

---

## 工作原理

1. **开热点** —— 手机开 WiFi 热点,车机连上。
2. **插 USB** —— 手机插车机 USB 口(或同 WiFi 网络)。
3. **自动安装** —— 手机通过 WiFi ADB 把车机应用装到车机(首次,一键)。
4. **自动连接** —— 三条 WiFi TCP 流:视频(9638)、触摸(9639)、控制(9637)。
5. **用应用** —— 从车机启动器点应用,在手机上跑,投到车机,触摸回传。

手机在虚拟显示器上跑应用,编码成 H.264 推到车机;车机屏的触摸事件回传到手机,作为真实触摸事件注入。手机物理屏保持关闭(省电),可独立使用。

完整架构、连接流程、设计决策见 [docs/architecture.md](./docs/architecture.md)。docs 下文档已与本 fork 同步更新。

---

## 系统要求

**手机:**
- Android 10+(`minSdk = 29`)
- 已开启 USB 调试(开发者选项)
- All Files Access 权限(首次启动时引导授权)

**车机:**
- BYD DiLink 3.0+,已在 **比亚迪 秦PLUS DM-i 2023款 冠军版 55KM 领先型**(DiLink 4.0 低配,骁龙 439 / Android 9 / 1280x800)上实测
- Android 8.0+(`minSdk = 26`)
- 一个空闲 USB-A 口(或同 WiFi)

**手机热点必须开启** —— 车机连手机热点。无需配对码、无需 Google 账号。

**不需要联网。** DiLink-Auto 通过手机热点本地传输,车机和手机直接对话。联网只是给手机上跑的应用(导航、音乐)用,DiLink-Auto 本身不需要。

---

## 安装

下载 [Releases](https://github.com/ID-VerNe/dilink-auto-android/releases/latest) 里的 APK,或自行构建:

1. **构建:** `./gradlew :app-client:assembleDebug`
2. **装手机:** `app-client/build/outputs/apk/debug/app-client-debug.apk`
3. **开 USB 调试**(设置 → 开发者选项)
4. **打开 DiLink-Auto**,按引导授权 All Files Access
5. **开热点,插车机 USB** —— 车机应用首次运行时通过 WiFi ADB 自动安装

车机 APK 和 VD server JAR 都打包在手机 APK 内部 —— 不需要单独给车机装东西,安装车机应用也不需要联网。手机插车机 USB,点"Install on Car"即可。

---

## 项目结构

手机 APK(`app-client`)内嵌车机 APK(`app-server`)和 VD server JAR(`vd-server`)。装手机应用时一切就绪。

```
DiLink-Auto/
├── protocol/       共享库(framing、消息、发现、USB/TCP ADB)
├── app-client/     手机 APK —— 编排、VD 部署、车机自更新、应用允许列表
├── app-server/     车机 APK —— UI、连接状态机、视频解码器
├── vd-server/      VirtualDisplay server(编译成 JAR,手机部署)
├── docs/           文档(多语言入口)
└── gradle/         构建系统
```

| 模块 | 角色 | minSdk |
|------|------|--------|
| `protocol` | 共享协议库(UsbAdbConnection、AdbProtocol、VideoConfig、NioReader、FrameCodec) | 26 |
| `app-client` | 手机应用(ConnectionService、VD 部署、车机自更新、FileLog、AllowlistScreen) | 29 |
| `app-server` | 车机应用(CarConnectionService、VideoDecoder、CarShell、HomeScreen、PersistentNavBar) | 26 |
| `vd-server` | Shell 权限进程(PipelineServer、TouchInjector、GlPipeline、DisplayPowerController) | — |

---

## 构建

```bash
# 构建手机 APK(自动跑 buildVdServer + embedServerApk,把 vd-server.jar 和 app-server.apk 塞进 client assets)
./gradlew :app-client:assembleDebug

# APK 位置:
# app-client/build/outputs/apk/debug/app-client-debug.apk  (手机端,内嵌车机 APK)
```

要求:JDK 17、Android SDK 34。release 构建需要 `RELEASE_KEYSTORE_PASSWORD` / `RELEASE_KEY_PASSWORD` 环境变量,否则生成 unsigned APK。

---

## 文档

完整文档(setup、architecture、protocol、client、server、progress)入口在 [docs/README.md](./docs/README.md)。本 fork 的文档以英文为主,与根目录中文 README 互补。

---

## 贡献

PR 欢迎。本 fork 在 `main` 分支上开发(不再使用 upstream 的 git-flow develop 分支模型)。提交前请确认 `./gradlew :app-client:assembleDebug` 通过。

---

## License

MIT,见 [LICENSE](./LICENSE)。本项目 fork 自 [andersonlucasg3/dilink-auto-android](https://github.com/andersonlucasg3/dilink-auto-android),感谢原作者的工作。
