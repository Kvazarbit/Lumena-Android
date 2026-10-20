package com.lumena.android.agent.core

/** A logical project ID is not authorization for an unscoped workspace root. */
object ProjectActivationGate {
    fun problem(resolvedId: String?, selectedId: String?): String? =
        if (resolvedId != null && resolvedId != selectedId)
            "PROJECT_SCOPE_NOT_ACTIVE: вибери проєкт у «Проєкти · локальна ОС контексту». " +
                "Назва в повідомленні не активує каталог Termux."
        else null

    fun requiresRoot(tool: String): Boolean {
        val name = ToolRegistry.canonicalize(tool)
        return name in setOf("workspace.list", "inspect.batch", "context.snapshot") ||
            name.startsWith("file.") || name.startsWith("git.") ||
            name.startsWith("python.") || name.startsWith("project.") || name == "dir.create"
    }
}
