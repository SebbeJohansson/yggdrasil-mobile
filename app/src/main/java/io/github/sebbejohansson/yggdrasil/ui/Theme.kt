package io.github.sebbejohansson.yggdrasil.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.sebbejohansson.yggdrasil.R

/**
 * The HUD look from my-widget (the desktop Yggdrasil's dashboard,
 * `app/assets/css/dashboard.css`): a near-black ground, cyan for what is
 * normal and amber for what wants you. Rajdhani for numbers and headings,
 * JetBrains Mono for labels, Inter for reading. Always dark.
 */
object Hud {
    val crust = Color(0xFF05090D)
    val mantle = Color(0xFF071017)
    val base = Color(0xFF09141B)
    val base2 = Color(0xFF0C1C24)
    val field = Color(0xFF0A171E)
    val hover = Color(0xFF0E2530)
    val surface0 = Color(0xFF173440)
    val surface1 = Color(0xFF23495A)
    val surface2 = Color(0xFF2A5262)
    val line = Color(0xFF1E4250)
    val overlay0 = Color(0xFF6F95A3)
    val overlay1 = Color(0xFF7FA3B0)
    val overlay2 = Color(0xFF8FB3C0)
    val subtext0 = Color(0xFFA9C4CE)
    val subtext1 = Color(0xFFB9D3DC)
    val text = Color(0xFFD7EEF5)
    val bright = Color(0xFFE9F6FA)
    val group = Color(0xFF7FCFE0)

    val cyan = Color(0xFF4FD8F0)
    val onCyan = Color(0xFF04141A)
    val green = Color(0xFF9AE6C4)
    val yellow = Color(0xFFFFD98A)
    val red = Color(0xFFFF7A8A)
    val amber = Color(0xFFFFB54D)
    val mauve = Color(0xFFB9A6FF)

    /** A panel's fill: base at 86%, so the backdrop shows through a little. */
    val panel = Color(0xDB09141B)
}

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

object HudFonts {
    val display = FontFamily(
        Font(R.font.rajdhani_medium, FontWeight.Medium),
        Font(R.font.rajdhani_semibold, FontWeight.SemiBold),
        Font(R.font.rajdhani_bold, FontWeight.Bold),
    )
    val mono = FontFamily(
        variable(R.font.jetbrains_mono, 400),
        variable(R.font.jetbrains_mono, 500),
        variable(R.font.jetbrains_mono, 700),
    )
    val body = FontFamily(
        variable(R.font.inter, 400),
        variable(R.font.inter, 500),
        variable(R.font.inter, 600),
    )
}

object HudText {
    /** `.hud-label`: small monospace caps for section names, units, states. */
    val label = TextStyle(
        fontFamily = HudFonts.mono, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, letterSpacing = 0.14.em, color = Hud.overlay2,
    )

    /** `h1`: Rajdhani, bold, a little spaced. */
    val title = TextStyle(
        fontFamily = HudFonts.display, fontWeight = FontWeight.Bold,
        fontSize = 26.sp, letterSpacing = 0.04.em, color = Hud.text,
    )

    /** The brand: Rajdhani caps, wide. */
    val brand = TextStyle(
        fontFamily = HudFonts.display, fontWeight = FontWeight.Bold,
        fontSize = 19.sp, letterSpacing = 0.22.em, color = Color(0xFFE6F7FC),
    )

    val body = TextStyle(fontFamily = HudFonts.body, fontSize = 14.sp, lineHeight = 20.sp, color = Hud.text)
    val small = TextStyle(fontFamily = HudFonts.body, fontSize = 12.sp, lineHeight = 17.sp, color = Hud.text)
    val meta = TextStyle(fontFamily = HudFonts.body, fontSize = 11.sp, color = Hud.overlay0)

    /** Numbers: Rajdhani, as the dashboard's readouts. */
    val number = TextStyle(fontFamily = HudFonts.display, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Hud.text)
}

@Composable
fun YggTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = Hud.cyan, onPrimary = Hud.onCyan,
        secondary = Hud.green, onSecondary = Hud.onCyan,
        tertiary = Hud.amber, onTertiary = Hud.onCyan,
        background = Hud.crust, onBackground = Hud.text,
        surface = Hud.base, onSurface = Hud.text,
        surfaceVariant = Hud.base2, onSurfaceVariant = Hud.overlay2,
        surfaceContainer = Hud.base, surfaceContainerHigh = Hud.base2, surfaceContainerLow = Hud.mantle,
        outline = Hud.surface1, outlineVariant = Hud.surface0,
        error = Hud.red, onError = Hud.onCyan,
    )
    MaterialTheme(
        colorScheme = colors,
        typography = MaterialTheme.typography.run {
            copy(
                bodyLarge = bodyLarge.copy(fontFamily = HudFonts.body),
                bodyMedium = bodyMedium.copy(fontFamily = HudFonts.body),
                bodySmall = bodySmall.copy(fontFamily = HudFonts.body),
                labelLarge = labelLarge.copy(fontFamily = HudFonts.body),
                labelMedium = labelMedium.copy(fontFamily = HudFonts.mono),
                labelSmall = labelSmall.copy(fontFamily = HudFonts.mono),
                titleLarge = titleLarge.copy(fontFamily = HudFonts.display, fontWeight = FontWeight.Bold),
                titleMedium = titleMedium.copy(fontFamily = HudFonts.display, fontWeight = FontWeight.SemiBold),
                titleSmall = titleSmall.copy(fontFamily = HudFonts.display, fontWeight = FontWeight.SemiBold),
            )
        },
        content = content,
    )
}
