package com.lumena.android.agent.core

import org.junit.Assert.*
import org.junit.Test

class HtmlProtocolRegressionTest {
    @Test fun htmlCreationIsCodeWorkWithWorkspacePreflight() {
        val profile = TaskIntentRouter.route("створи в html, 3d акваріум з рибками, симулятор")
        assertEquals(TaskIntent.CODE_WORK, profile.intent)
        assertEquals("context.snapshot", profile.preflight?.tool)
    }

    @Test fun labelledHtmlAndJavascriptFencesAreTextNotProtocolEnvelopes() {
        for (language in listOf("html", "javascript", "css", "python")) {
            val raw = "```$language\n<div>Example</div>\n```"
            assertEquals(NormalizationResult.PlainText(raw), ProtocolNormalizer().normalize(raw))
        }
    }

    @Test fun incompleteToolJsonIsStillRejected() {
        for (raw in listOf("{\"tool\":\"file.write\",\"args\":{", "```json\n{\"tool\":\"file.write\"")) {
            assertTrue(ProtocolNormalizer().normalize(raw) is NormalizationResult.Failure)
        }
    }

    @Test fun codeInChatDoesNotProveWorkspaceCreation() {
        val controller = AgentController()
        val state = controller.initial(TaskState("html", null, "Create HTML aquarium"))
        val result = controller.interpret("```html\n<html>Aquarium</html>\n```", state)
        assertTrue(result is ControllerInstruction.AskModelAgain)
        assertEquals(0, state.task.kernel.observed)
    }

    @org.junit.Test fun leadingCreationTypoStillRoutesExplicitHtmlWork() {
        org.junit.Assert.assertEquals(TaskIntent.CODE_WORK,
            TaskIntentRouter.route("стаори 3d акваріум ,з рибками, html").intent)
        org.junit.Assert.assertEquals(TaskIntent.GENERAL, TaskIntentRouter.route("що таке html").intent)
        org.junit.Assert.assertEquals(TaskIntent.GENERAL, TaskIntentRouter.route("стаори акваріум").intent)
    }
}
