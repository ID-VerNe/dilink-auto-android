# UI / UX 审计报告 — DiLink-Auto

- **审计时间**：2026-10-08（深夜）
- **修复 pass**：2026-10-08（同日）—— 15 项全部闭环，见文末「修复状态」
- **范围（mode: full）**：三个界面的全部用户可见界面与交互 —— `app-client` 手机端、`app-server` 车机端、`app-desktop` 桌面调试端；约 4,400 行 UI 代码 + 两套 `strings.xml`（220 键 × 9 个本地化目录）。桌面端的 probe / 命令行模式无 GUI，不在范围内。
- **方法**：六领域技能逐项对照（better-accessibility / layout / writing / typography / colors / ui）+ Apple 视觉与动效原则附加镜；19 对前景/背景做 WCAG 对比度实测；material3 1.1.2 字节码核对；数值输入控件行为逻辑复刻；`debug-logs/` 截图核查。
- **边界**：只读审计，未改动任何代码。发现编号 `UX-01…UX-15`，与 SRP/DRY 审计编号不混用。

## 范围与覆盖

| 领域 | 检查的证据 | 结果 |
| --- | --- | --- |
| 可访问性 | 三端全部交互控件的命名 / 命中区 / 状态语义：`AllowlistScreen`、`NowPlayingBar`、`PersistentNavBar`+`NavBarComponents`、`UiPrimitives`（桌面自绘控件）、`OnboardingScreen` | 3 项（1 × HIGH、2 × MEDIUM） |
| 布局 | 分组与间距、空态、断点：`MainScreen` / `SettingsScreen` 卡片间距、`CarLaunchScreen` 700dp 宽屏断点、两支应用网格、桌面 96dp 导航栏 | 3 项（2 × MEDIUM、1 × LOW） |
| 文案 | 两套 `strings.xml` 全量 220 键、9 个语言目录覆盖、Kotlin 硬编码字符串扫描、按钮词汇一致性 | 3 项（1 × HIGH、1 × MEDIUM、1 × LOW） |
| 字体排印 | 两套 `Typography`（`CarTheme` 有、`Theme.kt` 无）与全部 `fontSize` 调用点；行高 / 字号下限 | 1 项（MEDIUM） |
| 色彩 | `UiPalette` + `CarTheme` token 体系 + 19 对渲染组合实测（WCAG 相对亮度） | 2 项（1 × HIGH、1 × MEDIUM） |
| UI 细节 | 按钮状态、进度组件、长按菜单、输入控件交互：`CarLaunchSettings`、`CarSetupInstallSection`、`HomeScreen` 菜单 | 3 项（1 × HIGH、2 × MEDIUM） |
| Apple 视觉与动效（附加镜） | 光学校正、弹簧 / 可打断、材质、reduced-motion、邻近原则 | Clear（无新增可执行发现；3 条候选被否决，见否决表） |

## 发现

| # | 严重度 | 领域 | 位置 | 现状 | 建议 | 原因 |
| --- | --- | --- | --- | --- | --- | --- |
| UX-01 | 🔴 HIGH | 文案 | `app-server/.../ui/screen/HomeScreen.kt:365`（菜单触发 `:300`；执行侧 `vd-server/.../CarCommandRouter.kt:36`） | 长按应用磁贴 → 菜单里点 "Uninstall"，**立即**向手机下发 `pm uninstall`，无任何确认、无撤销 | 弹出确认对话框："Uninstall Dropbox？该应用将从你的手机上卸载，无法在车机上撤销。" 按钮为 `Uninstall` / `Cancel` | 破坏性操作跨设备执行（车机点、手机卸）且无二次确认；车机触屏误触代价高，属于"隐去后果"（better-writing §Destructive） |
| UX-02 | 🔴 HIGH | UI | `app-server/.../ui/screen/CarLaunchSettings.kt:94` + `CarLaunchScreen.kt:317-322` | DPI 输入框的显示值由**已强转的持久值**派生（`isCustom` 时取 `currentValueText` 数字），且**每次击键**即 `coerceDpiOverride`。逐键轨迹复刻：输入 `160`→ 首键 "1" 即被强转为 **120**，后续 "6"/"0" 被吞，最终恒为 120；退格同样被弹回 120。手动输入实际只能产生 0 或 120，仅"粘贴"可绕开 | 输入用本地原始字符串状态（`remember { mutableStateOf("") }`），仅在 Done / 失焦时强转并回写；或改为滑杆 / 分段控件 | 控件与用户对抗：想输 160 得到 120，且**静默写入错误配置**（misleads + blocks） |
| UX-03 | 🔴 HIGH | 色彩 | `app-server/.../ui/screen/CarLaunchSettings.kt:131-132` | 选中态预设 chip：`containerColor = primary(#4FC3F7)`、`contentColor = Color.White` → 实测 **2.00:1** | `contentColor = MaterialTheme.colorScheme.onPrimary`（本主题为黑色，**10.48:1**） | 白字压在浅蓝上是全库最低对比度；选中态恰恰是用户最需要读取的状态（WCAG 2.x 正常文本需 4.5:1） |
| UX-04 | 🔴 HIGH | 可访问性 | `app-client/.../AllowlistScreen.kt:110-112` | 搜索框清除按钮：`IconButton { Icon(Close, contentDescription = null) }` —— 图标按钮**完全没有可访问名称** | `Icon(Close, contentDescription = stringResource(R.string.clear_search))` | TalkBack 只读出"按钮"，用途对辅助技术用户不可达（better-accessibility §Accessible names 示例同级别） |
| UX-05 | 🟠 MED | 色彩 | `MainScreen.kt:143` / `CarSetupInstallSection.kt:192` / `CarLaunchSettings.kt:145` | 三处字面量颜色实测不达标：停止按钮黑字压 `#D32F2F` = **4.22:1**；安装阶段"待执行"字 `#757575` 压 `#1A2332` = **3.43:1**（12sp）；hint 文字 `onSurfaceVariant@0.7α` 压 `#161B22` = **4.32:1**（11sp） | 停止按钮补 `contentColor = Color.White`（4.98:1）；待执行字用 `onSurfaceVariant`（7.45:1）；hint 去掉 0.7α 减弱（7.45:1） | 逐处手挑颜色 + 透明度衰减，未按渲染组合验证；三处均低于 4.5:1（better-colors §Measure contrast） |
| UX-06 | 🟠 MED | UI | `app-server/.../ui/screen/CarLaunchScreen.kt:331` | 码率当前值显示 `"${startupBitrate / 1_000_000}M"` 为整数除法：选中预设 **2.5M** 时显示 **"2M"**，与相邻的 "2M (极速)" 预设显示完全相同 | 用小数格式化并去掉尾部 `.0`（`2.5M` / `4M`） | 显示值 ≠ 实际配置值；用户无法分辨 2M 与 2.5M 哪个在生效 |
| UX-07 | 🟠 MED | 字体排印 | `CarTheme.kt:32-41`、`Theme.kt:14-25`、`MainScreen.kt:84`、`CarLaunchSettings.kt:137,147`、`AllowlistScreen.kt:178` | 车机端定义了字阶却几乎不被使用（各屏继续手写 11–28sp）；手机端**没有** Typography，全靠 M3 默认 + 逐处硬编码；11sp 出现 4 处（低于 12sp 底线）且行高另手写（22sp/15sp） | 两端各建一套语义化字阶（`bodySmall` / `label` …）替换全部裸 `fontSize`；11sp 提到 ≥12sp | 没有体系时一致性靠自觉；同类信息已出现 12sp/13sp/14sp 混用（better-typography §Type scale、§Size floors） |
| UX-08 | 🟠 MED | 布局 | `app-server/.../ui/screen/HomeScreen.kt:300-305` | 应用磁贴的辅助操作（置顶 / 卸载 / 应用信息）**只存在于长按菜单**，界面上无任何提示 | 磁贴上给一个可见的溢出入口（角落 `⋮`），或首启时提示"长按磁贴可管理应用" | 车机触屏上"长按"是无提示的隐藏手势；隐藏内容必须有可见线索（better-layout §Hint at hidden content） |
| UX-09 | 🟠 MED | 布局 | `AllowlistScreen.kt:147-190`、`HomeScreen.kt:204-226` | 两个搜索框输入无匹配时，列表直接空白，无任何反馈（无"未找到"、无清除入口） | 空结果态：`No apps match "xxx"` + `Clear search` 按钮（清空 query） | 空态要指路并给下一步；当前用户会误以为列表加载失败（better-writing §Empty states） |
| UX-10 | 🟠 MED | 可访问性 | `NowPlayingBar.kt:74,80,86` | 传输控制按钮名称硬编码英文，且播放/暂停恒为 `"Play/Pause"`——不随状态变化，TalkBack 无法告知当前该动作是播放还是暂停 | 名称走资源且随状态：`if (playing) "Pause" else "Play"` | 可访问名称必须反映当前状态；固定复合标签让用户无法判断控件含义（better-accessibility §Accessible names） |
| UX-11 | 🟠 MED | 可访问性 | `app-server/.../ui/nav/NavBarComponents.kt:27-40` + `PersistentNavBar.kt:112-115` | 横屏左栏三键（Home/Back/Eject）命中区实测约 **40×60dp**（图标 40dp 即按钮全宽），低于触屏 44–48dp 指导值 | 给按钮最小宽度约束（≥48dp）或让图标区 `fillMaxWidth()` | 车机使用场景颠簸、盲操；横向导航栏是投屏模式下的常驻控件（better-accessibility §Hit areas） |
| UX-12 | 🟠 MED | 文案 | `CarLaunchScreen.kt:311-313,334-338,360-363`、`CarLaunchSettings.kt:99`、`AppInfoDialog.kt:37-41,46`、`MainScreen.kt:88`、`AllowlistScreen.kt:92`、`SettingsScreen.kt:106,242`、`values-zh/strings.xml`（缺 6 键） | 用户可见文案绕过资源体系：车机设置预设写死中文（`"2.5M (推荐439)"`、占位符 `"自定义"`、`"Auto"`），App 信息对话框 5 个标签 + `"OK"` 写死英文，若干 `contentDescription`（`"Back"`/`"Settings"`）与 `"unknown"` 写死；`values-zh`（通用中文）缺 `video_bitrate_*` / `video_fps_*` 6 键（`zh-rCN` 有） | 全部移入 `strings.xml`（预设标签按语义键名 + 各语言目录翻译）；补齐 `values-zh` 6 键 | 十语言应用里中文写死在英文壳中（英文用户看到"推荐439"）；写死文案既不可翻译也不可统一改稿（better-writing §One voice；better-accessibility 命名影响） |
| UX-13 | 🟠 MED | UI | `app-client/.../ui/SettingsScreen.kt:184-198` | Shizuku 行在"未安装 / 未运行"状态：卡片带 chevron + 涟漪（看似可点），`onClick` 的 `when` 无分支命中 → **点了什么都不发生** | 未安装时改为引导动作（打开应用市场 / 说明页）并在描述文案中写明"点此安装"；确为纯信息行时去掉 chevron 与点击态 | 有交互外观的死链接；车机配对前置条件里 Shizuku 是常见路径，用户会反复点击无果（better-ui §controls distinct / affordance） |
| UX-14 | 🟡 LOW | 布局 | `MainScreen.kt:135,151,159` vs `:171,226` | 卡片间距 12–24dp **小于**卡片内边距 16–20dp：状态卡与主按钮之间仅 12dp，而卡内元素离卡边 20dp，分组语法倒挂 | 卡间距 ≥ 卡内边距的 2 倍（如统一卡间距 16–24dp，或把 12dp 提到 24dp） | 组内应比组间更近；倒挂会让相邻卡片看起来像一个散块（better-layout §Group with space） |
| UX-15 | 🟡 LOW | 文案 | `MainScreen.kt:288` vs `CarSetupInstallSection.kt:94` | 同一"安装重试"动作在两个界面分别叫 **"Continue"** 和 **"Retry"**；主屏按钮叫 "Install"，设置区叫 "Install on Car" | 同一动作用一个词：统一为 `Retry install` / `Install on Car` | 同流程多词汇会让用户怀疑按钮行为不同（better-writing §Consistent flow vocabulary） |

## 已考虑但被否决的候选

| 位置 | 候选 | 否决原因 |
| --- | --- | --- |
| `PersistentNavBar.kt` / `MirrorScreen.kt` | 按 Apple 材质原则给导航栏加半透明 / 模糊 | 骁龙 439 上 blur/alpha 合成是实打实的每帧成本，且纯色对行车可读性更稳——属有意取舍，不是遗漏 |
| 两端全部 Compose 按钮 | 引入 `scale(0.96)` 按压缩放（Apple 手感原则） | 平台惯例是涟漪反馈（已有）；自绘缩放与 M3 设计系统冲突，且无证据表明反馈不足 |
| `MainScreen.kt:229` / `CarLaunchScreen.kt:217` | 状态圆点仅颜色编码 | 每个圆点旁都有文字状态标签，冗余通道已具备，"不依赖颜色"已满足 |
| `SettingsScreen.kt:126` | 已授权项标题追加的 `" ✓"` 字形 | 描述行已用文字给出 "Granted"，AT / 色觉用户已有冗余；改造收益低 |
| `app-desktop` 各控件 | 无 hover 反馈、自绘勾选行无语义 | 内部调试工具、指针命中区充足（整行可点）；未在运行中复现问题，证据不足以立案 |

## 验证

**已执行**

| 检查 | 方式 | 结果 |
| --- | --- | --- |
| UI 源码全量精读 | 三端 30 个 UI 相关文件（含主题 / 字符串 / Manifest） | 全部发现均带 `文件:行` 与现状代码 |
| 对比度实测 | 内联 Python 按 WCAG 2.x 相对亮度公式复算 19 对渲染组合（含 α 合成的 `#AAAAAA@0.7`） | UX-03/05 数值即实测值；另确认 `Share` 按钮（黑字 `#2196F3` = 6.72:1）、未选中 chip（6.55:1）等**合格**，未立案 |
| material3 版本行为核对 | 解包 `material3-1.1.2.aar`（Compose BOM 2023.10.01）用 `javap` 读 `ButtonDefaults.buttonColors` 字节码 | 确认自定义 `containerColor` 时 `contentColor` 默认取 `onPrimary`（黑）——据此**撤回**了一条"Share 按钮白字对比度不足"的误报 |
| 数值输入行为 | 用脚本逐击键复刻 `CarLaunchSettings.kt:94` + `CarLaunchScreen.kt:317-322` 的取值 / 强转链 | DPI 字段恒收敛到 120（见 UX-02 轨迹）；标注为**源码推导 + 逻辑复刻**，未在真机复现 |
| 截图核查 | `debug-logs/*.png`（车机底栏三键、"连接中"空态） | 底栏布局与空态外观正常；截图状态有限，仅作外观参照 |

**未验证（受环境限制，不转化为发现）**

- TalkBack 实际朗读、键盘 / D-pad 焦点遍历（车机无方向盘按键场景未测）
- 真机触控复现（UX-02 的输入卡死、UX-11 的命中区手感）
- 系统字体放大 200% 下的布局表现（多处固定高度 56dp / 30dp 按钮存在裁剪风险，但未实测）
- `app-desktop` 运行态表现（未启动窗口）、RTL 镜像（`supportsRtl="true"` 但无方向相关布局测试）

## 结论

**Block**（审计轮次）→ **Approve**（修复 pass 后）—— 4 项 HIGH 已闭环（UX-01 远程卸载二次确认、UX-02 手动输入本地原始文本 + 提交时强转、UX-03 选中态 chip 改用 `onPrimary`、UX-04 清除按钮补可访问名称）。车机侧条目仍建议以真机回归收口（见「修复状态」§验证边界）。

---

# 修复状态（2026-10-08 修复 pass）

15 项全部修复。验证基线：**282 个单测全绿**（protocol-core 80 / app-desktop 106 / app-server 38 / vd-server 34 / app-client 24，较修复前 +10），`./gradlew test :app-client:assembleDebug :app-server:assembleDebug` 通过。

## HIGH

| # | 状态 | 修复位置 | 做法 |
| --- | --- | --- | --- |
| UX-01 | ✅ | `HomeScreen.kt`（`AppGrid`）+ 两套 `strings.xml` | 新增 `pendingUninstall: AppTileData?` 状态：菜单点 Uninstall 只**记下目标**，弹出 `AlertDialog`（复用 `AppInfoDialog` 的容器色/字色约定），确认按钮用 `colorScheme.error` 着色。新键 `action_uninstall_confirm_title`（带 `%1$s` 应用名）/ `action_uninstall_confirm_body` / `action_cancel` |
| UX-02 | ✅ | `CarLaunchSettings.kt` + `CarLaunchScreen.kt` + 新增 `SettingsFormat.kt` | 字段改为**自持原始文本**（`var rawText by remember(manualSeed)`），`onValueChange` 只做数字过滤 + 3 位截断，**不再逐键强转**；仅在 `ImeAction.Done` 与 `onFocusChanged`（`hadFocus` 门闩防首次合成 / 防 Done 与失焦双提交）时经 `coerceManualValue` 提交。强转逻辑移入 protocol-core 的 `SettingsFormat`（JVM 可测） |
| UX-03 | ✅ | `CarLaunchSettings.kt` | `contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else …` —— 本主题 `onPrimary` 为黑，实测 **10.48:1**（原白字 2.00:1） |
| UX-04 | ✅ | `AllowlistScreen.kt` | 清除按钮补 `contentDescription = stringResource(R.string.clear_search)`；顺带补上同屏另外 2 处硬编码名称（`action_back`）与 `MainScreen` 的 `action_settings` |

## MEDIUM / LOW

| # | 状态 | 修复位置 | 做法 |
| --- | --- | --- | --- |
| UX-05 | ✅ | `MainScreen.kt` / `CarSetupInstallSection.kt` / `CarLaunchSettings.kt` | 停止按钮补 `contentColor = Color.White`（4.98:1）；安装阶段"待执行"由 `#757575` 改 `onSurfaceVariant`（7.45:1）；hint 去掉 `0.7α` 衰减（7.45:1）。Share 按钮的 `onPrimary`（6.72:1）改为**显式书写**并加注释，避免下一轮被误当成遗漏 |
| UX-06 | ✅ | 新增 `protocol-core/.../SettingsFormat.kt` + `SettingsFormatTest.kt` | 新增 `formatBitrateMbps`：纯整数运算、四舍五入到 2 位小数、去尾零，`2_500_000 → "2.5M"`、`4_000_000 → "4M"`。**顺带收敛了另一处漂移**：手动码率原先自带 `coerceIn(1, 20)` Mbps，与 `VideoConfig.MIN_BITRATE..MAX_BITRATE`（UI 区间，1–12 Mbps）不一致；现统一走 `SettingsFormat.coerceBitrate` |
| UX-07 | ✅ | 新增 `ClientTypography`（`Theme.kt`）、扩写 `CarTypography`（`CarTheme.kt`）、两端全部调用点 | 手机端原先**没有 Typography**；两端各自建立语义化字阶并替换**全部**裸 `fontSize`（修复前 client 42 处 / server 22 处 → 现在仅剩 token 定义内的 `fontSize`）。字号一律维持原值，13sp 并入 `bodySmall`(12sp)、11sp 提到 12sp（`AllowlistScreen` 包名、`CarLaunchSettings` chip 与 hint）。补上原先**未定义、静默落到 M3 默认值**的 `titleSmall` / `bodySmall` | 
| UX-08 | ✅ | `HomeScreen.kt` | 搜索框上方增常驻提示行 `app_manage_hint`（"长按应用可置顶、卸载或查看应用信息"），走 `bodySmall` + `onSurfaceVariant` |
| UX-09 | ✅ | `HomeScreen.kt` / `AllowlistScreen.kt` | 两处搜索均加空结果态：`no_apps_match` / `allowlist_no_match`（含用户输入）+ `clear_search` 动作（车机端用 `TextButton`，手机端在 `LazyColumn` 内作为 `item(key = "no_match")`） |
| UX-10 | ✅ | `NowPlayingBar.kt` | 名称走资源且**随状态**：`if (isPlaying) R.string.now_playing_pause else R.string.now_playing_play`，上一首/下一首同样入资源 |
| UX-11 | ✅ | `NavBarComponents.kt` | `NavActionButton` 根 `Column` 加 `widthIn(min = 48.dp)`；横屏左栏可用宽度 68dp（`NAV_BAR_TARGET_DP = 76f` − 2×4dp 内边距），不会裁切 |
| UX-12 | ✅ | 两端 `strings.xml` + `CarLaunchScreen` / `CarLaunchSettings` / `AppInfoDialog` / `MainScreen` / `AllowlistScreen` / `SettingsScreen` | 车机预设标签（`preset_*` × 11）、`manual_custom_hint`、`fps_value`、App 信息对话框 5 标签 + `action_ok`、若干 `contentDescription`、`value_unknown` 全部入资源。**`values-zh` 缺的 6 键补齐**；三套完整语言目录（`values` / `values-zh` / `values-zh-rCN`）经脚本校验为 **108/108、145/145 完全对齐** |
| UX-13 | ✅ | `SettingsScreen.kt` + `ShizukuManager.kt` | `when` 补 `else -> ShizukuManager.openShizukuStorePage(context)`（新增：先试 `market://` 再回落 GitHub releases）；`shizukuAvailable` 分支由空实现改为打开 Shizuku 应用（原本也是死点击）；未安装态文案改为"未检测到 Shizuku … 点击打开安装页面" |
| UX-14 | ✅ | `MainScreen.kt` | 状态卡与主按钮之间 12dp → **24dp**，Samsung 警告卡之后同样 12 → 24dp，Share Logs 前重复的 24+16dp 合并为 24dp；卡间距自此大于卡内 16–20dp 内边距 |
| UX-15 | ✅ | `MainScreen.kt` / `CarSetupInstallSection.kt` | 三处重试动作统一为 `car_app_retry`（"Retry"/"重试"），主屏安装按钮由 `car_app_install`("Install") 改为 `install_on_car`("Install on Car")，与引导流按钮同词。**未新增键**——复用既有翻译，7 个非中文目录无需重译 |

## 修复 pass 中的附带决定

| 决定 | 原因 |
| --- | --- |
| 手动码率上限由 20 Mbps 收紧到 `VideoConfig.MAX_BITRATE`(12M)，预设 6M 不受影响 | 原 `coerceIn(1, 20)` 是第 5 份码率区间；`VideoConfig` 的 KDoc 明说"settings UI 是消费者之一，不得再漂移"。已写入 `SettingsFormatTest` |
| 预设 chip 行改 `FlowRow` | UX-07 把 chip 文字 11→12sp 会**放大**溢出风险（最窄情景 5 个 chip 需 ~394dp、可用 ~341dp）。`FlowRow` 让换行成为布局行为而非裁切 |
| 字阶 token **不设 lineHeight**，两处段落级 `lineHeight` 保留在调用点 | token 只设 `fontSize`(+`fontWeight`) 时，`Text(style = token)` 与原先的 `Text(fontSize = n.sp)` 渲染**逐位相同**（`merge`/`copy` 的差异仅在被指定字段上）。设了 lineHeight 会让所有多行文本与包裹高度变化——在没有车机回归的前提下不引入这类不可验证的位移 |
| 7 个非中文语言目录**不补英文占位** | 缺键时平台回落到 `values/`（英文），与写入英文占位**输出完全一致**且少一份维护负担。缺口由 3→8（client）/20→48（server）键确实扩大了，属"需要真实译者"的独立工作项，不是本轮的机制缺陷 |
| `app-desktop` 未改动 | 审计 15 项无一落在桌面端（唯一的桌面相关候选已在否决表内） |

## 验证边界

**已在本地验证**：`./gradlew test :app-client:assembleDebug :app-server:assembleDebug` 通过；282 单测全绿；多语言键对齐脚本（三套完整目录 0 缺失 0 多余）；`SettingsFormatTest` 锁定码率标签、`manualSeed` 空值语义与三者的强转边界。

**无法在本地验证**（与审计轮的"未验证"清单一致）：TalkBack 实际朗读、车机真机触控下的 UX-02 输入流程与 UX-11 命中区手感、`FlowRow` 在车机实际密度下的换行表现、系统字体放大 200% 的布局。**判据仍是"车机连上后能出画面 / 能触摸 / 能旋转 / 能恢复"**，建议随下一次 BYD 真机回归一并复测 UX-01/02/03/06/08/11。

## 闭环记录（2026-10-10 追加，正文未改写）

15 项**全部修复落地**（4 HIGH 在内），随 2026-10-10 提交 `d8a5900` 入库：

- **可在本环境验证的条目**（手机端 `app-client` / 桌面端 `app-desktop` UI）：已随 2026-10-10 MI 9 + Windows 实机轮回归通过（显示页 / DPI 200↔300 / 桌面端 F11 与失败重试等，16 项真机清单全过）。
- **车机端条目（`app-server` UI）**：代码已落地；BYD DiLink 4.0 真机未接入本环境，仍按上文判据待真机复测 UX-01/02/03/06/08/11。

