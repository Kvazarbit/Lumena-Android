package com.lumena.android.llama

data class ChatContextFit(
    val roles: Array<String>,
    val contents: Array<String>,
    val prompt: String,
    val promptTokens: Int,
    val droppedMessages: Int,
    val fits: Boolean
)

object ChatContextPolicy {
    fun fit(
        roles: Array<String>,
        contents: Array<String>,
        maxPromptTokens: Int,
        formatter: (Array<String>, Array<String>) -> String,
        tokenCounter: (String) -> Int
    ): ChatContextFit {
        require(roles.size == contents.size) { "roles/contents length mismatch" }
        require(maxPromptTokens > 0) { "maxPromptTokens must be positive" }

        val mutableRoles = roles.toMutableList()
        val mutableContents = contents.toMutableList()
        var dropped = 0

        fun render(): Pair<String, Int> {
            val prompt = formatter(
                mutableRoles.toTypedArray(),
                mutableContents.toTypedArray()
            )
            return prompt to tokenCounter(prompt)
        }

        var rendered = render()
        while (rendered.second > maxPromptTokens) {
            val lastUser = mutableRoles.indexOfLast { it == "user" }
            val removable = mutableRoles.indices.firstOrNull { index ->
                mutableRoles[index] != "system" && index < lastUser
            } ?: break

            val removedRole = mutableRoles.removeAt(removable)
            mutableContents.removeAt(removable)
            dropped++

            val newLastUser = mutableRoles.indexOfLast { it == "user" }
            if (
                removedRole == "user" &&
                removable < mutableRoles.size &&
                mutableRoles[removable] == "assistant" &&
                removable < newLastUser
            ) {
                mutableRoles.removeAt(removable)
                mutableContents.removeAt(removable)
                dropped++
            }

            rendered = render()
        }

        return ChatContextFit(
            roles = mutableRoles.toTypedArray(),
            contents = mutableContents.toTypedArray(),
            prompt = rendered.first,
            promptTokens = rendered.second,
            droppedMessages = dropped,
            fits = rendered.second in 1..maxPromptTokens
        )
    }
}
