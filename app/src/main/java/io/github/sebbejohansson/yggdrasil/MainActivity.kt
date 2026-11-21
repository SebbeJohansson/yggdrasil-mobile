package io.github.sebbejohansson.yggdrasil

import androidx.activity.compose.BackHandler
import io.github.sebbejohansson.yggdrasil.home.HomeScreenPanel
import io.github.sebbejohansson.yggdrasil.home.HomeRole
import io.github.sebbejohansson.yggdrasil.home.AppsScreen
import io.github.sebbejohansson.yggdrasil.home.AppsViewModel
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import io.github.sebbejohansson.yggdrasil.history.RunesScreen
import io.github.sebbejohansson.yggdrasil.history.TreeScreen
import io.github.sebbejohansson.yggdrasil.history.HistoryViewModel
import android.Manifest
import android.content.Intent
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.ui.platform.LocalContext
import io.github.sebbejohansson.yggdrasil.boot.BootKind
import io.github.sebbejohansson.yggdrasil.boot.BootLogic
import io.github.sebbejohansson.yggdrasil.boot.BootOverlay
import io.github.sebbejohansson.yggdrasil.boot.BootPhone
import io.github.sebbejohansson.yggdrasil.boot.BootSettings
import io.github.sebbejohansson.yggdrasil.boot.BootSettingsScreen
import io.github.sebbejohansson.yggdrasil.boot.Health
import java.time.LocalDate
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import io.github.sebbejohansson.yggdrasil.ui.Aurora
import io.github.sebbejohansson.yggdrasil.ui.AuroraSky
import java.time.Instant
import java.time.LocalDateTime
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
import io.github.sebbejohansson.yggdrasil.dellingr.DellingrService
import io.github.sebbejohansson.yggdrasil.huginn.HuginnScreen
import io.github.sebbejohansson.yggdrasil.huginn.HuginnViewModel
import io.github.sebbejohansson.yggdrasil.dellingr.JobsScreen
import io.github.sebbejohansson.yggdrasil.dellingr.JobsViewModel
import io.github.sebbejohansson.yggdrasil.vedrfolnir.PhoneScreen
import io.github.sebbejohansson.yggdrasil.vedrfolnir.PhoneViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalTime
import java.time.format.DateTimeFormatter

open class MainActivity : ComponentActivity() {

    /** True in [io.github.sebbejohansson.yggdrasil.home.HomeActivity]: Ygg as the phone's home screen. */
    protected open val home: Boolean = false

    /** Home-button presses while Ygg is the home screen. */
    protected val homePresses = MutableStateFlow(0)

    /** Which tab a notification asked for. */
    private val requestedTab = MutableStateFlow<Int?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bar = Color(0xF005090D).toArgb()
        enableEdgeToEdge(SystemBarStyle.dark(Color.Transparent.toArgb()), SystemBarStyle.dark(bar))
        requestedTab.value = intent.tabExtra()
        val container = (application as YggApp).container
        val fromNotification = requestedTab.value != null
        setContent { YggTheme { Root(container, requestedTab, fromNotification, home, homePresses) } }
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
        const val TAB_APPS = 4
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Root(
    container: AppContainer,
    requestedTab: MutableStateFlow<Int?>,
    fromNotification: Boolean = false,
    home: Boolean = false,
    homePresses: MutableStateFlow<Int> = MutableStateFlow(0),
) {
    var tab by rememberSaveable { mutableIntStateOf(if (home) MainActivity.TAB_APPS else MainActivity.TAB_CHAT) }
    val context = LocalContext.current

    // The boot sequence: the full one once a day, the quick one on other opens. Opened from a
    // notification you want the thing it's about, so at most the quick one.
    var boot by rememberSaveable {
        mutableStateOf(
            BootLogic.kind(container.bootSettings.choice, container.bootSettings.lastDay, LocalDate.now()).let {
                when {
                    fromNotification && it == BootKind.FULL -> BootKind.QUICK
                    // As the home screen it's open all day: the daily boot only, never the quick check.
                    home && it == BootKind.QUICK -> BootKind.NONE
                    else -> it
                }
            },
        )
    }
    // Let the parts' states arrive first, as the desktop waits for its first poll.
    var bootReady by remember { mutableStateOf(false) }
    LaunchedEffect(boot) {
        if (boot != BootKind.NONE) {
            delay(700)
            bootReady = true
        }
    }
    val asked by requestedTab.collectAsStateWithLifecycle()
    LaunchedEffect(asked) {
        asked?.let { tab = it; requestedTab.value = null }
    }

    val chat: ChatViewModel = viewModel(factory = ChatViewModel.factory(container))
    val alerts: AlertsViewModel = viewModel(factory = AlertsViewModel.factory(container))
    val messages: MessagesViewModel = viewModel(factory = MessagesViewModel.factory(container))
    val phone: PhoneViewModel = viewModel(factory = PhoneViewModel.factory(container))
    val jobs: JobsViewModel = viewModel(factory = JobsViewModel.factory(container))
    val huginnVm: HuginnViewModel = viewModel(factory = HuginnViewModel.factory(container, LocalContext.current))
    val historyVm: HistoryViewModel = viewModel(factory = HistoryViewModel.factory(container))
    val appsVm: AppsViewModel? = if (home) viewModel(factory = AppsViewModel.factory(container)) else null
    val presses by homePresses.collectAsStateWithLifecycle()
    // Home goes to the apps, whatever page you were on.
    LaunchedEffect(presses) { if (home && presses > 0) tab = MainActivity.TAB_APPS }
    // Back never leaves the home screen: it goes back to the apps.
    BackHandler(enabled = home && tab != MainActivity.TAB_APPS) { tab = MainActivity.TAB_APPS }
    var homeHeld by remember { mutableStateOf(HomeRole.held(context)) }
    val askHome = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { homeHeld = HomeRole.held(context) }
    val huginnState by huginnVm.state.collectAsStateWithLifecycle()
    val jobsState by jobs.state.collectAsStateWithLifecycle()
    val failingJobs = jobsState.jobs.count { it.failures >= DellingrService.FAILING_AFTER }
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
        jobs.recheck()
        huginnVm.recheck()
        homeHeld = HomeRole.held(context)
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
        ReactorPart("Dellingr", if (failingJobs > 0) PartState.TROUBLED else PartState.UP),
        ReactorPart("Huginn", if (huginnState.notificationsAllowed) PartState.UP else PartState.TROUBLED),
        // Optional: not set up is dark (not running), not troubled.
        ReactorPart("Claude", if (chatState.providers.firstOrNull { it.leavesDevice }?.status == ProviderStatus.Ready) PartState.UP else PartState.DOWN),
    )

    // Bifröst at night: idle long enough (sooner after work hours), the ground turns to aurora.
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var auroraDue by remember { mutableStateOf(false) }
    val typing = WindowInsets.isImeVisible
    LaunchedEffect(typing, chatState.busy) {
        while (true) {
            if (typing || chatState.busy) lastTouch = System.currentTimeMillis()
            auroraDue = Aurora.due(System.currentTimeMillis() - lastTouch, Aurora.afterHours(LocalDateTime.now()))
            delay(1_000)
        }
    }
    val auroraAlpha by animateFloatAsState(
        if (auroraDue) 1f else 0f,
        animationSpec = tween(if (auroraDue) 4_000 else 500),
        label = "aurora",
    )
    val storm = Aurora.storminess(
        alerts = alertState.active.map { Aurora.SeenAlert(it.alert.severity, it.alert.lastSeenAt, it.muted) } +
            alertState.resolved.map { Aurora.SeenAlert(it.severity, it.lastSeenAt, false) },
        troubledParts = reactor.count { it.name != "Gjallar" && it.state == PartState.TROUBLED },
        now = Instant.now(),
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Hud.crust)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        lastTouch = System.currentTimeMillis()
                        auroraDue = false
                    }
                }
            },
    ) {
        Backdrop(needs = openAlerts + unseen, modifier = Modifier.fillMaxSize())
        AuroraSky(storm, shown = auroraAlpha > 0f, alpha = auroraAlpha, modifier = Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().imePadding()) {
            TopBar(reactor, busy = chatState.busy, onReactor = { tab = MainActivity.TAB_ALERTS })
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    MainActivity.TAB_ALERTS -> AlertsScreen(alerts)
                    MainActivity.TAB_MESSAGES -> MessagesScreen(messages)
                    MainActivity.TAB_PHONE -> PartsScreen(phone, jobs, huginnVm, historyVm, container.bootSettings, onRunBoot = { boot = BootKind.FULL }) {
                        HomeScreenPanel(
                            held = homeHeld,
                            onUse = {
                                val ask = HomeRole.request(context)
                                runCatching { if (ask != null) askHome.launch(ask) else context.startActivity(HomeRole.settings()) }
                            },
                            onChange = { runCatching { context.startActivity(HomeRole.settings()) } },
                        )
                    }
                    MainActivity.TAB_APPS -> if (appsVm != null) AppsScreen(appsVm, presses) else ChatScreen(chat)
                    else -> ChatScreen(chat)
                }
            }
            // Out of the way while typing; the keyboard takes that space.
            if (!WindowInsets.isImeVisible) {
                TabBar {
                    if (home) Tab(tab == MainActivity.TAB_APPS, "Apps", 0, tight = true) { tab = MainActivity.TAB_APPS }
                    Tab(tab == MainActivity.TAB_CHAT, "Norn", 0, tight = home) { tab = MainActivity.TAB_CHAT }
                    Tab(tab == MainActivity.TAB_ALERTS, "Alerts", openAlerts, tight = home) { tab = MainActivity.TAB_ALERTS }
                    Tab(tab == MainActivity.TAB_MESSAGES, "Messages", unseen, tight = home) { tab = MainActivity.TAB_MESSAGES }
                    Tab(tab == MainActivity.TAB_PHONE, "Parts", phoneFailing.size + failingJobs, tight = home) { tab = MainActivity.TAB_PHONE }
                }
            }
        }
        if (boot != BootKind.NONE && bootReady) {
            val readings by container.vedrfolnir.latest.collectAsStateWithLifecycle()
            // Claude is an add-on: listed only when it's there, never "not responding".
            val parts = reactor.filter { it.name != "Claude" || it.state == PartState.UP }.map { it.name to it.state.toHealth() }
            val phoneNow = BootPhone(readings?.memoryTotalBytes, readings?.batteryPercent, "heat" in phoneFailing)
            val outside = listOf(
                "Network" to networkUp(context),
                "Calendar" to container.calendar.allowed(),
                "Notifications" to (messageState.blocked == null),
            )
            BootOverlay(
                kind = boot,
                lines = BootLogic.lines(parts, outside, phoneNow),
                speech = BootLogic.speech(parts, phoneNow, LocalTime.now()),
                voice = container.bootSettings.choice.voice,
                onStarted = { container.bootSettings.lastDay = LocalDate.now() },
                onClose = { boot = BootKind.NONE },
            )
        }
    }
}

private fun PartState.toHealth() = when (this) {
    PartState.UP -> Health.UP
    PartState.TROUBLED -> Health.TROUBLED
    PartState.DOWN -> Health.DOWN
}

private fun networkUp(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
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
private fun RowScope.Tab(selected: Boolean, label: String, count: Int, tight: Boolean = false, onClick: () -> Unit) {
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
                // Five (as the home screen) tighter still.
                fontSize = if (tight) 10.sp else 11.sp,
                letterSpacing = if (tight) 0.03.em else 0.08.em,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) Hud.onCyan else Hud.subtext1,
            ),
            maxLines = 1,
            softWrap = false,
        )
        if (count > 0) {
            Spacer(Modifier.width(4.dp))
            HudBadge(count, color = if (selected) Hud.onCyan else Hud.amber)
        }
    }
}

private fun Modifier.bottomLine(c: Color, width: androidx.compose.ui.unit.Dp = 1.dp) = drawBehind {
    val w = width.toPx()
    drawRect(c, Offset(0f, size.height - w), androidx.compose.ui.geometry.Size(size.width, w))
}

private fun Modifier.topLine(c: Color) = drawBehind {
    drawLine(c, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
}

/** The parts with no tab of their own, under page tabs as on the dashboard (`PageTabs.vue`). */
@Composable
private fun PartsScreen(
    phone: PhoneViewModel,
    jobs: JobsViewModel,
    huginn: HuginnViewModel,
    history: HistoryViewModel,
    bootSettings: BootSettings,
    onRunBoot: () -> Unit,
    settingsMore: @Composable () -> Unit = {},
) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp).bottomLine(Color(0xFF17323F)).horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            listOf("Phone", "Jobs", "Huginn", "Tree", "Runes", "Settings").forEachIndexed { i, label ->
                val on = page == i
                Text(
                    label,
                    style = HudText.small.copy(fontSize = 13.sp, color = if (on) Hud.bright else Hud.overlay2),
                    modifier = Modifier
                        .clickable { page = i }
                        .then(if (on) Modifier.bottomLine(Hud.cyan, 2.dp) else Modifier)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
        Box(Modifier.weight(1f)) {
            when (page) {
                0 -> PhoneScreen(phone)
                1 -> JobsScreen(jobs)
                2 -> HuginnScreen(huginn)
                3 -> TreeScreen(history)
                4 -> RunesScreen(history)
                else -> BootSettingsScreen(bootSettings, onRunBoot, settingsMore)
            }
        }
    }
}
