package io.github.sebbejohansson.yggdrasil.ui

import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * `.hud-panel`: a thin border, a see-through fill, and cyan corner brackets
 * top left and bottom right. [warn] turns the brackets amber.
 */
fun Modifier.hudPanel(warn: Boolean = false): Modifier = this
    .background(Hud.panel)
    .border(1.dp, Hud.surface0)
    .drawWithContent {
        drawContent()
        val c = if (warn) Hud.amber else Hud.cyan
        val len = 14.dp.toPx()
        val w = 2.dp.toPx()
        // Top left.
        drawRect(c, Offset(0f, 0f), androidx.compose.ui.geometry.Size(len, w))
        drawRect(c, Offset(0f, 0f), androidx.compose.ui.geometry.Size(w, len))
        // Bottom right.
        drawRect(c, Offset(size.width - len, size.height - w), androidx.compose.ui.geometry.Size(len, w))
        drawRect(c, Offset(size.width - w, size.height - len), androidx.compose.ui.geometry.Size(w, len))
    }

/** A 2dp stripe down the left edge, as the dashboard's alert list and agent messages. */
fun Modifier.leftStripe(color: Color, width: Dp = 2.dp): Modifier = drawBehind {
    drawRect(color, size = androidx.compose.ui.geometry.Size(width.toPx(), size.height))
}

/** `.hud-label`: monospace caps. */
@Composable
fun HudLabel(text: String, modifier: Modifier = Modifier, color: Color = Hud.overlay2, style: TextStyle = HudText.label) {
    Text(text.uppercase(), modifier, style = style.copy(color = color))
}

/** A section heading as `.hlbl`: a cyan square, the name, a line to the edge. */
@Composable
fun HudSection(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(5.dp).background(Hud.cyan))
        Spacer(Modifier.width(8.dp))
        HudLabel(text, color = Hud.group, style = HudText.label.copy(fontSize = 10.sp, letterSpacing = 0.16.em))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Hud.line))
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

enum class HudButtonKind { ACTION, PRIMARY, GHOST, SMALL }

/**
 * The dashboard's buttons: `.action` (filled surface), PRIMARY (solid cyan,
 * as an active menu leaf), GHOST (outline), SMALL (the alert list's 11px
 * outline buttons).
 */
@Composable
fun HudButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: HudButtonKind = HudButtonKind.ACTION,
    enabled: Boolean = true,
    accent: Color = Hud.cyan,
) {
    val shape = RoundedCornerShape(if (kind == HudButtonKind.PRIMARY) 2.dp else 5.dp)
    val (bg, fg, border) = when (kind) {
        HudButtonKind.ACTION -> Triple(Hud.surface0, Hud.text, Hud.surface1)
        HudButtonKind.PRIMARY -> Triple(accent, Hud.onCyan, accent)
        HudButtonKind.GHOST -> Triple(Color.Transparent, accent, accent.copy(alpha = 0.55f))
        HudButtonKind.SMALL -> Triple(Color.Transparent, Hud.subtext0, Hud.surface1)
    }
    val pad = if (kind == HudButtonKind.SMALL) Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
    else Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.45f)
            .background(bg, shape)
            .border(1.dp, border, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .then(pad),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = if (kind == HudButtonKind.SMALL) HudText.meta.copy(color = fg)
            else HudText.small.copy(color = fg, fontSize = 13.sp),
        )
    }
}

/** A small square toggle in HUD colours. */
@Composable
fun HudToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val track = if (checked) Hud.cyan.copy(alpha = 0.18f) else Hud.field
    val edge = if (checked) Hud.cyan else Hud.surface1
    Box(
        modifier
            .size(width = 40.dp, height = 22.dp)
            .background(track, RoundedCornerShape(2.dp))
            .border(1.dp, edge, RoundedCornerShape(2.dp))
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(14.dp).background(if (checked) Hud.cyan else Hud.overlay0))
    }
}

/** `.search`: a dark field, a teal border that turns cyan in focus. */
@Composable
fun HudTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 5,
    leading: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        textStyle = HudText.body.copy(color = Hud.text),
        cursorBrush = SolidColor(Hud.cyan),
        maxLines = maxLines,
        singleLine = maxLines == 1,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        interactionSource = interaction,
        decorationBox = { inner ->
            Row(
                Modifier
                    .background(Hud.field, RoundedCornerShape(2.dp))
                    .border(1.dp, if (focused) Hud.cyan else Hud.surface1, RoundedCornerShape(2.dp))
                    .heightIn(min = 42.dp)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (leading != null) Text(leading, style = HudText.body.copy(color = Hud.cyan, fontFamily = HudFonts.mono))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, style = HudText.body.copy(color = Hud.overlay0))
                    inner()
                }
            }
        },
    )
}

/** A count badge: amber when it wants you. */
@Composable
fun HudBadge(count: Int, color: Color = Hud.amber) {
    if (count <= 0) return
    Box(
        Modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(2.dp))
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(2.dp))
            .padding(horizontal = 5.dp),
    ) {
        Text("$count", style = HudText.label.copy(color = color, fontSize = 10.sp, letterSpacing = 0.sp))
    }
}

/** A segmented progress bar, as the HUD's readouts. */
@Composable
fun HudProgress(fraction: Float, modifier: Modifier = Modifier, segments: Int = 24, color: Color = Hud.cyan) {
    Row(modifier.height(8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        val lit = (fraction.coerceIn(0f, 1f) * segments).toInt()
        repeat(segments) { i ->
            Box(
                Modifier
                    .weight(1f)
                    .height(8.dp)
                    .background(if (i < lit) color else Hud.surface0),
            )
        }
    }
}
