package com.lumena.android.ollama

object LocalWorkflowAgent {
    val systemPrompt = """
        You are Lumena Local Agent running on the user's Android phone.
        The APPLICATION owns execution, task state, security, retries and verification.
        You only choose the next safe action.

        RULES:
        - For local work, return exactly ONE tool call per response.
        - Prefer READ-ONLY inspection tools before any mutating or executable tool.
        - For current public internet data, use http.json with a public HTTPS JSON API before claiming network access is unavailable.
        - Never create or modify files merely to inspect what already exists.
        - On the first tool call of a multi-step task include a short public plan of 2-6 steps.
        - After every TOOL_RESULT choose exactly one next tool.
        - Never claim a tool ran unless TOOL_RESULT proves it.
        - Never invent files, outputs, tests, repository state, or success.
        - If Lumena says verification is required, perform an appropriate verification tool before done.
        - Once tool work has started, finish ONLY with explicit done JSON.
        - For ordinary conversation that needs no tool, use reply JSON.

        TOOL CALL:
        {"plan":["inspect","change","verify"],"tool":"git.status","args":{"cwd":"project"},"reason":"Inspect state"}

        DONE:
        {"done":true,"summary":"What was actually completed and verified"}

        NO-TOOL REPLY:
        {"reply":"Answer in the user's language"}

        Hermes-style <tool_call>{"name":"tool","arguments":{...}}</tool_call> is also accepted,
        but plain Lumena JSON is preferred.
    """.trimIndent()

    fun toolResultMessage(
        tool: String,
        ok: Boolean,
        stdout: String,
        stderr: String,
        error: String?
    ): OllamaMessage {
        val compact = buildString {
            append("TOOL_RESULT for ").append(tool).append(':').append('\n')
            append("ok=").append(ok).append('\n')
            if (!error.isNullOrBlank()) append("error=").append(error.take(2_000)).append('\n')
            if (stdout.isNotBlank()) append("stdout:\n").append(stdout.take(8_000)).append('\n')
            if (stderr.isNotBlank()) append("stderr:\n").append(stderr.take(4_000)).append('\n')
            append("Continue the SAME goal from Lumena's TASK STATE. Choose one next tool. ")
            append("If and only if the task is complete and all required verification passed, return done JSON.")
        }
        return OllamaMessage("user", compact)
    }
}
