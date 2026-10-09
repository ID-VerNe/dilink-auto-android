package com.dilinkauto.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.rememberWindowState
import com.dilinkauto.desktop.DesktopApp
import com.dilinkauto.desktop.display.Screens

/** 主视图：镜像（手机画面）/ 应用启动器 / 显示设置。 */
enum class DesktopView { MIRROR, APPS, DISPLAY }

/**
 * 桌面窗口外壳：左侧导航栏 + 主视图。
 *
 * 直接把 [DesktopApp] 交给外壳（而不是十几个回调参数）：窗口模式的所有编排都在
 * 那里，UI 只负责"读状态、调方法"。会话代（[DesktopApp.Session]）变化时，
 * 视频组件按 generation 重建。
 *
 * **视频面板只在"镜像"页组合**（audit WIN-01）：AWT 组件恒在 Compose 内容之上，
 * 常驻组合会让「应用」「显示」两页被它盖住。
 *
 * [DesktopApp.Session.sessionEnded] 变为 true 时自动关窗退出（手机断开/会话出错时
 * 不留一个空窗口）。这个信号只由**没有被下一代顶替**的会话发出，所以"应用并重连"
 * 不会误关窗口（audit WIN-04，判据在 [DesktopApp]）；且只由**到过 STREAMING** 的
 * 会话发出 —— 启动失败（手机端未启动、连接被拒）时窗口保留，用户可点
 * 「应用并重连」重试（2026-10-09：否则双击 exe 3 秒即整窗退出，形同闪退）。
 *
 * 导航三键与车机端 `PersistentNavBar` 语义一致，但走输入口发
 * [com.dilinkauto.protocol.ControlMsg]：主页 = GO_HOME、返回 = GO_BACK、最近 = GO_RECENT。
 */
@Composable
fun DesktopWindow(app: DesktopApp, onClose: () -> Unit) {
    val session by app.session.collectAsState()
    val ended by (session?.sessionEnded ?: NoSessionEnded).collectAsState()
    val keepAwake by app.keepAwakeOn.collectAsState()
    val hardwareInUse by (session?.hardwareDecode ?: NoHardwareDecode).collectAsState()
    val appList by (session?.apps ?: NoApps).collectAsState()

    var view by remember { mutableStateOf(DesktopView.MIRROR) }
    // 窗口宽度额外加上导航栏（audit WIN-14）：视频面板占的是"窗口宽 − 导航栏宽"，
    // 只有把导航栏算进去，这块区域才与握手视口同比例，画面不出现上下黑边。
    val windowState = rememberWindowState(
        size = DpSize(app.viewportWidth.dp + RAIL_WIDTH, app.viewportHeight.dp),
    )
    var fullscreen by remember { mutableStateOf(false) }
    // 每次进入"显示"页重新枚举显示器（audit WIN-11）：拔插外接屏后列表要能刷出来；
    // 只在首次组合时枚举一次的话，用户不重启应用就看不到新屏。
    val screens = remember(view) { if (view == DesktopView.DISPLAY) Screens.list() else emptyList() }

    // 手机物理屏的"意图"状态。连上后 vd-server 会自动熄屏，所以每一代会话都从
    // "已熄屏"起步 —— 用 generation 当 key，重连后自动归位，不会留下上一代的残影。
    var phoneScreenOn by remember(session?.generation) { mutableStateOf(false) }

    // 命令没进入输入通道时的提示（audit WIN-12）：此时开关不翻转，但要让用户知道原因。
    var phoneScreenHint by remember(session?.generation) { mutableStateOf<String?>(null) }

    LaunchedEffect(ended) {
        if (ended) onClose()
    }

    fun setFullscreen(on: Boolean) {
        fullscreen = on
        windowState.placement = if (on) WindowPlacement.Fullscreen else WindowPlacement.Floating
    }

    Window(
        onCloseRequest = onClose,
        title = "DiLink Desktop — ${app.phoneHost}",
        state = windowState,
        // F11 切全屏：窗口类应用的标准快捷键，不占导航栏。
        onKeyEvent = { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.F11) {
                setFullscreen(!fullscreen)
                true
            } else {
                false
            }
        },
    ) {
        Row(Modifier.fillMaxSize().background(Palette.Backdrop)) {
            NavRail(
                view = view,
                onSelectView = { view = it },
                onGoHome = { session?.goHome() },
                onGoBack = { session?.goBack() },
                onGoRecent = { session?.goRecent() },
            )
            Box(Modifier.fillMaxSize()) {
                val current = session
                if (current == null) {
                    BasicText(
                        "正在连接 ${app.phoneHost} …",
                        style = TextStyle(color = Palette.TextDim, fontSize = 14.sp),
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else if (view == DesktopView.MIRROR) {
                    // 只在"镜像"页组合视频面板（audit WIN-01）。
                    //
                    // AWT 组件恒在 Compose 内容之上，而 `SwingPanel` 的 background
                    // 是画在它自己那层 JPanel 上的（容器不透明，`setBackground` 见
                    // 1.6.2 的 `SwingPanel$5`），所以"把 videoView.isVisible 置 false"
                    // 治不了遮挡 —— 黑 JPanel 还在。唯一可靠的做法是这一页之外
                    // **根本不组合** SwingPanel。
                    //
                    // 摘挂是安全的：AWT 的 `Container.addImpl` 在 add 时会把组件从
                    // 旧父容器移走（自动 reparent），`FocusSwitcher` 也不注册全局
                    // 监听器（均以字节码核对过），因此不存在"失去父容器"或泄漏。
                    // 不组合期间 `videoView.setFrame` 只是 repaint 空转，回到本页
                    // 立刻显示最近一帧。
                    //
                    // key(generation)：重连后换的是新的 videoView，必须让 SwingPanel 重建。
                    key(current.generation) {
                        SwingPanel(
                            background = Color.Black,
                            factory = { current.videoView },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                when (view) {
                    DesktopView.APPS -> AppGridView(
                        apps = appList,
                        onLaunch = { pkg ->
                            session?.launchApp(pkg)
                            // 点击后自动切回"镜像"页：启动命令走输入通道到手机侧
                            // vd-server，app 出现在镜像里而不是本页 —— 留在本页就是
                            // "点了没反应"的体感（2026-10-09 实测：用户点高德后以为
                            // 失败，手动切到镜像页才发现早已成功）。
                            view = DesktopView.MIRROR
                        },
                        modifier = Modifier.fillMaxSize().background(Palette.Backdrop),
                    )

                    DesktopView.DISPLAY -> DisplaySettingsView(
                        state = DisplayPanelState(
                            screens = screens,
                            fullscreen = fullscreen,
                            dpi = app.startupDpi,
                            hwaccel = app.startupHwaccel,
                            hardwareInUse = hardwareInUse,
                            keepAwake = keepAwake,
                            keepAwakeSupported = app.keepAwakeSupported,
                            phoneScreenOn = phoneScreenOn,
                            phoneScreenHint = phoneScreenHint,
                            phoneScreenEnabled = current != null,
                        ),
                        onToggleFullscreen = ::setFullscreen,
                        onMoveToScreen = { screen ->
                            // 全屏状态下位置被忽略，先退回浮动窗口再搬。
                            if (fullscreen) setFullscreen(false)
                            window.setLocation(screen.x, screen.y)
                            // D-L7：只 setLocation 不 setSize 的话，从小屏搬到大屏
                            // 窗口还是原来大小（浪费屏幕），从大屏搬到小屏则大部分
                            // 在屏外（用户只看到一角）。按目标屏 bounds 钳制尺寸，
                            // 留出任务栏余量。极小屏时保证上界不低于下限（coerceIn
                            // 要求 min <= max）。
                            val maxWidth = maxOf(MIN_MOVED_WINDOW_PX, screen.width - MOVED_WINDOW_MARGIN_PX)
                            val maxHeight = maxOf(MIN_MOVED_WINDOW_PX, screen.height - MOVED_WINDOW_MARGIN_PX)
                            window.setSize(
                                window.width.coerceIn(MIN_MOVED_WINDOW_PX, maxWidth),
                                window.height.coerceIn(MIN_MOVED_WINDOW_PX, maxHeight),
                            )
                        },
                        onToggleKeepAwake = { app.setKeepAwake(it) },
                        onTogglePhoneScreen = { on ->
                            // 命令真的进了输入通道才翻转本地开关（audit WIN-12）：
                            // 输入口未连上时 setDisplayPower 返回 false，若照翻，
                            // 显示的"意图"就会与手机的真实状态长期不一致。
                            if (session?.setDisplayPower(on) == true) {
                                phoneScreenOn = on
                                phoneScreenHint = null
                            } else {
                                phoneScreenHint = "开关未变：输入口尚未连上，请稍后重试"
                            }
                        },
                        onApplyAndReconnect = { dpi, hwaccel ->
                            app.restart(dpiOverride = dpi, hwaccelEnabled = hwaccel)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )

                    DesktopView.MIRROR -> Unit
                }
            }
        }
    }
}

/** 左侧竖向导航栏：上方是视图切换，下方是发给手机的导航键。 */
@Composable
private fun NavRail(
    view: DesktopView,
    onSelectView: (DesktopView) -> Unit,
    onGoHome: () -> Unit,
    onGoBack: () -> Unit,
    onGoRecent: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(RAIL_WIDTH)
            .fillMaxHeight()
            .background(Palette.Rail)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RailButton("镜像", onClick = { onSelectView(DesktopView.MIRROR) }, selected = view == DesktopView.MIRROR)
        RailButton("应用", onClick = { onSelectView(DesktopView.APPS) }, selected = view == DesktopView.APPS)
        RailButton("显示", onClick = { onSelectView(DesktopView.DISPLAY) }, selected = view == DesktopView.DISPLAY)

        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Divider))
        Spacer(Modifier.height(4.dp))

        RailButton("主页", onGoHome)
        RailButton("返回", onGoBack)
        RailButton("最近", onGoRecent)
    }
}

@Composable
private fun RailButton(
    label: String,
    onClick: () -> Unit,
    selected: Boolean = false,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Palette.ButtonActive else Palette.Button)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = TextStyle(color = Palette.Text, fontSize = 13.sp))
    }
}

private val RAIL_WIDTH = 96.dp

/** 搬窗口后允许的最小尺寸（audit D-L7）：再小就没法用了。 */
private const val MIN_MOVED_WINDOW_PX = 480

/** 搬窗口时给目标屏留的余量（任务条 / 屏边，audit D-L7）。 */
private const val MOVED_WINDOW_MARGIN_PX = 80