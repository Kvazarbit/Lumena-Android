package com.lumena.android.agent.core

data class CodeGoalResolution(val text: String, val anchor: String?, val continued: Boolean = false)

/** Goal text only. Never carries execution state, approvals or tool receipts. */
object CodeTaskAnchor {
    private val confirmations = setOf("так", "да", "yes", "ok", "okay", "добре", "так зроби", "tak")
    private val stack = Regex("(?i)^(?:html|css|js|javascript|typescript|python|kotlin|java|webgl)(?:\\s*[+/,&]\\s*(?:html|css|js|javascript|typescript|python|kotlin|java|webgl))*$")
    private val implementationContinuation = Regex(
        "(?iu)^(?:реалізуй(?:\\s+(?:це|його|її))?|зроби(?:\\s+це)?|виконай(?:\\s+це)?|" +
            "продовж(?:уй)?\\s+реалізацію|реализуй(?:\\s+это)?|сделай(?:\\s+это)?|" +
            "выполни(?:\\s+это)?|implement(?:\\s+it)?|do\\s+it|execute(?:\\s+it)?|" +
            "continue\\s+(?:the\\s+)?implementation|zaimplementuj(?:\\s+to)?|" +
            "zrób(?:\\s+to)?|wykonaj(?:\\s+to)?)$"
    )
    private val meta = Regex("(?iu)^(?:чому|почему|why|dlaczego|що\\s+(?:це|сталося|не\\s+так)|what\\s+happened)\\b")
    private val offer = Regex("(?iu)(?:створ|напис|реаліз|созда|напис|create|write|implement|utworz|napisz)")
    private val code = Regex("(?iu)(?:код|скрипт|code|script|html|javascript|python|kotlin|програм)")

    fun restore(turns: List<Pair<String, String>>): String? {
        var anchor: String? = null
        var assistant: String? = null
        turns.forEach { (role, text) ->
            if (role == "assistant") assistant = text
            if (role == "user") anchor = resolve(text, anchor, assistant).anchor
        }
        return anchor
    }

    fun resolve(text: String, anchor: String?, lastAssistant: String?): CodeGoalResolution {
        val value = text.trim()
        val normalized = value.lowercase().trimEnd('.', '!', '?', '…').trim()
        val confirmsOffer = normalized in confirmations && lastAssistant != null &&
            lastAssistant.contains('?') && offer.containsMatchIn(lastAssistant) && code.containsMatchIn(lastAssistant)
        val continuesImplementation =
            !anchor.isNullOrBlank() &&
                implementationContinuation.matches(normalized)
        if (!anchor.isNullOrBlank() && (stack.matches(normalized) || confirmsOffer || continuesImplementation)) {
            val goal = when {
                confirmsOffer || continuesImplementation -> anchor
                else -> anchor.take(7_500) + "\nUser clarification: " + value.take(500)
            }
            return CodeGoalResolution(goal, goal, continued = true)
        }
        if (meta.containsMatchIn(value)) return CodeGoalResolution(value, anchor)
        return when (TaskIntentRouter.route(value).intent) {
            TaskIntent.CODE_WORK -> CodeGoalResolution(value, value.take(8_000))
            TaskIntent.GENERAL -> CodeGoalResolution(value,
                anchor.takeIf { FollowUpGoal.isReference(value) || normalized in confirmations })
            else -> CodeGoalResolution(value, null)
        }
    }
}
