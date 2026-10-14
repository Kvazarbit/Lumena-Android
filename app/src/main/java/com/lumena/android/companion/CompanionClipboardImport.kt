package com.lumena.android.companion

/**
 * Explicit, foreground-only clipboard import for ChatGPT tool requests.
 *
 * Clipboard text is neither stored nor executed here. Parsing deliberately
 * shares CompanionProtocol with the Accessibility route (including its
 * base64-sha256-v1 integrity checks). CompanionScreen still routes the
 * resulting request through ToolGate and the normal approval lifecycle.
 */
internal object CompanionClipboardImport {
    private const val MAX_CLIPBOARD_CHARS = 160_000

    fun parse(text: String?): CompanionCommand? {
        if (text.isNullOrBlank() || text.length > MAX_CLIPBOARD_CHARS) return null
        return CompanionProtocol.parseVisibleText(text)
    }

    fun diagnostic(text: String?): String = when {
        text.isNullOrBlank() ->
            "Буфер порожній або не містить тексту. Скопіюй повний блок LUMENA_TOOL."
        text.length > MAX_CLIPBOARD_CHARS ->
            "Команда з буфера завелика. Скороти блок або використай base64-sha256-v1."
        !text.contains(CompanionProtocol.TOOL_MARKER) ->
            "У буфері немає маркера LUMENA_TOOL. Скопіюй команду разом із маркером."
        else -> CompanionProtocol.captureDiagnosticText(text)
    }
}
