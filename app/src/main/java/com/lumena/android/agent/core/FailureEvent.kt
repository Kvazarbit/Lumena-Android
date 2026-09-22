package com.lumena.android.agent.core

enum class FailureSource {
    PROTOCOL,
    MODEL_RUNTIME,
    TOOL,
    CONTEXT,
    RESOURCE,
    POLICY,
    TRANSPORT
}

data class FailureEvent(
    val source: FailureSource,
    val failureClass: FailureClass,
    val retryable: Boolean?,
    val effectClass: EffectClass,
    val dependency: String?,
    val evidence: String,
    val actionFamily: String?,
    val attempt: Int,
    val outcomeUnknown: Boolean = false,
    val code: String? = null
) {
    init {
        require(attempt >= 1) { "FailureEvent.attempt must be >= 1" }
    }
}

object FailureClassifier {
    fun protocol(kind: ProtocolFailureKind): FailureClass = when (kind) {
        ProtocolFailureKind.SYNTAX,
        ProtocolFailureKind.AMBIGUOUS,
        ProtocolFailureKind.UNKNOWN_ACTION,
        ProtocolFailureKind.UNSUPPORTED_SHAPE ->
            FailureClass.INVALID_INPUT
    }

    fun model(message: String): FailureClass {
        val detail = message.lowercase()

        return when {
            listOf(
                "context length",
                "context window",
                "prompt too long",
                "too many tokens",
                "input is too long",
                "maximum context",
                "requested tokens exceed",
                "exceeds the context",
                "num_ctx"
            ).any(detail::contains) ->
                FailureClass.CONTEXT_PRESSURE

            listOf(
                "not enough free ram",
                "allocation/mmap failed",
                "out of memory"
            ).any(detail::contains) ->
                FailureClass.RESOURCE_PRESSURE

            listOf(
                "unauthorized",
                "http 401",
                "bridge token is required"
            ).any(detail::contains) ->
                FailureClass.AUTH_OR_CONFIG

            listOf(
                "embedded model load failed",
                "embedded generation failed",
                "could not load this gguf model",
                "gguf model not found",
                "invalid android file descriptor",
                "metadata could not be parsed",
                "full model load failed",
                "tensor layout is not accepted"
            ).any(detail::contains) ->
                FailureClass.MODEL_RUNTIME

            "timeout" in detail || "timed out" in detail ->
                FailureClass.TIMEOUT

            "http 429" in detail || "rate limit" in detail || "retry-after" in detail ->
                FailureClass.RATE_LIMIT

            listOf(
                "temporary model transport failure",
                "connection reset",
                "connection refused",
                "broken pipe"
            ).any(detail::contains) ->
                FailureClass.TRANSIENT_TRANSPORT

            else ->
                FailureClass.OTHER
        }
    }

    fun tool(
        tool: String,
        errorCode: String? = null,
        suppliedClass: String? = null,
        error: String? = null,
        stderr: String = "",
        stdout: String = "",
        outcomeUnknown: Boolean = false
    ): FailureClass {
        if (outcomeUnknown) return FailureClass.UNKNOWN_EFFECT

        suppliedClass
            ?.trim()
            ?.uppercase()
            ?.let { raw -> runCatching { FailureClass.valueOf(raw) }.getOrNull() }
            ?.let { return it }

        val code = errorCode.orEmpty().trim().uppercase()
        if (code == "SEARCH_EXHAUSTED") return FailureClass.DEPENDENCY_EXHAUSTED
        if (code == "BRIDGE_TRANSPORT") return FailureClass.TRANSIENT_TRANSPORT

        val detail = sequenceOf(error, stderr, stdout)
            .filterNotNull()
            .joinToString(" ")
            .lowercase()
            .take(8_000)

        return when {
            "bridge transport" in detail ||
                "connection reset" in detail ||
                "connection refused" in detail ||
                "dns lookup failed" in detail ->
                FailureClass.TRANSIENT_TRANSPORT

            "timeout" in detail || "timed out" in detail ->
                FailureClass.TIMEOUT

            "http 429" in detail || "rate limit" in detail || "retry-after" in detail ->
                FailureClass.RATE_LIMIT

            "http 202" in detail ||
                "human verification" in detail ||
                "challenge" in detail ||
                "captcha" in detail ->
                FailureClass.PROVIDER_CHALLENGE

            "http 401" in detail ||
                "unauthorized" in detail ||
                "api key" in detail ||
                "bridge token is required" in detail ->
                FailureClass.AUTH_OR_CONFIG

            "search unavailable" in detail && ToolRegistry.canonicalize(tool) == "web.search" ->
                FailureClass.DEPENDENCY_EXHAUSTED

            "no such file" in detail ||
                "not found" in detail ||
                "does not exist" in detail ||
                "not a git repository" in detail ||
                "requested path" in detail ->
                FailureClass.STATE_DRIFT

            "missing required args" in detail ||
                "requires query" in detail ||
                "requires a url" in detail ||
                "invalid argument" in detail ->
                FailureClass.INVALID_INPUT

            "context length" in detail ||
                "context window" in detail ||
                "too many tokens" in detail ||
                "requested tokens exceed" in detail ||
                "num_ctx" in detail ->
                FailureClass.CONTEXT_PRESSURE

            "not enough free ram" in detail ||
                "allocation/mmap failed" in detail ||
                "out of memory" in detail ->
                FailureClass.RESOURCE_PRESSURE

            "policy rejected" in detail || "permission denied" in detail ->
                FailureClass.POLICY_DENIED

            else -> FailureClass.OTHER
        }
    }
}

object FailureEvents {
    fun fromProtocol(
        failure: NormalizationResult.Failure,
        attempt: Int
    ): FailureEvent = FailureEvent(
        source = FailureSource.PROTOCOL,
        failureClass = FailureClassifier.protocol(failure.kind),
        retryable = true,
        effectClass = EffectClass.NONE,
        dependency = "model-protocol",
        evidence = compact(failure.reason),
        actionFamily = null,
        attempt = attempt
    )

    fun fromModel(
        message: String,
        attempt: Int
    ): FailureEvent {
        val klass = FailureClassifier.model(message)
        val source = when (klass) {
            FailureClass.CONTEXT_PRESSURE -> FailureSource.CONTEXT
            FailureClass.RESOURCE_PRESSURE -> FailureSource.RESOURCE
            FailureClass.TRANSIENT_TRANSPORT,
            FailureClass.TIMEOUT,
            FailureClass.RATE_LIMIT -> FailureSource.TRANSPORT
            else -> FailureSource.MODEL_RUNTIME
        }
        val retryable = klass !in setOf(
            FailureClass.CONTEXT_PRESSURE,
            FailureClass.RESOURCE_PRESSURE,
            FailureClass.AUTH_OR_CONFIG,
            FailureClass.MODEL_RUNTIME
        )

        return FailureEvent(
            source = source,
            failureClass = klass,
            retryable = retryable,
            effectClass = EffectClass.NONE,
            dependency = "model",
            evidence = compact(message),
            actionFamily = null,
            attempt = attempt
        )
    }

    fun fromToolOutcome(
        call: AgentDecision.ToolCall,
        errorCode: String? = null,
        suppliedClass: String? = null,
        error: String? = null,
        stderr: String = "",
        stdout: String = "",
        retryable: Boolean? = null,
        dependency: String? = null,
        outcomeUnknown: Boolean = false,
        attempt: Int
    ): FailureEvent {
        val canonicalTool = ToolRegistry.canonicalize(call.tool)
        val klass = FailureClassifier.tool(
            tool = canonicalTool,
            errorCode = errorCode,
            suppliedClass = suppliedClass,
            error = error,
            stderr = stderr,
            stdout = stdout,
            outcomeUnknown = outcomeUnknown
        )

        return FailureEvent(
            source = FailureSource.TOOL,
            failureClass = klass,
            retryable = if (outcomeUnknown) false else retryable,
            effectClass = if (ToolRegistry.get(canonicalTool)?.risk == ToolRisk.READ_ONLY) {
                EffectClass.READ_ONLY
            } else {
                EffectClass.MUTATING_OR_EXECUTABLE
            },
            dependency = dependency,
            evidence = compact(
                buildString {
                    if (!error.isNullOrBlank()) append("error=").append(error).append(' ')
                    if (stderr.isNotBlank()) append("stderr=").append(stderr).append(' ')
                    if (stdout.isNotBlank()) append("stdout=").append(stdout)
                }.trim()
            ),
            actionFamily = canonicalTool,
            attempt = attempt,
            outcomeUnknown = outcomeUnknown,
            code = errorCode
        )
    }

    fun policyDenied(
        reason: String,
        actionFamily: String?,
        effectClass: EffectClass,
        attempt: Int,
        dependency: String? = null
    ): FailureEvent = FailureEvent(
        source = FailureSource.POLICY,
        failureClass = FailureClass.POLICY_DENIED,
        retryable = false,
        effectClass = effectClass,
        dependency = dependency,
        evidence = compact(reason),
        actionFamily = actionFamily,
        attempt = attempt
    )

    private fun compact(value: String, maxChars: Int = 8_000): String {
        val clean = value
            .replace('\u0000', ' ')
            .trim()
        if (clean.length <= maxChars) return clean

        val marker = "\n...[failure evidence middle omitted]...\n"
        val available = (maxChars - marker.length).coerceAtLeast(0)
        val headChars = (available * 3) / 5
        val tailChars = available - headChars
        return clean.take(headChars) + marker + clean.takeLast(tailChars)
    }
}
