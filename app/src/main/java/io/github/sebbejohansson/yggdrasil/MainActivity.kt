package io.github.sebbejohansson.yggdrasil

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sebbejohansson.yggdrasil.gjallar.AlertState
import io.github.sebbejohansson.yggdrasil.gjallar.AlertsScreen
import io.github.sebbejohansson.yggdrasil.gjallar.AlertsViewModel
import io.github.sebbejohansson.yggdrasil.gjallar.Severity
import io.github.sebbejohansson.yggdrasil.hermodr.MessagesScreen
import io.github.sebbejohansson.yggdrasil.hermodr.MessagesViewModel
import io.github.sebbejohansson.yggdrasil.llm.ProviderStatus
import io.github.sebbejohansson.yggdrasil.norn.ChatScreen
import io.github.sebbejohansson.yggdrasil.norn.ChatViewModel
import io.github.sebbejohansson.yggdrasil.ui.ArcReactor
import io.github.sebbejohansson.yggdrasil.ui.Backdrop
import io.github.sebbejohansson.yggdrasil.ui.Hud
import io.github.sebbejohansson.yggdrasil.ui.HudBadge
import io.github.sebbejohansson.yggdrasil.ui.HudFonts
import io.github.sebbejohansson.yggdrasil.ui.HudLabel
import io.github.sebbejohansson.yggdrasil.ui.HudText
import io.github.sebbejohansson.yggdrasil.ui.PartState
import io.github.sebbejohansson.yggdrasil.ui.ReactorPart
import io.github.sebbejohansson.yggdrasil.ui.YggTheme
import io.github.sebbejohansson.yggdrasil.vedrfolnir.PhoneScreen
import io.github.sebbejohansson.yggdrasil.vedrfolnir.PhoneViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {

    /** Which tab a notification asked for. */
    private val requestedTab = MutableStateFlow<Int?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bar = Color(0xF005090D).toArgb()
        enableEdgeToEdge(SystemBarStyle.dark(Color.Transparent.toArgb()), SystemBarStyle.dark(bar))
        requestedTab.value = intent.tabExtra()
        val container = (application as YggApp).container
        setContent { YggTheme { Root(container, requestedTab) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requestedTab.value = intent.tabExtra()
    }

    private fun Intent.tabExtra() = getIntExtra(EXTRA_TAB, -1).takeIf { it >= 0 }

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_CHAT = 0
        const val TAB_ALERTS = 1
        const val TAB_MESSAGES = 2
        const val TAB_PHONE = 3
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Root(container: AppContainer, requestedTab: MutableStateFlow<Int?>) {
    var tab by rememberSaveable { mutableIntStateOf(MainActivity.TAB_CHAT) }
    val asked by requestedTab.collectAsStateWithLifecycle()
    LaunchedEffect(asked) {
        asked?.let { tab = it; requestedTab.value = null }
    }

    val chat: ChatViewModel = viewModel(factory = ChatViewModel.factory(container))
    val alerts: AlertsViewModel = viewModel(factory = AlertsViewModel.factory(container))
    val messages: MessagesViewModel = viewModel(factory = MessagesViewModel.factory(container))
    val phone: PhoneViewModel = viewModel(factory = PhoneViewModel.factory(container))
    val phoneFailing by container.vedrfolnir.failing.collectAsStateWithLifecycle()

    // Ask once for notifications (Android 13+), and look again on every return:
    // the user may have changed it in Android's settings.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        messages.recheckPermission()
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LifecycleResumeEffect(Unit) {
        messages.recheckPermission()
        chat.refreshProviders()
        onPauseOrDispose { }
    }

    val chatState by chat.state.collectAsStateWithLifecycle()
    val alertState by alerts.state.collectAsStateWithLifecycle()
    val messageState by messages.state.collectAsStateWithLifecycle()
    val openAlerts = alertState.active.count { it.alert.state == AlertState.OPEN }
    val unseen = messageState.messages.count { it.seenAt == null }

    val reactor = listOf(
        ReactorPart("Norn", chatState.providers.firstOrNull { !it.leavesDevice }?.status.toPart()),
        ReactorPart(
            "Gjallar",
            when {
                alertState.active.any { it.alert.state == AlertState.OPEN && it.alert.severity == Severity.CRITICAL } -> PartState.TROUBLED
                alertState.active.any { it.alert.state == AlertState.OPEN && it.alert.severity == Severity.WARNING } -> PartState.TROUBLED
                else -> PartState.UP
            },
        ),
        ReactorPart("Hermóðr", if (messageState.blocked != null) PartState.TROUBLED else PartState.UP),
        ReactorPart("Veðrfölnir", if (phoneFailing.isEmpty()) PartState.UP else PartState.TROUBLED),
        ReactorPart("Claude", chatState.providers.firstOrNull { it.leavesDevice }?.status.toPart()),
    )

    Box(Modifier.fillMaxSize().background(Hud.crust)) {
        Backdrop(needs = openAlerts + unseen, modifier = Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().imePadding()) {
            TopBar(reactor, busy = chatState.busy, onReactor = { tab = MainActivity.TAB_ALERTS })
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    MainActivity.TAB_ALERTS -> AlertsScreen(alerts)
                    MainActivity.TAB_MESSAGES -> MessagesScreen(messages)
                    MainActivity.TAB_PHONE -> PhoneScreen(phone)
                    else -> ChatScreen(chat)
                }
            }
            // Out of the way while typing; the keyboard takes that space.
            if (!WindowInsets.isImeVisible) {
                TabBar {
                    Tab(tab == MainActivity.TAB_CHAT, "Norn", 0) { tab = MainActivity.TAB_CHAT }
                    Tab(tab == MainActivity.TAB_ALERTS, "Alerts", openAlerts) { tab = MainActivity.TAB_ALERTS }
                    Tab(tab == MainActivity.TAB_MESSAGES, "Messages", unseen) { tab = MainActivity.TAB_MESSAGES }
                    Tab(tab == MainActivity.TAB_PHONE, "Phone", phoneFailing.size) { tab = MainActivity.TAB_PHONE }
                }
            }
        }
    }
}

private fun ProviderStatus?.toPart() = when (this) {
    ProviderStatus.Ready -> PartState.UP
    is ProviderStatus.NeedsSetup -> PartState.TROUBLED
    is ProviderStatus.Unavailable, null -> PartState.DOWN
}

private val clockFmt = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun TopBar(parts: List<ReactorPart>, busy: Boolean, onReactor: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            now = System.currentTimeMillis()
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xF005090D))
            .bottomLine(Hud.surface0)
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painterResource(R.drawable.ic_tree), contentDescription = null, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(10.dp))
        Text("YGGDRASIL", style = HudText.brand.copy(fontSize = 17.sp))
        Spacer(Modifier.weight(1f))
        @Suppress("UNUSED_EXPRESSION") now
        HudLabel(LocalTime.now().format(clockFmt), color = Hud.subtext0, style = HudText.label.copy(fontSize = 13.sp))
        Spacer(Modifier.width(12.dp))
        ArcReactor(parts, busy = busy, size = 36.dp, modifier = Modifier.clickable(onClick = onReactor))
    }
}

@Composable
private fun TabBar(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xF005090D))
            .topLine(Hud.surface0)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

/** A menu leaf as the dashboard's: solid cyan when it's the page you're on. */
@Composable
private fun RowScope.Tab(selected: Boolean, label: String, count: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .weight(1f)
            .height(40.dp)
            .background(if (selected) Hud.cyan else Color.Transparent, RoundedCornerShape(2.dp))
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label.uppercase(),
            style = HudText.label.copy(
                fontFamily = HudFonts.mono,
                // Four tabs share a phone's width: a little tighter than the dashboard's labels.
                fontSize = 11.sp,
                letterSpacing = 0.08.em,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) Hud.onCyan else Hud.subtext1,
            ),
        )
        if (count > 0) {
            Spacer(Modifier.width(4.dp))
            HudBadge(count, color = if (selected) Hud.onCyan else Hud.amber)
        }
    }
}

private fun Modifier.bottomLine(c: Color) = drawBehind {
    drawLine(c, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
}

private fun Modifier.topLine(c: Color) = drawBehind {
    drawLine(c, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
}
