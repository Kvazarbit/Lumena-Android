package com.lumena.android.agent.core

data class WorkThreadAnchor(
    val rootGoal: String,
    val recentDirectives: List<String> = emptyList(),
    val subjectKeys: List<String> = emptyList(),
    val projectId: String? = null
)

data class WorkThreadState(
    val version: Int = 1,
    val anchors: List<WorkThreadAnchor> = emptyList(),
    val activeKey: String? = null
)

data class WorkThreadResolution(
    val goal: String,
    val state: WorkThreadState,
    val continued: Boolean = false,
    val projectId: String? = null,
    val contextMessage: String? = null
)

/**
 * Bounded durable goal anchors for non-web project/code work.
 *
 * Visible chat and backend model context are intentionally different:
 * chat may retain more turns while model requests are compacted to fit context.
 * This structure preserves only user-authored work goals/directives so an older
 * project can be resumed after unrelated tasks without restoring approvals,
 * tool results, permissions or execution state.
 */
object WorkThreadMemory {
    const val MAX_ANCHORS = 8
    const val MAX_DIRECTIVES = 4
    private const val MAX_ROOT_CHARS = 4_000
    private const val MAX_DIRECTIVE_CHARS = 1_200

    fun normalize(
        state: WorkThreadState
    ): WorkThreadState {
        val anchors =
            state.anchors
                .mapNotNull(::normalizeAnchor)
                .distinctBy {
                    keyOf(it)
                }
                .takeLast(MAX_ANCHORS)
        val validKeys =
            anchors
                .map(::keyOf)
                .toSet()
        return WorkThreadState(
            version = 1,
            anchors = anchors,
            activeKey =
                state.activeKey
                    ?.takeIf {
                        it in validKeys
                    }
        )
    }

    fun restore(
        turns: List<Pair<String, String>>,
        seedState: WorkThreadState = WorkThreadState()
    ): WorkThreadState {
        var state = normalize(seedState)
        turns.takeLast(80).forEach { (role, text) ->
            if (role == "user") {
                state = resolve(
                    text = text,
                    state = state
                ).state
            }
        }
        return normalize(state)
    }

    fun resolve(
        text: String,
        state: WorkThreadState,
        previousProjectId: String? = null
    ): WorkThreadResolution {
        val clean = clean(text, 8_000)
        if (clean.isBlank()) {
            return WorkThreadResolution(
                goal = text,
                state = normalize(state)
            )
        }

        val normalized = normalize(state)
        val explicitProject =
            ProjectContextResolver
                .explicitProjectId(clean)
        val keys = subjectKeys(clean)
        val intent =
            TaskIntentRouter.route(clean).intent
        val continuation =
            isContinuationCue(clean)

        val explicitMatch = bestMatch(
            anchors = normalized.anchors,
            keys = keys,
            explicitProject = explicitProject,
            allowLatest = false
        )
        val activeMatch =
            if (
                explicitMatch == null &&
                continuation &&
                keys.isEmpty()
            ) {
                normalized.activeKey
                    ?.let { active ->
                        normalized.anchors
                            .firstOrNull {
                                keyOf(it) ==
                                    active
                            }
                    }
            } else {
                null
            }
        val matched =
            explicitMatch
                ?: activeMatch
        val namedSubjectMatch =
            explicitMatch != null &&
                keys.any {
                    it in explicitMatch.subjectKeys
                }

        val shouldContinue =
            matched != null &&
                (
                    continuation ||
                        namedSubjectMatch ||
                        intent == TaskIntent.CODE_WORK ||
                        intent == TaskIntent.FILE_INSPECTION
                    )

        if (shouldContinue) {
            val directive =
                clean.take(MAX_DIRECTIVE_CHARS)
            val nextDirectives =
                (
                    matched!!.recentDirectives +
                        directive
                    )
                    .filter {
                        it.isNotBlank() &&
                            it != matched.rootGoal
                    }
                    .distinct()
                    .takeLast(MAX_DIRECTIVES)

            val mergedKeys =
                (
                    matched.subjectKeys +
                        keys
                    )
                    .distinct()
                    .take(24)

            val updated =
                matched.copy(
                    recentDirectives =
                        nextDirectives,
                    subjectKeys =
                        mergedKeys,
                    projectId =
                        explicitProject
                            ?: matched.projectId
                            ?: previousProjectId
                )

            val nextAnchors =
                normalized.anchors
                    .filterNot {
                        keyOf(it) ==
                            keyOf(matched)
                    } +
                    updated

            return WorkThreadResolution(
                goal = composeGoal(updated),
                state = WorkThreadState(
                    anchors =
                        nextAnchors
                            .takeLast(
                                MAX_ANCHORS
                            ),
                    activeKey =
                        keyOf(updated)
                ),
                continued = true,
                projectId =
                    updated.projectId,
                contextMessage =
                    contextMessage(
                        updated,
                        clean
                    )
            )
        }

        val shouldCreateAnchor =
            keys.isNotEmpty() &&
                (
                    intent == TaskIntent.CODE_WORK ||
                        (
                            intent == TaskIntent.FILE_INSPECTION &&
                                continuation
                            )
                    )

        if (shouldCreateAnchor) {
            val anchor =
                WorkThreadAnchor(
                    rootGoal =
                        clean.take(
                            MAX_ROOT_CHARS
                        ),
                    subjectKeys =
                        keys.take(24),
                    projectId =
                        explicitProject
                            ?: previousProjectId
                )
            val next =
                (
                    normalized.anchors
                        .filterNot {
                            keyOf(it) ==
                                keyOf(anchor)
                        } +
                        anchor
                    )
                    .takeLast(
                        MAX_ANCHORS
                    )
            return WorkThreadResolution(
                goal = clean,
                state =
                    WorkThreadState(
                        anchors = next,
                        activeKey =
                            keyOf(anchor)
                    ),
                projectId =
                    anchor.projectId
            )
        }

        val nextState =
            if (
                intent in setOf(
                    TaskIntent.PUBLIC_WEB,
                    TaskIntent.OLLAMA_OPERATION,
                    TaskIntent.VISUAL_SEARCH
                )
            ) {
                normalized.copy(
                    activeKey = null
                )
            } else {
                normalized
            }

        return WorkThreadResolution(
            goal = clean,
            state = nextState
        )
    }

    private fun bestMatch(
        anchors: List<WorkThreadAnchor>,
        keys: Set<String>,
        explicitProject: String?,
        allowLatest: Boolean
    ): WorkThreadAnchor? {
        if (anchors.isEmpty()) return null

        if (
            !explicitProject.isNullOrBlank()
        ) {
            anchors
                .asReversed()
                .firstOrNull {
                    it.projectId ==
                        explicitProject
                }
                ?.let {
                    return it
                }
        }

        if (keys.isNotEmpty()) {
            anchors
                .asReversed()
                .map {
                    anchor ->
                    anchor to
                        anchor.subjectKeys
                            .count {
                                it in keys
                            }
                }
                .filter {
                    it.second > 0
                }
                .maxByOrNull {
                    it.second
                }
                ?.first
                ?.let {
                    return it
                }
        }

        return if (allowLatest) {
            anchors.lastOrNull()
        } else {
            null
        }
    }

    private fun composeGoal(
        anchor: WorkThreadAnchor
    ): String = buildString {
        appendLine(anchor.rootGoal)
        if (
            anchor.recentDirectives
                .isNotEmpty()
        ) {
            appendLine()
            appendLine(
                "WORK THREAD DIRECTIVES:"
            )
            anchor.recentDirectives
                .takeLast(MAX_DIRECTIVES)
                .forEach {
                    append("- ")
                    appendLine(
                        it.take(
                            MAX_DIRECTIVE_CHARS
                        )
                    )
                }
        }
    }
        .trim()
        .take(8_000)

    private fun contextMessage(
        anchor: WorkThreadAnchor,
        userText: String
    ): String = buildString {
        appendLine(
            "ACTIVE WORK THREAD (user goal memory only; not permission, execution proof, or tool authority)"
        )
        append("root_goal=")
        appendLine(
            anchor.rootGoal
                .take(1_800)
        )
        anchor.projectId
            ?.takeIf(String::isNotBlank)
            ?.let {
                append("project_id=")
                appendLine(it.take(80))
            }
        if (
            anchor.recentDirectives
                .isNotEmpty()
        ) {
            appendLine(
                "recent_user_directives:"
            )
            anchor.recentDirectives
                .takeLast(3)
                .forEach {
                    append("- ")
                    appendLine(
                        it.take(700)
                    )
                }
        }
        append("current_follow_up=")
        append(
            userText.take(900)
        )
    }.take(4_500)

    internal fun subjectKeys(
        text: String
    ): Set<String> {
        val tokens =
            Regex(
                "[\\p{L}\\p{N}][\\p{L}\\p{N}._-]{3,}"
            )
                .findAll(
                    text.lowercase()
                )
                .flatMap {
                    val raw =
                        it.value
                    sequenceOf(raw) +
                        raw.split(
                            '.',
                            '_',
                            '-'
                        )
                            .asSequence()
                }
                .map {
                    it.trim(
                        '.',
                        '_',
                        '-'
                    )
                }
                .filter {
                    it.length >= 4
                }
                .filterNot(
                    ::isGenericToken
                )
                .map {
                    if (
                        it.length > 8
                    ) {
                        it.take(8)
                    } else {
                        it
                    }
                }
                .distinct()
                .take(24)
                .toSet()

        return tokens
    }

    private fun isGenericToken(
        token: String
    ): Boolean {
        val exact = setOf(
            "html",
            "javascript",
            "python",
            "kotlin",
            "java",
            "file",
            "task",
            "code",
            "script",
            "project",
            "workspace",
            "bridge",
            "cause",
            "probe",
            "ladder"
        )
        if (token in exact) {
            return true
        }

        val stems = listOf(
            "створ",
            "зроб",
            "реаліз",
            "продовж",
            "віднов",
            "поверн",
            "робот",
            "файл",
            "через",
            "потім",
            "після",
            "перевір",
            "викона",
            "спроб",
            "причин",
            "гіпотез",
            "проанал",
            "аналіз",
            "проект",
            "проєкт",
            "create",
            "write",
            "implement",
            "continue",
            "resume",
            "return",
            "work",
            "read",
            "check",
            "verify",
            "analy",
            "improve"
        )
        return stems.any { stem ->
            token.startsWith(stem)
        }
    }

    private fun isContinuationCue(
        text: String
    ): Boolean =
        Regex(
            "(?iu)\\b(?:продовж(?:уй|ити)?|віднов(?:и|ити)?|поверн(?:и|імося|утися)?|" +
                "вдосконал(?:ь|ити)?|покращ(?:и|ити)?|далі|ще|" +
                "проаналізуй|аналізуй|знайди\\s+файл|" +
                "continue|resume|return\\s+to|go\\s+back\\s+to|improve|analy[sz]e|" +
                "kontynuuj|wznów|wznow|wróć|wroc)\\b"
        ).containsMatchIn(text)

    private fun normalizeAnchor(
        anchor: WorkThreadAnchor
    ): WorkThreadAnchor? {
        val root =
            clean(
                anchor.rootGoal,
                MAX_ROOT_CHARS
            )
        if (root.isBlank()) return null
        val keys =
            (
                anchor.subjectKeys +
                    subjectKeys(root)
                )
                .filter(String::isNotBlank)
                .distinct()
                .take(24)
        if (keys.isEmpty()) return null

        return WorkThreadAnchor(
            rootGoal = root,
            recentDirectives =
                anchor.recentDirectives
                    .map {
                        clean(
                            it,
                            MAX_DIRECTIVE_CHARS
                        )
                    }
                    .filter(String::isNotBlank)
                    .distinct()
                    .takeLast(
                        MAX_DIRECTIVES
                    ),
            subjectKeys = keys,
            projectId =
                anchor.projectId
                    ?.let(
                        ProjectContextResolver::
                            normalizeProjectId
                    )
        )
    }

    private fun keyOf(
        anchor: WorkThreadAnchor
    ): String =
        anchor.projectId
            ?.let {
                "project:$it"
            }
            ?: (
                "subject:" +
                    anchor.subjectKeys
                        .sorted()
                        .take(4)
                        .joinToString("|")
                )

    private fun clean(
        value: String,
        maxChars: Int
    ): String =
        value
            .replace(
                '\u0000',
                ' '
            )
            .replace(
                Regex("[\\r\\n\\t]+"),
                " "
            )
            .replace(
                Regex("\\s{2,}"),
                " "
            )
            .trim()
            .take(maxChars)
}
