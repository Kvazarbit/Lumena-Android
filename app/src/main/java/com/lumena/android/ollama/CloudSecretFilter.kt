package com.lumena.android.ollama

/** Red-test stub: masking before *-cloud models lands in the next commit. */
object CloudSecretFilter {
    fun outbound(model: String, messages: List<OllamaMessage>): List<OllamaMessage> = messages
}
