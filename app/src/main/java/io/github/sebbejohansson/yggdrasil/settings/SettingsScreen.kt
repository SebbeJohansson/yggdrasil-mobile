package io.github.sebbejohansson.yggdrasil.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sebbejohansson.yggdrasil.boot.BootPanel
import io.github.sebbejohansson.yggdrasil.boot.BootSettings
import io.github.sebbejohansson.yggdrasil.gjallar.AlertTests
import io.github.sebbejohansson.yggdrasil.gjallar.AlertsViewModel
import io.github.sebbejohansson.yggdrasil.gjallar.MimirSettings
import io.github.sebbejohansson.yggdrasil.hermodr.MessageSettings
import io.github.sebbejohansson.yggdrasil.hermodr.MessageTests
import io.github.sebbejohansson.yggdrasil.hermodr.MessagesViewModel
import io.github.sebbejohansson.yggdrasil.norn.AssistantSettings
import io.github.sebbejohansson.yggdrasil.norn.ChatViewModel
import io.github.sebbejohansson.yggdrasil.ui.Hud
import io.github.sebbejohansson.yggdrasil.ui.HudButton
import io.github.sebbejohansson.yggdrasil.ui.HudButtonKind
import io.github.sebbejohansson.yggdrasil.ui.HudLabel
import io.github.sebbejohansson.yggdrasil.ui.HudSection
import io.github.sebbejohansson.yggdrasil.ui.HudText
import io.github.sebbejohansson.yggdrasil.ui.hudPanel

/**
 * Every switch in one place, as the dashboard's Settings page: opened from
 * the gear in the top bar, not a tab of its own.
 */
@Composable
fun SettingsScreen(
    chat: ChatViewModel,
    alerts: AlertsViewModel,
    messages: MessagesViewModel,
    bootSettings: BootSettings,
    onRunBoot: () -> Unit,
    onBack: () -> Unit,
    /** The Code tab's view model, for the machines reached directly (Connection → Machines). */
    code: io.github.sebbejohansson.yggdrasil.saga.CodeViewModel? = null,
    /** Home screen and updates: they need the activity, so the caller draws them. */
    phoneMore: @Composable () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Settings", style = HudText.title)
                HudLabel("on this phone")
            }
            HudButton("Back", onBack, kind = HudButtonKind.SMALL)
        }

        HudSection("Assistant", Modifier.padding(top = 8.dp))
        AssistantSettings(chat)

        HudSection("Connection", Modifier.padding(top = 8.dp))
        if (code != null) {
            HudLabel("Machines: reached directly over the tailnet", color = Hud.overlay2)
            io.github.sebbejohansson.yggdrasil.nodes.MachinesSettings(code)
        }
        HudLabel("Mímir: alerts, sync, and coding sessions of machines not reached directly", color = Hud.overlay2)
        MimirSettings(alerts)

        HudSection("Notifications", Modifier.padding(top = 8.dp))
        NotificationsPanel(messages)
        MessageSettings(messages)

        HudSection("Boot sequence", Modifier.padding(top = 8.dp))
        BootPanel(bootSettings, onRunBoot)

        phoneMore()

        HudSection("Developer", Modifier.padding(top = 8.dp))
        HudLabel("Test alerts", color = Hud.overlay2)
        AlertTests(alerts)
        HudLabel("Test messages", color = Hud.overlay2)
        MessageTests(messages)
    }
}

/** Whether Android lets Ygg show notifications, and the way to its settings if not. */
@Composable
private fun NotificationsPanel(messages: MessagesViewModel) {
    val s by messages.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val blocked = s.blocked
    Row(Modifier.fillMaxWidth().hudPanel(warn = blocked != null).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            HudLabel("Notifications", color = if (blocked == null) Hud.green else Hud.amber)
            Text(if (blocked == null) "Allowed. Messages show as notifications." else "Off: $blocked.", style = HudText.meta)
        }
        HudButton("Android settings", {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }, kind = HudButtonKind.SMALL)
    }
}
