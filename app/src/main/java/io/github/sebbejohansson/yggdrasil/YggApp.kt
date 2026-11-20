package io.github.sebbejohansson.yggdrasil

import android.app.Application
import io.github.sebbejohansson.yggdrasil.llm.LlmRouter
import io.github.sebbejohansson.yggdrasil.llm.claude.ClaudeBridgeProvider
import io.github.sebbejohansson.yggdrasil.llm.litert.LiteRtLmProvider
import io.github.sebbejohansson.yggdrasil.model.ModelCatalog
import io.github.sebbejohansson.yggdrasil.model.ModelDownloader
import io.github.sebbejohansson.yggdrasil.model.ModelStore

class YggApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Everything the parts share. Built once, by hand: no DI framework yet. */
class AppContainer(app: Application) {
    val modelStore = ModelStore(app)
    val modelDownloader = ModelDownloader(modelStore)

    val defaultModel = ModelCatalog.GEMMA_4_E2B
    val localLlm = LiteRtLmProvider(defaultModel, modelStore, app.cacheDir)
    val claudeBridge = ClaudeBridgeProvider()

    /** On-device first; Claude only for requests allowed to leave the phone. */
    val llm = LlmRouter(listOf(localLlm, claudeBridge))
}
