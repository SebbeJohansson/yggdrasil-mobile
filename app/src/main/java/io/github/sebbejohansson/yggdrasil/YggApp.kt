package io.github.sebbejohansson.yggdrasil

import io.github.sebbejohansson.yggdrasil.loki.Loki
import io.github.sebbejohansson.yggdrasil.heimdall.Heimdall
import io.github.sebbejohansson.yggdrasil.munin.Munin
import io.github.sebbejohansson.yggdrasil.ratatoskr.Watchdog
import io.github.sebbejohansson.yggdrasil.core.Watch
import io.github.sebbejohansson.yggdrasil.linux.LinuxLogin
import io.github.sebbejohansson.yggdrasil.linux.LinuxShell
import io.github.sebbejohansson.yggdrasil.linux.OwnLinux
import io.github.sebbejohansson.yggdrasil.llm.claude.BridgeSettings
import io.github.sebbejohansson.yggdrasil.llm.claude.BridgeClient
import io.github.sebbejohansson.yggdrasil.core.NetMeter
import io.github.sebbejohansson.yggdrasil.norn.Embedder
import java.time.Instant
import io.github.sebbejohansson.yggdrasil.llm.LlmMeter
import io.github.sebbejohansson.yggdrasil.core.PerfMath
import io.github.sebbejohansson.yggdrasil.core.PerfStep
import io.github.sebbejohansson.yggdrasil.core.Perf
import io.github.sebbejohansson.yggdrasil.llm.LlmRole
import io.github.sebbejohansson.yggdrasil.llm.LlmMessage
import io.github.sebbejohansson.yggdrasil.norn.PromiseModel
import io.github.sebbejohansson.yggdrasil.norn.PromiseService
import io.github.sebbejohansson.yggdrasil.parts.Parts
import io.github.sebbejohansson.yggdrasil.core.Loops
import io.github.sebbejohansson.yggdrasil.hermodr.FocusTileService
import io.github.sebbejohansson.yggdrasil.ratatoskr.YouSettings
import io.github.sebbejohansson.yggdrasil.ratatoskr.YouService
import android.os.PowerManager
import io.github.sebbejohansson.yggdrasil.update.KeystoreTokenVault
import io.github.sebbejohansson.yggdrasil.update.Updates
import io.github.sebbejohansson.yggdrasil.update.UpdateWorker
import io.github.sebbejohansson.yggdrasil.update.UpdateSettings
import io.github.sebbejohansson.yggdrasil.update.Updater
import android.os.Bundle
import android.app.Activity
import io.github.sebbejohansson.yggdrasil.home.HomeSettings
import io.github.sebbejohansson.yggdrasil.home.InstalledApps
import android.app.Application
import androidx.work.Configuration
import io.github.sebbejohansson.yggdrasil.boot.BootSettings
import io.github.sebbejohansson.yggdrasil.core.YggDatabase
import io.github.sebbejohansson.yggdrasil.dellingr.AndroidJobAlarm
import io.github.sebbejohansson.yggdrasil.dellingr.DellingrService
import io.github.sebbejohansson.yggdrasil.gjallar.Alert
import io.github.sebbejohansson.yggdrasil.gjallar.AlertChange
import io.github.sebbejohansson.yggdrasil.gjallar.GjallarService
import io.github.sebbejohansson.yggdrasil.history.HistoryEvent
import io.github.sebbejohansson.yggdrasil.history.HistoryKind
import io.github.sebbejohansson.yggdrasil.history.HistoryLog
import io.github.sebbejohansson.yggdrasil.huginn.HuginnRuntime
import io.github.sebbejohansson.yggdrasil.huginn.HuginnService
import io.github.sebbejohansson.yggdrasil.huginn.PeopleSettings
import io.github.sebbejohansson.yggdrasil.hermodr.AndroidNotifier
import io.github.sebbejohansson.yggdrasil.hermodr.HermodrService
import io.github.sebbejohansson.yggdrasil.hermodr.HermodrSettings
import io.github.sebbejohansson.yggdrasil.hermodr.WorkManagerFlushScheduler
import io.github.sebbejohansson.yggdrasil.llm.LlmRouter
import io.github.sebbejohansson.yggdrasil.llm.NameAnswers
import io.github.sebbejohansson.yggdrasil.llm.NameFinder
import io.github.sebbejohansson.yggdrasil.llm.NameGuard
import io.github.sebbejohansson.yggdrasil.llm.complete
import io.github.sebbejohansson.yggdrasil.norn.Person
import io.github.sebbejohansson.yggdrasil.llm.claude.ClaudeBridgeProvider
import io.github.sebbejohansson.yggdrasil.llm.litert.LiteRtLmProvider
import io.github.sebbejohansson.yggdrasil.model.ModelCatalog
import io.github.sebbejohansson.yggdrasil.model.ModelDownloader
import io.github.sebbejohansson.yggdrasil.model.ModelDownloads
import io.github.sebbejohansson.yggdrasil.model.ModelStore
import io.github.sebbejohansson.yggdrasil.norn.AndroidCalendarReader
import io.github.sebbejohansson.yggdrasil.norn.CalendarBusySignal
import io.github.sebbejohansson.yggdrasil.norn.NornAsker
import io.github.sebbejohansson.yggdrasil.norn.Brain
import io.github.sebbejohansson.yggdrasil.norn.FactModel
import io.github.sebbejohansson.yggdrasil.norn.Facts
import io.github.sebbejohansson.yggdrasil.norn.NornChat
import io.github.sebbejohansson.yggdrasil.norn.NornSettings
import io.github.sebbejohansson.yggdrasil.llm.LlmRequest
import io.github.sebbejohansson.yggdrasil.llm.LlmTier
import io.github.sebbejohansson.yggdrasil.llm.Privacy
import io.github.sebbejohansson.yggdrasil.llm.litert.LiteRtEmbedder
import io.github.sebbejohansson.yggdrasil.saga.SagaService
import io.github.sebbejohansson.yggdrasil.saga.SagaSettings
import io.github.sebbejohansson.yggdrasil.vedrfolnir.AndroidPhoneReader
import io.github.sebbejohansson.yggdrasil.vedrfolnir.VedrfolnirService
import io.github.sebbejohansson.yggdrasil.vedrfolnir.VedrfolnirWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

class YggApp : Application(), Configuration.Provider {
    lateinit var container: AppContainer
        private set

    private var started = 0

    /** Whether one of Ygg's screens is showing: Android only lets an app on screen open a page. */
    val onScreen: Boolean get() = started > 0

    // WorkManager starts on first use from this, not from its startup provider
    // (removed in the manifest), so it also works where that provider doesn't run.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) { started++ }
            override fun onActivityStopped(a: Activity) { started = (started - 1).coerceAtLeast(0) }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        container.start()
    }
}

/** Everything the parts share. Built once, by hand: no DI framework yet. */
class AppContainer(private val app: Application) {
    /** Lives as long as the process; for work that must outlive a screen. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** What the parts pull: model calls, loop rounds, bytes. For the Performance page. */
    val perf by lazy { Perf(db.perf) }

    // Models
    val modelStore = ModelStore(app)
    val modelDownloader = ModelDownloader(modelStore)
    val modelDownloads = ModelDownloads(app)
    val defaultModel = ModelCatalog.GEMMA_4_E2B
    val localLlm = LiteRtLmProvider(defaultModel, modelStore, app.cacheDir)
    /** Ygg's Claude bridge in Termux: cloud sessions and the Claude provider. Its key is kept encrypted. */
    val bridge = BridgeClient(
        KeystoreTokenVault(app, prefsName = "bridge_key", alias = "ygg-bridge-key"),
    )
    val bridgeSettings = BridgeSettings(app)
    /** Ygg's own Linux (Ubuntu under proot), where the bridge runs by default. */
    val ownLinux = OwnLinux(app)
    val linuxLogin = LinuxLogin(ownLinux)
    /** A shell in it, for what the app has no button for. */
    val linuxShell = LinuxShell(ownLinux)
    /**
     * Claude, behind the names guard: names are swapped for placeholders by the
     * local model before anything leaves the phone, or nothing is sent.
     */
    val claudeBridge = NameGuard(
        inner = ClaudeBridgeProvider(bridge),
        finder = NameFinder { text ->
            NameAnswers.chunks(text).flatMap { chunk ->
                val raw = perf.time("model", "names", localLlm.displayName) {
                    localLlm.complete(
                    LlmRequest(
                        messages = listOf(io.github.sebbejohansson.yggdrasil.llm.LlmMessage(io.github.sebbejohansson.yggdrasil.llm.LlmRole.USER, chunk)),
                        system = NameAnswers.SYSTEM, tier = LlmTier.SMALL, privacy = Privacy.ON_DEVICE_ONLY,
                        maxOutputTokens = 300, temperature = 0.0, side = true, purpose = "names",
                    ),
                )
                }
                NameAnswers.read(raw, chunk)
            }.distinct()
        },
        known = { people.names.map { Person(it, role = "someone who matters to the user", source = "people who matter") } },
        namesOff = { nornSettings.namesOffUntil > System.currentTimeMillis() },
    )

    /** On-device first; Claude only for requests allowed to leave the phone. */
    val llm = LlmRouter(listOf(localLlm, claudeBridge), meter = LlmMeter { request, provider, ms, chars, failure ->
        perf.record(
            PerfStep(
                "model", request.purpose, Instant.now(), ms, failure == null, failure?.let(PerfMath::failureKind),
                provider?.displayName, chars,
            ),
        )
    })

    // Parts
    private val db = YggDatabase(app)
    val notifier = AndroidNotifier(app)
    val hermodrSettings = HermodrSettings(app)
    val bootSettings = BootSettings(app)

    // The home screen, when you make Ygg the phone's launcher.
    val installedApps = InstalledApps(app)
    val homeSettings = HomeSettings(app)

    /** The phone's calendars, for Norn and for Hermóðr's hold. */
    val calendar = AndroidCalendarReader(app)
    val hermodr = HermodrService(
        store = db.messages,
        notifier = notifier,
        scheduler = WorkManagerFlushScheduler(app),
        quietHours = { hermodrSettings.quietHours },
        busy = CalendarBusySignal(calendar),
        focus = db.focus,
    )
    val gjallar = GjallarService(db.alerts, hermodr)
    /** The parts' timed loops, for the Health page. */
    val loops = Loops(app, perf = { perf })
    // Ratatoskr's watch on your rhythm: long runs of meetings, late nights, full weeks.
    val you = YouService(calendar, hermodr, YouSettings(app), inUse = { app.getSystemService(PowerManager::class.java).isInteractive })
    // Updates from the GitHub releases (release builds only).
    val updater = Updater(app, UpdateSettings(app), hermodr, KeystoreTokenVault(app))
    val vedrfolnir = VedrfolnirService(AndroidPhoneReader(app), gjallar)
    val jobAlarm = AndroidJobAlarm(app)
    // Norn's memory: facts are picked out on the side, at temperature 0, on the phone only.
    val embedder = LiteRtEmbedder(ModelCatalog.EMBEDDING_GEMMA_2, modelStore, app.cacheDir)
    val nornSettings = NornSettings(app)
    /** The world tree's history, kept for good. */
    val history = HistoryLog(db.history)
    val brain = Brain(db.memories, object : Embedder {
        override val model get() = embedder.model
        override suspend fun embed(texts: List<String>) = perf.time("model", "embed", "EmbeddingGemma") { embedder.embed(texts) }
    }, onRemembered = { m ->
        history.record(HistoryEvent(HistoryKind.BRANCH, "memory:${m.id}", m.createdAt.toEpochMilli(), m.text))
    }, facts = FactModel { messages ->
        llm.complete(
            LlmRequest(
                messages = messages, system = Facts.system, tier = LlmTier.SMALL, privacy = Privacy.ON_DEVICE_ONLY,
                maxOutputTokens = 200, temperature = 0.0, side = true, purpose = "memory",
            ),
        )
    })
    // The promise ledger: read by the local model only, never Claude.
    val promises = PromiseService(db.promises, PromiseModel { system, user ->
        llm.complete(
            LlmRequest(
                messages = listOf(LlmMessage(LlmRole.USER, user)), system = system, tier = LlmTier.SMALL,
                privacy = Privacy.ON_DEVICE_ONLY, maxOutputTokens = 400, temperature = 0.0, side = true, purpose = "promises",
            ),
        )
    }, hermodr)
    val nornChat = NornChat(db.chats, brain, promises = promises)
    val dellingr = DellingrService(db.jobs, NornAsker(llm, calendar, brain, purpose = "jobs"), hermodr, gjallar, jobAlarm)
    val people = PeopleSettings(app)
    val huginn = HuginnService(db.huginn, NornAsker(llm, calendar, brain, purpose = "huginn"), hermodr, onDelivered = { r ->
        history.record(HistoryEvent(HistoryKind.RAVEN, "raven:${r.id}", r.at.toEpochMilli(), r.title))
    })
    val huginnRuntime = HuginnRuntime(huginn, calendar)

    private fun healed(a: Alert): HistoryEvent {
        val at = (a.resolvedAt ?: a.lastSeenAt).toEpochMilli()
        return HistoryEvent(HistoryKind.SCAR, "healed:${a.id}:$at", at, "${a.title} healed", a.openedAt.toEpochMilli())
    }

    /** What happened before the tree kept history: what's still kept elsewhere grows in too, once. */
    private suspend fun backfillHistory() {
        brain.memories().forEach { m -> history.record(HistoryEvent(HistoryKind.BRANCH, "memory:${m.id}", m.createdAt.toEpochMilli(), m.text)) }
        gjallar.resolved(500).forEach { history.record(healed(it)) }
        huginn.reactions(500).filter { it.state == io.github.sebbejohansson.yggdrasil.huginn.ReactionState.DELIVERED }
            .forEach { r -> history.record(HistoryEvent(HistoryKind.RAVEN, "raven:${r.id}", r.at.toEpochMilli(), r.title)) }
    }

    /** Mímir on the VPS, set up under Settings → Connection. */
    val sagaSettings = SagaSettings(app)

    // The watchers, as the desktop's: Ratatoskr's watchdog over the parts, Munin over what's
    // outside the phone, Heimdall the security watchman, Loki testing them all.
    private fun mine(source: String): suspend () -> Set<String> = { gjallar.active(1000).filter { it.source == source }.map { it.key }.toSet() }
    val ratatoskrWatch = Watch(app, "ratatoskr", gjallar, mine("ratatoskr"))
    // The Claude bridge, while it's meant to run: it answers, with Claude Code in it.
    private val claudeProbe: suspend () -> String? = probe@{
        val on = app.getSharedPreferences("linux_bridge", android.content.Context.MODE_PRIVATE).getBoolean("always_on", false)
        if (!on || !bridge.hasKey) return@probe null
        val h = bridge.health() ?: return@probe "the bridge doesn't answer on 127.0.0.1:8765"
        if (h.claude.isEmpty()) "Claude Code isn't installed in the bridge's Linux" else null
    }
    val watchdog: Watchdog by lazy {
        Watchdog(ratatoskrWatch, gjallar, loops::get, loops.startedAt, devBuild = BuildConfig.DEBUG, probes = mapOf("claude" to claudeProbe))
    }
    val munin: Munin by lazy {
        Munin(
            Watch(app, "munin", gjallar, mine("munin")),
            targets = { Munin.targets(sagaSettings.url.takeIf { sagaSettings.client() != null }, claude = bridge.hasKey) },
            online = {
                val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
                cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            },
        )
    }
    val heimdall: Heimdall by lazy {
        Heimdall(app, gjallar, token = { KeystoreTokenVault(app).get() }, repo = io.github.sebbejohansson.yggdrasil.update.Updates.REPO, rootfs = ownLinux.rootfs)
    }
    val loki: Loki by lazy {
        Loki(app, gjallar, loops, watchdog = { watchdog }, ratatoskrChecks = { ratatoskrWatch.states() }, scope = appScope, devBuild = BuildConfig.DEBUG)
    }

    /** One round of every watcher, after the parts' own rounds; Loki's plan hourly. */
    suspend fun watchRounds() {
        loops.run(Parts.MUNIN_CHECK.first, Parts.MUNIN_CHECK.second) { munin.round() }
        loops.run(Parts.HEIMDALL_WATCH.first, Parts.HEIMDALL_WATCH.second) { heimdall.round() }
        val plan = loops.get(Parts.LOKI_PLAN.first, Parts.LOKI_PLAN.second)
        if (plan.lastRunAt == null || java.time.Duration.between(plan.lastRunAt, java.time.Instant.now()) >= Parts.LOKI_PLAN.second) {
            loops.run(Parts.LOKI_PLAN.first, Parts.LOKI_PLAN.second) { loki.plan() }
        }
        // Ratatoskr last, so it sees this round's loops.
        loops.run(Parts.RATATOSKR_CHECK.first, Parts.RATATOSKR_CHECK.second) { watchdog.round() }
    }

    /** Keeps the phone's alerts and the desktop's in step through Mímir. */
    val saga = SagaService(db.saga, gjallar, mimir = { sagaSettings.client() }, device = { sagaSettings.device })

    @OptIn(FlowPreview::class)
    fun start() {
        // Network code reports what it moves; the Performance page shows who pulled what.
        NetMeter.sink = { part, kind, bytes, ms -> perf.bytes(part, kind, bytes, ms) }
        appScope.launch(Dispatchers.IO) { perf.prune() }
        notifier.createChannels()
        huginnRuntime.follow(gjallar, appScope)
        // A scar in the tree for every alert that heals.
        appScope.launch {
            gjallar.events.collect { (change, a) ->
                if (change == AlertChange.RESOLVED) history.record(healed(a))
            }
        }
        VedrfolnirWorker.schedule(app)
        UpdateWorker.schedule(app)
        // The Quick Settings tile follows a focus started or ended anywhere.
        appScope.launch { hermodr.changes.flow.collect { FocusTileService.refresh(app) } }
        appScope.launch {
            updater.sayIfUpdated()
            if (Updates.due(updater.settings.lastCheck, System.currentTimeMillis())) updater.round()
        }
        appScope.launch {
            hermodr.prune()
            gjallar.prune()
            // Anything held past its time (the phone was off, or Doze ran late).
            hermodr.flushHeld()
            loops.run(Parts.VEDRFOLNIR_CHECK.first, Parts.VEDRFOLNIR_CHECK.second) { vedrfolnir.check() }
            // Runs what came due while the app wasn't running, and sets the next alarm.
            loops.run(Parts.DELLINGR_TICK.first, Parts.DELLINGR_TICK.second) { dellingr.tick() }
            huginn.prune()
            loops.run(Parts.HUGINN_CALENDAR.first, Parts.HUGINN_CALENDAR.second) { huginnRuntime.checkCalendar() }
            backfillHistory()
            loops.run(Parts.RATATOSKR_YOU.first, Parts.RATATOSKR_YOU.second) { you.round() }
            loops.run(Parts.NORN_PROMISES.first, Parts.NORN_PROMISES.second) { promises.remind() }
            saga.prune()
            watchRounds()
        }
        // Sync a moment after any alert changes here (and once now, on start).
        appScope.launch {
            gjallar.changes.flow.debounce(2_000).collect { saga.sync() }
        }
    }
}
