package com.lumena.android.agent

internal object ChatGptUiPolicy {
    private val generatingLabels = setOf(
        "stop",
        "stop generating",
        "zatrzymaj",
        "zatrzymaj generowanie",
        "зупинити",
        "зупинити генерацію",
        "остановить",
        "остановить генерацию"
    )

    private val generatingPhrases = listOf(
        "stop generating",
        "zatrzymaj generowanie",
        "зупинити генерац",
        "остановить генерац"
    )

    private val sendLabels = setOf(
        "send",
        "send message",
        "wyślij",
        "wyslij",
        "надіслати",
        "відправити",
        "отправить",
        "submit"
    )

    fun normalize(label: String): String =
        label.trim()
            .lowercase()
            .replace(Regex("\\s+"), " ")

    fun isGeneratingLabel(label: String): Boolean {
        val value = normalize(label)
        if (value in generatingLabels) return true
        return generatingPhrases.any { value.contains(it) }
    }

    fun isSendLabel(label: String): Boolean =
        normalize(label) in sendLabels
}
