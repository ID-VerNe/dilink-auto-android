package com.dilinkauto.server.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dilinkauto.server.ui.theme.NavBarBackgroundColor

/**
 * 顶部信息条（issue #39 落地脚手架）。
 *
 * 一条右对齐的横向信息容器，接 `List<InfoChip>`：#39 只放系统时间 chip，
 * #40（海拔）、#41（天气）后续直接往同一个 [InfoBar] 加 chip，不在各自 issue 里再造容器，
 * 避免三份重复布局（DRY）。
 *
 * - `chips` 为空时不渲染（也不占位）。镜像页不再传空：横屏走 [InfoChipColumn] 常驻左栏（issue #54）。
 * - 非交互：不抢焦点、不响应点击（issue #39 验收标准「非交互」）。
 * - 背景与导航条同色 [NavBarBackgroundColor]，视觉上是顶部 chrome 的一部分。
 *
 * 落位见 `CarShell` / `CarLaunchScreen`：竖屏顶部；横屏自 issue #54 起移入左侧常驻栏
 *（[InfoChipColumn]），不再占内容区高度，镜像页也常驻。
 */
/**
 * 信息条的单个 chip。
 *
 * - [text]：纯文本（`86%` / `多云 22°`），长度受左栏 76dp 定宽约束保持 ≤4 字符（issue #189）。
 * - [onClick]：**issue #278-PA 首次引入可点 chip**（此前 #39 验收标准「非交互」对所有 chip 成立）。
 *   传 `null`（含默认值）时渲染与扩展前**逐字节一致**——老 chip（时间/天气/电量）的非交互
 *   契约继续成立；非 null 时加胶囊描边 + `clickable`（可点语义与展示 chip 视觉区分），
 *   点击由 CarShell 接弹出设置块（`AcControlSheet`）。
 */
@Immutable
data class InfoChip(val text: String, val onClick: (() -> Unit)? = null)

/**
 * 左栏竖排信息 chip（issue #54）。
 *
 * 用户要求时间 / 海拔 / 天气固定在左侧那一栏、与三个导航键同栏，进入镜像页也不能消失。
 * 栏宽只有 76dp（`CarViewport.navBarWidthPx` 与编码视口偶数对齐强相关，不能改宽），
 * 因此这里用 12sp 等宽数字、单行省略，避免文本撑破栏宽反过来触发视口重握手。
 *
 * issue #278-PA：`onClick != null` 的 chip（空调）加胶囊描边 + 可点；
 * `onClick == null` 的 chip 走原有纯 `Text` 渲染分支，形态不变。
 */
@Composable
fun InfoChipColumn(chips: List<InfoChip>, modifier: Modifier = Modifier) {
    if (chips.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        chips.forEach { chip ->
            if (chip.onClick == null) {
                Text(
                    text = chip.text,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 12.sp,
                        fontFeatureSettings = "tnum"
                    ),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                ClickableInfoChip(chip)
            }
        }
    }
}

/**
 * 可点 chip 的统一形态（issue #278-PA）：胶囊描边 + `clickable`。
 * 描边与既有展示 chip 视觉区分（可点语义可发现性）；文本样式与展示 chip 完全一致，
 * 栏内高度不跳变。左栏 76dp 定宽内水平 padding 收敛（4dp），撑不破栏宽。
 */
@Composable
private fun ClickableInfoChip(chip: InfoChip) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(50))
            .clickable(onClickLabel = chip.text) { chip.onClick?.invoke() }
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Text(
            text = chip.text,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 12.sp,
                fontFeatureSettings = "tnum"
            ),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun InfoBar(chips: List<InfoChip>, modifier: Modifier = Modifier) {
    if (chips.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(NavBarBackgroundColor)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        chips.forEachIndexed { index, chip ->
            if (index > 0) Spacer(Modifier.width(16.dp))
            if (chip.onClick == null) {
                Text(
                    text = chip.text,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.End
                )
            } else {
                ClickableInfoChip(chip)
            }
        }
    }
}
