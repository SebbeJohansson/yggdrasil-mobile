package io.github.sebbejohansson.yggdrasil.vedrfolnir

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.sebbejohansson.yggdrasil.AppContainer
import io.github.sebbejohansson.yggdrasil.ui.Hud
import io.github.sebbejohansson.yggdrasil.ui.HudButton
import io.github.sebbejohansson.yggdrasil.ui.HudButtonKind
import io.github.sebbejohansson.yggdrasil.ui.HudFonts
import io.github.sebbejohansson.yggdrasil.ui.HudLabel
import io.github.sebbejohansson.yggdrasil.ui.HudSection
import io.github.sebbejohansson.yggdrasil.ui.HudText
import io.github.sebbejohansson.yggdrasil.ui.hudPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PhoneViewModel(private val vedrfolnir: VedrfolnirService) : ViewModel() {
    val readings = vedrfolnir.latest

    /** Reads the phone every few seconds while the screen shows. Raises nothing: the worker does that. */
    suspend fun follow() {
        while (true) {
            withContext(Dispatchers.Default) { vedrfolnir.sample() }
            delay(5_000)
        }
    }

    fun checkNow() = viewModelScope.launch { vedrfolnir.check() }

    companion object {
        fun factory(c: AppContainer): ViewModelProvider.Factory = viewModelFactory { initializer { PhoneViewModel(c.vedrfolnir) } }
    }
}

private fun gb(bytes: Long) = if (bytes >= 10e9) "%.0f".format(bytes / 1e9) else "%.1f".format(bytes / 1e9)

/** Veðrfölnir's readouts: one tile per thing it watches, amber when its check fails. */
@Composable
fun PhoneScreen(vm: PhoneViewModel, modifier: Modifier = Modifier) {
    val r by vm.readings.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.follow() }
    val failing = r?.let { Checks.of(it).filterNot { c -> c.ok }.associateBy { c -> c.key } } ?: emptyMap()

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text("Veðrfölnir", style = HudText.title)
                HudLabel(
                    if (failing.isEmpty()) "the phone is fine" else "${failing.size} to look at",
                    color = if (failing.isEmpty()) Hud.overlay2 else Hud.amber,
                )
            }
        }
        item { HudSection("At a glance", Modifier.padding(top = 4.dp)) }
        val now = r
        if (now == null) {
            item { HudLabel("Reading the phone…", color = Hud.overlay0) }
        } else {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Tile(
                        "Battery", now.batteryPercent?.toString() ?: "–", if (now.batteryPercent != null) "%" else null,
                        listOfNotNull(if (now.charging) "charging" else "on battery", now.batteryTempC?.let { "%.0f °C".format(it) }).joinToString(" · "),
                        warn = "battery-low" in failing, modifier = Modifier.weight(1f),
                    )
                    Tile(
                        "Heat", now.thermal.label(), null,
                        if (now.thermal <= Thermal.LIGHT) "not throttled" else "throttling",
                        warn = "heat" in failing, modifier = Modifier.weight(1f),
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Tile(
                        "Storage", gb(now.storageFreeBytes), "GB",
                        "free of ${gb(now.storageTotalBytes)} GB · %.0f%% used".format(now.storageUsedPercent),
                        warn = "storage" in failing, modifier = Modifier.weight(1f),
                    )
                    Tile(
                        "Memory", gb(now.memoryAvailBytes), "GB",
                        "free of ${gb(now.memoryTotalBytes)} GB",
                        warn = false, modifier = Modifier.weight(1f),
                    )
                }
            }
            if (failing.isNotEmpty()) {
                item { HudSection("Raised with Gjallar", Modifier.padding(top = 8.dp)) }
                failing.values.forEach { c ->
                    item { Text("${c.title}. ${c.detail.orEmpty()}", style = HudText.small.copy(color = Hud.amber)) }
                }
            }
        }
        item { HudSection("Checks", Modifier.padding(top = 8.dp)) }
        item {
            Text(
                "Every 15 minutes in the background: battery at 15% or less and not charging, storage 90% full, " +
                    "or the phone hot enough to throttle raise a warning with Gjallar; 5%, 95% and Android's " +
                    "critical heat make it critical. Each resolves by itself when it passes again.",
                style = HudText.meta,
            )
        }
        item { HudButton("Check now", { vm.checkNow() }, kind = HudButtonKind.SMALL) }
    }
}

private fun Thermal.label() = when (this) {
    Thermal.NONE -> "Cool"
    Thermal.LIGHT -> "Warm"
    Thermal.MODERATE -> "Hot"
    else -> name.lowercase().replaceFirstChar { it.uppercase() }
}

/** A readout tile: a label, a big Rajdhani number, a line under it. */
@Composable
private fun Tile(label: String, value: String, unit: String?, line: String, warn: Boolean, modifier: Modifier = Modifier) {
    val c = if (warn) Hud.amber else Color.Unspecified
    Column(modifier.hudPanel(warn).padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HudLabel(label, color = if (warn) Hud.amber else Hud.overlay2)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                style = HudText.number.copy(fontSize = 40.sp, fontWeight = FontWeight.Bold, color = if (warn) Hud.amber else Hud.bright, lineHeight = 40.sp),
            )
            if (unit != null) {
                Text(
                    " $unit",
                    style = HudText.label.copy(fontFamily = HudFonts.display, fontSize = 18.sp, color = if (warn) Hud.amber else Hud.overlay2),
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
        Text(line, style = HudText.meta.copy(color = if (c == Color.Unspecified) Hud.overlay2 else c), maxLines = 2)
    }
}
