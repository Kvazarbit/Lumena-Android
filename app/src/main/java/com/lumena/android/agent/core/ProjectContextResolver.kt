package com.lumena.android.agent.core

/**
 * Resolves an explicit project scope for a task without guessing from arbitrary
 * paths or prose.
 *
 * A new task gets a project only when the user names one explicitly. A clearly
 * related follow-up may carry the previous verified project id forward.
 */
object ProjectContextResolver {
    private const val MAX_PROJECT_ID_CHARS = 80

    private val explicitProject = Regex(
        """(?iu)\b(?:project|projekt|projekcie|projektu|проєкт|проєкті|проєкту|проект|проекті|проекте|проекту)\b\s*(?:[:=]\s*)?[\`"'']?([A-Za-z0-9][A-Za-z0-9._-]{0,79})[\`"'']?(?=$|[\s.,;:!?\)\]\}])"""
    )

    fun resolve(
        text: String,
        previousProjectId: String?,
        carryForward: Boolean
    ): String? {
        explicitProjectId(text)?.let { return it }
        if (!carryForward) return null
        return normalizeProjectId(previousProjectId.orEmpty())
    }

    internal fun explicitProjectId(text: String): String? =
        explicitProject
            .find(text.take(8_000))
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::normalizeProjectId)

    internal fun normalizeProjectId(raw: String): String? {
        val value = raw.trim()
        if (
            value.isBlank() ||
            value.length > MAX_PROJECT_ID_CHARS ||
            value == "." ||
            value == ".." ||
            '/' in value ||
            '\\' in value
        ) {
            return null
        }

        if (
            !Regex("""[A-Za-z0-9][A-Za-z0-9._-]{0,79}""")
                .matches(value)
        ) {
            return null
        }

        return value
    }
}
