package io.github.sebbejohansson.yggdrasil.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Page tabs under a menu leaf, as the dashboard's `PageTabs.vue`: cyan underline on the open one. */
@Composable
fun PageTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, counts: List<Int> = emptyList()) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).underline(Color(0xFF17323F), 1.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val on = selected == i
            Row(
                Modifier
                    .clickable { onSelect(i) }
                    .then(if (on) Modifier.underline(Hud.cyan, 2.dp) else Modifier)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = HudText.small.copy(fontSize = 13.sp, color = if (on) Hud.bright else Hud.overlay2))
                val count = counts.getOrElse(i) { 0 }
                if (count > 0) {
                    Spacer(Modifier.width(6.dp))
                    HudBadge(count)
                }
            }
        }
    }
}

private fun Modifier.underline(c: Color, width: Dp) = drawBehind {
    val w = width.toPx()
    drawRect(c, Offset(0f, size.height - w), Size(size.width, w))
}
