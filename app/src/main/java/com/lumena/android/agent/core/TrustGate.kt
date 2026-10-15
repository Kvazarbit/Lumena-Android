package com.lumena.android.agent.core

/** Red-test stub: the taint/egress rule lands in the next commit. */
data class TaskTaint(
    val sources: List<String> = emptyList(),
    val knownUrls: List<String> = emptyList()
) {
    val tainted: Boolean
        get() = sources.isNotEmpty()
}

data class EgressReview(
    val tool: String,
    val target: String,
    val reason: String,
    val taintSources: List<String>
) {
    fun render(): String = "$tool $target $reason ${taintSources.joinToString()}"
}

object TrustGate {
    const val MAX_SOURCES = 6
    const val MAX_KNOWN_URLS = 240
    const val MAX_QUERY_CHARS = 200

    fun isTaintSource(tool: String): Boolean = false

    fun observe(
        taint: TaskTaint,
        tool: String,
        args: Map<String, String>,
        output: String,
        step: Int
    ): TaskTaint = taint

    fun fromHistory(taint: TaskTaint, history: List<String>): TaskTaint = taint

    fun inherit(previous: TaskTaint?): TaskTaint = TaskTaint()

    fun review(
        taint: TaskTaint,
        tool: String,
        args: Map<String, String>,
        ownerText: String
    ): EgressReview? = null
}
