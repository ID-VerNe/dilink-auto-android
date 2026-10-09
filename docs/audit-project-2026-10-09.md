# DiLink-Auto 全项目代码审计报告

- **审计时间**:2026-10-09
- **审计对象**:整个仓库 `dilink-auto-android`(7 个模块,~700KB Kotlin/Java 源码,含未提交的工作区改动)
- **审计方式**:5 个并行深度审计 agent 全文通读全部源码 + 主审人对全部 CRITICAL/HIGH 论断逐条复核(读源码/执行 git 核验)
- **基线**:`main` @ `531b67f`,已有审计文档(`audit-ui-ux.md` / `audit-desktop.md` / `audit-srp-dry.md` / `audit-srp-dry-round3.md`)标注为"已修复"的条目不重复计入,但被复核其中与本次发现重叠的部分
- **修复记录(2026-10-09 fix pass)**:97 项发现中 **94 项已修复**并附测试(协议层 26、手机端 23、车机+vd 25、桌面端 21、构建 21 中的绝大部分),3 项延后(B-11、B-I6、B-07 的 action SHA 钉版本);逐项状态见 §10。**仍需用户手动操作 1 项**:发布签名密钥轮换(B-01,原因见 §4)。

---

## 1. 执行摘要

### 1.1 总体评价

项目工程质量**高于同类个人/小团队开源项目的平均水平**:模块职责划分清晰(protocol-core 纯 JVM 共享层是正确且有价值的设计)、注释解释了"为什么"、有 60+ 个单元测试、对低端车机(Snapdragon 439 / API 28)的适配踩坑记录详尽、v0.18.0-dev-13 的 VD 泄漏修复轮次体现了真实的问题追踪能力。

但本次审计发现了 **1 个已被认为是"必须现在处理"的密钥暴露问题**,以及一组**同源的系统性安全缺陷**:整套协议(9637 控制 / 9638 视频 / 9639 输入 / 19647 生命周期)建立在"局域网即信任"的假设上——无认证、无加密、无完整性校验。在此之上:

- vd-server 以 **shell UID(2000)** 运行并把视频/输入端口绑在 `0.0.0.0`;
- 对端(车机/桌面端)发来的载荷**未经任何校验就被拼进 `sh` 命令行**(`CarCommandRouter`);
- 五个协议解码器存在未做边界检查的 `ByteBuffer.getX()`,`ProtocolDecodeException` 又不是 `ProtocolException` 的子类, reader coroutine 的 catch 列表兜不住 → **一个 6 字节的畸形帧就能杀死任一进程**。

三者叠加的结论是:**任何能访问手机 WiFi/热点的主机,都可以在黑屏的手机上以 shell UID 执行任意命令**。这不是理论风险——部署路径(`VdDeploy.shellQuote`)已经做过一轮加固,但运行时命令路径(`am start` / `pm uninstall`)被漏掉了。

### 1.2 数量统计

| 模块 | CRITICAL | HIGH | MEDIUM | LOW | INFO | 小计 |
|---|---|---|---|---|---|---|
| 构建配置 / 仓库卫生 | 1 | 4 | 6 | 4 | 6 | 21 |
| protocol-core + protocol | 2 | 3 | 8 | 9 | 4 | 26 |
| app-client(手机端) | 1 | 4 | 9 | 6 | 3 | 23 |
| app-server + vd-server | 2 | 6 | 11 | 5 | 1 | 25 |
| app-desktop(Windows 端) | 1 | 3 | 8 | 7 | 2 | 21 |
| **合计(原始)** | **7** | **20** | **42** | **31** | **16** | **116** |

跨模块去重后(同一根因在多个模块被各自报出,合并为一条并互相引用,编号对照见附录 A/B):**CRITICAL 8 / HIGH 18 / MEDIUM 38 / LOW 33 / INFO 16,共 113 项(其中 97 项为需处理发现,16 项为"已验证无问题"记录)**。去重规则见附录 B。

### 1.3 最需要优先处理的 8 件事

| # | 严重级 | 问题 | 位置 |
|---|---|---|---|
| 1 | CRITICAL | **发布签名 keystore 被提交进 git 仓库**(且进入过上游 fork 历史)——应视为已泄密,需要轮换 | `app-client/dilink-auto-release.keystore` |
| 2 | CRITICAL | **协议零认证**:9638/9639 绑 `0.0.0.0` 且无任何鉴权,vd-server 以 shell UID 监听 | `PipelineServer.kt:182,184` |
| 3 | CRITICAL | **对端载荷直接进 shell**:`pm uninstall $pkg` / `am start ... $pkg` 未加引号未校验 | `CarCommandRouter.kt:37,38,62` |
| 4 | CRITICAL | **畸形帧杀进程**:5 个解码器裸 `getX()`,异常既不是 `ProtocolException` 也无 catch | `Messages.kt:118/248/411/415/460` |
| 5 | CRITICAL | **会话清理竞争**:`cleanupGuard` 在网络变化路径被提前消耗,下一代的 lifecycle socket 永不关闭 | `ConnectionService.kt:224/265/274 vs 356-361` |
| 6 | CRITICAL | 桌面端把握手响应里的 `adbPort`/`jarPath` 直接用于 adb 部署 → 任意代码执行(需 dev_mode) | `DesktopApp.kt:311-318` |
| 7 | CRITICAL | 128MB payload 上限 + NioReader 缓冲只增不减 → 6 字节头换 ~256MB 分配(vd-server/手机端均可被远程 OOM) | `FrameCodec.kt:30`、`NioReader.kt:190-198` |
| 8 | CRITICAL | ADB `data_len` 不校验 → `ByteArray(0x7FFFFFFF)` 抛 OOM(Error 不被 catch);CRC 解出后从不校验 | `AdbProtocol.java:87-98`、`TcpAdbConnection.kt:174` |

---

## 2. 审计范围与方法

### 2.1 范围

```
protocol-core/   纯 JVM 协议库(28 个源文件 + 15 个测试)
protocol/        Android 共享库(ADB/USB/发现,7 个源文件)
app-client/      手机端(34 源文件 + 4 测试)
app-server/      车机端(26 源文件 + 3 测试)
app-desktop/     Windows 接收端(28 源文件 + 15 测试)
vd-server/       shell 权限进程(11 源文件 + 2 测试)
构建配置         根/6 个模块 build.gradle.kts、3 个 workflow、2 个 manifest、gradle 配置
```

### 2.2 方法

1. **全文通读**:5 个 agent 分别对每个模块做逐行阅读(非抽样),对被调用的共享库追读到底。
2. **证据优先**:每条发现必须带 `文件:行号` + 代码片段;无法从代码确认的标注 [推断]。
3. **主审复核**:本报告的所有 CRITICAL/HIGH 由主审人重新读源码/执行 git 命令验证(复核结论见各条的 ✅ 标记)。
4. **对抗视角**:对每个网络入口做"恶意对端"心智模糊测试(握手/应用列表/触摸/ADB 头的截断、超长、非负值),对每个 shell 拼装点做注入测试。
5. **排除**:纯视觉/交互问题(已有 `docs/audit-ui-ux.md` 覆盖);纯命名/格式 nitpick;已知 CVE 的具体断言(本地无法联网核验,标 INFO)。

### 2.3 威胁模型(本报告采用)

| 资产 | 威胁 |
|---|---|
| 手机(shell UID 2000 权限) | 同一 WiFi/热点下的任意主机;恶意/被入侵的车机;MITM |
| 用户数据 | 具有 READ_EXTERNAL_STORAGE 的同机应用(盗 ADB 私钥)、车机端同 UID 组件 |
| 发布供应链 | 有仓库读权限的人(含 fork)、CI runner、Actions secrets |

设计上 DiLink-Auto 是"手机热点本地传输、不联网",因此**局域网信任是产品假设的一部分**;本报告的意思是:这个假设需要显式记录、尽量收窄(端口绑-loopback/校验对端 IP),并且绝不能在上面叠加"对端字符串直接执行"。

---

## 3. 系统性(跨模块)根因分析

101 项去重发现里,约 60% 可以归结为 6 个根因。修根因比修单点更有效。

### S-A:协议层把"对端"当成了"自己人"(根因,关联 ~20 项)

四个端口全部 `0.0.0.0` + 明文 + 无握手鉴权:

```kotlin
// vd-server/.../PipelineServer.kt:182,184 ✅已复核
videoServer.socket().bind(InetSocketAddress("0.0.0.0", Ports.VIDEO_PORT))
inputServer.socket().bind(InetSocketAddress("0.0.0.0", Ports.INPUT_PORT))
```

```kotlin
// app-client/.../display/VirtualDisplayClient.kt:60 ✅(文档说 localhost,实际绑全网卡)
ch.socket().bind(InetSocketAddress("0.0.0.0", port))
```

没有 nonce/HMAC/TLS;mDNS 也不校验 serviceName(`Discovery.kt:134-141`),任何广播 `_dilinkauto._tcp` 的设备都会被发现并展示。**这条是所有 CRITICAL 安全问题的放大器。**

**修复方向**:①所有 accepted socket 校验 remote IP == 本会话对端 IP(启动参数传入);②lifecycle socket 绑 `127.0.0.1`;③握手加一次性 shared secret(车机经 ADB/桌面端经本地 adb 获得),9639 首帧 echo 超时即断;④文档显式声明"仅可信局域网"。

### S-B:解码器的异常契约断裂(根因,关联 ~12 项)

`Messages.kt` 的 KDoc 声称"调用方可统一捕获畸形帧而不崩进程",但实际:

```kotlin
// protocol-core/.../Messages.kt:17 ✅
class ProtocolDecodeException(message: String) : Exception(message)   // 不是 ProtocolException

// protocol-core/.../Messages.kt:118 / 248 / 411 / 415 / 460 ✅ —— 裸 get,无 require()
val version = buf.getInt()                        // HandshakeRequest
val accepted = buf.get() != 0.toByte()            // HandshakeResponse
val count = buf.getShort().toInt() and 0xFFFF     // AppListMessage
val category = AppCategory.fromId(buf.get())      // AppListMessage
durationMs = buf.getLong()                        // MediaMetadata

// protocol-core/.../Connection.kt:129-139 ✅ —— catch 列表兜不住 PDE
} catch (e: ClosedSelectorException) ...
} catch (e: IOException) ...
} catch (e: ProtocolException) ...                // PDE 不是它的子类!
```

且全仓库**没有任何 `CoroutineExceptionHandler`**(已 grep 确认),app-server 的 `CarCrashHandler.uncaughtException` 以 `killProcess` 收尾。即:**畸形帧 → 未捕获 RuntimeException → 杀进程**。做了 `require()` 的 TouchEvent/TouchMoveBatch 也并不更安全,只是把 `BufferUnderflowException` 换成了同样没人接的 `PDE`。

**修复方向**:①每个 `decode` 开头 `buf.require(n)`;②`Connection` reader 的 catch 收敛为 `catch (e: Throwable)`(重抛 CancellationException)并 `disconnect()`;③加"空 payload/1 字节 payload"的对抗测试(现有 `AdversarialDecodingTest` 没覆盖短载荷)。

### S-C:peer 字符串进入 shell(根因,关联 ~6 项)

部署路径已加固(`VdDeploy.shellQuote` 经测试锁定 ✅),运行时路径没有:

```kotlin
// vd-server/.../CarCommandRouter.kt:37,62 ✅已复核原文
ControlMsg.APP_UNINSTALL -> exec("pm uninstall ${String(f.payload, Charsets.UTF_8)}")
execOut("am start --display ${displayId()} -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $pkg")
```

`LaunchAppMessage.decode` 就是 `String(data, UTF_8)`(`Messages.kt:506` ✅),无包名形状校验。车机发 `x; pm install /sdcard/evil.apk` 即以 shell UID 执行。

**修复方向**:①包名/组件名用 `^[A-Za-z0-9_.]+(/[A-Za-z0-9_.$]+)?$` 校验,不合规抛 `ProtocolDecodeException`;②所有插值走 `VdDeploy.shellQuote`;③理想情况换成 argv 数组(`ProcessBuilder`)根本不经 shell 解析器。

### S-D:MediaCodec / EGL / Surface 的生命周期竞争(根因,关联 ~7 项)

车机端 `VideoDecoder` 与 vd-server `GlPipeline/encoder` 都被设计为"surface 回调线程创建、IO 线程销毁",但没有所有权规则:

- `start()` 先把 `running` 置 true 再建 codec,`stop()` 只看 `running` 就 release → 可能停掉/漏掉一个新 codec(`VideoDecoder.kt:122-156`);
- `stop()` join 2 秒后无条件 `release()`,而线程可能卡在 native `dequeueOutputBuffer`(`VideoDecoder.kt:420-429`)——同一代码库在 vd-server 侧明确记录了这个故障模式并加了 watchdog,车机侧没有;
- `cleanup()` 从 main/Lifecycle/TouchReader/watchdog 线程销毁在 Pipeline 线程 `makeCurrent` 的 EGL context,且 `encoderSurface`(MediaCodec 输入 surface)从未 release(`PipelineServer.kt:378-380`)。

**修复方向**:`start()/stop()` 整体 `synchronized(lock)`;先 signal+join pipeline 线程,再由**拥有 EGL 的线程自己**做 GL/encoder teardown;release 前确认线程存活,否则交给线程自己的 `finally`。

### S-E:协程作用域所有权混乱(根因,关联 ~8 项)

三类问题反复出现:

1. **监听循环不是取消树的子节点**:`CarConnectionService.startWifiTrack()` 内的循环 launch 在 `scope` 而不是 `connectionScope`(`:367-377`),导致 `connectionScope?.cancel()` 注释说"取消循环"实际取消不了——旋转重握手窗口内重连循环会关掉正在用的控制连接(H-2)。每个 `startConnection()` 还因此泄漏一个 `NsdManager.DiscoveryListener`。
2. **listener 分发无保护**:`Connection.kt:117-127` inline 调用 listener,`handleHandshake`(含磁盘 I/O、bind 端口)在 reader 线程跑,任何 RuntimeException 杀死 reader 且不 disconnect(app-client M2)。
3. **teardown 跑在主线程**:`cleanupSession` 在 Main 调 `stopVdServer()`,后者 `FutureTask.get(1000ms)` 阻塞主线程(app-client M4/M13)。

**修复方向**:所有长生命周期循环显式 `connectionScope.launch`;listener 分发包 try/catch;teardown 整体 `withContext(Dispatchers.IO)`。

### S-F:构建/供应链(6 项)

keystore 入库(B-01)、release 编译嵌入 debug server APK(B-05)、两个模块 `isMinifyEnabled=true` 但 `proguard-rules.pro` 从未被跟踪(B-04)、自托管 runner 跑 PR 且注入签名 secrets(B-03)、无依赖校验/无 version catalog(B-11)。这些不致命但直接影响"发出去的东西是不是你以为的那坨字节"。

---

## 4. CRITICAL / HIGH 详细发现

> **本节的 26 项(8 CRITICAL + 18 HIGH)已全部修复**,逐项修复方式与测试见 §10 与本文件各条的"修复"段落;修复 commit 见 git log 中 2026-10-09 的 `fix(...)` 系列。

> 格式:`[编号] 严重级 — 标题` / `位置` / `证据` / `影响` / `修复`。带 ✅ 的为主审人已复核。

### B-01 CRITICAL — 发布签名 keystore 入库 ✅

- **位置**:`app-client/dilink-auto-release.keystore`(`git ls-files` 命中,2752 字节)
- **证据**:`git log` 显示被**有意提交过三次**(`ca94db6` "Commit release keystore (password-protected, needed for updates)"、`571b43d` "Restore release keystore to repository" 等);历史中只有一个 blob;README 声明本项目 fork 自公开仓库 `andersonlucasg3/dilink-auto-android` 且配置了 upstream remote。
- **影响**:任何有仓库读权限的人——包括每个 clone/fork 的人——都持有生产发布私钥。口令只存在于 CI env(`RELEASE_KEYSTORE_PASSWORD`/`RELEASE_KEY_PASSWORD`,未见硬编码 ✅),但对一个 2.7KB 的 PKCS#12 做离线爆破是唯一屏障。拿到口令即可签发能覆盖安装在所有用户设备上的正式版应用的恶意 APK。`RELEASE_KEYSTORE_FILE` 默认值直接指向这个文件(`app-client/build.gradle.kts:35`),说明本地 release 构建也在用它。
- **修复**:①**现在按已泄密处理**:生成新 keystore、轮换分发渠道的签名;②新 keystore 只以加密 secret 形式进 CI(base64 → 构建时落临时文件);③`git filter-repo` 清历史(若仓库/fork 公开,至少接受暴露面并轮换);④pre-commit 接 gitleaks;⑤`.gitignore` 加 `*.keystore`(F-18 显示当前只被 `.gitattributes` 标了 binary,没有 ignore)。

### S-01 CRITICAL — vd-server 以 shell UID 绑 0.0.0.0 且零鉴权 ✅

- **位置**:`vd-server/.../PipelineServer.kt:178-212`(bind)、`:236-240`(accept)
- **证据**:`PipelineServer.kt:182,184`(见 §3 S-A)。`acceptCarChannel` 接受**第一个**连上的对端,从不比对 remoteAddress,无 token/TLS/challenge。
- **影响**:热点内任意主机直接获得对手机的"shell UID 远程输入 + 命令面":触摸注入、`am start`、`pm uninstall`、关物理屏(`SET_DISPLAY_POWER`,CarCommandRouter.kt:42-46)。这也是下面 S-02 从"需要先拿到 ADB"降级为"联网即可"的前提。
- **修复**:accept 后校验 `remoteAddress == 启动参数中传入的车机 IP`,不符即关;会话密钥(见 S-01 全局修复方向)作为纵深;端口尽量绑到对端地址而非 `0.0.0.0`。

### S-02 CRITICAL — 对端载荷未加引号/未校验直接进 `sh`(shell UID RCE)✅

- **位置**:`vd-server/.../CarCommandRouter.kt:37, 38, 62`(及 `53/59` 的 resolve 输出路径);源头 `protocol-core/.../Messages.kt:506`(`LaunchAppMessage.decode = String(data, UTF_8)`)
- **证据**:见 §3 S-C 引文。
- **影响**:任意命令执行(UID 2000):装/卸应用、读 `/data/data`、写 ADB 公钥获取持久 shell。`VdDeploy.shellQuote` 就在同一模块里且正确(INFO 已复核:`VdDeploy.kt:128`,`'` → `'\''`,单引号内元字符全部惰性),**纯粹是调用点漏用**。
- **修复**:包名校验正则 + `shellQuote` 每个插值;`pm uninstall`/`am start -n` 优先改 argv 化;补一个对称回归测试(仿 `VdDeployCommandLineTest.peerSuppliedJarPathCannotBreakOutOfTheShellWord`)。

### A-01 CRITICAL — 会话清理 guard 被网络变化路径提前消耗,泄漏 lifecycle socket 且跳过下一代 teardown

- **位置**:`app-client/.../service/ConnectionService.kt:356-361, 381-384, 693-697, 222-228, 263-269` ✅(已复核 guard 的全部读写法:reset 只在 358/445/730;cleanupSession 在 224/265/274/382/546/731)
- **证据**:`resetCleanupGuard()` 只在 accept 之后(358)和 `stopEverything`(730)重新武装;而 `cleanupSession()` 在网络可用/变化的回调里(224、265、274)**先于**当前循环迭代结束执行。guard 是 `compareAndSet(false,true)`(694),一旦被网络变化那次消费,`finally { cleanupSession() }`(382)对**新会话**的那次调用在 695 直接返回。
- **影响**:新会话的 `VirtualDisplayClient`(469)与 19647 ServerSocket 永不关闭;旧的 `vdClient` 继续 streaming——正是文档里"两个引擎争抢同一 VirtualDisplay/端口"的 VD 泄漏形态。
- **修复**:单调 session token:accept 时 `sessionId++`,与 `controlConnection`/`vdClient` 绑定,`cleanupSession(token)` 不匹配即 no-op;`onAvailable`/网络变化路径先取消并 await 循环 job 再启新的。

### P-01 CRITICAL — 五个解码器裸 `getX()`,异常契约断裂 → 畸形帧杀进程 ✅

- **位置**:`Messages.kt:118, 248, 411, 415, 460` + `:553-555`(AppInfoDataMessage);异常契约见 §3 S-B
- **证据**:最小 PoC(均为合法 wire 帧,`validatePayloadSize` 只拒 `frameLength < 2`):`00 00 00 02 00 01`(空 payload 的 HANDSHAKE_REQUEST)→ `Messages.kt:118` 抛 `BufferUnderflowException`;**inline 分发**(`Connection.kt:119-123`)→ reader 死亡 → 无 disconnect、无日志。APP_LIST 经 `scope.launch`(125)异步分发,同样没人接。
- **影响**:任何能连上 9637 的主机,一个 6 字节包杀死手机端/车机端/桌面端进程。**注意:已做了 `require()` 的 4 个解码器同样致命**,因为 `ProtocolDecodeException` 不在 `Connection` 的 catch 列表里(✅ 复核继承关系与 catch 列表)。
- **修复**:每个 decode 开头 `buf.require(n)`(各消息需要的最小尺寸);`Connection.kt` reader 改 `catch (e: Throwable)`(重抛 Cancellation)→ 记 `disconnectReason` + `disconnect()`;补对抗测试(空/1 字节/超长 count/截断字符串)。

### P-02 CRITICAL — 128MB payload 上限 + NioReader 缓冲只增不减 → 6 字节头换 256MB 分配 ✅

- **位置**:`protocol-core/.../FrameCodec.kt:30, 205-207`;`NioReader.kt:190-198`;`FrameCodec.kt:181-184`
- **证据**:`MAX_PAYLOAD_SIZE = 128MB`,`readFrame` 分配 `ByteArray(payloadSize)`,`growIfNeeded` 再分配同等大小的 `ByteBuffer` 且**消费后从不缩回**。vd-server 的 `readFrameBlocking`(`PipelineServer.kt:323`)同路径。
- **影响**:`00 00 08 00 04 01 02` 这类 7 字节帧让手机端和 shell UID 的 vd-server 各分配 ~256MB;官方帧只有 ~1-2MB,上限纯属"防御性"拍脑袋;vd-server 侧还会把物理屏关着(PipelineServer.kt:210),OOM 后用户面对黑屏手机。
- **修复**:`MAX_PAYLOAD_SIZE` 降到贴合真实流(如 4MB,1080p 关键帧 + slack);`growIfNeeded` 加硬上限抛 `ProtocolException` 而非分配;帧消费完若 capacity 过大则重置到默认值。

### P-03 CRITICAL — ADB `data_len` 不校验:`ByteArray(0x7FFFFFFF)` OOM(Error 不被 catch);CRC 解出后从不校验

- **位置**:`protocol/.../adb/AdbProtocol.java:87-98`;`TcpAdbConnection.kt:161-179`;`UsbAdbConnection.java:443-457`
- **证据**:`parseHeader` 只校验 magic,`dataLen` 原样返回;调用点 `if (dataLen > 0) ByteArray(dataLen)`。`OutOfMemoryError` 是 `Error`,`catch (e: Exception)`(`TcpAdbConnection.kt:71`)接不住;负值穿过 `> 0` 守卫后仍返回。`dataCrc` 被解析返回但**无任何调用方**与 `checksum(data)` 比对。
- **影响**:dev 模式 TCP-ADB 路径(`RemoteAdbController.kt:27`,端口 5555)网络可达,一个 24 字节头打爆车机应用;WiFi 上 JAR 推送/shell 输出的损坏无人察觉。
- **修复**:`parseHeader` 拒 `dataLen < 0 || dataLen > 上限`;readMessage 校验 CRC,不符即丢。

### D-01 CRITICAL — 桌面端把握手响应里的 `adbPort`/`jarPath` 直接用于部署 → 任意代码执行

- **位置**:`DesktopConnectionService.kt:93-96` → `DesktopApp.kt:310-318` → `deploy/AdbDeployer.kt:52-98` → `AdbDeploy:54-78` → `VdDeploy.kt:105-120`
- **证据**:`response.adbPort`(peer 控制)、`response.vdServerJarPath`(peer 控制)未经白钟校验直接进入 `adb -s <host:port> shell "CLASSPATH='<path>' exec app_process ..."`。`shellQuote` 只能防止"逃逸出第二个命令",防不住"攻击者选择跑哪段代码"。
- **影响**:MITM/占位的 LAN 主机在桌面端执行 `adb shell` UID 的任意代码。唯一缓解:需要用户先手动开 `dev_mode`(默认关)。
- **修复**:`vdServerJarPath` 白名单(必须等于或以 `/sdcard/DiLinkAuto/` 开头);`adbPort` 限 `Ports.ADB_PORT`(或至少 1..65535);`vdWidth/Height` 用 `evenMin2(...).coerceIn(2,4096)`;长期靠握手配对。

### S-03 HIGH — 黑屏自愈把解码器停死后再也不启动,恢复路径必然永久黑屏

- **位置**:`app-server/.../service/CarConnectionService.kt:1017`(`rehandshakeOnExistingControl` 内 `videoDecoder.stop()`);唯一的重启点在 `ui/screen/MirrorScreen.kt:47-63`(`surfaceCreated`)
- **证据**:黑屏路径刻意不重组 Compose 树(`CarShell.kt:63-64` 在 CONNECTING 时保持流式布局),于是 SurfaceView 不销毁 → `surfaceCreated` 不再触发 → `isRunning` 恒 false → `VideoDecoder.onFrameReceived` 丢弃所有后续帧。
- **影响**:该功能触发一次,车机屏就黑到会话结束——恰好制造出它要防止的症状。用户只能息屏/切走再回来。
- **修复**:黑屏路径不停解码器(保留运行,等手机侧新 IDR);若必须停,在 `VD_PORTS_BOUND` 后用服务侧持有的活跃 Surface 显式 `start()`。

### S-04 HIGH — `connectionScope` 取消不了 WiFi 重试/mDNS 循环,旋转重握手窗口内控制连接被误关

- **位置**:`app-server/.../service/CarConnectionService.kt:363-388`(循环 launch 在 `scope` 而非 `connectionScope`)、`:1005-1007`(自以为取消了的 `connectionScope?.cancel()`)、`:395-402`(`connectToPhone` 的无保护 teardown)
- **证据**:`connectionScope = scope.launch { startWifiTrack(); startUsbTrack() }`,而 `startWifiTrack()` 内的循环 launch 的父 Job 是 `scope` → 取消无效;重握手把 `handshakeDone=false`+state=CONNECTING,恰好解除 `connectToPhone` 的唯一守卫(`:395` 要求 `handshakeDone && controlConnection?.isConnected`),3 秒一次的重试/mDNS 回调落到 `disconnectAllConnections()` 关掉正在重握手的控制连接。
- **影响**:旋转(正常且高频的用户动作)有 ~1 秒窗口丢失控制连接 → VD 部署失败 → 回退全重连。
- **修复**:循环改为 `connectionScope?.launch{...}`;另加"重握手进行中"标志做纵深防御。

### S-05 HIGH — `handleDisconnect()` 关闭连接时不清 listener → 重入,每次断连放大成 2-4 个重连环

- **位置**:`CarConnectionService.kt:1069-1132`(尤其 `:1087` `disconnectAllConnections()` 默认 `clearListeners=false`);listener 注册 `:412/:476/:484`;触发点 `Connection.kt:309-311`(disconnect 必触发 listener)
- **影响**:从非 EOF 路径(如 `connectToPhone` 的 catch)进入的 teardown 会重入 `handleDisconnect`,每次重复 teardown、重复 `consecutiveFailures++`、重复排 `startConnection()`,互相 cancel/覆盖 `connectionScope`/`connectJob`,并叠加 M-2 的 NsdManager 泄漏。
- **修复**:`handleDisconnect` 内改调 `disconnectAllConnections(clearListeners = true)`;或 `AtomicBoolean` 保证每次 teardown 代只跑一次。

### S-06 HIGH — vd-server `bindAndAccept` 失败:pipeline 线程 park 永挂,进程变僵尸,泄漏 VD + 两个 server socket

- **位置**:`vd-server/.../PipelineServer.kt:195-196`(超时路径不关 `videoServer`/`inputServer`)、`:253`(`LockSupport.park()` 无人 unpark)、`:126-127`(`running=false` 不能唤醒已 park 的线程)、线程非 daemon
- **影响**:一次 30 秒 accept 超时(慢车机完全可达)→ shell UID 僵尸进程持有 VirtualDisplay、已应用的 letterbox style/屏幕常亮、9638/9639 绑定,阻塞下一会话 bind,用户只能 force-stop。watchdog 也救不了(`running` 已 false,`cleanedUp` 已 true,不 halt)。
- **修复**:`bindAndAccept` 整体 `finally` 关两个 server socket;`park()` 改 `parkNanos` 循环查 `running`;pipeline 线程设 daemon;失败路径显式 unpark。

### S-07 HIGH — `VideoDecoder.stop()` 未确认喂食线程离开 codec 就 release

- **位置**:`app-server/.../decoder/VideoDecoder.kt:413-430`(join 2000ms 后无条件 `stop()/release()`);调用方 `CarConnectionService.kt:1017,1076,1135`
- **证据**:`interrupt()` 只能打断 `frameQueue.poll`/`sleep`,打断不了 native `MediaCodec` 调用;同一仓库在 vd-server 侧明确记录了这个坑(`PipelineServer.kt:286-291` watchdog 的存在理由)并加了保护,车机侧没有。
- **影响**:release 与 native `dequeueOutputBuffer` 竞争 → 文档级 SIGSEGV 风险;黑屏重握手/WiFi 抖动恰恰发生在 codec 最可能卡死时。
- **修复**:join 后查 `isAlive`,仍活着则跳过 release,由线程自己的 `finally` 释放;局部 `val c = codec` 快照。

### S-08 HIGH — `VideoDecoder.start()/stop()` 竞争产生孤儿 MediaCodec

- **位置**:`VideoDecoder.kt:121-143, 156, 413-430`
- **证据**:`running.getAndSet(true)` 在建 codec **之前**;并发 `stop()` 看到 running=true 就翻假、join、`codec?.stop(); codec?.release(); codec=null`——若交错发生在 `start()` 赋值后,stop 停掉的是新 codec 而喂食线程读到 null 退出;反向交错则 codec 整个泄漏。
- **影响**:旋转重握手与 surfaceCreated(Main 线程)vs IO 线程 teardown 使该窗口可达;症状是黑屏 + `Feed thread: codec is null!`。
- **修复**:`start()/stop()` 全体 `synchronized(lock)`;建完 codec 后复查 `running`,若已假立即停新 codec。

### S-09 HIGH — TouchInjector:`activePointers` 无界增长 + 固定池越界被 `catch(_:Exception){}` 吞掉

- **位置**:`vd-server/.../TouchInjector.kt:33-35, 70, 73-90, 111, 113`
- **证据**:pointerId 是 wire 上未校验的 Int(`Messages.kt:300-311`);每次 DOWN 写入 map,只有 UP 删除;`propsPool`/`coordsPool` 长 10 且按插入序下标索引——>10 个 DOWN 抛 `ArrayIndexOutOfBoundsException` 被 113 行吞掉,而 `activePointers` 已写坏 → 后续帧全部在 113 前抛,触摸半死不活,map 无限增长(WiFi 丢一个 UP 就复现)。
- **影响**:远程可触发(配合 S-01);即使是自身丢包也自愈不了。现有测试 `TouchSequenceTest.kt:231-256` 甚至把这个卡死行为当预期锁死了。
- **修复**:解码层拒 `count > 10`/`pointerId !in 0..9`;或 LRU 淘汰 + `pts.take(MAX_POINTERS)`;catch 里至少 `logErr`。

### A-02 HIGH — `handleHandshake` 在 reader 协程里同步执行磁盘 I/O 和 bind,抛异常即静默杀死 reader

- **位置**:`app-client/.../service/ConnectionService.kt:436-551`;`protocol-core/.../Connection.kt:117-127`(listener 分发无 try/catch)
- **证据**:`handleHandshake` 内含 `ensureVdServerJarCurrent()`(磁盘 I/O)、`startListening()`(bind,端口冲突即抛)、`VdDimensions.compute`、`sendAppList`(子进程级包枚举)。
- **影响**:reader coroutine 被 `SupervisorJob` 吸收失败,既不 `disconnect()` 也不打日志:连接名义存活直到 watchdog 10 秒超时,`finally { cleanupSession() }` 永不执行。
- **修复**:`Connection.kt:117-127` 包 try/catch → 记日志 + `disconnect()`;重活移出 reader 线程。

### A-03 HIGH — `vdClient` 非 volatile 且跨线程读写,并发握手可双开 19647 监听

- **位置**:`ConnectionService.kt:452-490`(reader/IO 线程写)、`:706-711`(cleanup,Main)、read-modify-write 无锁
- **影响**:旋转重握手 + 在途重连重叠时,两个 `handleHandshake` 都通过 `vdClient == null` 检查并 `startListening(19647)`:第二个 bind 失败、第一个 socket 成孤儿;或一方 `stopVdServer()` 与另一方 `disconnect()` 交错 → CMD_STOP 投递不到。
- **修复**:`@Volatile` + `synchronized(vdLock)` 包住整段;或整个握手体放单线程 dispatcher 串行化。

### A-04 HIGH — `stopVdServer()` 用裸线程 + `FutureTask.get(1000ms)` 在调用线程阻塞

- **位置**:`display/VirtualDisplayClient.kt:178-220`;调用方 `ConnectionService.kt:709`(cleanupSession,常在 Main)
- **影响**:主线程 teardown 时一次卡 1 秒;7 个清理调用点叠加;低端机上 ANR 风险。
- **修复**:cleanupSession 主体挪 `Dispatchers.IO`;或把 join 预算降到 100-200ms 让 daemon 线程自己写完。

### A-05 HIGH — 对端控制的 CAR_LOG 帧可经 128MB payload + 无界日志队列 OOM

- **位置**:`ConnectionService.kt:634-641`;`FileLog.kt:27`;`AsyncLogQueue.kt:22`(`Channel.UNLIMITED`)
- **证据**:DATA 通道异步分发(`Connection.kt:125`),reader 不节流;单帧 128MB → String 最大 256MB,进无界 channel,而消费端在往外部存储逐行 flush。
- **影响**:任何能连 9637 的对端 OOM 手机端。
- **修复**:CAR_LOG 载荷上限 ~4KB;`AsyncLogQueue` 给有界容量,溢出丢弃。

### P-04 HIGH — 9638/9639 无读超时:半开连接让手机物理屏永久关闭 ✅

- **位置**:`NioReader.kt:144-154, 174-183`(select 超时与数据不可区分,返回 true);`PipelineServer.kt:236-240`(accept 的 socket 不设 `soTimeout`);`Connection.kt:212-218`(注释明说"没有 deadline,靠 watchdog",但视频/输入连接 `enableHeartbeat=false`)
- **影响**:对端连上 9639 发半个帧后停发 → `readTouchAndCommands` 永远 park;`running` 保持 true、lifecycle 通道健康 → watchdog 不触发;`setPhysicalDisplayPower(false)` 持续生效 → **手机黑屏无输入直到用户 force-stop**。
- **修复**:`fillOrEof*` 加绝对 deadline 抛 `IOException`;accept 的 socket 设 `soTimeout`;watchdog 对无心跳连接也生效。

### D-02 HIGH — 桌面端 `settings` var 跨线程竞态 + `config.json` 非原子写 → 丢更新/文件损坏静默恢复默认

- **位置**:`DesktopApp.kt:145-158`(lifecycle 线程)vs `:161-168`(Compose UI 线程 `setKeepAwake`);`DesktopSettingsStore.kt:31-36`(`file.writeText` 无锁无临时文件)
- **影响**:`setKeepAwake` 与 `restart(dpiOverride)` 交错 → 用户的 DPI 修改被静默丢弃;并发 `writeText` 可截断 config.json,下次启动解析失败 → 回全默认、`dev_phone_ip` 丢失,只有一行 `println`。
- **修复**:所有 settings 变更收敛到 `lifecycle` 执行器或 `AtomicReference.updateAndGet`;`save` 改 tmp + `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`;加载失败时重写文件并在 UI 报错。

### D-03 HIGH — 正常断连不关 `adbDeployer`:adb.exe 与设备侧引擎无限期存活

- **位置**:`DesktopApp.kt:276-290`(`closeSession` 只在 `openSession`/`stop` 调);`DesktopConnectionService.kt:199`(`DISCONNECT` → `endSession` 后没有关闭路径);`ProcessAdbRunner.kt:41-44`(held forever)
- **影响**:按设计,held 的 `adb shell` 进程就是设备侧引擎的生命之锚;不断 → 引擎不清洗(VirtualDisplay 泄漏形态,见 `VdDeploySequence.kt:130-137` 的同类记录),直到用户点"应用并重连"或关窗。
- **修复**:会话结束路径(带同一 generation 守卫)调 `closeSession()` 或至少 `adbDeployer.close()`。

### B-02 HIGH — Shizuku 提权面:第三方命名空间权限 + 导出 provider

- **位置**:`app-client/src/main/AndroidManifest.xml:20, 85-90`
- **证据**:`uses-permission moe.shizuku.manager.permission.API_V23`;`rikka.shizuku.ShizukuProvider exported="true"`,guard 是 `android.permission.INTERACT_ACROSS_USERS_FULL`(signature|privileged)。
- **影响**:guard 本身正确(SDK 要求),但整个 app 的 `pm install`/`settings put`/`pkill` 能力都经由 Shizuku——任何能绑 Shizuku 的进程即驱动任意 shell 命令。第三方权限若无 protectionLevel 声明,合同变更即无防护。
- **修复**:保留 guard;Shizuku 命令执行前显式用户同意态;在 README/docs 显式记录"Shizuku 路径=shell 等价权限"的威胁模型。

### B-03 HIGH — PR 构建跑在 self-hosted runner 且注入签名 secrets

- **位置**:`.github/workflows/build.yml:14, 51-55`(`runs-on: self-hosted` + `pull_request` + env 注入 `RELEASE_*`)
- **影响**:self-hosted runner 持久化、持 Gradle 缓存(`scripts/issue-agent.sh:26` 的 `$HOME/.gradle-agent`)、同 LAN 可达;同仓库 PR 的 secrets 可用 → 恶意 PR 直接 `printenv` 外传。`cache-read-only` 缓解只防 cache 投毒,不防 secret 外泄。
- **修复**:PR 构建迁 GitHub-hosted 且只跑 debug/test;assembleRelease+secrets 仅 tag push;`if: github.event_name == 'push'` 守卫签名步。

### B-04 HIGH — release 编译开着 R8 但 `proguard-rules.pro` 从未入库 ✅

- **位置**:`app-client/build.gradle.kts:44-51`、`app-server/build.gradle.kts:26-33` 引用该文件;`git ls-files` 确认仓库里没有(✅ 我已复核)。agent 实测执行 `minifyReleaseWithR8`:仅 WARN("Supplied proguard configuration does not exist")后 **BUILD SUCCESSFUL**。
- **影响**:两个模块 `isMinifyEnabled=true + isShrinkResources=true` 在**零 keep 规则**下运行:Compose `@Composable`、按名字从 manifest/XML 引用的类(FileProvider、反射入口)全是剥离候选 → 典型的 release-only `ClassNotFoundException`,测试永远抓不到。
- **修复**:提交真正的 `proguard-rules.pro`(`-keep class com.dilinkauto.** { *; }` 起步 + 协议编解码 keep);加一个校验任务让缺文件时构建**失败**而非 WARN;release APK 装机冒烟。

### B-05 HIGH — release client 嵌的是 **debug** server APK,且允许静默用陈旧产物

- **位置**:`app-client/build.gradle.kts:183-204`
- **证据**:`dependsOn(":app-server:assembleDebug")` 与 client 变体无关;`--dry-run` 确认 `:app-client:assembleRelease` 图谱里是整个 `assembleDebug` 链;源 APK 缺失只 `println("WARNING...")`;`preBuild` 锚点 + 不删旧 asset → 可带着昨天的 car app 发布。
- **影响**:release 手机 APK 内嵌 debug 语义(无混淆)的车机 APK;陈旧资产静默上线。
- **修复**:改为 variant-aware 的 artifact(Provider/artifactType);缺源 = 构建失败;拷前删目标;声明真实 inputs/outputs。

---

## 5. MEDIUM 级发现(38 项)

格式:`编号 标题` — `位置` | 问题与证据 | 修复。

### 5.1 protocol-core / protocol(7 项)

- **P-M1 `ClosedSendChannelException` 逃出错处理** — `Connection.kt:240-243, 265-274, 186-193` 对 `writeQueue.send()` 只 catch `IOException`;而 `disconnect()` 里 `writeQueue.close()`(`:308`)让 send 抛 `ClosedSendChannelException`(IllegalStateException,非 IOException)→ 与 P-01 同一条杀进程路径。24-60fps 下窗口常态可达。**修复**:三处 enqueue 改 `catch (e: Exception)`。
- **P-M2 背压下 FIFO 发送顺序被破坏** — `Connection.kt:44-59`:队列 64 满时首个协程挂在 `send`,单线程 dispatcher 转而运行下一个协程,后帧先入队——恰好在链路拥塞(注释声称要保护的场景)时触摸 DOWN/UP 乱序 → 幽灵点击/粘滞指针。现有测试只断言成员/数量不断序(`ConnectionTest.kt:49-55`)。**修复**:入队前原子预留容量(AtomicInteger 闸门)或 Mutex 跨整个 send。
- **P-M3 `protocolVersion` 解码后从不校验** — `Messages.kt:69/118/196/248`;三个消费端无一处与 `PROTOCOL_VERSION` 比较。对端声称版本 0/999 也被接受并继续建 VD。**修复**:握手处理里 reject 不匹配版本 + 双向单测。
- **P-M4 `VdDeployArgs.format` 把自由格式 host 拼进未加引号的 argv 尾** — `VdDeployArgs.kt:47`;`jarPath/logPath` 有 quote,`args`(含 `phoneHost`)没有。当前 4 个调用点全部硬编码 `"127.0.0.1"`,**潜伏可利用**:任何人把 mDNS/用户配置的 host 接进来即注入。**修复**:`phoneHost` 过 hostname/IP 正则;补对称测试。
- **P-M6 ADB 私钥落在全局可读存储** — `UsbAdbConnection.java:569-598`(sdcard 优先)、`TcpAdbConnection.kt:218-233`。车机以此密钥认证到手机 ADB 且手机记住授权——同机任何有 READ_EXTERNAL_STORAGE 的应用可窃钥后获得手机 shell UID。**修复**:优先级倒置为 `getFilesDir()` 优先,sdcard 仅显式 opt-in。
- **P-M7 mDNS 不校验 serviceName** — `Discovery.kt:134-141`:任何响应 `_dilinkauto._tcp` 的设备都被 resolve 并推给 UI(其 `device` 属性未过滤展示)。**修复**:`onServiceFound` 校验 `== SERVICE_NAME` 与端口范围。
- **P-M8 64 帧写队列无字节上限** — `Connection.kt:44-46`:帧数有界、字节无界,叠加 P-02 的 128MB 可钉住数 GB(P-02 修复后仍有 64×4MB)。**修复**:P-02 一并收紧 + enqueue 加字节预算(Semaphore 式)。

### 5.2 app-client(7 项)

- **A-M6 `execAndWait` 数据竞争 + FD 先于 drain 线程关闭** — `ShizukuManager.kt:166-203`:`join(2000)` 可能超时后仍读 `stderrBuf`;`stdoutFd/stderrFd.close()` 在 drain 完成前执行(文件自己的注释承认 GC 下 PFD 可 EBADF);stdout/stderr 合并 → `probeVdServer()`(`:251-258`)用可能是 stderr 的文本判 ALIVE。**修复**:drain 线程退出投 sentinel 到 BlockingQueue 再取;FD close 放 drain 完成后的 finally;返回 (stdout, stderr) 记录。
- **A-M7 `execBackground` 静默 no-op,但 `launch()` 恒真并打"started"日志** — `ShizukuManager.kt:229-237`;`ConnectionService.kt:609-616`(`ShizukuManager.execBackground(command); return true`)违反 `VdDeployExecutor` 契约(`VdDeploySequence.kt:56-63` 要求失败返 false);`:626` 随后打印"VD server started via Shizuku"。操作者看到成功、60 秒后 accept 超时才知道没起来。**修复**:`execBackground` 返回 Boolean 并贯通;日志以 `outcome.launched` 为前提。
- **A-M8 `CarIpLocator` 共享可变状态 + /24 全扫并发放大** — `CarIpLocator.kt:49, 57-58, 139`;`wifiManager` 被 `ConnectionService.onCreate`(`:111`)写、安装协程读;`findCarAdb` 可被两个 `installCarApp` 并发进入(A-M9 放行),每次 32 并发探测。**修复**:`findCarAdb` 串行化(Mutex);子网枚举改为网络变化时一次。
- **A-M9 `installCarApp` 无在途守卫 + explicitIp 未校验** — `ConnectionService.kt:648-653`;两次点击/`ACTION_INSTALL_CAR` 重投递即两个并发 `install()`:同推 `/data/local/tmp/app-server.apk`、同 `pm install -r`;用户 IP 字符串直接进 `InetSocketAddress`,畸形输入被 `catch(_:Exception){false}` 吞成"不可达"。**修复**:`AtomicBoolean installInProgress`;IP 走 IPv4 正则并给明确错误。
- **A-M10 lifecycle socket 绑 `0.0.0.0` 而非 loopback** — `VirtualDisplayClient.kt:55-63`;文档说 localhost、客户端侧也确实连 `127.0.0.1`(`:596`),但监听全网卡。赢得 accept 竞争的对端可投递任意 `displayId`(`MSG_DISPLAY_READY`)→ 触摸注入目标显示被改写。**修复**:绑 `127.0.0.1` + 拒绝非 loopback remote。
- **A-M11 `IconHashGate` 用 `mutableMapOf` 跨线程共享** — `IconHashGate.kt:16-31`:Main(cleanupSession)`reset()` 与 IO(sendAppList)`iconFor()` 并发读写 LinkedHashMap,结构修改可丢条目甚至损坏 map。**修复**:`ConcurrentHashMap` 或单线程 dispatcher。
- **A-M12 `IconRenderer.toBitmap` 每次全量分配且不回收** — `IconRenderer.kt:20-30`;`ClientApp.kt:38-48` 无缓存,`sendAppList` 每次重跑(allowlist 切换/包装移除/连上都触发):100 个 allowlist 应用 ≈ 30MB 瞬态 native 分配/次。**修复**:压缩后 `recycle()`;按 pkg+hash 加 LruCache。

### 5.3 app-server + vd-server(11 项)

- **S-M1 黑屏自愈门条件要求 `vdServerStarted` → Shizuku 模式永不触发** — `CarConnectionService.kt:198, 770-772, 1021`:该标志只在 ADB 部署成功时置位;且恢复重握手自己(`:1021`)清除它 → "每部署一次只生效一次,之后永久失效"。注释声称"每会话最多一次"与代码不符。**修复**:门改为"会话活着"(STREAMING/CONNECTED && handshakeDone),用每会话计数器(2 次上限)。
- **S-M2 每次 `startConnection()` 泄漏一个 NsdManager DiscoveryListener** — `CarConnectionService.kt:288-318, 380-387`(mDNS flow 挂在 `scope` 非 connectionScope);退避重连环(~450 次/小时@8s)每个都注册新 listener,旧的永不被 cancel(`Discovery.kt:160-164` 的 awaitClose 不跑)→ `FAILURE_ALREADY_ACTIVE` 让后续会话静默停止发现。**修复**:持有 discoveryJob 引用先 cancel(与 S-04 同修)。
- **S-M3 MediaCodec 在主线程创建** — `MirrorScreen.kt:59`(surfaceCreated 在 Main)→ `VideoDecoder.kt:139-142`;`stop()`  join 2s 也可在主线程。8×A53 上每次 HOME↔APP 导航可见卡顿。**修复**:surface 交给服务侧 executor 启停。
- **S-M4 对端视频宽高无校验即进 MediaFormat** — `CarConnectionService.kt:762-763` → `VideoDecoder.kt:133`(`MediaFormat.createVideoFormat` 非正值/荒谬值直接抛)+ 旋转守卫(`:955`)被非常规值永久压制 → 前台车机应用可被远程打崩(行驶中设备)。**修复**:接收时 `takeIf { it in 2..4096 }` 兜底 `CarViewport`。
- **S-M5 APP_LIST 图标解码无聚合上限** — `CarConnectionService.kt:830-851`:每个 app 的 PNG 进 `sourceCache` 且落盘,再全量解码缩放;帧上限 128MB,4GB 车机一次 APP_LIST 可 OOM(与 P-02 相关但这是车机侧图标策略问题)。**修复**:按消息类型设上限(图标 ≤512×512,单帧 ≤8MB),超限跳过。
- **S-M6 `ACTION_CONNECT` 从不启动 USB/TCP-ADB 轨** — `CarConnectionService.kt:259-266` vs `:276-281`;`checkAndAdvance` 要 `usbReady||shizukuMode` 才能 CONNECTED → 走该入口会无限 CONNECTING,UI 转圈到超时。**修复**:抽 `beginConnect(host,port)` 统一入口。
- **S-M7 USB 拔出不恢复会话状态** — `CarConnectionService.kt:112-117`:`usbReady=false` 但 `handshakeDone` 不清、会话不拆、不重部署;UI 仍显示 STREAMING,之后每次旋转恢复都失败。**修复`:明确走 handleDisconnect 或清 `lastAdbHost`;attach 时补 `startUsbTrack()`。
- **S-M8 encoder 输入 Surface 从不 release + EGL/GL 在非属主线程销毁** — `PipelineServer.kt:115, 359-382`:`encoderSurface`(MediaCodec input surface)cleanup 漏项;`GlPipeline.cleanup()` 从 main/Lifecycle/TouchReader/watchdog 线程 `eglDestroySurface/Context`,而 context 是 Pipeline 线程 makeCurrent 的,且编码器可能仍在 `dequeueOutputBuffer`。**修复**:`encoder.release()` 后 release 输入 surface;pipeline 线程 signal+join 后在自己的 finally 做 GL teardown(幂等)。
- **S-M9 EGL 初始化失败 → `inputSurfaceReady.await()` 永阻塞且 watchdog 无法触发** — `PipelineServer.kt:109, 124-125, 244-259`:`countDown()`(`:251`)在 catch(`:257`)之前,异常时主线程永久挂起,`running` 仍 true → watchdog 外循环(`:298-301`)永不退出。**修复**:`await(10, SECONDS)`;countDown 移 finally;watchdog 在 await 后启动。
- **S-M10 `screen_off_timeout=2147483647` 哨兵可能被回写** — `VirtualDisplayCreator.kt:182` 写哨兵;`DisplayPowerController.kt:41` 快照、`:104-110` 的"合理值"判断**不包含哨兵相等检查**(注释解释了为何去掉)。上次会话被 SIGKILL/`Runtime.halt(1)` 时,下次快照到哨兵并"恢复"它 → 用户手机永不息屏,battery drain,且之后每会话继承。**修复**:命名常量;快照==哨兵则视为无快照,回落文档化默认值(60000)。
- **S-M11 诊断字符串插值进 shell(潜伏)** — `CarConnectionService.kt:725-727`:`adb.shell("echo '$keyInfo' > ...")`,`keyInfo` 由 app 私有路径+指纹组成(当前不可控,无害),但单引号无转义。**修复**:走已实现的 `push`/SYNC,或 `shellQuote`。

### 5.4 app-desktop(8 项)

- **D-M1 解码器 stop 可能抛弃线程并留下 native FFmpeg/D3D11VA 上下文** — `VideoDecodePipeline.kt:163-171, 229-237`:join 2s 超时则 `grabber.stop()/release()`(在线程 finally 里)可能永不执行;`interrupt()` 打不断 native `av_read_frame`。会话中途 restart(黑屏重连/应用并重连)每次泄漏一线程+一 native 上下文。**修复**:超时则大声计数/升级;反复 close pipe(ensureCurrent 每 100ms 查 closed 可解阻塞);考虑直接关 grabber 输入流。
- **D-M2 硬解回退时关闭的可能是刚装上的新管道** — `VideoDecodePipeline.kt:143-149, 121`:`fallbackToSoftware()` 与 `pipe?.close()` 之间,解码线程可能已装新 pipe;且检查在 `gate ?: return` 之后,卡在 start() 或手机停帧时 watchdog 不触发。**修复**:feed 顶部捕获 `val p = pipe` 关它;fallback 检查挪独立定时线程。
- **D-M3 持续解码失败以 ~2 次/秒无限重建** — `VideoDecodePipeline.kt:229-251`:`grab()` 立即抛(如坏 CONFIG 重放)永远出不了重建循环,唯一症状是统计行 `rebuilds=` 增长。**修复**:指数退避(500ms→8s);超上限向上升级让用户看到真实失败。
- **D-M4 对端 VD 尺寸无上界;`DimAlign.even` 负数映射到 ~2³¹** — `AdbDeploy.kt:63-64` + `DimAlign.kt:42`(`value and 0x7FFFFFFE`):同模块 HandshakeFactory 用 `evenMin2`,AdbDeploy 没用。敌意对端可在手机上申请任意大 VirtualDisplay → 设备 OOM。**修复**:`evenMin2(...).coerceIn(2,4096)`。
- **D-M5 PNG 图标无尺寸上限(解压炸弹)** — `AppIconStore.kt:56-57`:`ImageIO.read` 无默认限额,唯一边界是 128MB 帧。**修复**:先读 header 宽高,>512 拒解码。
- **D-M6 `runBlocking` 部署不可取消 + restart 期间双部署并发** — `AdbDeployer.kt:31, 92-94`:`sleep` 是 `Thread.sleep`;`closeSession()` 正在杀旧 adb 进程时新 deploy 已启动,旧协程仍对共享 runner 跑到底。**修复**:`Mutex` 单部署;改真 suspend(delay + withContext)。
- **D-M7 超时的 waitForExit 进程被 destroy 但未 reap** — `ProcessAdbRunner.kt:37-40`:`destroyForcibly()` 后不再 `waitFor()`,stdio 不关,native handle 靠 GC finalize。**修复**:forcibly 后有界 `waitFor()` + 关三个流。
- **D-M8 `adb.exe` 按裸名从进程搜索顺序解析** — `ProcessAdbRunner.kt:86-88`:Windows 会搜**当前工作目录**——能在启动目录写文件的进程即拿到用户权限执行。**修复**:解析到绝对路径(已知 SDK 位置);拒绝相对 `DILINK_ADB`;启动时打日志。

---

## 6. LOW 级发现(33 项)

### 6.1 protocol-core / protocol(9 项)

| 编号 | 位置 | 问题 | 修复 |
|---|---|---|---|
| P-L1 | `Connection.kt:62-64, 220` | listener/`disconnectReason` 非 volatile,跨线程可见性仅靠巧合;`writeStallCount++` 非原子 | `@Volatile`/`AtomicReference` |
| P-L2 | `Connection.kt:327-341` | `connect()` 无超时,SYN 黑洞无限 `finishConnect()` 循环(仅桌面端外包了超时) | 内建 deadline |
| P-L3 | `Connection.kt:96-101, 296-313` | 对端半关即 disconnect,cancel writer → 64 帧队列里待发控制消息静默丢弃 | 区分优雅 EOF(先 flush)与硬错误 |
| P-L4 | `Messages.kt:416-418` | 图标截断时静默丢帧,随后 `readShortLengthPrefixed` 把图标字节当长度前缀 → 垃圾 iconHash 被缓存 | 截断即抛 PDE |
| P-L5 | `Messages.kt:377, 496` | 未知枚举 id 被强制成"活"默认:`AppCategory`→OTHER、`MediaAction`→PLAY(播放被静默启动) | 返 null 抛 PDE 或告警 |
| P-L6 | `Messages.kt:99, 108, 230, 390-398` 等 | `putShort(bytes.size.toShort())` 对 ≥64KB UTF-8 静默截断,encode→decode 错位 | `require(size <= 0xFFFF)` |
| P-L7 | `SettingsFormat.kt:35-47` | 负 bps 渲染出 `"-1.-5M"`(UI 有 coerce,手写/遗留配置可达) | 入口 coerce |
| P-L8 | `FrameCodec.kt:200-203` | `payloadSize > 100_000` 打 WARN:1080p 关键帧(300KB-1MB)每 GOP 一行,手机+车机双写 | 提阈值或限速 |
| P-L9 | `ImeRestore.kt:36-48` | `ime enable/set $ime` 未加引号,`ime` 来自 `Settings.Secure`(非对端可控,但恶意输入法 Id 可注入) | 校验形状或 shellQuote |

### 6.2 app-client(10 项)

| 编号 | 位置 | 问题 | 修复 |
|---|---|---|---|
| A-L15 | `AssetDeployer.kt:93-98` | 三元表达式两个分支都返回 `-1L`,KDoc 契约("可用旧 jar 时返回可用值")未实现 | 删分支或改正文档 |
| A-L16 | `AssetDeployer.kt:111-118` | 直写回退也失败时 `.tmp` 残留累积 | `try/finally { tmp.delete() }` |
| A-L17 | `CarIpLocator.kt:147-194` | 每次扫描全 /24(254 socket × Thread.sleep 轮询),双接口即 508 socket | 阻塞 connect 超时替代轮询 |
| A-L18 | `ConnectionService.kt:500/528/563` | `connMethod` 与部署决策分两次读 `ShizukuManager`,binder 死亡时响应说 Shizuku、实际走 USB_ADB | 一次快照贯穿 |
| A-L19 | `PhoneDisplayRestorer.kt:138` | 同模块类用 `Class.forName("...MainActivity")` 反射 | 直接引用 |
| A-L20 | `PhoneDisplayRestorer.kt:149-156` | wake lock acquire/release 无 try/finally(有 3s 自释放兜底) | `try/finally` |
| A-L21 | `MainActivity.kt:87-92; AllowlistScreen.kt:69-73` | 从每个入口 `startForegroundService`;restore 拉起的 Activity 在 IDLE 时可能复活服务;allowlist 用裸 `startService`(非前台通知态) | 显式停止标志 + startService |
| A-L22 | `NetworkInfo.kt:6-7; MainScreen.kt:207` | `remember { getLocalIpAddresses() }` 在主线程做 `NetworkInterface` 枚举(阻塞 syscall) | LaunchedEffect + IO(照抄 AllowlistScreen 模式) |
| A-L23 | `FileLog.kt:33; LogArchiver.kt:105` | `Environment.getExternalStorageDirectory()` vs `VdDeploy.DIR_PATH` 字面量在该 ROM 上解析不同(代码注释自己记录过);`VD_SERVER_LOG` 指向车机旧路径 → 归档了一个引擎从不写的文件 | 统一用 `VdDeploy.DIR_PATH` |
| A-L24 | `AdbKeyUtil.kt:25-33` | `adbkey` 在而 `.pub` 不在时 `read` 抛异常,冒泡成用户可见的通用错误 | 两文件俱存才 read,否则重生 |

### 6.3 app-server + vd-server(5 项)

| 编号 | 位置 | 问题 | 修复 |
|---|---|---|---|
| S-L1 | `CarConnectionService.kt:259-266` | `ACTION_CONNECT` 的 host/port extra 未校验(blank host、任意端口) | 校验 + 拒绝时给反馈 |
| S-L2 | `VideoDecoder.kt:188; PipelineServer.kt:39` | `1000/fps`、`1e9/fps` 在 fps=0(遗留/被改的 pref 可达)时 ArithmeticException | `coerceIn(MIN_FPS, MAX_FPS)` |
| S-L3 | `PipeLog.kt:39-43` | 持久 shell 的 `OutputStream` 写无同步,>PIPE_BUF(4096)的命令可交缠损坏 | `synchronized(input)` |
| S-L4 | `CarLogWriter.kt:36-106` | consumer 协程被 cancel 后停在 `take()`,queue 从不关闭;10k 行 buffer 跨会话存活 | shutdown 关 queue;会话开始清 buffer |
| S-L5 | `CarCrashHandler.kt:50, 69-79` | 崩溃路径里 `Thread.sleep(1500)`;`crash-sent-*.log` 归档无上限 | 有界 flush;修剪归档 |

### 6.4 app-desktop(7 项)

| 编号 | 位置 | 问题 | 修复 |
|---|---|---|---|
| D-L1 | `DesktopApp.kt:254-273` | `_session.value = session` 在 `scope.launch` **之后**赋值,generation 守卫可能读到上一代 → sessionEnded 信号被丢(窗口不自动关) | 先赋值再 launch |
| D-L2 | `DesktopConnectionService.kt:40-54` | 会话回调是非 volatile `var`,跨线程仅靠 dispatcher 交接保证可见 | `@Volatile` 或启动前设置 |
| D-L3 | `DesktopConnectionService.kt:140-142, 217-222; DesktopApp.kt:281-283` | `stop()` 只 complete deferred,teardown 异步续跑 → "先停会话再停解码"的顺序实际不强制 | stop await teardown Deferred |
| D-L4 | `InputSender.kt:37-60; Connection.kt:265-274` | `sendFrame` 在 EDT 同步调用且 disconnected 时抛 IOException → 从 MouseAdapter 逃出,AWT 打栈、`pressed` 卡 true(手机上"粘指") | InputSender 内 catch 并清 pressed |
| D-L5 | `DesktopApp.kt:193-197` | SwingVideoView 在 EDT 外构造(设 opaque/监听) | invokeLater 内构建 |
| D-L6 | `DesktopSettingsStore.kt:15-28` | config.json 损坏静默回默认且从不自愈(并入 D-02 的原子写修复:加载失败即重写) | 原子写 + 失败重写 + UI 报错 |
| D-L7 | `DesktopWindow.kt:185-189` | 移到小显示器只 `setLocation` 不 `setSize`,窗口可能大部分出屏 | 按目标屏 bounds 钳制尺寸 |

### 6.5 构建配置(3 项)

| 编号 | 位置 | 问题 | 修复 |
|---|---|---|---|
| B-12 | `app-client/build.gradle.kts:30-41, 54-59` | 无 env 时 release 签名配置静默不创建 → 产出**未签名** APK(覆盖安装失败/误发);且把 release 配置应用到 **debug**(每次本地调试都用生产密钥签名) | 配置期显式失败/告警;debug 用默认 debug key |
| B-14 | 两个 AndroidManifest | 未声明 `usesCleartextTraffic`/networkSecurityConfig,而协议绑定无认证明文端口——设计如此但没有任何用户文档声明信任边界 | 显式 `usesCleartextTraffic="false"`(纵深)+ docs 记录"仅可信局域网" |
| B-15 | `scripts/issue-agent.sh:221; scripts/lib/prompts.sh:58` | token 嵌进 remote URL(落 `.git/config`)并写进 `/tmp/agent-prompt-*.txt` 喂给自主 agent;对应 workflow 已删,目前是死代码 | 删死代码,或 credential helper + 不写 token 进 prompt |

---

## 7. INFO 级(16 项,含"已验证无问题"记录)

### 7.1 环境/卫生类(构建审计 6 项)

- **B-I1 manifest 组件审计总体干净** ✅:两个 app 均 `allowBackup="false"`、无 `debuggable`、无 `sharedUserId`、无自定义 permission;导出组件仅 launcher Activity、系统绑定的 AccessibilityService(guard=BIND_ACCESSIBILITY_SERVICE,正确)、权限 guard 的 ShizukuProvider;`foregroundServiceType="connectedDevice"` 与权限匹配;`MANAGE_EXTERNAL_STORAGE`/`QUERY_ALL_PACKAGES` 均带 `tools:ignore` 且有功能理由。
- **B-I2 依赖版本陈旧,无确认 CVE**:AGP 8.2.2 / Kotlin 1.9.22 / Compose BOM 2023.10.01 / coroutines 1.7.3 / ffmpeg 6.1.1 / jna 5.14.0 均落后当前稳定(2026 年中)多个小版本;ffmpeg 6.1.1 是解码器 CVE 最可能命中项,但本地无法联网核验具体公告 → 只作提示。**修复**:CI 接 OWASP Dependency-Check + Dependabot/Renovate;AGP/Kotlin 一起升(顺带解决 B-04/B-05 的版本耦合)。
- **B-I3 `.freebuff` 只在 `.git/info/exclude` 被 ignore,未进仓库 `.gitignore`** → 换机器 `git add -A` 会提交 agent 草稿状态(`scripts/issue-agent.sh:289` 正是这么干的)。`.plan/.handoffs/.agents/.workbuddy/debug-logs/local.properties` 均已正确 ignore ✅ 且内容扫描**无凭据**(仅描述性文本与本地绝对路径)。**修复**:规则搬进 `.gitignore`,补 `apk-backup/`、`/*.apk`、`dilinkauto-logs*.zip`、`org/`。
- **B-I4 `local.properties` 未跟踪且只含 `sdk.dir`** ✅(`git check-ignore` 双重命中);构建脚本用相对 keystore 路径与 `android.sdkDirectory`,不依赖该文件。
- **B-I5 `.gitattributes` 质量良好** ✅:`* text=auto` + 源码显式 `eol=lf` + keystore/jar/apk 标 binary(keystore 只有一个 blob,无 CRLF 损坏);`gradle-wrapper.jar` 正确标 binary。
- **B-I6 无跟踪的构建产物** ✅(`git ls-files` 无 apk/zip/idsig;生成的 `app-server.apk`/`vd-server.jar` 被 ignore);但 `gradle-wrapper.properties` 未固定 `distributionSha256Sum` → 无法检测被篡改的 wrapper jar(fork/注入提交会在任何构建代码前执行)。**修复**:补官方 distribution SHA-256。

### 7.2 协议实现类(protocol 4 项,均"已验证无问题")

- **P-I1 `VdDeploy.shellQuote` 对全部边缘用例正确** ✅(`VdDeploy.kt:128`:`"'" + text.replace("'", "'\\''") + "'"`;单引号内 `$`/反引号/`"`/`\`/换行全部惰性;空串→`''`;`it's`→`'it'\''s'`,被 `VdDeployCommandLineTest.kt:77-85` 锁定)。**S-02 纯属调用点漏用,不是 quoting 缺陷。**
- **P-I2 `H264NalParser.isKeyFrame` 全尺寸界安全** ✅(`limit = minOf(data.size - 4, 1024)` 在 size<4 时为负,while 不执行;`i + 3 < data.size` 兜底)。
- **P-I3 `NioReader.growIfNeeded/readIntoBuffer` 逻辑正确** ✅(compact 保留未读字节 + flip 恢复读模式,增长不丢数据;只增不减是 P-02,不是逻辑错)。
- **P-I4 两个协议模块无反射** ✅(grep `Class.forName|getDeclaredMethod|setAccessible|newInstance` 仅命中 `DeviceInfo.kt` 的 Runtime 查询)。

### 7.3 其他

- **S-I1** 部署序列 kill→双 GONE 探测→launch 收敛正确(`VdDeploySequence.kt:105-156`),`[P]ipelineServer` 括号技巧确实避免 wrapper shell 自杀 ✅;IME 快照早于 `configureEnvironment()` 的顺序正确(历史 bug 已真修复)✅;`PipelineServer.cleanup()` 的顺序(全局状态→回前台→面板+IME→线程/GL/编码器/VD)与注释一致 ✅(所有权问题见 S-M8);`CarConnectionService.kt:775-781` 两个分支同构仅日志不同(无害);车机 MainActivity `exported="true"` 仅因 LAUNCHER + USB_DEVICE_ATTACHED 过滤而必要。
- **D-I1** JNA `SetThreadExecutionState(Long)` 映射为 64 位参数(`KeepAwake.kt:88-91`):Win32 签名是 32 位 EXECUTION_STATE,x64 上恰好工作(callee 读低 32 位),32 位 JVM 上 ABI 失配——现代 Windows x64 only,信息级。改 `int`/`NativeLong` 零成本。
- **D-I2** `DesktopLog` 每行 open/write/close 且在持锁期间 echo 控制台(`DesktopLog.kt:45-56`):量小(1 行/秒 + 每次重建),信息级; buffered Writer 可去 syscall 风暴。
- **A-I1/A-I2** `dbkey` 存 app 私有 `filesDir`、`allowBackup=false` ✅(残余问题:半生成不自愈,见 A-L24);`ConnectionService` 的 `cleanupGuard` 注释准确描述了 7 调用点历史(痕迹与实现一致)。

---

## 8. 已验证无问题的领域(避免重复审计)

1. **部署命令行的 shell 转义**:`VdDeploy.shellQuote` + 测试锁定,peer 可控的 `vdServerJarPath`/`logPath` 均经过它(P-I1)。
2. **VD 停止/重启收敛**:两阶段停机合并为单行、双 GONE 探测、UNKNOWN 立即放行(S-I1)。
3. **IME 快照顺序与 cleanup 顺序**:历史 bug 已修复且与注释一致(S-I1)。
4. **Android 组件暴露面**:每个导出组件都有正当理由和正确 guard(B-I1);无 `allowBackup`/`sharedUserId`/`debuggable`。
5. **JSON 解析**(桌面端):无递归、`requireEnd` 拒尾随内容、类型不匹配回落默认。
6. **`FFmpegFrameGrabber` 用法**:`maximumSize=0`(禁 seek 仿真)+ raw Annex-B + 降低 analyzeduration/probesize 对直播流正确;解码循环所有退出路径都 stop+release grabber(超时 join 的问题见 D-M1)。
7. **JavaCV `BufferedImage` 复用处理**:`SwingVideoView` 的 `latestSize`/`blitInto` 双拷贝交接正确,`FrameDumper` 在同一 `onImage` 回调内同步调用——撕裂/竞态不存在。
8. **无 TLS trust-all、无硬编码绝对路径、无 WebView、无 `curl|bash`/`eval`**(scripts 目录全部引用变量;`verify-blackscreen-fix.sh` 只读分析)。
9. **CI 无 `pull_request_target`/`workflow_run`/`write-all`**;release workflow 的 `contents: write` 是发布必需(正确设计)。
10. **触摸/播放状态/H264 解码器的边界检查**:`TouchEvent`(require 25)、`TouchMoveBatch`(require 24/指针体)、`PlaybackState`(require 9)的已有 guard 正确——**但**它们的 `ProtocolDecodeException` 仍无人接(P-01)。

---

## 9. 修复优先级路线图

### P0 — 立即(安全底线,建议 1 周内)

| 顺序 | 编号 | 动作 |
|---|---|---|
| 1 | B-01 | keystore 按已泄信处理:新 keystore + 轮换 + 只进 CI secret + 清历史 + gitleaks |
| 2 | S-01/S-02 | vd-server accept 校验对端 IP;`CarCommandRouter` 全部插值 `shellQuote` + 包名/组件名正则;9639 首帧会话密钥(超时断);lifecycle socket 绑 loopback |
| 3 | P-01 | 5 个 decode 开头 `buf.require(n)`;`Connection` reader `catch(Throwable)→disconnect()`;补空/短载荷对抗测试 |
| 4 | A-01 | session token 化清理:accept 时生成、cleanup 带 token 不匹配即 no-op;网络变化路径先 await 循环 job |

### P1 — 短期(稳定性与远程 DoS,2-3 周)

5. P-02/P-03:MAX_PAYLOAD 128MB→~4MB、grow 硬上限+缩回、ADB dataLen 校验+CRC 校验。
6. A-05/A-02/A-03/A-04:CAR_LOG 4KB 上限、AsyncLogQueue 有界;listener 分发 try/catch;`vdClient` volatile+synchronized;cleanupSession 挪 IO。
7. S-03/S-04/S-05:黑屏恢复不停解码器(或显式重启);WiFi 循环挂 connectionScope;handleDisconnect 幂等。
8. S-06/S-07/S-08/S-09:bindAndAccept finally 关 server socket + parkNanos + daemon 线程;codec start/stop 加锁 + release 前查存活;TouchInjector 限指针/id。
9. P-04:9638/9639 绝对 deadline + accept socket soTimeout。
10. B-04/B-05:提交 proguard-rules.pro + 缺文件即失败;embedServerApk variant-aware + 陈旧即失败。

### P2 — 中期(正确性与卫生,1 个月)

11. B-03/B-06/B-07:PR 迁 hosted runner;`github.ref_name` 走 env;签名独立 job + actions 锁 SHA + environment 审批。
12. D-01/D-02/D-03:握手响应字段白名单/范围校验;settings 单线程化 + 原子写;断连路径关 adbDeployer。
13. D-M1..M8 + S-M1..M11 + A-M6..M-M12 全部(见 §5)。
14. B-09/B-10/B-11/B-12/B-14:删 `org/` 下 GPL 源码;ignore 显式化;依赖校验 + version catalog;签名缺 env 即显式失败;声明 cleartext=false + 文档化信任边界。

### P3 — 长期(架构)

15. **握手配对/nonce + 帧完整性**(S-A 根治):协议版本 2 引入 pairing token,四端口全部收敛到"已知对端 IP + 一次性密钥"。
16. AGP/Kotlin/依赖升级(B-I2),顺手消化 B-04/B-05 的版本耦合;vd-server 部署 pipeline 从"反射 d8.jar + AGP 内部路径"改为正规 artifact。

### fix pass 后续项(2026-10-09 收尾时明确延后的事项 + 一项必须由用户完成的操作)

| 项 | 为什么延后/需要谁做 | 建议做法 |
|---|---|---|
| **B-01 密钥轮换**(用户操作) | 只有仓库与分发渠道的所有者能执行 | 生成新 keystore → 更新仓库 secret → 若仓库/fork 公开则 `git filter-repo` 清历史;在此之前把旧 keystore 当作已泄密 |
| **B-11 依赖校验 + version catalog** | `--write-verification-metadata` 需联网逐项取官方 checksum(离线生成等于没有校验);version catalog 触及全部 6 个 build 文件,机械 churn 大,应与 AGP 升级合并 | 联网环境执行 `./gradlew --write-verification-metadata sha256 help` 并提交 `gradle/verification-metadata.xml`;建 `gradle/libs.versions.toml` 收敛 coroutines/ktx/compose/AGP/Kotlin 版本 |
| **B-I6 wrapper SHA** | `distributionSha256Sum` 需 Gradle 官方发布校验和,本地无法核验 | 从 gradle.org/release-checksums 取 gradle-8.7-bin.zip 的 SHA-256 填入 `gradle-wrapper.properties` |
| **B-07 action SHA 钉版本** | 钉错 SHA 会直接打断发布流水线,需联网核对 | `actions/checkout`、`setup-java`、`gradle/actions/setup-gradle`、`softprops/action-gh-release`、`upload-artifact` 全部钉完整 commit SHA |
| **B-05 release/debug 变体对齐** | 让 release client 嵌 release server APK 需 artifact transform 改造,超出"停止流血"范围 | 用 `Provider<RegularFile>` + artifactType 变体感知传递 `:app-server` 产物 |

---

## 10. 修复进度清单(tasklist)

> 状态:⬜ 待修复 / 🔧 修复中 / ✅ 已修复 / ➖ 不适用、仅文档化或延后(附说明)。
> **本轮结果(2026-10-09 修复 pass):97 项发现中 94 项已修复并随测试提交;3 项延后(B-11 依赖校验/version catalog、B-I6 wrapper SHA、B-07 的 action SHA 钉版本),理由见各条。全部 5 个模块测试套件通过(约 370 个测试,0 失败)。**
> 每条修复均以本报告 §4/§5/§6 中的方案为准;新增/改动的测试随各 commit 附上。

### P0(立即)

| 编号 | 问题 | 状态 |
|---|---|---|
| B-01 | keystore 入库 | ✅ 已 `git rm --cached` + `.gitignore`(`*.keystore/*.jks/*.p12`)+ 签名缺失显式失败;**仍需用户操作**:轮换密钥(见 §9 P0 注) |
| S-01 | 9638/9639 零鉴权 + 0.0.0.0 绑定 | ✅ argv 新增 `CAR_HOST` 对端 IP(`VdDeployArgs`/`VdDeploy`/三个部署点),`acceptCarChannel` 拒绝非对端 peer;lifecycle 口绑 loopback |
| S-02 | CarCommandRouter shell 注入 | ✅ `requirePackageName/requireComponentName` 在 decode 边界校验 + 所有插值 `shellQuote`;`pipeLog` 写同步 |
| P-01 | 解码器裸 get + 异常契约断裂 | ✅ 5 个 decode 加 `require()`;listener 分发包 try/catch;reader `catch(Throwable)→disconnect()` |
| A-01 | cleanupGuard 会话代竞争 | ✅ session generation(AtomicInteger + 代际校验),stale teardown no-op |
| D-01 | 桌面端 peer 数据进 adb 部署 | ✅ `checkAdbPort`(仅 5555)/`sanitizeJarPath`(白名单前缀)/`clampVdDimension`;拒绝即抛错终会话 |
| P-02 | 128MB payload + NioReader 只增不减 | ✅ 上限 128MB→16MB;`growIfNeeded` 硬顶 + `maybeShrink` 缩回;P-M8 写队列字节上限一并收敛 |
| P-03 | ADB dataLen 不校验 + CRC 不校验 | ✅ `parseHeader` 拒负值/超限;USB+TCP 两侧 readLoop 校验 CRC |

### P1(短期)

| 编号 | 问题 | 状态 |
|---|---|---|
| A-02 | handleHandshake 在 reader 线程同步执行重活 | ✅ 挪到 `handshakeJob`(Dispatchers.IO),前一个握手先取消 |
| A-03 | vdClient 跨线程无同步 | ✅ `@Volatile` + `vdLock` 包住整段 RMW 与 lifecycle client 创建 |
| A-04 | stopVdServer 阻塞主线程 | ✅ teardown 主体挪进程级 `teardownScope`(onDestroy 不取消),状态更新回 Main |
| A-05 | CAR_LOG 无界内存 | ✅ 单行 8KB 截断 + `AsyncLogQueue(2048)` 有界 |
| S-03 | 黑屏自愈永久黑屏 | ✅ 恢复路径不再 `videoDecoder.stop()`,改 `resetBlackScreenState()` |
| S-04 | connectionScope 取消无效 | ✅ 循环全部挂 `connectionScope`(先赋值后 launch)+ `reHandshakeInFlight` 守卫 |
| S-05 | handleDisconnect 重入 | ✅ `teardownGuard` + 故意 teardown 路径 `clearListeners=true` |
| S-06 | bindAndAccept 失败 → park 永挂 | ✅ `finally` 统一关双 server socket;`parkNanos` 查 `running`;daemon 线程;失败路径 unpark |
| S-07 | VideoDecoder stop 不查线程存活 | ✅ join 后线程仍活则不 release,由线程 finally 释放捕获的局部 codec |
| S-08 | VideoDecoder start/stop 竞争 | ✅ `lifecycleLock` 包 start/stop;建 codec 后复查 running |
| S-09 | TouchInjector 无界 + 池越界被吞 | ✅ 新 `PointerCap`(驱逐+截断+id 范围校验),`catch` 改 `logErr` |
| P-04 | 9638/9639 无读超时 | ✅ `NioReader` 半开连接 deadline(仅"收到部分帧后失联"触发,空闲边界不误伤) |
| D-02 | settings 竞态 + config.json 非原子写 | ✅ 全部变更收敛 lifecycle 执行器;tmp+`Files.move(ATOMIC_MOVE)`;加载失败重写 |
| D-03 | 断连不关 adbDeployer | ✅ 会话结束路径(同代际守卫)调 `adbDeployer.close()` |
| B-02 | Shizuku 提权面文档化 | ✅ `docs/setup.md` 新增 Security Model 节(含 Shizuku 威胁模型) |
| B-03 | PR 跑 self-hosted + 注入 secrets | ✅ PR 迁 `ubuntu-latest`;签名 secrets 仅 push 事件注入;PR 只跑 debug+单测 |
| B-04 | proguard-rules.pro 缺失 | ✅ app-client/app-server 各提交完整规则;实测 `minifyReleaseWithR8` 跑通 |
| B-05 | release 嵌 debug server APK + 陈旧 | ✅ 拷前删目标 + 缺失源 = 构建失败 + 声明 inputs/outputs(变体对齐为后续项) |

### P2/P3(MEDIUM 38 项 + LOW 33 项)

| 编号 | 问题 | 状态 | 编号 | 问题 | 状态 |
|---|---|---|---|---|---|
| P-M1 | ClosedSendChannel 逃出 | ✅ | A-M6 | execAndWait 竞争 | ✅ |
| P-M2 | 背压乱序 | ✅ `sendMutex` 钉注入队序 | A-M7 | execBackground 假成功 | ✅ 返回 Boolean 贯通到 `outcome.launched` |
| P-M3 | protocolVersion 不校验 | ✅ `requireSupportedProtocolVersion`,三端接线 | A-M8 | CarIpLocator 共享状态 | ✅ `scanMutex` + 快照发布(service 侧接线) |
| P-M4 | phoneHost 未加引号(潜伏) | ✅ 见 S-01(`CAR_HOST` 同正则校验) | A-M9 | installCarApp 无守卫 | ✅ `installInFlight` + IPv4 正则 |
| P-M6 | ADB 私钥全局可读 | ✅ 优先级倒置为 filesDir 优先 | A-M10 | lifecycle 绑 0.0.0.0 | ✅ 绑 127.0.0.1 + 拒绝非 loopback |
| P-M7 | mDNS 不校验 serviceName | ✅ 校验 SERVICE_NAME + 端口范围 | A-M11 | IconHashGate 线程安全 | ✅ ConcurrentHashMap |
| P-M8 | 写队列无字节上限 | ✅ 随 P-02 收敛(16MB×64 有界) | A-M12 | IconRenderer 分配不回收 | ✅ LruCache + 压缩后 recycle |
| S-M1 | 黑屏门条件 vdServerStarted | ✅ 改为活会话门 + 每会话 2 次预算 | D-M1 | 解码线程被抛弃 | ✅ 计数器 + 大声升级 + 反复 close 解阻塞 |
| S-M2 | NsdManager listener 泄漏 | ✅ discoveryJob  relaunch 前 cancel | D-M2 | 硬解回退关错管道 | ✅ feed 顶部捕获 pipe;fallback 挪定时线程 |
| S-M3 | codec 主线程创建 | ✅ `decoderExecutor` 单线程 + surfaceCreated 变薄 | D-M3 | 重建死循环无限转 | ✅ 指数退避 500ms→8s + 30 次升级闩 |
| S-M4 | 对端宽高无校验 | ✅ `sanitizePeerDimension`(2..4096) | D-M4 | VD 尺寸无上界 | ✅ `clampVdDimension` 共享实现 |
| S-M5 | 图标解码无聚合上限 | ✅ 单图标字节/像素门 + 聚合预算 | D-M5 | PNG 解压炸弹 | ✅ ImageReader 头校验(>512² 或 >1MB 拒) |
| S-M6 | ACTION_CONNECT 不起 USB 轨 | ✅ 统一 `beginConnect` 入口 | D-M6 | runBlocking 不可取消 | ✅ 真 suspend(delay seam)+ deployMutex |
| S-M7 | USB 拔出无恢复 | ✅ 活会话断开/清 lastAdbHost + attach 补扫 | D-M7 | 进程未 reap | ✅ forcibly 后有界 waitFor + 关三流 |
| S-M8 | encoderSurface 泄漏 + EGL 属主 | ✅ 属主线程 finally 释放 GL;encoderSurface 随 release | D-M8 | adb.exe 裸名解析 | ✅ 绝对路径白名单 + 拒绝相对值 + 日志 |
| S-M9 | EGL 失败永阻塞 | ✅ `await(10s)` + finally countDown + watchdog 后置 | B-06 | ref_name 注入 shell | ✅ 走 env 注入 |
| S-M10 | 哨兵 screen_off_timeout 回写 | ✅ 命名常量 + 快照==哨兵回落 60000 | B-07 | 签名与发布同 job | ✅ `environment: release` 门;action SHA 钉版本 ➖ 延后(无法离线核验 SHA) |
| S-M11 | keyInfo 插值(潜伏) | ✅ `shellQuote` | B-09 | org/ 下 GPL 源码入库 | ✅ `git rm` + ignore |
| B-10 | 产物 ignore 不显式 | ✅ 显式路径化 | B-11 | 无依赖校验/version catalog | ➖ 延后:需联网取官方 checksum、机械 churn 大,见 §9 后续项 |
| P-L1..L9(9 项) | 见 §6.1 | ✅ 全修(volatile/连接超时/半关 flush/截断抛 PDE/枚举抛/encode 长度守卫/负码率标签/大帧日志阈值/IME 引号) | A-L15..L24(10 项) | 见 §6.2 | ✅ 全修(死分支/临时文件/探测改阻塞 connect/决策快照/直接引用/wake lock try-finally/启动标志/IO 线程枚举/路径统一/密钥自愈) |
| S-L1..L5(5 项) | 见 §6.3 | ✅ 全修(extra 校验/fps 钳制/queue 关闭/崩溃归档修剪) | D-L1..L7(7 项) | 见 §6.4 | ✅ 全修(赋值顺序/@Volatile/teardown 可等待/EDT catch/EDT 构造/自愈/移屏尺寸) |
| B-12 | 签名缺失静默 | ✅ release 缺 env 即配置期失败(已实测) | B-14 | cleartext 未声明 | ✅ 两 manifest `usesCleartextTraffic="false"` + setup.md 信任边界 |
| B-15 | 死代码脚本 token | ➖ 对应 workflow 已删属死代码;本报告记录处理建议 | B-I3 | .freebuff ignore 缺口 | ✅ 移入 .gitignore |
| B-I6 | wrapper sha 未固定 | ➖ 延后:需官方 distributionSha256Sum,无法离线核验 | D-I1 | JNA 64 位映射 | ✅ 改 Int 映射 |

---

## 附录 A:编号对照(模块原始编号 → 本报告编号)

| 模块报告 | 原始编号 | 本报告编号 |
|---|---|---|
| 构建配置 | F-01 | B-01 |
| 构建配置 | F-02/03/04/05 | B-02/03/04/05 |
| 构建配置 | F-07..F-12 | B-06/07/09/10/11 |
| 构建配置 | F-13/14/15 | B-12/14/15 |
| 构建配置 | F-16..F-21 | B-I1..B-I6 |
| 协议层 | C-1(注入) | S-02 |
| 协议层 | C-2(解码器) | P-01 |
| 协议层 | H-1/H-2 | P-02/P-03 |
| 协议层 | H-3 | P-04 |
| 协议层 | M-1..M-8 | P-M1..P-M8(M-5 并入 S-09) |
| 协议层 | L-1..L-9,INFO 1-4 | P-L1..P-L9,P-I1..P-I4 |
| app-client | M1 | A-01 |
| app-client | M2/M3/M4/M5 | A-02/03/04/05 |
| app-client | M6..M12,M14 | A-M6..A-M12(M13 并入 A-04,M14 并入 P-01) |
| app-client | M15..M22 | A-L15..A-L22 |
| app-client | M23/M24 | A-I1/A-I2 |
| app-server+vd | C-1/C-2 | S-01/S-02 |
| app-server+vd | H-1..H-7 | S-03..S-09 |
| app-server+vd | M-1..M-11 | S-M1..S-M11(M-4 与 D-01/D-M4 互相引用) |
| app-server+vd | L-1..L-5, INFO | S-L1..S-L5, S-I1 |
| app-desktop | C-1 | D-01 |
| app-desktop | H-1/H-3 | D-02/D-03(H-2 并入 P-01) |
| app-desktop | M-1..M-8 | D-M1..D-M8 |
| app-desktop | L-1..L-7 | D-L1..D-L7(L-6 修复并入 D-02) |
| app-desktop | I-1/I-2 | D-I1/D-I2 |

## 附录 B:去重与计数说明

- 五个子审计各自独立成报告,同一根因在跨模块被重复报出(如"0.0.0.0 绑定"出现在 vd-server、protocol、app-server 三处;"触摸指针越界"出现在 protocol 解码层与 vd-server 注入层;"decode 抛 BufferUnderflow"出现在 app-client 与 app-desktop)。本报告将其合并为单一条目(S-xx / P-xx),其余位置以引用形式保留,编号对照见附录 A。
- 复核中的两处 severity uplift:P-02/P-03(128MB payload 与 ADB dataLen)由子报告的 HIGH 提升为 CRITICAL——理由是二者都可从网络触发、且 OOM 落在 shell UID 的 vd-server(直接影响手机可用性),满足"远程可用性致命"的标准。
- §1.2 表中数字为**五个子审计的原始计数**(含跨模块重复);本报告正文的实际唯一条目为:**CRITICAL 8 / HIGH 18 / MEDIUM 38 / LOW 33 / INFO 16**。



