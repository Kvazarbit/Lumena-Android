package com.lumena.android.collaboration

import com.lumena.android.agent.local.ToolResult
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

/**
 * Suggests the owner's verification boundary from the bridge file inventory (project.inspect).
 * Only a suggestion: nothing is saved until the owner presses save, so the boundary stays owner-declared.
 */
object VerificationBoundaryProposal {
    const val MAX_PATHS = 16
    data class Inventory(val files: List<String>, val partial: Boolean)

    private val adapter = Moshi.Builder().build().adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java))

    /** Bridge inventory of this project root, or null when the result is not a matching project.inspect. */
    fun inventory(result: ToolResult?, root: String): Inventory? {
        if (result == null || !result.ok || result.outcomeUnknown) return null
        val data = try { adapter.fromJson(result.stdout) } catch (_: Exception) { null } ?: return null
        if (data["schema"] != "lumena.project.v1" || data["cwd"] != root || data["exists"] != true) return null
        val files = (data["files"] as? List<*>)?.filterIsInstance<String>() ?: return null
        val listed = (data["files_count"] as? Number)?.toInt() ?: files.size
        return Inventory(files.take(400), data["truncated"] == true || listed > files.size)
    }

    private fun isTest(name: String) =
        name == "conftest.py" || (name.endsWith(".py") && (name.startsWith("test_") || name.endsWith("_test.py")))

    /** tests/ or test/ as whole directories, plus loose test files and conftest.py outside them. */
    fun propose(files: List<String>): List<String> {
        val clean = files.map { it.replace('\\', '/').trim('/') }
            // Same rules as the owner's saved boundary (ProjectWorkspacePolicy.root): a suggestion must be savable.
            .filter { p -> p.isNotEmpty() && p.length <= 360 && !p.startsWith('@') && ':' !in p &&
                p.none { it.isWhitespace() || it.isISOControl() } && p.split('/').none { it == ".." || it == "." || it.isEmpty() } }
            .distinct().sorted()
        val dirs = listOf("tests", "test").filter { d -> clean.any { it.startsWith("$d/") } }
        val loose = clean.filter { path -> dirs.none { path.startsWith("$it/") } && isTest(path.substringAfterLast('/')) }
        return (dirs + loose).take(MAX_PATHS)
    }
}
