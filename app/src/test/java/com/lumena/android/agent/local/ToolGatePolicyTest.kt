package com.lumena.android.agent.local

import com.lumena.android.agent.core.AgentDecision
import com.lumena.android.agent.core.EffectiveTaskPolicyCompiler
import com.lumena.android.agent.core.HistoricalRecordOrigin
import com.lumena.android.agent.core.PolicyUnresolvedEffect
import com.lumena.android.agent.core.ContextKernel
import java.security.MessageDigest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolGatePolicyTest {
    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(
                value.toByteArray(
                    Charsets.UTF_8
                )
            )
            .joinToString("") {
                "%02x".format(
                    it.toInt() and 0xff
                )
            }

    @Test
    fun readOnlyCurrentInstructionBlocksMutationAtGate() {
        val policy =
            EffectiveTaskPolicyCompiler
                .compile(
                    rootGoal =
                        "Виправ aquarium.html",
                    currentInstruction =
                        "Тільки прочитай aquarium.html і нічого не змінюй."
                )
        val plan =
            ToolGate.plan(
                PlannerDecision(
                    request =
                        ToolRequest(
                            tool =
                                "file.write",
                            args =
                                mapOf(
                                    "path" to
                                        "aquarium.html",
                                    "content" to
                                        "bad"
                                )
                        ),
                    reason = "model request"
                ),
                taskPolicy = policy
            )

        assertFalse(plan.allowed)
        assertTrue(
            plan.reason.contains(
                "TASK_POLICY_EFFECT_NOT_ALLOWED"
            )
        )
    }

    @Test
    fun readOnlyCurrentInstructionStillAllowsRead() {
        val policy =
            EffectiveTaskPolicyCompiler
                .compile(
                    rootGoal =
                        "Виправ aquarium.html",
                    currentInstruction =
                        "Тільки прочитай aquarium.html і нічого не змінюй."
                )
        val plan =
            ToolGate.plan(
                PlannerDecision(
                    request =
                        ToolRequest(
                            tool =
                                "file.read",
                            args =
                                mapOf(
                                    "path" to
                                        "aquarium.html"
                                )
                        ),
                    reason = "read"
                ),
                taskPolicy = policy
            )

        assertTrue(plan.allowed)
        assertFalse(
            plan.requiresConfirmation
        )
    }

    @Test
    fun nestedMutationCannotHideInsideInspectBatch() {
        val policy =
            EffectiveTaskPolicyCompiler
                .compile(
                    rootGoal =
                        "Перевір файли",
                    currentInstruction =
                        "Тільки прочитай файли, нічого не змінюй."
                )
        val plan =
            ToolGate.plan(
                PlannerDecision(
                    request =
                        ToolRequest(
                            tool =
                                "inspect.batch",
                            args =
                                mapOf(
                                    "requests" to
                                        """[{"tool":"file.patch","args":{"path":"a.txt","old":"a","new":"b"}}]"""
                                )
                        ),
                    reason = "batch"
                ),
                taskPolicy = policy
            )

        assertFalse(plan.allowed)
        assertTrue(
            plan.reason.contains(
                "BATCH_CHILD_BLOCKED"
            )
        )
    }

    @Test
    fun unknownLocalWriteReplayIsBlockedAtGate() {
        val writeCall =
            AgentDecision.ToolCall(
                tool = "file.write",
                args =
                    mapOf(
                        "path" to
                            "demo.txt",
                        "content" to
                            "hello"
                    )
            )
        val policy =
            EffectiveTaskPolicyCompiler
                .compile(
                    rootGoal =
                        "Онови demo.txt",
                    currentInstruction =
                        "Запиши demo.txt",
                    unresolvedEffects =
                        listOf(
                            PolicyUnresolvedEffect(
                                sourceTaskRef =
                                    "a".repeat(64),
                                tool =
                                    "file.write",
                                requestSignature =
                                    ContextKernel
                                        .signature(
                                            writeCall
                                        ),
                                targetRef =
                                    sha256(
                                        "demo.txt"
                                    ),
                                origin =
                                    HistoricalRecordOrigin
                                        .LOCAL_CURRENT
                            )
                        )
                )
        val plan =
            ToolGate.plan(
                PlannerDecision(
                    request =
                        ToolRequest(
                            tool =
                                writeCall.tool,
                            args =
                                writeCall.args
                        ),
                    reason = "retry"
                ),
                taskPolicy = policy
            )

        assertFalse(plan.allowed)
        assertTrue(
            plan.reason.contains(
                "RECONCILIATION_REQUIRED"
            )
        )
    }
}
