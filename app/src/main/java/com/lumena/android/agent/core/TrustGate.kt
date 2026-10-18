package com.lumena.android.agent.core

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

/**
 * Task taint: set once text the owner did not write (web, http, mcp,
 * marketplace, python output) entered this task's context. Owned by the
 * application from observed tool calls; model prose never writes it.
 */
data class TaskTaint(
    /** Where untrusted text came from; the first source is always kept. */
    val sources: List<String> = emptyList(),
    /** URLs seen in this task's search/read results or fetched by it. */
    val knownUrls: List<String> = emptyList()
) {
    val tainted: Boolean
        get() = sources.isNotEmpty()
}

/** Why a network call in a tainted task waits for the owner. */
data class EgressReview(
    val tool: String,
    /** The full URL, or the query/args that leave the phone. */
    val target: String,
    val reason: String,
    val taintSources: List<String>
) {
    fun render(): String = buildString {
        appendLine("TRUST GATE · мережевий виклик після недовіреного вмісту")
        appendLine("Інструмент: $tool")
        appendLine("Повний URL / що піде в мережу: $target")
        appendLine("Чому потрібне підтвердження: $reason")
        append("Джерело taint: ").append(taintSources.joinToString(" | "))
    }
}

/**
 * Trust Gate: a code rule, not a prompt sentence. Model competence never
 * changes it.
 *
 * Before taint, READ_ONLY network tools keep running automatically. After
 * taint, a URL fetch runs without the owner only if the URL appeared in this
 * task's search/read results or in the owner's own message, and only with a
 * subset of that URL's query parameters (no new names, no changed values,
 * no new path or host). Search queries go to owner-configured providers and
 * stay automatic while short and free of URLs and secret-shaped text.
 * Everything else that can reach the network shows the full target and the
 * taint source before it runs.
 *
 * Not covered: choosing among already-known URLs is a slow covert channel,
 * bounded only by the step budget.
 */
object TrustGate {
    const val MAX_SOURCES = 6
    const val MAX_KNOWN_URLS = 240
    const val MAX_URL_CHARS = 2_048
    const val MAX_QUERY_CHARS = 200

    private val taintPrefixes = listOf("web.", "http.", "mcp.", "marketplace.", "python.", "file.", "git.", "project.")
    private val fetchedUrlTools = setOf("web.read", "http.get", "http.json")
    private val urlPattern = Regex("""https?://[^\s"'<>`\\{}|^\[\]()]+""", RegexOption.IGNORE_CASE)
    private val urlParts = Regex("""^(https?)://([^/?#]+)([^?#]*)(?:\?([^#]*))?(?:#.*)?$""", RegexOption.IGNORE_CASE)
    private val toolResultHeader = Regex("""^TOOL_RESULT for ([A-Za-z0-9_.-]+):""")
    private val batchAdapter = Moshi.Builder().build().adapter<List<Map<String, Any?>>>(
        Types.newParameterizedType(
            List::class.java,
            Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        )
    )
    private val resultAdapter = Moshi.Builder().build().adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )

    fun isTaintSource(tool: String): Boolean {
        val canonical = ToolRegistry.canonicalize(tool)
        return canonical == "image.search" ||
            taintPrefixes.any(canonical::startsWith) ||
            ToolCapability.NETWORK in ToolRegistry.capabilities(canonical)
    }

    /** Python output is untrusted but its URLs are not search/read results. */
    private fun teachesUrls(tool: String): Boolean =
        ToolRegistry.canonicalize(tool).let { name ->
            name == "image.search" || listOf("web.", "http.", "mcp.", "marketplace.").any(name::startsWith)
        }

    /**
     * Records one executed tool. Failed calls taint too: the bridge puts up
     * to 1000 characters of a remote HTTP error body into error=.
     */
    fun observe(
        taint: TaskTaint,
        tool: String,
        args: Map<String, String>,
        output: String,
        step: Int
    ): TaskTaint {
        val canonical = ToolRegistry.canonicalize(tool)
        val calls =
            if (canonical == "inspect.batch") batchChildren(args).orEmpty()
            else listOf(canonical to args)
        val sourceCalls = calls.filter { (child, _) -> isTaintSource(child) }
        if (sourceCalls.isEmpty()) return taint

        val labels = sourceCalls.map { (child, childArgs) ->
            val target = ContextKernel.target(AgentDecision.ToolCall(child, childArgs))
            "крок $step · $child" + if (target.isBlank()) "" else " · $target"
        }
        val learned = buildList {
            sourceCalls.forEach { (child, childArgs) ->
                if (child in fetchedUrlTools) childArgs["url"]?.let(::add)
                if (canonical != "inspect.batch" && teachesUrls(child)) addAll(extractUrls(output))
            }
            if (canonical == "inspect.batch") addAll(batchResultUrls(output))
        }
        return TaskTaint(
            sources = boundedSources(taint.sources + labels),
            knownUrls = (taint.knownUrls + learned.mapNotNull(::normalizeForStorage))
                .distinct()
                .takeLast(MAX_KNOWN_URLS)
        )
    }

    /**
     * A run may start from history that already carries app-generated tool
     * results, for example after an approval or a restored checkpoint.
     */
    fun fromHistory(taint: TaskTaint, history: List<String>): TaskTaint {
        val tools = history.mapNotNull { content ->
            toolResultHeader.find(content.trimStart())?.groupValues?.get(1)
        }.filter(::isTaintSource).distinct()
        if (tools.isEmpty()) return taint
        return taint.copy(
            sources = boundedSources(taint.sources + tools.map { "історія цієї задачі · TOOL_RESULT $it" })
        )
    }

    /**
     * Visible replies of a tainted task stay in the chat and can carry an
     * injected instruction into the next task, so taint carries forward.
     * Known URLs do not: they belong to the task whose results showed them.
     */
    fun inherit(previous: TaskTaint?): TaskTaint {
        if (previous == null || !previous.tainted) return TaskTaint()
        return TaskTaint(
            sources = listOf(
                "попередня задача цього чату: " + previous.sources.first().take(240)
            )
        )
    }

    /** Retrieved source/model text keeps its trust boundary across chats and projects. */
    fun withMemory(taint: TaskTaint): TaskTaint = taint.copy(
        sources = boundedSources(taint.sources + "локальна історична пам'ять: дані джерел і попередніх моделей")
    )

    fun review(
        taint: TaskTaint,
        tool: String,
        args: Map<String, String>,
        ownerText: String
    ): EgressReview? {
        if (!taint.tainted) return null
        val canonical = ToolRegistry.canonicalize(tool)
        if (canonical == "inspect.batch") {
            val children = batchChildren(args)
                ?: return EgressReview(
                    canonical,
                    args["requests"].orEmpty().take(MAX_URL_CHARS),
                    "inspect.batch не вдалося розібрати, тож мережеві виклики в ньому не перевірені",
                    taint.sources
                )
            return children.firstNotNullOfOrNull { (child, childArgs) ->
                review(taint, child, childArgs, ownerText)
            }?.let { it.copy(tool = "inspect.batch → ${it.tool}") }
        }
        if (ToolCapability.NETWORK !in ToolRegistry.capabilities(canonical)) return null

        val url = args["url"]
        if (url != null) {
            val reason = urlReason(url, taint.knownUrls + extractUrls(ownerText)) ?: return null
            return EgressReview(canonical, url.take(MAX_URL_CHARS), reason, taint.sources)
        }

        val described = args.toSortedMap().entries.joinToString("; ") { (key, value) -> "$key=$value" }
        if (ToolRegistry.get(canonical)?.risk == ToolRisk.READ_ONLY) {
            val reason = queryReason(args) ?: return null
            return EgressReview(canonical, described.take(MAX_URL_CHARS), reason, taint.sources)
        }
        // MUTATING/EXECUTABLE network tools already need confirmation; the
        // owner should still see that untrusted text is in the task.
        return EgressReview(
            canonical,
            described.take(MAX_URL_CHARS),
            "інструмент сам може звертатися в мережу, а задача вже містить недовірений текст",
            taint.sources
        )
    }

    private fun urlReason(raw: String, allowed: List<String>): String? {
        val wanted = parseUrl(raw) ?: return "URL не вдалося однозначно розібрати"
        val samePage = allowed.mapNotNull(::parseUrl).filter {
            it.origin == wanted.origin && it.path == wanted.path
        }
        if (samePage.isEmpty()) {
            return "цього URL не було ні в результатах пошуку/читання цієї задачі, ні у твоєму повідомленні"
        }
        if (samePage.any { known -> isSubMultiset(wanted.params, known.params) }) return null
        val knownNames = samePage.flatMap { it.paramNames }.toSet()
        val newNames = wanted.paramNames.filterNot { it in knownNames }.distinct()
        return if (newNames.isNotEmpty()) {
            "нові query-параметри: " + newNames.joinToString(", ")
        } else {
            "змінені значення query-параметрів: " +
                wanted.params.filterNot { param -> samePage.any { param in it.params } }
                    .map { it.substringBefore('=') }
                    .distinct()
                    .joinToString(", ")
        }
    }

    private fun queryReason(args: Map<String, String>): String? {
        val values = args.values
        return when {
            values.any { it.length > MAX_QUERY_CHARS } ->
                "запит довший за $MAX_QUERY_CHARS символів"
            values.any { urlPattern.containsMatchIn(it) } ->
                "запит містить URL"
            values.any { SecretMasker.mask(it).masks > 0 } ->
                "запит містить текст, схожий на секрет"
            else -> null
        }
    }

    private data class ParsedUrl(
        val origin: String,
        val path: String,
        val params: List<String>
    ) {
        val paramNames: List<String>
            get() = params.map { it.substringBefore('=') }
    }

    private fun parseUrl(raw: String): ParsedUrl? {
        val text = raw.trim().replace("&amp;", "&")
        if (text.length > MAX_URL_CHARS) return null
        val match = urlParts.matchEntire(text) ?: return null
        val scheme = match.groupValues[1].lowercase()
        val authority = match.groupValues[2].lowercase()
        // Credentials in a URL are never automatic.
        if ('@' in authority) return null
        val defaultPort = if (scheme == "https") ":443" else ":80"
        val host = authority.removeSuffix(defaultPort).trimEnd('.')
        val path = match.groupValues[3].ifEmpty { "/" }
        val params = match.groupValues[4].split('&').filter(String::isNotEmpty).sorted()
        return ParsedUrl("$scheme://$host", path, params)
    }

    private fun normalizeForStorage(raw: String): String? =
        raw.trim().take(MAX_URL_CHARS).takeIf { parseUrl(it) != null }

    private fun isSubMultiset(wanted: List<String>, known: List<String>): Boolean {
        val remaining = known.groupingBy { it }.eachCount().toMutableMap()
        return wanted.all { param ->
            val count = remaining[param] ?: 0
            if (count == 0) false else {
                remaining[param] = count - 1
                true
            }
        }
    }

    private fun extractUrls(text: String): List<String> =
        urlPattern.findAll(text)
            .map { it.value.trimEnd('.', ',', ';', ':', '!', '?') }
            .toList()

    private fun boundedSources(sources: List<String>): List<String> {
        val distinct = sources.distinct()
        if (distinct.size <= MAX_SOURCES) return distinct
        return listOf(distinct.first()) + distinct.takeLast(MAX_SOURCES - 1)
    }

    private fun batchChildren(args: Map<String, String>): List<Pair<String, Map<String, String>>>? {
        val parsed = runCatching { batchAdapter.fromJson(args["requests"].orEmpty()) }.getOrNull()
            ?: return null
        return parsed.map { child ->
            val tool = child["tool"] as? String ?: return null
            val childArgs = (child["args"] as? Map<*, *>)
                ?.entries
                ?.associate { it.key.toString() to it.value?.toString().orEmpty() }
                .orEmpty()
            ToolRegistry.canonicalize(tool) to childArgs
        }
    }

    private fun batchResultUrls(output: String): List<String> {
        val results = runCatching { resultAdapter.fromJson(output)?.get("results") as? List<*> }
            .getOrNull()
            .orEmpty()
        return results.flatMap { item ->
            val child = item as? Map<*, *> ?: return@flatMap emptyList()
            val tool = child["tool"] as? String ?: return@flatMap emptyList()
            if (!teachesUrls(tool)) return@flatMap emptyList()
            extractUrls(child["stdout"]?.toString().orEmpty())
        }
    }
}
