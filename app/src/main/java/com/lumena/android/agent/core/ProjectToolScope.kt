package com.lumena.android.agent.core

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

/** Bind paths before validation, approval, dispatch and evidence recording. No shell. */
object ProjectToolScope {
    private val json = Moshi.Builder().build().adapter<List<Map<String, Any?>>>(
        Types.newParameterizedType(List::class.java,
            Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)))

    fun bind(call: AgentDecision.ToolCall, projectRoot: String?, depth: Int = 0): AgentDecision.ToolCall {
        if (projectRoot == null) return call
        val root = ProjectWorkspacePolicy.root(projectRoot)
        val tool = ToolRegistry.canonicalize(call.tool)
        require(ToolCapability.WRITE_WORKSPACE !in ToolRegistry.capabilities(tool) || tool in setOf(
            "file.write", "file.patch", "dir.create", "git.init", "git.add", "git.commit", "python.run", "python.tests")) {
            "PROJECT_SCOPE: this tool has unbounded workspace effects"
        }
        require(depth <= 2) { "PROJECT_SCOPE: nested batch exceeds limit" }
        if (root.startsWith('@')) {
            require(ToolRegistry.get(tool)?.risk == ToolRisk.READ_ONLY) { "PROJECT_SCOPE: this repository is read-only" }
        }
        val args = call.args.toMutableMap()
        fun path(key: String, default: Boolean = false) {
            val raw = args[key]?.takeIf { it.isNotBlank() } ?: if (default) "." else return
            require(raw.length <= 800 && '\\' !in raw && ':' !in raw && !raw.startsWith('/') &&
                raw.none { it.isISOControl() } && raw.split('/').none { it == ".." }) { "PROJECT_SCOPE: path escapes workspace" }
            val clean = raw.split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/").ifEmpty { "." }
            val resolved = when {
                clean == "." -> root
                root == "." && !clean.startsWith('@') -> clean
                clean == root || clean.startsWith("$root/") -> clean
                clean.startsWith('@') -> throw IllegalArgumentException("PROJECT_SCOPE: another read root")
                else -> "$root/$clean"
            }
            args[key] = resolved
        }
        when (tool) {
            "file.read", "file.write", "file.patch", "dir.create" -> path("path")
            "file.list", "file.search" -> path("path", true)
            "python.run", "python.syntax_check" -> { path("script"); path("cwd", true) }
            "python.tests", "git.status", "git.diff", "git.log", "git.init", "git.add", "git.commit",
            "project.inspect", "project.search" -> path("cwd", true)
            "project.create" -> throw IllegalArgumentException("PROJECT_SCOPE: register a separate project before creating another root")
            "context.snapshot", "workspace.list" -> return AgentDecision.ToolCall(
                "project.inspect", mapOf("cwd" to root), call.reason, call.plan)
            "inspect.batch" -> {
                val children = requireNotNull(json.fromJson(args["requests"].orEmpty())) { "PROJECT_SCOPE: unreadable batch" }
                require(children.size in 1..8)
                args["requests"] = json.toJson(children.map { child ->
                    val name = requireNotNull(child["tool"] as? String)
                    val values = (child["args"] as? Map<*, *>).orEmpty().entries
                        .associate { it.key.toString() to it.value?.toString().orEmpty() }
                    val bound = bind(AgentDecision.ToolCall(name, values), root, depth + 1)
                    mapOf("tool" to bound.tool, "args" to bound.args)
                })
            }
        }
        if (tool == "git.add") {
            require(call.args["paths"].orEmpty().none { it in "\"'`" }) { "PROJECT_SCOPE: quoted Git paths are unsupported; stage a simple path or ." }
            val paths = args["paths"].orEmpty().split(Regex("\\s+"))
            require(paths.none { it.startsWith('/') || '\\' in it || it.split('/').contains("..") }) {
                "PROJECT_SCOPE: Git paths escape project"
            }
        }
        if (tool in setOf("git.add", "git.commit")) args["project_scope"] = "true"
        if (tool == "python.tests") args["project_receipt"] = "true"
        return call.copy(tool = tool, args = args)
    }
}
