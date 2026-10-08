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
 * [DesktopApp.Session.sessionEnded] 变为 true 时自动关窗退出（手机断开/会话出错时
 * 不留一个空窗口）。
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
    val windowState = rememberWindowState(size = DpSize(app.viewportWidth.dp, app.viewportHeight.dp))
    var fullscreen by remember { mutableStateOf(false) }
    val screens = remember { Screens.list() }

    // 手机物理屏的"意图"状态。连上后 vd-server 会自动熄屏，所以每一代会话都从
    // "已熄屏"起步 —— 用 generation 当 key，重连后自动归位，不会留下上一代的残影。
    var phoneScreenOn by remember(session?.generation) { mutableStateOf(false) }

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
                } else {
                    // 镜像常驻组合：切到其它页时也不卸载 SwingPanel ——
                    // 反复摘挂会让 Swing 组件失去父容器、画面尺寸抖动。
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
                        onLaunch = { session?.launchApp(it) },
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
                            phoneScreenEnabled = current != null,
                        ),
                        onToggleFullscreen = ::setFullscreen,
                        onMoveToScreen = { screen ->
                            // 全屏状态下位置被忽略，先退回浮动窗口再搬。
                            if (fullscreen) setFullscreen(false)
                            window.setLocation(screen.x, screen.y)
                        },
                        onToggleKeepAwake = { app.setKeepAwake(it) },
                        onTogglePhoneScreen = { on ->
                            phoneScreenOn = on
                            session?.setDisplayPower(on)
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