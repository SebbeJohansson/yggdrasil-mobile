package io.github.sebbejohansson.yggdrasil

import io.github.sebbejohansson.yggdrasil.llm.claude.BridgePanel
import io.github.sebbejohansson.yggdrasil.llm.claude.BridgeViewModel
import io.github.sebbejohansson.yggdrasil.llm.claude.LocalViewModel
import io.github.sebbejohansson.yggdrasil.parts.PerformanceScreen
import io.github.sebbejohansson.yggdrasil.today.TodayTarget
import io.github.sebbejohansson.yggdrasil.today.TodayCard
import io.github.sebbejohansson.yggdrasil.norn.PromisesScreen
import io.github.sebbejohansson.yggdrasil.norn.PromisesViewModel
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.sebbejohansson.yggdrasil.parts.partReports
import io.github.sebbejohansson.yggdrasil.parts.Parts
import io.github.sebbejohansson.yggdrasil.parts.HealthScreen
import io.github.sebbejohansson.yggdrasil.ui.HudSection
import io.github.sebbejohansson.yggdrasil.ratatoskr.YouPanel
import io.github.sebbejohansson.yggdrasil.hermodr.FocusPanel
import io.github.sebbejohansson.yggdrasil.hermodr.FocusStrip
import io.github.sebbejohansson.yggdrasil.hermodr.FocusButton
import io.github.sebbejohansson.yggdrasil.update.UpdatesPanel
import androidx.activity.compose.BackHandler
import io.github.sebbejohansson.yggdrasil.home.HomeScreenPanel
import io.github.sebbejohansson.yggdrasil.home.HomeRole
import io.github.sebbejohansson.yggdrasil.home.AppsScreen
import io.github.sebbejohansson.yggdrasil.home.AppsViewModel
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
import io.github.sebbejohansson.yggdrasil.settings.SettingsScreen
import io.github.sebbejohansson.yggdrasil.ui.PageTabs
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
import io.github.sebbejohansson.yggdrasil.saga.CodeScreen
import io.github.sebbejohansson.yggdrasil.saga.CodeViewModel
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
        // Kept as they were: notifications ask for these numbers. Alerts and
        // Messages are both the Inbox tab, each opening its own page of it.
        const val TAB_CHAT = 0
        const val TAB_ALERTS = 1
        const val TAB_MESSAGES = 2
        const val TAB_SYSTEM = 3
        const val TAB_APPS = 4
        const val TAB_CODE = 5
        /** Not in the tab bar: the gear in the top bar opens it. */
        const val TAB_SETTINGS = 6
        /** Not in the tab bar: the arc reactor opens it. */
        const val TAB_HEALTH = 7
        /** Inbox → Promises. */
        const val TAB_PROMISES = 8
        /** Not in the tab bar: Health → Performance. */
        const val TAB_PERF = 9

        fun isInbox(tab: Int) = tab == TAB_ALERTS || tab == TAB_MESSAGES || tab == TAB_PROMISES
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
    // The Inbox opens on whichever page you left it on; Settings goes back to where you came from.
    var lastInbox by rememberSaveable { mutableIntStateOf(MainActivity.TAB_ALERTS) }
    var beforeSettings by rememberSaveable { mutableIntStateOf(MainActivity.TAB_CHAT) }
    var systemPage by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(tab) { if (MainActivity.isInbox(tab)) lastInbox = tab }
    fun openSettings() {
        if (tab != MainActivity.TAB_SETTINGS && tab != MainActivity.TAB_HEALTH) beforeSettings = tab
        tab = MainActivity.TAB_SETTINGS
    }

    /** The arc reactor's page: every part's health. Again closes it. */
    fun toggleHealth() {
        if (tab == MainActivity.TAB_HEALTH || tab == MainActivity.TAB_PERF) {
            tab = beforeSettings
            return
        }
        if (tab != MainActivity.TAB_SETTINGS) beforeSettings = tab
        tab = MainActivity.TAB_HEALTH
    }
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
    val promisesVm: PromisesViewModel = viewModel(factory = PromisesViewModel.factory(container))
    val promiseList by promisesVm.state.collectAsStateWithLifecycle()
    val promisesDue = PromisesViewModel.dueSoon(promiseList)
    val codeVm: CodeViewModel = viewModel(factory = CodeViewModel.factory(container))
    val bridgeVm: BridgeViewModel = viewModel(factory = BridgeViewModel.factory(container))
    val localVm: LocalViewModel = viewModel(factory = LocalViewModel.factory(container))
    val appsVm: AppsViewModel? = if (home) viewModel(factory = AppsViewModel.factory(container)) else null
    val presses by homePresses.collectAsStateWithLifecycle()
    // Home goes to the apps, whatever page you were on.
    LaunchedEffect(presses) { if (home && presses > 0) tab = MainActivity.TAB_APPS }
    // Back from Settings goes to where you were.
    BackHandler(enabled = tab == MainActivity.TAB_SETTINGS || tab == MainActivity.TAB_HEALTH) { tab = beforeSettings }
    BackHandler(enabled = tab == MainActivity.TAB_PERF) { tab = MainActivity.TAB_HEALTH }
    // Back never leaves the home screen: it goes back to the apps.
    BackHandler(
        enabled = home && tab != MainActivity.TAB_APPS && tab != MainActivity.TAB_SETTINGS && tab != MainActivity.TAB_HEALTH && tab != MainActivity.TAB_PERF,
    ) {
        tab = MainActivity.TAB_APPS
    }
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
        alerts.syncNow()
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
            TopBar(
                reactor,
                busy = chatState.busy,
                // The ring is the parts' health: it opens the Health page, as the dashboard's.
                onReactor = ::toggleHealth,
                settingsOpen = tab == MainActivity.TAB_SETTINGS,
                onSettings = { if (tab == MainActivity.TAB_SETTINGS) tab = beforeSettings else openSettings() },
                focus = { FocusButton(container.hermodr, container.calendar) },
            )
            FocusStrip(container.hermodr)
            val todayCard: @Composable () -> Unit = {
                TodayCard(container, promiseList, alertState.active, jobsState.jobs, unseen, onOpen = { target ->
                    when (target) {
                        TodayTarget.PROMISES -> tab = MainActivity.TAB_PROMISES
                        TodayTarget.ALERTS -> tab = MainActivity.TAB_ALERTS
                        TodayTarget.JOBS -> { systemPage = 1; tab = MainActivity.TAB_SYSTEM }
                    }
                })
            }
            if (tab == MainActivity.TAB_CHAT) todayCard()
            // Health, from the arc reactor and from System; Performance opens from it.
            val healthPage: @Composable (onBack: () -> Unit, onPerformance: () -> Unit) -> Unit = { onBack, onPerformance ->
                val loopsVersion by container.loops.changes.flow.collectAsStateWithLifecycle()
                val readings by container.vedrfolnir.latest.collectAsStateWithLifecycle()
                val focusVersion by container.hermodr.changes.flow.collectAsStateWithLifecycle()
                @Suppress("UNUSED_EXPRESSION") loopsVersion
                @Suppress("UNUSED_EXPRESSION") focusVersion
                val now = java.time.Instant.now()
                val all = alertState.active
                val cards = Parts.cards(
                    partReports(
                        chatState, messageState, container.hermodr.focusNow(), jobsState, huginnState, readings, phoneFailing,
                        alertState, container.you, container.updater, container.loops, now,
                    ),
                    all, container.loops.startedAt, now,
                )
                HealthScreen(cards, Parts.unclaimed(cards, all), alerts, onBack = onBack, onPerformance = onPerformance)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    MainActivity.TAB_ALERTS, MainActivity.TAB_MESSAGES, MainActivity.TAB_PROMISES -> InboxScreen(
                        page = tab,
                        alerts = alerts,
                        messages = messages,
                        promises = promisesVm,
                        openAlerts = openAlerts,
                        unseen = unseen,
                        promisesDue = promisesDue,
                        onPage = { tab = it },
                    )
                    MainActivity.TAB_CODE -> CodeScreen(codeVm, phone = bridgeVm, local = localVm, onSetUpPhone = ::openSettings)
                    MainActivity.TAB_SYSTEM -> SystemScreen(systemPage, { systemPage = it }, phone, jobs, huginnVm, historyVm,
                        health = { healthPage({ systemPage = 0 }, { systemPage = 6 }) },
                        performance = { PerformanceScreen(container, onBack = { systemPage = 5 }) })
                    MainActivity.TAB_HEALTH -> healthPage({ tab = beforeSettings }, { tab = MainActivity.TAB_PERF })
                    MainActivity.TAB_PERF -> PerformanceScreen(container, onBack = { tab = MainActivity.TAB_HEALTH })
                    MainActivity.TAB_SETTINGS -> SettingsScreen(
                        chat = chat,
                        alerts = alerts,
                        messages = messages,
                        bootSettings = container.bootSettings,
                        onRunBoot = { boot = BootKind.FULL },
                        onBack = { tab = beforeSettings },
                    ) {
                        HomeScreenPanel(
                            held = homeHeld,
                            onUse = {
                                val ask = HomeRole.request(context)
                                runCatching { if (ask != null) askHome.launch(ask) else context.startActivity(HomeRole.settings()) }
                            },
                            onChange = { runCatching { context.startActivity(HomeRole.settings()) } },
                        )
                        UpdatesPanel(container.updater, container.appScope)
                        HudSection("Claude bridge", Modifier.padding(top = 8.dp))
                        BridgePanel(bridgeVm, container.bridgeSettings, container.ownLinux, container.linuxLogin, container.linuxShell)
                        HudSection("Focus", Modifier.padding(top = 8.dp))
                        FocusPanel(container.hermodr)
                        HudSection("Watching you", Modifier.padding(top = 8.dp))
                        YouPanel(container.you)
                    }
                    MainActivity.TAB_APPS -> if (appsVm != null) AppsScreen(appsVm, presses, header = todayCard) else ChatScreen(chat, onSettings = ::openSettings)
                    else -> ChatScreen(chat, onSettings = ::openSettings)
                }
            }
            // Out of the way while typing; the keyboard takes that space.
            if (!WindowInsets.isImeVisible) {
                TabBar {
                    if (home) Tab(tab == MainActivity.TAB_APPS, "Apps", 0, tight = true) { tab = MainActivity.TAB_APPS }
                    Tab(tab == MainActivity.TAB_CHAT, "Ask", 0, tight = home) { tab = MainActivity.TAB_CHAT }
                    Tab(MainActivity.isInbox(tab), "Inbox", openAlerts + unseen + promisesDue, tight = home) { tab = lastInbox }
                    Tab(tab == MainActivity.TAB_CODE, "Code", 0, tight = home) { tab = MainActivity.TAB_CODE }
                    Tab(tab == MainActivity.TAB_SYSTEM, "System", phoneFailing.size + failingJobs, tight = home) { tab = MainActivity.TAB_SYSTEM }
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
private fun TopBar(
    parts: List<ReactorPart>,
    busy: Boolean,
    onReactor: () -> Unit,
    settingsOpen: Boolean,
    onSettings: () -> Unit,
    focus: @Composable () -> Unit = {},
) {
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
        focus()
        Box(
            Modifier.size(40.dp).clickable(onClick = onSettings),
            contentAlignment = Alignment.Center,
        ) {
            Text("⚙", style = HudText.title.copy(fontSize = 20.sp, color = if (settingsOpen) Hud.cyan else Hud.subtext0))
        }
        Spacer(Modifier.width(4.dp))
        ArcReactor(
            parts, busy = busy, size = 36.dp,
            modifier = Modifier.semantics { contentDescription = "Health of the parts" }.clickable(onClick = onReactor),
        )
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
                // Five (as the home screen) tighter still; the labels are short enough for it.
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

/** Gjallar's alerts and Hermóðr's messages: everything that wants you, under one tab. */
@Composable
private fun InboxScreen(
    page: Int,
    alerts: AlertsViewModel,
    messages: MessagesViewModel,
    promises: PromisesViewModel,
    openAlerts: Int,
    unseen: Int,
    promisesDue: Int,
    onPage: (Int) -> Unit,
) {
    val pages = listOf(MainActivity.TAB_ALERTS, MainActivity.TAB_MESSAGES, MainActivity.TAB_PROMISES)
    Column(Modifier.fillMaxSize()) {
        PageTabs(
            listOf("Alerts", "Messages", "Promises"),
            selected = pages.indexOf(page).coerceAtLeast(0),
            onSelect = { onPage(pages[it]) },
            counts = listOf(openAlerts, unseen, promisesDue),
        )
        Box(Modifier.weight(1f)) {
            when (page) {
                MainActivity.TAB_MESSAGES -> MessagesScreen(messages)
                MainActivity.TAB_PROMISES -> PromisesScreen(promises)
                else -> AlertsScreen(alerts)
            }
        }
    }
}

/** The parts with no tab of their own, under page tabs as on the dashboard's System (`PageTabs.vue`). */
@Composable
private fun SystemScreen(
    page: Int,
    onPage: (Int) -> Unit,
    phone: PhoneViewModel,
    jobs: JobsViewModel,
    huginn: HuginnViewModel,
    history: HistoryViewModel,
    /** Every part's health, as the arc reactor opens it. */
    health: @Composable () -> Unit,
    /** What pulls on what: processes, battery, storage, the phone, the model. */
    performance: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        PageTabs(listOf("Phone", "Jobs", "Huginn", "Tree", "Runes", "Health", "Performance"), selected = page, onSelect = onPage)
        Box(Modifier.weight(1f)) {
            when (page) {
                0 -> PhoneScreen(phone)
                1 -> JobsScreen(jobs)
                2 -> HuginnScreen(huginn)
                3 -> TreeScreen(history)
                4 -> RunesScreen(history)
                5 -> health()
                else -> performance()
            }
        }
    }
}
