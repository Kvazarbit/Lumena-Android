package com.lumena.android.agent.core

import com.lumena.android.llama.ChatContextPolicy
import com.lumena.android.ollama.*
import org.junit.Assert.*
import org.junit.Test

class ProjectContextBudgetTest {
    private val required = RequiredProjectContext.wrap("OWNER: preserve API\nOPEN: finish migration\n" + "p".repeat(900))
    private val system = "static instructions ".repeat(500) + required + "optional memory ".repeat(500)

    @Test fun ollamaCompactionRetainsEntireMandatoryBlockAndLatestTurn() {
        val budget = OllamaContextPolicy.budget(null, false, "local-model")
        val compacted = OllamaContextPolicy.compact(listOf(OllamaMessage("system", system),
            OllamaMessage("user", "CURRENT OWNER TASK")), budget)
        assertTrue(compacted.first().content.contains(required))
        assertTrue(compacted.last().content.contains("CURRENT OWNER TASK"))
        assertTrue(compacted.sumOf { it.content.length } <= budget.maxChars)
    }

    @Test fun embeddedExactTokenFitPreservesMandatoryBlockUnderPressure() {
        val fit = ChatContextPolicy.fit(arrayOf("system", "user"), arrayOf(system, "CURRENT OWNER TASK"), 1800,
            formatter = { roles, contents -> roles.indices.joinToString("\n") { roles[it] + ":" + contents[it] } },
            tokenCounter = { (it.length + 1) / 2 })
        assertTrue(fit.fits)
        assertTrue(fit.contents[0].contains(required))
        assertTrue(fit.contents.last().contains("CURRENT OWNER TASK"))
    }

    @Test fun actualProjectContextKeepsToolSchemasWhenStaticPromptConsumesBudget() {
        val project = WorkspaceProject("p", "Demo", "demo", "Maintain a tested application", listOf("Preserve public API"), updatedAt = 1)
        val task = TaskState("t", "p", "Прочитай main.py", projectRoot = "demo", projectContext = ProjectWorkspacePolicy.capsule(project))
        val dynamic = ContextBuilder().build(task, null, emptyList())
        val compact = OllamaContextPolicy.compact(listOf(OllamaMessage("system", "static ".repeat(2000) + dynamic),
            OllamaMessage("user", task.goal)), OllamaContextPolicy.budget(null, false))
        val system = compact.first().content
        assertTrue(system.contains("Preserve public API"))
        assertTrue(system.contains("file.write{path,content}"))
        assertTrue(system.contains("python.tests{cwd,argv}"))
        assertTrue(system.contains(RequiredProjectContext.block(dynamic)!!))
    }

    @Test fun impossibleBudgetFailsInsteadOfSilentlyRemovingObligations() {
        val huge = RequiredProjectContext.wrap("owner-rule".repeat(3000))
        assertThrows(IllegalArgumentException::class.java) {
            OllamaContextPolicy.compact(listOf(OllamaMessage("system", huge), OllamaMessage("user", "task")),
                OllamaContextPolicy.budget(null, false))
        }
        val fit = ChatContextPolicy.fit(arrayOf("system", "user"), arrayOf(huge, "task"), 100,
            formatter = { _, contents -> contents.joinToString("\n") }, tokenCounter = { it.length })
        assertFalse(fit.fits)
        assertTrue(fit.contents[0].contains(huge))
    }

    @Test fun selectedProjectAdviceSurvivesActualBackendCompaction() {
        val p = WorkspaceProject("p", "Demo", "demo", "Maintain the application", updatedAt = 1)
        val task = TaskState("t", "p", "Continue", projectRoot = "demo", projectContext = ProjectWorkspacePolicy.capsule(p))
        val dynamic = ContextBuilder().build(task, null,
            listOf("HANDOFF_MAIN_ALREADY_EXISTS", "CROSS_MODEL_EXPERIENCE_PRESERVE_API"))
        val compact = OllamaContextPolicy.compact(listOf(
            OllamaMessage("system", "static ".repeat(2000) + dynamic + "irrelevant tail ".repeat(1000)),
            OllamaMessage("user", "Continue")), OllamaContextPolicy.budget(null, false))
        assertTrue(compact.first().content.contains("HANDOFF_MAIN_ALREADY_EXISTS"))
        assertTrue(compact.first().content.contains("CROSS_MODEL_EXPERIENCE_PRESERVE_API"))
    }
}
