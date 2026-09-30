package com.lumena.android.agent.core

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalContractPolicyTest {
    private fun recorded(
        contract: GoalContract,
        call: AgentDecision.ToolCall,
        ok: Boolean,
        previousKernel: ContextKernelState = ContextKernelState()
    ): Pair<GoalContract, ContextKernelState> {
        val kernel = ContextKernel.record(
            previousKernel,
            call,
            ok,
            if (ok) "ok" else "failed"
        )
        return GoalContractPolicy.afterTool(
            contract = contract,
            call = call,
            ok = ok,
            kernel = kernel
        ) to kernel
    }

    @Test
    fun generalConversationHasNoSyntheticGoalCriteria() {
        val contract = GoalContractPolicy.initial(
            intent = TaskIntent.GENERAL,
            requiredTools = emptySet(),
            visualRequired = false
        )

        assertEquals(
            GoalContractCoverage.NONE,
            contract.coverage
        )
        assertTrue(contract.criteria.isEmpty())
        assertTrue(
            GoalContractPolicy.allMandatoryPassed(contract)
        )
    }

    @Test
    fun publicWebNeedsReadSourceNotOnlySearchSnippet() {
        var contract = GoalContractPolicy.initial(
            intent = TaskIntent.PUBLIC_WEB,
            requiredTools = emptySet(),
            visualRequired = false
        )
        var kernel = ContextKernelState()

        val search = AgentDecision.ToolCall(
            tool = "web.search",
            args = mapOf("query" to "current evidence")
        )
        val searchRecorded = recorded(
            contract,
            search,
            true,
            kernel
        )
        contract = searchRecorded.first
        kernel = searchRecorded.second

        assertTrue(
            contract.criteria
                .first { it.id == "operational-tool-evidence" }
                .status == CriterionStatus.PASSED
        )
        assertTrue(
            contract.criteria
                .first { it.id == "source-content-evidence" }
                .status == CriterionStatus.PENDING
        )
        assertFalse(
            GoalContractPolicy.allMandatoryPassed(contract)
        )

        val read = AgentDecision.ToolCall(
            tool = "web.read",
            args = mapOf(
                "url" to "https://example.com/source"
            )
        )
        val readRecorded = recorded(
            contract,
            read,
            true,
            kernel
        )
        contract = readRecorded.first

        val source = contract.criteria
            .first { it.id == "source-content-evidence" }
        assertEquals(
            CriterionStatus.PASSED,
            source.status
        )
        assertEquals(
            VerificationStrength.INDEPENDENT_TOOL_RESULT,
            source.evidence.single().strength
        )
        assertTrue(
            GoalContractPolicy.allMandatoryPassed(contract)
        )
    }

    @Test
    fun directoryListingGoalDoesNotInventFileContentCriterion() {
        val contract = GoalContractPolicy.initial(
            intent = TaskIntent.FILE_INSPECTION,
            requiredTools = emptySet(),
            visualRequired = false,
            goal = "покажи список файлів у папці"
        )

        assertTrue(
            contract.criteria.none {
                it.kind ==
                    CriterionKind.FILE_CONTENT_EVIDENCE
            }
        )
        assertTrue(
            contract.criteria.any {
                it.kind ==
                    CriterionKind.OPERATIONAL_TOOL_EVIDENCE
            }
        )
    }

    @Test
    fun fileInspectionNeedsRealFileRead() {
        var contract = GoalContractPolicy.initial(
            intent = TaskIntent.FILE_INSPECTION,
            requiredTools = emptySet(),
            visualRequired = false
        )
        var kernel = ContextKernelState()

        val list = AgentDecision.ToolCall(
            tool = "workspace.list"
        )
        val listed = recorded(
            contract,
            list,
            true,
            kernel
        )
        contract = listed.first
        kernel = listed.second

        assertFalse(
            GoalContractPolicy.allMandatoryPassed(contract)
        )

        val read = AgentDecision.ToolCall(
            tool = "file.read",
            args = mapOf("path" to "README.md")
        )
        val readResult = recorded(
            contract,
            read,
            true,
            kernel
        )
        contract = readResult.first

        assertTrue(
            GoalContractPolicy.allMandatoryPassed(contract)
        )
    }

    @Test
    fun codeWorkNeedsMutationOrExecutableEvidence() {
        var contract = GoalContractPolicy.initial(
            intent = TaskIntent.CODE_WORK,
            requiredTools = emptySet(),
            visualRequired = false
        )
        var kernel = ContextKernelState()

        val context = AgentDecision.ToolCall(
            tool = "context.snapshot"
        )
        val observed = recorded(
            contract,
            context,
            true,
            kernel
        )
        contract = observed.first
        kernel = observed.second

        assertEquals(
            CriterionStatus.PENDING,
            contract.criteria
                .first { it.id == "code-action-evidence" }
                .status
        )

        val write = AgentDecision.ToolCall(
            tool = "file.write",
            args = mapOf(
                "path" to "demo.html",
                "content" to "<p>ok</p>"
            )
        )
        contract = recorded(
            contract,
            write,
            true,
            kernel
        ).first

        assertEquals(
            CriterionStatus.PASSED,
            contract.criteria
                .first { it.id == "code-action-evidence" }
                .status
        )
    }

    @Test
    fun pythonMutationCreatesIndependentVerificationCriterion() {
        var contract = GoalContractPolicy.initial(
            intent = TaskIntent.CODE_WORK,
            requiredTools = emptySet(),
            visualRequired = false
        )
        var kernel = ContextKernelState()

        val write = AgentDecision.ToolCall(
            tool = "file.write",
            args = mapOf(
                "path" to "demo.py",
                "content" to "print('ok')"
            )
        )
        val written = recorded(
            contract,
            write,
            true,
            kernel
        )
        contract = written.first
        kernel = written.second

        val pythonCriterion = contract.criteria
            .first {
                it.kind ==
                    CriterionKind.PYTHON_TARGET_VERIFIED
            }
        assertEquals(
            CriterionStatus.PENDING,
            pythonCriterion.status
        )

        val unrelated = AgentDecision.ToolCall(
            tool = "python.syntax_check",
            args = mapOf("script" to "other.py")
        )
        val unrelatedResult = recorded(
            contract,
            unrelated,
            true,
            kernel
        )
        contract = unrelatedResult.first
        kernel = unrelatedResult.second

        assertEquals(
            CriterionStatus.PENDING,
            contract.criteria
                .first { it.id == pythonCriterion.id }
                .status
        )

        val verify = AgentDecision.ToolCall(
            tool = "python.syntax_check",
            args = mapOf("script" to "demo.py")
        )
        contract = recorded(
            contract,
            verify,
            true,
            kernel
        ).first

        val passed = contract.criteria
            .first { it.id == pythonCriterion.id }
        assertEquals(
            CriterionStatus.PASSED,
            passed.status
        )
        assertEquals(
            VerificationStrength.INDEPENDENT_TOOL_RESULT,
            passed.evidence.single().strength
        )
    }

    @Test
    fun rewritingVerifiedPythonTargetInvalidatesOldCriterion() {
        var contract = GoalContractPolicy.initial(
            intent = TaskIntent.CODE_WORK,
            requiredTools = emptySet(),
            visualRequired = false
        )
        var kernel = ContextKernelState()

        fun apply(
            call: AgentDecision.ToolCall,
            ok: Boolean = true
        ) {
            val result = recorded(
                contract,
                call,
                ok,
                kernel
            )
            contract = result.first
            kernel = result.second
        }

        apply(
            AgentDecision.ToolCall(
                "file.write",
                mapOf(
                    "path" to "demo.py",
                    "content" to "print(1)"
                )
            )
        )
        apply(
            AgentDecision.ToolCall(
                "python.syntax_check",
                mapOf("script" to "demo.py")
            )
        )
        assertTrue(
            contract.criteria
                .first {
                    it.kind ==
                        CriterionKind.PYTHON_TARGET_VERIFIED
                }
                .status == CriterionStatus.PASSED
        )

        apply(
            AgentDecision.ToolCall(
                "file.patch",
                mapOf(
                    "path" to "demo.py",
                    "old" to "1",
                    "new" to "2"
                )
            )
        )

        val invalidated = contract.criteria
            .first {
                it.kind ==
                    CriterionKind.PYTHON_TARGET_VERIFIED
            }
        assertEquals(
            CriterionStatus.PENDING,
            invalidated.status
        )
        assertTrue(invalidated.evidence.isEmpty())
    }

    @Test
    fun explicitRequiredToolNeedsExactSuccessfulTool() {
        var contract = GoalContractPolicy.initial(
            intent = TaskIntent.CODE_WORK,
            requiredTools = setOf("python.tests"),
            visualRequired = false
        )
        var kernel = ContextKernelState()

        val syntax = AgentDecision.ToolCall(
            "python.syntax_check",
            mapOf("script" to "demo.py")
        )
        val syntaxResult = recorded(
            contract,
            syntax,
            true,
            kernel
        )
        contract = syntaxResult.first
        kernel = syntaxResult.second

        assertEquals(
            CriterionStatus.PENDING,
            contract.criteria
                .first { it.id == "required-tool:python.tests" }
                .status
        )

        val tests = AgentDecision.ToolCall(
            "python.tests",
            mapOf("cwd" to ".")
        )
        contract = recorded(
            contract,
            tests,
            true,
            kernel
        ).first

        assertEquals(
            CriterionStatus.PASSED,
            contract.criteria
                .first { it.id == "required-tool:python.tests" }
                .status
        )
    }

    @Test
    fun oldTaskJsonDecodesWithEmptyGoalContract() {
        val json = """
            {
              "id":"old-task",
              "projectId":null,
              "goal":"old goal",
              "status":"WAITING_MODEL",
              "step":0,
              "maxSteps":4,
              "createdFiles":[],
              "modifiedFiles":[],
              "errors":[],
              "kernel":{"version":1}
            }
        """.trimIndent()

        val adapter = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
            .adapter(TaskState::class.java)
        val decoded = requireNotNull(
            adapter.fromJson(json)
        )

        assertEquals(
            GoalContractCoverage.NONE,
            decoded.goalContract.coverage
        )
        assertTrue(decoded.goalContract.criteria.isEmpty())
    }
}
