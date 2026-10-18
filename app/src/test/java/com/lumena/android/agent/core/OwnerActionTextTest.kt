package com.lumena.android.agent.core

import org.junit.Assert.*
import org.junit.Test

class OwnerActionTextTest {
    @Test fun descriptiveMentionsDoNotAcquireRequiredTools() {
        for (text in listOf(
            "Аудит: file.write та python.run існують у реєстрі.",
            "The model said run python.run, then reported success.",
            "Модель виправила код; file.patch — назва інструмента.",
            "Приклад:\n```json\n{\"tool\":\"file.write\",\"args\":{}}\n```",
            "Поясни `run python.run` та «використай file.patch»."
        )) assertTrue(text, EffectiveTaskPolicyCompiler.compile(text, text).requiredTools.isEmpty())
    }
    @Test fun currentAuditDoesNotInheritOldMutationObligation() {
        val policy = EffectiveTaskPolicyCompiler.compile("Виправ demo.py через file.patch", "Поясни попередній звіт.")
        assertTrue(policy.requiredTools.isEmpty())
    }
    @Test fun quotedFilenameStillSupportsAnAffirmativeCodeRequest() {
        assertEquals(TaskIntent.CODE_WORK, TaskIntentRouter.route("Виправ `demo.py` через file.patch").intent)
        assertEquals(TaskIntent.CODE_WORK, TaskIntentRouter.route("Можеш створити demo.py?").intent)
    }
    @Test fun explicitChecklistStillRequiresItsTools() {
        val text = "Створи demo.py.\nПісля запису файлів:\n- python.syntax_check для demo.py\n- python.tests з cwd=demo"
        assertEquals(setOf("python.syntax_check", "python.tests"),
            EffectiveTaskPolicyCompiler.compile(text, text).requiredTools.toSet())
    }
    @Test fun exactDuplicateReplanningTerminatesAsPartial() {
        val controller = AgentController()
        val call = AgentDecision.ToolCall("file.read", mapOf("path" to "notes.txt"))
        var state = controller.initial(TaskState("bounded", null, "Прочитай notes.txt", maxSteps = 12))
        repeat(2) { state = controller.afterTool(state, call, true, "same", "", null).state }
        val raw = """{"tool":"file.read","args":{"path":"notes.txt"},"reason":"Read again"}"""
        repeat(2) {
            val instruction = controller.interpret(raw, state)
            assertTrue(instruction.toString(), instruction is ControllerInstruction.AskModelAgain)
            state = instruction.state
        }
        val finish = controller.interpret(raw, state)
        assertTrue(finish.toString(), finish is ControllerInstruction.Finish)
        assertEquals(TaskStatus.PARTIAL, finish.state.task.status)
        assertEquals(2, finish.state.task.kernel.observed)
    }
}
