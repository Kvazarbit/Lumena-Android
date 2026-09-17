package com.lumena.android.ollama

import com.lumena.android.history.ConversationPayload
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.*
import org.junit.Test

class NativeToolsTest {
    private fun call(name: String, args: Map<String, Any?>) = OllamaMessage("assistant", tool_calls = listOf(OllamaToolCall(OllamaFunctionCall(name, args))))
    @Test fun validCallIsCanonicalAndValidated() {
        val parsed = NativeTools.decode(call("file.read", mapOf("path" to "a.txt")))
        assertTrue(parsed is NativeAction.Tool)
        assertEquals("a.txt", (parsed as NativeAction.Tool).decision.args["path"])
    }
    @Test fun batchIsRejectedWithoutPickingFirstCall() {
        val one = call("health", emptyMap()).tool_calls!!
        assertTrue(NativeTools.decode(OllamaMessage("assistant", tool_calls = one + one)) is NativeAction.Invalid)
    }
    @Test fun unknownNestedAndUndeclaredArgumentsAreRejected() {
        assertTrue(NativeTools.decode(call("shell.exec", mapOf("cmd" to "ls"))) is NativeAction.Invalid)
        assertTrue(NativeTools.decode(call("file.read", mapOf("path" to mapOf("x" to "y")))) is NativeAction.Invalid)
        assertTrue(NativeTools.decode(call("file.read", mapOf("path" to "a", "extra" to "b"))) is NativeAction.Invalid)
    }
    @Test fun nativePlanIsNotAnExecution() {
        assertTrue(NativeTools.decode(call("agent.plan", mapOf("steps" to listOf("inspect", "verify")))) is NativeAction.Plan)
        assertTrue(NativeTools.decode(call("agent.finish", mapOf("summary" to ""))) is NativeAction.Invalid)
    }
    @Test fun historySerializationPreservesNativeCallsAndResults() {
        val history = listOf(call("file.read", mapOf("path" to "a.txt")), NativeTools.result("file.read", true, "hello"))
        val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(ConversationPayload::class.java)
        assertEquals(history, adapter.fromJson(adapter.toJson(ConversationPayload(history = history)))!!.history)
    }
    @Test fun compactionNeverSplitsCallResultPairOrTruncatesGoal() {
        val sys = OllamaMessage("system", "PINNED GOAL AND SAFETY")
        val requested = call("file.read", mapOf("path" to "a"))
        val result = NativeTools.result("file.read", true, "x".repeat(600))
        val newest = OllamaMessage("user", "next")
        val compact = OllamaContextWindow.compact(listOf(sys, requested, result, newest), 400)
        assertEquals(sys, compact.first())
        assertTrue(compact.none { it.role == "tool" || !it.tool_calls.isNullOrEmpty() })
        assertEquals(newest, compact.last())
    }
    @Test fun fallbackFlattensNativeWireFieldsButStorageRemainsTyped() {
        val original = listOf(call("health", emptyMap()), NativeTools.result("health", true, "OK"))
        val json = OllamaContextWindow.asJsonProtocol(original)
        assertEquals("user", json.last().role)
        assertTrue(json.all { it.tool_calls == null && it.tool_name == null })
        assertEquals("tool", original.last().role)
    }
}
