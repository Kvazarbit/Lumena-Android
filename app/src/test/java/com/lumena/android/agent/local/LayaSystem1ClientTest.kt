package com.lumena.android.agent.local

import com.lumena.android.agent.core.BridgeTransportPolicy
import com.lumena.android.agent.core.EffectClass
import com.lumena.android.agent.core.FailureClass
import com.lumena.android.agent.core.FailureEvent
import com.lumena.android.agent.core.FailureSource
import com.lumena.android.agent.core.ReflexCandidateSet
import com.lumena.android.agent.core.ReflexOption
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayaSystem1ClientTest {
    private fun event() = FailureEvent(
        source = FailureSource.TOOL,
        failureClass = FailureClass.STATE_DRIFT,
        retryable = false,
        effectClass = EffectClass.READ_ONLY,
        dependency = "filesystem",
        evidence = "raw user-facing detail is intentionally not forwarded",
        actionFamily = "file.read",
        attempt = 1
    )

    private fun candidates() = ReflexCandidateSet(
        allowed = linkedSetOf(
            ReflexOption.TRY_ALTERNATIVE,
            ReflexOption.ASK_PLANNER,
            ReflexOption.STOP
        ),
        constitutionalAnchor = ReflexOption.TRY_ALTERNATIVE,
        reason = "Use another verified read path."
    )

    @Test
    fun typedDecisionUsesOnlyConstitutionalCandidates() = runBlocking {
        var captured: ToolRequest? = null
        val bridge = object : ToolExecutor {
            override suspend fun execute(
                toolRequest: ToolRequest
            ): ToolResult {
                captured = toolRequest
                return ToolResult(
                    ok = true,
                    tool = toolRequest.tool,
                    stdout = """
                        {
                          "model":"laya-rl-agent",
                          "answers":{
                            "recovery":{
                              "type":"choice",
                              "choice":"TRY_ALTERNATIVE",
                              "probabilities":{
                                "TRY_ALTERNATIVE":0.82,
                                "ASK_PLANNER":0.13,
                                "STOP":0.05
                              },
                              "confidence":0.57
                            }
                          }
                        }
                    """.trimIndent()
                )
            }
        }

        val result = LayaSystem1Client(bridge)
            .predictReflex(event(), candidates())

        assertTrue(result.ok)
        assertEquals(
            ReflexOption.TRY_ALTERNATIVE,
            result.decision?.option
        )
        assertEquals(
            candidates().allowed,
            result.decision?.probabilities?.keys
        )
        assertEquals("laya.predict", captured?.tool)

        val requestJson =
            captured?.args?.get("request").orEmpty()
        assertTrue(
            requestJson.contains(
                "\"constitutional_anchor\":\"TRY_ALTERNATIVE\""
            )
        )
        assertTrue(
            requestJson.contains(
                "\"ASK_PLANNER\""
            )
        )
        assertFalse(
            requestJson.contains(
                event().evidence
            )
        )
    }

    @Test
    fun responseOutsideConstitutionalSetFailsClosed() = runBlocking {
        val bridge = object : ToolExecutor {
            override suspend fun execute(
                toolRequest: ToolRequest
            ): ToolResult = ToolResult(
                ok = true,
                stdout = """
                    {
                      "model":"laya-rl-agent",
                      "answers":{
                        "recovery":{
                          "type":"choice",
                          "choice":"RETRY_VARIANT",
                          "probabilities":{
                            "RETRY_VARIANT":0.90,
                            "TRY_ALTERNATIVE":0.05,
                            "STOP":0.05
                          },
                          "confidence":0.71
                        }
                      }
                    }
                """.trimIndent()
            )
        }

        val result = LayaSystem1Client(bridge)
            .predictReflex(event(), candidates())

        assertFalse(result.ok)
        assertEquals(
            "LAYA_RESPONSE_INVALID",
            result.errorCode
        )
        assertTrue(result.decision == null)
    }

    @Test
    fun internalPredictionAndStatusAreRetrySafeButStartIsNot() {
        assertTrue(
            BridgeTransportPolicy.canRetry(
                "laya.predict"
            )
        )
        assertTrue(
            BridgeTransportPolicy.canRetry(
                "laya.status"
            )
        )
        assertFalse(
            BridgeTransportPolicy.canRetry(
                "laya.start"
            )
        )
        assertTrue(
            BridgeTransportPolicy.outcomeUnknown(
                "laya.start"
            )
        )
    }
}
