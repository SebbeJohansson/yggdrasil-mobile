package io.github.sebbejohansson.yggdrasil.llm.litert

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import io.github.sebbejohansson.yggdrasil.model.LocalModel
import io.github.sebbejohansson.yggdrasil.model.ModelStore
import io.github.sebbejohansson.yggdrasil.norn.Embedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * EmbeddingGemma 2 through LiteRT-LM, for Norn's memory. On the CPU, so it
 * never waits for Gemma on the GPU. Null vectors until the model is
 * downloaded: memory then falls back to the newest facts, as on the desktop.
 */
class LiteRtEmbedder(
    private val modelFile: LocalModel,
    private val store: ModelStore,
    private val cacheDir: File,
) : Embedder {
    // Vectors from another model or size don't compare, so both are in the name.
    override val model = "${modelFile.id}@$DIMENSIONS"

    private val lock = Mutex()
    private var engine: EmbeddingEngine? = null

    /** Whether the embedding model is in memory now. */
    val loaded: Boolean get() = engine != null

    fun ready() = store.isDownloaded(modelFile)

    override suspend fun embed(texts: List<String>): List<FloatArray>? {
        if (!ready() || texts.isEmpty()) return null
        return lock.withLock {
            withContext(Dispatchers.Default) {
                try {
                    val e = engine ?: EmbeddingEngine(
                        EmbeddingEngineConfig(store.fileFor(modelFile).absolutePath, Backend.CPU(), cacheDir = cacheDir.absolutePath),
                    ).also { it.initialize(); engine = it }
                    val options = EmbeddingOptions(normalize = true, outputSize = DIMENSIONS)
                    texts.map { e.computeEmbedding(listOf(InputData.Text(it)), options).embedding }
                } catch (t: Throwable) {
                    Log.w("LiteRtEmbedder", "no embedding", t)
                    null
                }
            }
        }
    }

    suspend fun release() = lock.withLock {
        engine?.close()
        engine = null
    }

    companion object {
        /** EmbeddingGemma 2 can give 128 to 768; 256 keeps a fact's vector at 1 KB. */
        const val DIMENSIONS = 256
    }
}
