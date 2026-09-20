package com.lumena.android.llama

data class ChatContextFit(
    val roles: Array<String>,
    val contents: Array<String>,
    val prompt: String,
    val promptTokens: Int,
    val droppedMessages: Int,
    val clippedSystem: Boolean,
    val clippedLatestUser: Boolean,
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
        var clippedSystem = false
        var clippedLatestUser = false

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

        fun clipMessageToFit(index: Int, minimumChars: Int): Boolean {
            if (index !in mutableContents.indices) return false
            val original = mutableContents[index]
            if (original.length <= minimumChars) return false

            var low = minimumChars.coerceAtMost(original.length)
            var high = original.length
            var best: String? = null
            var bestRendered: Pair<String, Int>? = null

            while (low <= high) {
                val mid = low + (high - low) / 2
                mutableContents[index] = clipMiddle(original, mid)
                val candidate = render()
                if (candidate.second in 1..maxPromptTokens) {
                    best = mutableContents[index]
                    bestRendered = candidate
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }

            if (best != null && bestRendered != null) {
                mutableContents[index] = best
                rendered = bestRendered
                return true
            }

            mutableContents[index] = clipMiddle(original, minimumChars)
            rendered = render()
            return rendered.second in 1..maxPromptTokens
        }

        if (rendered.second > maxPromptTokens) {
            val systemIndex = mutableRoles.indexOfFirst { it == "system" }
            if (systemIndex >= 0) {
                val before = mutableContents[systemIndex]
                val fit = clipMessageToFit(systemIndex, minimumChars = 256)
                clippedSystem = mutableContents[systemIndex] != before
                if (fit) {
                    return ChatContextFit(
                        roles = mutableRoles.toTypedArray(),
                        contents = mutableContents.toTypedArray(),
                        prompt = rendered.first,
                        promptTokens = rendered.second,
                        droppedMessages = dropped,
                        clippedSystem = clippedSystem,
                        clippedLatestUser = false,
                        fits = true
                    )
                }
            }
        }

        if (rendered.second > maxPromptTokens) {
            val latestUser = mutableRoles.indexOfLast { it == "user" }
            if (latestUser >= 0) {
                val before = mutableContents[latestUser]
                clipMessageToFit(latestUser, minimumChars = 256)
                clippedLatestUser = mutableContents[latestUser] != before
            }
        }

        return ChatContextFit(
            roles = mutableRoles.toTypedArray(),
            contents = mutableContents.toTypedArray(),
            prompt = rendered.first,
            promptTokens = rendered.second,
            droppedMessages = dropped,
            clippedSystem = clippedSystem,
            clippedLatestUser = clippedLatestUser,
            fits = rendered.second in 1..maxPromptTokens
        )
    }

    private fun clipMiddle(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val marker = "\n...[middle context omitted]...\n"
        if (limit <= marker.length + 8) return text.take(limit)

        val available = limit - marker.length
        val head = (available * 2) / 3
        val tail = available - head
        return text.take(head) + marker + text.takeLast(tail)
    }
}
