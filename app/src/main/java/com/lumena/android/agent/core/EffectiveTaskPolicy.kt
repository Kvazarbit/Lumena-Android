package com.lumena.android.agent.core

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.security.MessageDigest

enum class TaskPolicyDisposition {
    READY,
    NEEDS_CLARIFICATION
}

data class PolicyUnresolvedEffect(
    val sourceTaskRef: String,
    val tool: String,
    val requestSignature: String,
    val targetRef: String,
    val origin: HistoricalRecordOrigin
)

data class EffectiveTaskPolicy(
    val version: Int = 1,
    val rootGoal: String = "",
    val currentInstruction: String = "",
    val allowedRisks: List<ToolRisk> = listOf(
        ToolRisk.READ_ONLY,
        ToolRisk.MUTATING,
        ToolRisk.EXECUTABLE
    ),
    val forbiddenTools: List<String> = emptyList(),
    val requiredTools: List<String> = emptyList(),
    val scopeRef: String? = null,
    val unresolvedEffects: List<PolicyUnresolvedEffect> = emptyList(),
    val disposition: TaskPolicyDisposition =
        TaskPolicyDisposition.READY,
    val clarificationReason: String? = null
)

data class TaskPolicyToolDecision(
    val allowed: Boolean,
    val reason: String? = null
)

object EffectiveTaskPolicyCompiler {
    private val json =
        Moshi.Builder()
            .build()
            .adapter<List<Map<String, Any?>>>(
                Types.newParameterizedType(
                    List::class.java,
                    Types.newParameterizedType(
                        Map::class.java,
                        String::class.java,
                        Any::class.java
                    )
                )
            )

    private val readOnlyCues = listOf(
        Regex("(?iu)\\b(?:тільки|лише)\\s+(?:знайди\\s+і\\s+)?(?:прочитай|переглянь|проаналізуй|перевір)\\b"),
        Regex("(?iu)\\bнічого\\s+не\\s+(?:змінюй|редагуй)\\b"),
        Regex("(?iu)\\b(?:не\\s+змінюй|без\\s+змін)(?:\\s+зараз)?\\b"),
        Regex("(?iu)\\b(?:только|лишь)\\s+(?:прочитай|посмотри|проанализируй|проверь)\\b"),
        Regex("(?iu)\\bничего\\s+не\\s+(?:изменяй|редактируй)\\b"),
        Regex("(?iu)\\b(?:не\\s+изменяй|без\\s+изменений)(?:\\s+сейчас)?\\b"),
        Regex("(?iu)\\b(?:only\\s+(?:read|inspect|analyse|analyze|review)|read[- ]only)\\b"),
        Regex("(?iu)\\b(?:do\\s+not|don't|never)\\s+(?:modify|edit|write|patch)\\b"),
        Regex("(?iu)\\bno\\s+changes?(?:\\s+now)?\\b"),
        Regex("(?iu)\\b(?:tylko|jedynie)\\s+(?:przeczytaj|sprawdź|sprawdz|przeanalizuj)\\b"),
        Regex("(?iu)\\b(?:nie\\s+zmieniaj|nie\\s+modyfikuj|bez\\s+zmian)(?:\\s+teraz)?\\b")
    )

    private val noExecuteCues = listOf(
        Regex("(?iu)\\b(?:не\\s+запускай|не\\s+виконуй)\\b"),
        Regex("(?iu)\\b(?:не\\s+запускай|не\\s+выполняй)\\b"),
        Regex("(?iu)\\b(?:do\\s+not|don't|never)\\s+(?:run|execute)\\b"),
        Regex("(?iu)\\b(?:nie\\s+uruchamiaj|nie\\s+wykonuj)\\b")
    )

    private val mutationPositive = listOf(
        Regex("(?iu)\\b(?:зміни|змінити|виправ|виправи|редагуй|відредагуй|створи|створити|додай|онови|реалізуй|реалізувати)\\b"),
        Regex("(?iu)\\b(?:измени|изменить|исправь|редактируй|создай|добавь|обнови|реализуй)\\b"),
        Regex("(?iu)\\b(?:modify|edit|write|patch|create|fix|update|implement)\\b"),
        Regex("(?iu)\\b(?:zmień|zmien|modyfikuj|edytuj|utwórz|utworz|dodaj|napraw|zaktualizuj)\\b")
    )

    private val executionPositive = listOf(
        Regex("(?iu)\\b(?:запусти|запустити|виконай|виконати|прожени|прогнати)\\b"),
        Regex("(?iu)\\b(?:запусти|запустить|выполни|выполнить)\\b"),
        Regex("(?iu)\\b(?:run|execute|rerun|build|compile)\\b"),
        Regex("(?iu)\\b(?:uruchom|wykonaj|zbuduj|skompiluj)\\b")
    )

    private val sequenceCues =
        Regex(
            "(?iu)\\b(?:потім|після|тоді|згодом|then|after|next|потом|после|następnie|nastepnie|potem|po\\s+tym)\\b"
        )

    fun compile(
        rootGoal: String,
        currentInstruction: String,
        unresolvedEffects: List<PolicyUnresolvedEffect> = emptyList(),
        scopeRef: String? = null
    ): EffectiveTaskPolicy {
        val root = clean(rootGoal, 8_000)
        val current =
            clean(
                currentInstruction
                    .ifBlank { rootGoal },
                4_000
            )
        val maskedCurrent =
            maskQuoted(current)
        val maskedRoot =
            maskQuoted(root)

        val currentToolDirectives =
            toolDirectives(maskedCurrent)
        val rootToolDirectives =
            toolDirectives(maskedRoot)
        val effectText =
            maskToolNames(
                maskedCurrent
            )

        val readOnlyMatches =
            findPositions(
                effectText,
                readOnlyCues
            )
        val noExecuteMatches =
            findPositions(
                maskedCurrent,
                noExecuteCues
            )
                .filterNot {
                    explicitToolFollows(
                        maskedCurrent,
                        it
                    )
                }
        val mutationMatches =
            (
                findPositions(
                    effectText,
                    mutationPositive
                ) +
                    currentToolDirectives
                        .filter {
                            !it.forbidden &&
                                ToolRegistry.get(
                                    it.tool
                                )?.risk ==
                                ToolRisk.MUTATING
                        }
                        .map { it.start }
                )
        val executionMatches =
            (
                findPositions(
                    effectText,
                    executionPositive
                ) +
                    currentToolDirectives
                        .filter {
                            !it.forbidden &&
                                ToolRegistry.get(
                                    it.tool
                                )?.risk ==
                                ToolRisk.EXECUTABLE
                        }
                        .map { it.start }
                )

        val lastReadOnly = readOnlyMatches.maxOrNull()
        val lastMutation = mutationMatches.maxOrNull()
        val lastNoExecute = noExecuteMatches.maxOrNull()
        val lastExecution = executionMatches.maxOrNull()

        val mutationAllowed =
            when {
                lastReadOnly == null -> true
                lastMutation == null -> false
                else -> lastMutation > lastReadOnly
            }
        val executionAllowed =
            when {
                lastReadOnly != null &&
                    (
                        lastExecution == null ||
                            lastExecution <= lastReadOnly
                        ) -> false
                lastNoExecute == null -> true
                lastExecution == null -> false
                else -> lastExecution > lastNoExecute
            }

        val effectiveDirectives =
            linkedMapOf<String, ToolDirective>()
        rootToolDirectives.forEach {
            effectiveDirectives[it.tool] = it
        }
        currentToolDirectives.forEach {
            effectiveDirectives[it.tool] = it
        }

        val allowedRisks =
            buildList {
                add(ToolRisk.READ_ONLY)
                if (mutationAllowed) {
                    add(ToolRisk.MUTATING)
                }
                if (executionAllowed) {
                    add(ToolRisk.EXECUTABLE)
                }
            }

        val forbiddenTools =
            effectiveDirectives
                .values
                .filter { it.forbidden }
                .map { it.tool }
                .filter { ToolRegistry.get(it) != null }
                .distinct()
                .sorted()

        val requiredTools =
            effectiveDirectives
                .values
                .filterNot { it.forbidden }
                .map { it.tool }
                .filter { it !in forbiddenTools }
                .filter { tool ->
                    ToolRegistry.get(tool)?.risk in allowedRisks
                }
                .distinct()
                .sorted()

        val conflictingMutation =
            readOnlyMatches.isNotEmpty() &&
                mutationMatches.isNotEmpty() &&
                !sequenceCues.containsMatchIn(maskedCurrent) &&
                sameClauseConflict(
                    maskedCurrent,
                    readOnlyMatches,
                    mutationMatches
                )

        val disposition =
            if (conflictingMutation) {
                TaskPolicyDisposition.NEEDS_CLARIFICATION
            } else {
                TaskPolicyDisposition.READY
            }

        return EffectiveTaskPolicy(
            rootGoal = root,
            currentInstruction = current,
            allowedRisks = allowedRisks,
            forbiddenTools = forbiddenTools,
            requiredTools = requiredTools,
            scopeRef = scopeRef?.take(160),
            unresolvedEffects =
                unresolvedEffects
                    .filter(::validUnresolved)
                    .distinctBy {
                        it.sourceTaskRef + "|" +
                            it.requestSignature
                    }
                    .takeLast(8),
            disposition = disposition,
            clarificationReason =
                if (
                    disposition ==
                    TaskPolicyDisposition.NEEDS_CLARIFICATION
                ) {
                    "Current instruction contains both a mutation request and a read-only/no-change constraint without an explicit sequence."
                } else {
                    null
                }
        )
    }

    fun routingText(
        policy: EffectiveTaskPolicy
    ): String =
        sanitizeForRouting(
            policy.currentInstruction
                .ifBlank { policy.rootGoal }
        )

    fun sanitizeForRouting(
        text: String
    ): String {
        val masked = maskQuoted(text)
        val forbiddenTools =
            toolDirectives(masked)
                .groupBy {
                    it.tool
                }
                .mapNotNull {
                    (tool, directives) ->
                    directives
                        .maxByOrNull {
                            it.order
                        }
                        ?.takeIf {
                            it.forbidden
                        }
                        ?.let {
                            tool
                        }
                }
                .toSet()
        if (forbiddenTools.isEmpty()) {
            return clean(text, 8_000)
        }

        var out = text
        forbiddenTools.forEach { tool ->
            out = out.replace(
                Regex(
                    "(?iu)(?<![a-z0-9_])" +
                        Regex.escape(tool) +
                        "(?![a-z0-9_])"
                ),
                " "
            )
        }
        return clean(out, 8_000)
    }

    fun validateTool(
        policy: EffectiveTaskPolicy,
        call: AgentDecision.ToolCall
    ): TaskPolicyToolDecision {
        if (
            policy.disposition ==
            TaskPolicyDisposition.NEEDS_CLARIFICATION
        ) {
            return TaskPolicyToolDecision(
                allowed = false,
                reason =
                    "TASK_POLICY_NEEDS_CLARIFICATION: " +
                        policy.clarificationReason.orEmpty()
            )
        }

        val canonical =
            ToolRegistry.canonicalize(call.tool)
        val spec =
            ToolRegistry.get(canonical)
                ?: return TaskPolicyToolDecision(
                    false,
                    "TASK_POLICY_UNKNOWN_TOOL"
                )

        if (canonical in policy.forbiddenTools) {
            return TaskPolicyToolDecision(
                false,
                "TASK_POLICY_FORBIDDEN_TOOL: " + canonical
            )
        }

        if (spec.risk !in policy.allowedRisks) {
            return TaskPolicyToolDecision(
                false,
                "TASK_POLICY_EFFECT_NOT_ALLOWED: " + spec.risk
            )
        }

        unresolvedBlocker(policy, call)?.let {
            return TaskPolicyToolDecision(false, it)
        }

        if (canonical == "inspect.batch") {
            val raw = call.args["requests"].orEmpty()
            val requests =
                runCatching {
                    json.fromJson(raw)
                }.getOrNull()
                    ?: return TaskPolicyToolDecision(
                        false,
                        "TASK_POLICY_BATCH_UNREADABLE"
                    )
            for (child in requests) {
                val tool =
                    child["tool"] as? String
                        ?: return TaskPolicyToolDecision(
                            false,
                            "TASK_POLICY_BATCH_TOOL_MISSING"
                        )
                val args =
                    (child["args"] as? Map<*, *>)
                        ?.entries
                        ?.associate {
                            it.key.toString() to
                                it.value?.toString().orEmpty()
                        }
                        .orEmpty()
                val childDecision =
                    validateTool(
                        policy,
                        AgentDecision.ToolCall(
                            tool = tool,
                            args = args
                        )
                    )
                if (!childDecision.allowed) {
                    return TaskPolicyToolDecision(
                        false,
                        "TASK_POLICY_BATCH_CHILD_BLOCKED: " +
                            childDecision.reason.orEmpty()
                    )
                }
            }
        }

        return TaskPolicyToolDecision(true)
    }

    fun afterTool(
        policy: EffectiveTaskPolicy,
        call: AgentDecision.ToolCall,
        ok: Boolean
    ): EffectiveTaskPolicy {
        if (
            !ok ||
            ToolRegistry.canonicalize(
                call.tool
            ) != "file.read"
        ) {
            return policy
        }

        val targetRef =
            sha256(
                ContextKernel.target(
                    call
                )
            )
        val remaining =
            policy.unresolvedEffects
                .filterNot {
                    it.origin ==
                        HistoricalRecordOrigin
                            .LOCAL_CURRENT &&
                        it.tool in
                            setOf(
                                "file.write",
                                "file.patch"
                            ) &&
                        it.targetRef ==
                            targetRef
                }
        return if (
            remaining.size ==
            policy.unresolvedEffects.size
        ) {
            policy
        } else {
            policy.copy(
                unresolvedEffects =
                    remaining
            )
        }
    }

    fun render(
        policy: EffectiveTaskPolicy
    ): String = buildString {
        appendLine("EFFECTIVE TASK POLICY V1")
        appendLine(
            "Current user instruction controls this execution epoch; long goal is context, not automatic authority."
        )
        appendLine(
            "allowed_effects=" +
                policy.allowedRisks.joinToString(",")
        )
        appendLine(
            "forbidden_tools=" +
                policy.forbiddenTools
                    .joinToString(",")
                    .ifBlank { "(none)" }
        )
        appendLine(
            "required_tools=" +
                policy.requiredTools
                    .joinToString(",")
                    .ifBlank { "(none)" }
        )
        appendLine(
            "disposition=" + policy.disposition
        )
        if (policy.unresolvedEffects.isNotEmpty()) {
            appendLine(
                "unresolved_effects=" +
                    policy.unresolvedEffects.size
            )
            appendLine(
                "Unresolved prior effects are reconciliation constraints only; they grant no permission."
            )
        }
        appendLine(
            "current_instruction=" +
                GoalContext.clip(
                    policy.currentInstruction,
                    420
                )
        )
    }.take(1_800)

    private data class ToolDirective(
        val tool: String,
        val forbidden: Boolean,
        val start: Int,
        val order: Int
    )

    private fun toolDirectives(
        masked: String
    ): List<ToolDirective> {
        val names =
            ToolRegistry.all()
                .map { it.name }
                .sortedByDescending { it.length }
        if (names.isEmpty()) return emptyList()

        val alternation =
            names.joinToString("|") {
                Regex.escape(it)
            }
        val toolRegex =
            Regex(
                "(?iu)(?<![a-z0-9_])(?:$alternation)(?![a-z0-9_])"
            )
        val negativeCue =
            Regex(
                "(?iu)(?:не\\s+(?:використовуй|використовуйте|запускай|запускайте|виконуй|виконуйте|роби|робіть)|" +
                    "не\\s+(?:используй|используйте|запускай|запускайте|выполняй|выполняйте)|" +
                    "do\\s+not\\s+(?:use|run|execute)|don't\\s+(?:use|run|execute)|never\\s+(?:use|run|execute)|" +
                    "nie\\s+(?:używaj|uzywaj|uruchamiaj|wykonuj)|без)"
            )
        val positiveCue =
            Regex(
                "(?iu)(?:використай|використовуйте|запусти|виконай|используй|используйте|запусти|выполни|" +
                    "use|run|execute|użyj|uzyj|uruchom|wykonaj)"
            )

        val results =
            mutableListOf<ToolDirective>()
        var order = 0
        toolRegex
            .findAll(masked)
            .forEach { match ->
                val prefix =
                    clausePrefix(
                        masked,
                        match.range.first
                    )
                val tail =
                    prefix.takeLast(160)
                val negativeMatches =
                    negativeCue
                        .findAll(tail)
                        .toList()
                val lastNegative =
                    negativeMatches
                        .maxOfOrNull {
                            it.range.first
                        }
                        ?: -1
                val lastPositive =
                    positiveCue
                        .findAll(tail)
                        .filter { positive ->
                            negativeMatches
                                .none { negative ->
                                    positive.range.first in
                                        negative.range
                                }
                        }
                        .map {
                            it.range.first
                        }
                        .maxOrNull()
                        ?: -1
                val forbidden =
                    lastNegative >= 0 &&
                        lastNegative >
                            lastPositive
                results +=
                    ToolDirective(
                        tool =
                            ToolRegistry.canonicalize(
                                match.value
                            ),
                        forbidden = forbidden,
                        start =
                            match.range.first,
                        order = order++
                    )
            }
        return results
    }

    private fun unresolvedBlocker(
        policy: EffectiveTaskPolicy,
        call: AgentDecision.ToolCall
    ): String? {
        val canonical =
            ToolRegistry.canonicalize(call.tool)
        val risk =
            ToolRegistry.get(canonical)?.risk
                ?: return null
        if (risk == ToolRisk.READ_ONLY) return null

        val signature =
            ContextKernel.signature(call)
        val targetRef =
            sha256(ContextKernel.target(call))

        val blocker =
            policy.unresolvedEffects
                .firstOrNull {
                    it.origin ==
                        HistoricalRecordOrigin.LOCAL_CURRENT &&
                        (
                            it.requestSignature == signature ||
                                (
                                    canonical in
                                        setOf(
                                            "file.write",
                                            "file.patch"
                                        ) &&
                                        it.targetRef == targetRef
                                    )
                            )
                }
                ?: return null

        return "UNRESOLVED_EFFECT_RECONCILIATION_REQUIRED: source_task_ref=" +
            blocker.sourceTaskRef +
            "; previous_tool=" +
            blocker.tool
    }

    private fun validUnresolved(
        effect: PolicyUnresolvedEffect
    ): Boolean =
        effect.sourceTaskRef
            .matches(Regex("[0-9a-f]{64}")) &&
            effect.requestSignature
                .matches(Regex("[0-9a-f]{24}")) &&
            effect.targetRef
                .matches(Regex("[0-9a-f]{64}")) &&
            ToolRegistry.get(effect.tool) != null

    private fun sameClauseConflict(
        text: String,
        readOnly: List<Int>,
        mutation: List<Int>
    ): Boolean =
        readOnly.any { r ->
            mutation.any { m ->
                clauseIndex(text, r) ==
                    clauseIndex(text, m)
            }
        }

    private fun clauseIndex(
        text: String,
        position: Int
    ): Int =
        text.take(
            position.coerceIn(0, text.length)
        ).count {
            it in setOf(
                '.',
                '!',
                '?',
                ';',
                '\n'
            )
        }

    private fun clausePrefix(
        text: String,
        position: Int
    ): String {
        val before =
            text.substring(
                0,
                position.coerceIn(0, text.length)
            )
        val cut =
            listOf(
                before.lastIndexOf('.'),
                before.lastIndexOf('!'),
                before.lastIndexOf('?'),
                before.lastIndexOf(';'),
                before.lastIndexOf('\n')
            ).maxOrNull() ?: -1
        return before.substring(cut + 1)
    }

    private fun explicitToolFollows(
        text: String,
        position: Int
    ): Boolean {
        val start =
            position.coerceIn(
                0,
                text.length
            )
        val tail =
            text.substring(start)
        val clauseEndRelative =
            listOf(
                tail.indexOf('.'),
                tail.indexOf('!'),
                tail.indexOf('?'),
                tail.indexOf(';'),
                tail.indexOf('\n')
            )
                .filter { it >= 0 }
                .minOrNull()
                ?: tail.length
        val clause =
            tail.take(
                minOf(
                    clauseEndRelative,
                    180
                )
            )
        return ToolRegistry.all()
            .any { spec ->
                Regex(
                    "(?iu)(?<![a-z0-9_])" +
                        Regex.escape(
                            spec.name
                        ) +
                        "(?![a-z0-9_])"
                )
                    .containsMatchIn(
                        clause
                    )
            }
    }

    private fun findPositions(
        text: String,
        patterns: List<Regex>
    ): List<Int> =
        patterns.flatMap { regex ->
            regex.findAll(text)
                .map { it.range.first }
                .toList()
        }

    private fun maskToolNames(
        value: String
    ): String {
        val chars =
            value.toCharArray()
        ToolRegistry.all()
            .map { it.name }
            .sortedByDescending {
                it.length
            }
            .forEach { name ->
                Regex(
                    "(?iu)(?<![a-z0-9_])" +
                        Regex.escape(name) +
                        "(?![a-z0-9_])"
                )
                    .findAll(value)
                    .forEach { match ->
                        for (
                            i in match.range
                        ) {
                            chars[i] = ' '
                        }
                    }
            }
        return String(chars)
    }

    private fun maskQuoted(
        value: String
    ): String {
        val chars = value.toCharArray()
        val patterns =
            listOf(
                Regex("\\\"[^\\\"\\n]*\\\""),
                Regex("'[^'\\n]*'"),
                Regex("«[^»\\n]*»"),
                Regex("\\x60[^\\x60\\n]*\\x60")
            )
        patterns.forEach { regex ->
            regex.findAll(value)
                .forEach { match ->
                    for (i in match.range) {
                        chars[i] = ' '
                    }
                }
        }
        return String(chars)
    }

    private fun clean(
        value: String,
        maxChars: Int
    ): String =
        value
            .replace('\u0000', ' ')
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

    private fun sha256(
        value: String
    ): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(
                value.toByteArray(
                    Charsets.UTF_8
                )
            )
            .joinToString("") {
                "%02x".format(
                    it.toInt() and 0xff
                )
            }
}
