package io.github.sebbejohansson.yggdrasil

import android.app.Application
import androidx.work.Configuration
import io.github.sebbejohansson.yggdrasil.boot.BootSettings
import io.github.sebbejohansson.yggdrasil.core.YggDatabase
import io.github.sebbejohansson.yggdrasil.dellingr.AndroidJobAlarm
import io.github.sebbejohansson.yggdrasil.dellingr.DellingrService
import io.github.sebbejohansson.yggdrasil.gjallar.GjallarService
import io.github.sebbejohansson.yggdrasil.huginn.HuginnRuntime
import io.github.sebbejohansson.yggdrasil.huginn.HuginnService
import io.github.sebbejohansson.yggdrasil.huginn.PeopleSettings
import io.github.sebbejohansson.yggdrasil.hermodr.AndroidNotifier
import io.github.sebbejohansson.yggdrasil.hermodr.HermodrService
import io.github.sebbejohansson.yggdrasil.hermodr.HermodrSettings
import io.github.sebbejohansson.yggdrasil.hermodr.WorkManagerFlushScheduler
import io.github.sebbejohansson.yggdrasil.llm.LlmRouter
import io.github.sebbejohansson.yggdrasil.llm.claude.ClaudeBridgeProvider
import io.github.sebbejohansson.yggdrasil.llm.litert.LiteRtLmProvider
import io.github.sebbejohansson.yggdrasil.model.ModelCatalog
import io.github.sebbejohansson.yggdrasil.model.ModelDownloader
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
import io.github.sebbejohansson.yggdrasil.vedrfolnir.AndroidPhoneReader
import io.github.sebbejohansson.yggdrasil.vedrfolnir.VedrfolnirService
import io.github.sebbejohansson.yggdrasil.vedrfolnir.VedrfolnirWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class YggApp : Application(), Configuration.Provider {
    lateinit var container: AppContainer
        private set

    // WorkManager starts on first use from this, not from its startup provider
    // (removed in the manifest), so it also works where that provider doesn't run.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
    }
}

/** Everything the parts share. Built once, by hand: no DI framework yet. */
class AppContainer(private val app: Application) {
    /** Lives as long as the process; for work that must outlive a screen. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Models
    val modelStore = ModelStore(app)
    val modelDownloader = ModelDownloader(modelStore)
    val defaultModel = ModelCatalog.GEMMA_4_E2B
    val localLlm = LiteRtLmProvider(defaultModel, modelStore, app.cacheDir)
    val claudeBridge = ClaudeBridgeProvider()

    /** On-device first; Claude only for requests allowed to leave the phone. */
    val llm = LlmRouter(listOf(localLlm, claudeBridge))

    // Parts
    private val db = YggDatabase(app)
    val notifier = AndroidNotifier(app)
    val hermodrSettings = HermodrSettings(app)
    val bootSettings = BootSettings(app)

    /** The phone's calendars, for Norn and for Hermóðr's hold. */
    val calendar = AndroidCalendarReader(app)
    val hermodr = HermodrService(
        store = db.messages,
        notifier = notifier,
        scheduler = WorkManagerFlushScheduler(app),
        quietHours = { hermodrSettings.quietHours },
        busy = CalendarBusySignal(calendar),
    )
    val gjallar = GjallarService(db.alerts, hermodr)
    val vedrfolnir = VedrfolnirService(AndroidPhoneReader(app), gjallar)
    val jobAlarm = AndroidJobAlarm(app)
    // Norn's memory: facts are picked out on the side, at temperature 0, on the phone only.
    val embedder = LiteRtEmbedder(ModelCatalog.EMBEDDING_GEMMA_2, modelStore, app.cacheDir)
    val nornSettings = NornSettings(app)
    val brain = Brain(db.memories, embedder, FactModel { messages ->
        llm.complete(
            LlmRequest(
                messages = messages, system = Facts.system, tier = LlmTier.SMALL, privacy = Privacy.ON_DEVICE_ONLY,
                maxOutputTokens = 200, temperature = 0.0, side = true,
            ),
        )
    })
    val nornChat = NornChat(db.chats, brain)
    private val nornAsker = NornAsker(llm, calendar, brain)
    val dellingr = DellingrService(db.jobs, nornAsker, hermodr, gjallar, jobAlarm)
    val people = PeopleSettings(app)
    val huginn = HuginnService(db.huginn, nornAsker, hermodr)
    val huginnRuntime = HuginnRuntime(huginn, calendar)

    fun start() {
        notifier.createChannels()
        huginnRuntime.follow(gjallar, appScope)
        VedrfolnirWorker.schedule(app)
        appScope.launch {
            hermodr.prune()
            gjallar.prune()
            // Anything held past its time (the phone was off, or Doze ran late).
            hermodr.flushHeld()
            vedrfolnir.check()
            // Runs what came due while the app wasn't running, and sets the next alarm.
            dellingr.tick()
            huginn.prune()
            huginnRuntime.checkCalendar()
        }
    }
}
