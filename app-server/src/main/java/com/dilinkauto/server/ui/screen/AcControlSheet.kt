package com.dilinkauto.server.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dilinkauto.server.R
import com.dilinkauto.server.service.AcControlState
import com.dilinkauto.server.service.AcUiState

/**
 * 空调就地设置块（issue #278-PA：温度档先行）。
 *
 * 盖在镜像画面上层的弹出块，弹层模式沿用 [AppInfoDialog] 的 `AlertDialog` +
 * `onDismissRequest`（点块外任意处关闭，车机惯例路径，不做额外关闭按钮）。
 *
 * PA 范围 = 只做温度档（issue 影响面「按风险递增分节」：温度写失败最温和——
 * 温度没变 vs 意外关机——用它最小化验证整条写链路 + 回读确认）。
 * 开/关、风量、模式按钮组留 PB（PA 实车验证通过后另开单）。
 *
 * 布局（issue 交互设计，Nielsen #8）：
 * - 状态概览：`24℃ · 开启`（打开时由 CarShell 先触发一次全量读灌 state）；
 * - 温度行：`− 24° +`，Nielsen #5 错误预防——到 33 时「+」置灰、到 17 时「−」置灰
 *   （约束优于报错，不发 toast）；触控目标 56dp ≥48dp（驾驶盲操作）。
 *
 * 状态反馈（issue UX 表 S4：写失败静默是最严重失效模式）：本块只渲染
 * `AcUiState`（已是写后回读确认/回滚后的结果），失败反馈由 CarShell 的
 * 写失败事件 toast「设置未生效，请重试」负责，数据流单一不重复。
 */
@Composable
internal fun AcControlSheet(
    state: AcUiState?,
    onStep: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val temperature = state?.temperatureC
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.ac_title),
                color = Color.White,
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                // 状态概览：温度大字 + 开关状态（started 未知时只显温度）。
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        temperature?.let { "$it℃" } ?: "--",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineMedium
                    )
                    when (state?.started) {
                        true -> Text(
                            stringResource(R.string.ac_state_on),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        false -> Text(
                            stringResource(R.string.ac_state_off),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        else -> {}
                    }
                }
                // 温度行：− / 当前值 / +（边界置灰：Nielsen #5 约束优于报错）。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TempStepButton(
                        label = "−",
                        description = stringResource(R.string.ac_temp_down),
                        enabled = temperature != null && AcControlState.canStepDown(temperature),
                        onClick = { onStep(-1) }
                    )
                    Text(
                        text = temperature?.let { AcControlState.formatChipTemp(it) } ?: "--",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontFeatureSettings = "tnum"
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.size(width = 96.dp, height = 56.dp)
                    )
                    TempStepButton(
                        label = "+",
                        description = stringResource(R.string.ac_temp_up),
                        enabled = temperature != null && AcControlState.canStepUp(temperature),
                        onClick = { onStep(+1) }
                    )
                }
            }
        },
        confirmButton = {},
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = Color.White,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * 温度步进按钮：56dp 圆形触控目标（≥48dp 无障碍下限，驾驶场景盲操作），
 * 边界置灰不响应点击（enabled=false 时 clickable 不触发，见 NavBarActionButton 同款注释）。
 */
@Composable
private fun TempStepButton(
    label: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(
                if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                else Color.Transparent
            )
            .border(
                1.dp,
                Color.White.copy(alpha = if (enabled) 0.5f else 0.2f),
                CircleShape
            )
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description }
    ) {
        Text(
            label,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.38f),
            fontSize = 28.sp,
            textAlign = TextAlign.Center
        )
    }
}
