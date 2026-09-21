package com.lumena.android.llama

import kotlin.math.min

/** Zero selects automatic policy. Manual values are ceilings, not overrides of safety limits. */
data class LlamaTuning(
    val contextTokens: Int = 0,
    val batchTokens: Int = 0,
    val cpuThreads: Int = 0,
    val responseTokens: Int = 0,
    val extraRamMb: Int = 0
) {
    fun normalized() = copy(
        contextTokens = contextTokens.takeIf { it in CONTEXT } ?: 0,
        batchTokens = batchTokens.takeIf { it in BATCH } ?: 0,
        cpuThreads = cpuThreads.takeIf { it in THREADS } ?: 0,
        responseTokens = responseTokens.takeIf { it in RESPONSE } ?: 0,
        extraRamMb = extraRamMb.takeIf { it in RESERVE } ?: 0
    )

    fun applyTo(auto: LlamaGenerationConfig): LlamaGenerationConfig {
        val safe = normalized()
        fun cap(automatic: Int, manual: Int) = if (manual == 0) automatic else min(automatic, manual)
        val context = cap(auto.contextSize, safe.contextTokens)
        return LlamaGenerationConfig(
            contextSize = context,
            batchSize = min(context, cap(auto.batchSize, safe.batchTokens)),
            threads = cap(auto.threads, safe.cpuThreads),
            maxTokens = min(context / 3, cap(auto.maxTokens, safe.responseTokens))
        )
    }

    companion object {
        val CONTEXT = listOf(0, 512, 1024, 2048, 4096)
        val BATCH = listOf(0, 32, 64, 128, 256, 512)
        val THREADS = listOf(0, 1, 2, 3, 4, 6, 8)
        val RESPONSE = listOf(0, 64, 128, 256, 384, 512, 768)
        val RESERVE = listOf(0, 256, 512, 1024, 2048)
        val ECONOMY = LlamaTuning(1024, 32, 2, 128, 512)
    }
}
