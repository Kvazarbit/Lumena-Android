package com.lumena.android.ollama

/**
 * Builds the model history for a new user-authored execution epoch.
 *
 * Durable project/work-thread memory is injected separately by the application.
 * Internal execution traffic from an older task (TOOL_RESULT, protocol repair,
 * partial outcome capsules, budgets, pending actions) must never become active
 * context for a fresh TaskState.
 */
object ExecutionEpochHistory {
    private const val MAX_VISIBLE_TURNS = 80

    fun rebuild(
        systemMessage: OllamaMessage,
        visibleTurns: List<Pair<String, String>>,
        historicalContext: String? = null
    ): List<OllamaMessage> {
        val conversation =
            visibleTurns
                .takeLast(MAX_VISIBLE_TURNS)
                .mapNotNull { (role, raw) ->
                    val text = raw.trim()
                    if (text.isBlank()) {
                        return@mapNotNull null
                    }

                    when (role) {
                        "user" -> {
                            if (isTransportOnlyTurn(text)) {
                                null
                            } else {
                                OllamaMessage(
                                    "user",
                                    text
                                )
                            }
                        }

                        "assistant" ->
                            OllamaMessage(
                                "assistant",
                                text
                            )

                        "error" ->
                            OllamaMessage(
                                "assistant",
                                "Previous visible task error: " +
                                    text.take(4_000)
                            )

                        else -> null
                    }
                }

        val historical =
            historicalContext
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?.let {
                    listOf(
                        OllamaMessage(
                            "system",
                            it
                        )
                    )
                }
                .orEmpty()

        return listOf(systemMessage) +
            historical +
            conversation
    }

    internal fun isTransportOnlyTurn(
        text: String
    ): Boolean {
        val trimmed =
            text.trimStart()
        if (
            trimmed.startsWith(
                "LUMENA_TOOL"
            )
        ) {
            return true
        }

        return Regex(
            "(?is)^```(?:text)?\\s*\\n?LUMENA_TOOL\\b"
        ).containsMatchIn(
            trimmed
        )
    }
}
