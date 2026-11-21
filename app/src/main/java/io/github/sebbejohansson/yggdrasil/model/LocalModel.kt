package io.github.sebbejohansson.yggdrasil.model

import io.github.sebbejohansson.yggdrasil.llm.LlmTier

/** A model file that runs on the phone through LiteRT-LM. */
data class LocalModel(
    val id: String,
    val displayName: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    /** Total RAM the phone needs before we offer this model at all. */
    val minDeviceRamBytes: Long,
    val tiers: Set<LlmTier>,
)

private const val GB = 1024L * 1024 * 1024
private const val HF = "https://huggingface.co/litert-community"

object ModelCatalog {
    // The plain .litertlm files run on both the GPU and the CPU backend.
    val GEMMA_4_E2B = LocalModel(
        id = "gemma-4-e2b",
        displayName = "Gemma 4 E2B",
        fileName = "gemma-4-E2B-it.litertlm",
        url = "$HF/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        sizeBytes = 2_588_147_712,
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        minDeviceRamBytes = 6 * GB,
        tiers = setOf(LlmTier.SMALL, LlmTier.LARGE),
    )

    val GEMMA_4_E4B = LocalModel(
        id = "gemma-4-e4b",
        displayName = "Gemma 4 E4B",
        fileName = "gemma-4-E4B-it.litertlm",
        url = "$HF/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
        sizeBytes = 3_659_530_240,
        sha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
        minDeviceRamBytes = 8 * GB,
        tiers = setOf(LlmTier.LARGE),
    )

    /** Norn's memory: turns facts into vectors, so they're found by meaning. Small, runs on the CPU. */
    val EMBEDDING_GEMMA_2 = LocalModel(
        id = "embeddinggemma-2-270m",
        displayName = "EmbeddingGemma 2",
        fileName = "embeddinggemma-2-text-270m.litertlm",
        url = "$HF/embeddinggemma-2-text-270m-litert-lm/resolve/main/embeddinggemma-2-text-270m.litertlm",
        sizeBytes = 164_626_432,
        sha256 = "2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb",
        minDeviceRamBytes = 3 * GB,
        tiers = emptySet(),
    )

    val all = listOf(GEMMA_4_E2B, GEMMA_4_E4B)

    fun byId(id: String) = (all + EMBEDDING_GEMMA_2).firstOrNull { it.id == id }
}
