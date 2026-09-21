package com.lumena.android.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {
    @Test
    fun unknownToolIsBlocked() {
        val validation = ToolRegistry.validate(
            AgentDecision.ToolCall("shell.exec", mapOf("cmd" to "rm -rf /"))
        )
        assertFalse(validation.allowed)
        assertTrue(validation.requiresConfirmation)
    }

    @Test
    fun missingRequiredArgumentIsBlocked() {
        val validation = ToolRegistry.validate(
            AgentDecision.ToolCall("file.read", emptyMap())
        )
        assertFalse(validation.allowed)
        assertTrue(validation.error!!.contains("path"))
    }

    @Test
    fun readOnlyLocalToolDoesNotNeedConfirmation() {
        val validation = ToolRegistry.validate(
            AgentDecision.ToolCall("file.read", mapOf("path" to "README.md")),
            externalSource = false
        )
        assertTrue(validation.allowed)
        assertFalse(validation.requiresConfirmation)
    }

    @Test
    fun sameReadOnlyToolFromExternalChatNeedsConfirmation() {
        val validation = ToolRegistry.validate(
            AgentDecision.ToolCall("file.read", mapOf("path" to "README.md")),
            externalSource = true
        )
        assertTrue(validation.allowed)
        assertTrue(validation.requiresConfirmation)
    }

    @Test
    fun imageSearchIsReadOnlyAndRequiresQuery() {
        val missing = ToolRegistry.validate(
            AgentDecision.ToolCall("image.search", emptyMap())
        )
        assertFalse(missing.allowed)
        assertTrue(missing.error!!.contains("query"))

        val valid = ToolRegistry.validate(
            AgentDecision.ToolCall(
                "image.search",
                mapOf("query" to "woman portrait")
            )
        )
        assertTrue(valid.allowed)
        assertFalse(valid.requiresConfirmation)
    }

    @Test
    fun inspectBatchIsReadOnlyButRequiresRequests() {
        val missing = ToolRegistry.validate(
            AgentDecision.ToolCall("inspect.batch", emptyMap())
        )
        assertFalse(missing.allowed)
        assertTrue(missing.error!!.contains("requests"))

        val allowed = ToolRegistry.validate(
            AgentDecision.ToolCall(
                "inspect.batch",
                mapOf("requests" to """[{"tool":"system.info","args":{}}]""")
            )
        )
        assertTrue(allowed.allowed)
        assertFalse(allowed.requiresConfirmation)
    }

    @Test
    fun pythonRunRejectsInlineSourceAndAcceptsPath() {
        val inline = ToolRegistry.validate(
            AgentDecision.ToolCall(
                "python.run",
                mapOf("script" to "import requests\nprint('x')")
            )
        )
        assertFalse(inline.allowed)
        assertTrue(inline.error!!.contains("path"))

        val path = ToolRegistry.validate(
            AgentDecision.ToolCall(
                "python.run",
                mapOf("script" to "demo_project/api_test.py")
            )
        )
        assertTrue(path.allowed)
        assertTrue(path.requiresConfirmation)
    }

    @Test
    fun ollamaGenerateRequiresExplicitApproval() {
        val missing = ToolRegistry.validate(
            AgentDecision.ToolCall("ollama.generate", mapOf("model" to "ornith-1.5:9b"))
        )
        assertFalse(missing.allowed)
        assertTrue(missing.error!!.contains("prompt"))

        val valid = ToolRegistry.validate(
            AgentDecision.ToolCall(
                "ollama.generate",
                mapOf("model" to "ornith-1.5:9b", "prompt" to "Hi")
            )
        )
        assertTrue(valid.allowed)
        assertTrue(valid.requiresConfirmation)
    }

    @Test
    fun explicitAliasCanonicalizesWithoutFuzzyMatching() {
        assertEquals("git.status", ToolRegistry.canonicalize("git_status"))
        assertEquals("mystery-status", ToolRegistry.canonicalize("mystery-status"))
    }
}
