package com.dilinkauto.server.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dilinkauto.server.ui.theme.NavBarBackgroundColor

/**
 * 顶部信息条（issue #39 落地脚手架）。
 *
 * 一条右对齐的横向信息容器，接 `List<InfoChip>`：#39 只放系统时间 chip，
 * #40（海拔）、#41（天气）后续直接往同一个 [InfoBar] 加 chip，不在各自 issue 里再造容器，
 * 避免三份重复布局（DRY）。
 *
 * - `chips` 为空时不渲染（也不占位），镜像页传空即可满足「镜像页不覆盖手机画面」。
 * - 非交互：不抢焦点、不响应点击（issue #39 验收标准「非交互」）。
 * - 背景与导航条同色 [NavBarBackgroundColor]，视觉上是顶部 chrome 的一部分。
 *
 * 落位见 `CarShell` / `CarLaunchScreen`：竖屏顶部、横屏内容区顶部，不压导航键/磁贴/搜索框
 *（见 issue #39 追评二的 `ui-hierarchy.xml` 夹具核对）。
 */
@Immutable
data class InfoChip(val text: String)

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
            Text(
                text = chip.text,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.End
            )
        }
    }
}
