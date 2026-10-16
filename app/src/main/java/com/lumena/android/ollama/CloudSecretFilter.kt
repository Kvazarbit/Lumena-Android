package com.lumena.android.ollama

import com.lumena.android.agent.core.CloudSecretMaskingStats
import com.lumena.android.agent.core.SecretMasker

/**
 * The last step before messages leave the phone for a *-cloud model: known
 * secret shapes in prompts and tool outputs are masked, and every cloud
 * request is counted for Diagnostics. Local models see the original text.
 */
object CloudSecretFilter {
    fun outbound(model: String, messages: List<OllamaMessage>): List<OllamaMessage> {
        if (!isCloudBackedOllamaModel(model)) return messages
        val results = messages.map { SecretMasker.mask(it.content) }
        CloudSecretMaskingStats.record(model, results)
        return messages.zip(results) { message, result ->
            if (result.masks == 0) message else message.copy(content = result.text)
        }
    }
}
