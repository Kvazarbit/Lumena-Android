package com.lumena.android.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinatorExperiencePolicyTest {
    private fun event(
        id: String,
        session: String,
        tool: String,
        target: String,
        ok: Boolean,
        at: Long,
        surprise: Double = 0.8,
        evidence: String? = "ev-$id",
        modelId: String? = null
    ) = CoordinatorEpisodeEvent(
        id = id,
        sessionId = session,
        taskId = session,
        tool = tool,
        target = target,
        ok = ok,
        experienceId = evidence,
        at = at,
        surprise = surprise,
        modelId = modelId
    )

    @Test
    fun repeatedSuccessBecomesLessSurprising() {
        var state = CoordinatorEpisodeState()
        val first = CoordinatorExperiencePolicy.surprise(
            state = state,
            sessionId = "s1",
            tool = "file.read",
            target = "path=README.md",
            ok = true
        )
        assertEquals(0.80, first, 0.0001)

        state = CoordinatorExperiencePolicy.record(
            state,
            event(
                id = "1",
                session = "s1",
                tool = "file.read",
                target = "path=README.md",
                ok = true,
                at = 100,
                surprise = first
            )
        )
        val second = CoordinatorExperiencePolicy.surprise(
            state = state,
            sessionId = "s1",
            tool = "file.read",
            target = "path=README.md",
            ok = true
        )
        assertTrue(second < first)
    }

    @Test
    fun outcomeFlipGetsMaximumSurprise() {
        val state = CoordinatorExperiencePolicy.record(
            CoordinatorEpisodeState(),
            event(
                id = "1",
                session = "s1",
                tool = "web.search",
                target = "query=latest news",
                ok = false,
                at = 100,
                surprise = 0.85
            )
        )

        val score = CoordinatorExperiencePolicy.surprise(
            state = state,
            sessionId = "s1",
            tool = "web.search",
            target = "query=latest news",
            ok = true
        )

        assertEquals(1.0, score, 0.0001)
    }

    @Test
    fun failureThenSuccessCreatesRecoveryExample() {
        var state = CoordinatorEpisodeState()
        state = CoordinatorExperiencePolicy.record(
            state,
            event("1", "s1", "file.read", "path=missing.txt", false, 100, 0.9)
        )
        state = CoordinatorExperiencePolicy.record(
            state,
            event("2", "s1", "workspace.list", "", true, 200, 0.8)
        )
        state = CoordinatorExperiencePolicy.record(
            state,
            event("3", "s1", "file.read", "path=missing.txt", true, 300, 1.0)
        )

        val examples = CoordinatorExperiencePolicy.examples(
            state = state,
            query = "missing workspace file",
            limit = 4
        )

        val recovery = examples.firstOrNull {
            it.kind == CoordinatorExampleKind.RECOVERY
        }
        assertTrue(recovery != null)
        requireNotNull(recovery)
        assertEquals(
            listOf("file.read", "workspace.list", "file.read"),
            recovery.tools
        )
        assertTrue(recovery.text.contains("RECOVERY EXAMPLE"))
        assertTrue(recovery.text.contains("not whole-goal proof"))
        assertTrue(recovery.text.contains("not permission"))
    }

    @Test
    fun successfulSequenceNeverClaimsWholeGoalSuccess() {
        val state = listOf(
            event("1", "s1", "workspace.list", "", true, 100, 0.8),
            event("2", "s1", "file.read", "path=README.md", true, 200, 0.5),
            event("3", "s1", "git.status", "cwd=@Lumena-Android", true, 300, 0.4)
        ).fold(CoordinatorEpisodeState()) { acc, item ->
            CoordinatorExperiencePolicy.record(acc, item)
        }

        val example = CoordinatorExperiencePolicy.examples(
            state = state,
            query = "read repository status",
            limit = 4
        ).first { it.kind == CoordinatorExampleKind.VERIFIED_SEQUENCE }

        val prompt = CoordinatorExperiencePolicy.formatForPrompt(example)
        assertTrue(prompt.contains("VERIFIED EXECUTION SEQUENCE"))
        assertTrue(prompt.contains("not whole-goal proof"))
        assertTrue(prompt.contains("not permission"))
        assertFalse(prompt.contains("task succeeded", ignoreCase = true))
    }

    @Test
    fun differentSessionsAreNeverSplicedIntoOneExample() {
        var state = CoordinatorEpisodeState()
        state = CoordinatorExperiencePolicy.record(
            state,
            event("1", "s1", "file.read", "path=a.txt", false, 100)
        )
        state = CoordinatorExperiencePolicy.record(
            state,
            event("2", "s2", "workspace.list", "", true, 200)
        )

        val examples = CoordinatorExperiencePolicy.examples(
            state = state,
            query = "",
            limit = 8
        )

        assertTrue(
            examples.none {
                it.tools == listOf("file.read", "workspace.list")
            }
        )
    }

    @Test
    fun recordIsIdempotentAndBounded() {
        var state = CoordinatorEpisodeState()
        val first = event("same", "s1", "file.read", "path=a", true, 1)
        state = CoordinatorExperiencePolicy.record(state, first)
        state = CoordinatorExperiencePolicy.record(state, first)
        assertEquals(1, state.events.size)

        for (i in 2..(CoordinatorExperiencePolicy.MAX_EVENTS + 20)) {
            state = CoordinatorExperiencePolicy.record(
                state,
                event(
                    id = "e$i",
                    session = "s$i",
                    tool = "file.read",
                    target = "path=$i",
                    ok = true,
                    at = i.toLong()
                )
            )
        }

        assertEquals(CoordinatorExperiencePolicy.MAX_EVENTS, state.events.size)
    }
    @Test
    fun sameProjectDifferentTasksAreNotSplicedIntoOneExample() {
        var state = CoordinatorEpisodeState()
        state = CoordinatorExperiencePolicy.record(
            state,
            event("1", "project", "file.read", "path=a.txt", false, 100)
                .copy(taskId = "task-a")
        )
        state = CoordinatorExperiencePolicy.record(
            state,
            event("2", "project", "file.read", "path=a.txt", true, 200)
                .copy(taskId = "task-b")
        )

        val examples = CoordinatorExperiencePolicy.examples(
            state = state,
            query = "file read a",
            limit = 8
        )

        assertTrue(
            examples.none { it.kind == CoordinatorExampleKind.RECOVERY }
        )
    }

    @Test
    fun distilledRecoveryExampleSurvivesRawEventCompaction() {
        var state = CoordinatorEpisodeState()
        state = CoordinatorExperiencePolicy.record(
            state,
            event(
                id = "fail",
                session = "project",
                tool = "file.read",
                target = "path=missing.txt",
                ok = false,
                at = 100,
                surprise = 0.9
            ).copy(taskId = "repair-file")
        )
        state = CoordinatorExperiencePolicy.record(
            state,
            event(
                id = "discover",
                session = "project",
                tool = "workspace.list",
                target = "",
                ok = true,
                at = 200,
                surprise = 0.8
            ).copy(taskId = "repair-file")
        )
        state = CoordinatorExperiencePolicy.record(
            state,
            event(
                id = "resolved",
                session = "project",
                tool = "file.read",
                target = "path=missing.txt",
                ok = true,
                at = 300,
                surprise = 1.0
            ).copy(taskId = "repair-file")
        )

        assertTrue(
            state.learnedExamples.any {
                it.kind == CoordinatorExampleKind.RECOVERY
            }
        )

        val compacted = state.copy(events = emptyList())
        val recovered = CoordinatorExperiencePolicy.examples(
            state = compacted,
            query = "missing file workspace",
            limit = 4
        )

        assertTrue(
            recovered.any {
                it.kind == CoordinatorExampleKind.RECOVERY &&
                    it.tools == listOf(
                        "file.read",
                        "workspace.list",
                        "file.read"
                    )
            }
        )
    }

    @Test
    fun learnedPlaybookProjectionIsBounded() {
        val learned = (1..(CoordinatorExperiencePolicy.MAX_LEARNED_EXAMPLES + 20))
            .map { index ->
                CoordinatorExecutionExample(
                    id = "example-$index",
                    kind = CoordinatorExampleKind.VERIFIED_SEQUENCE,
                    sourceSessionHash = CoordinatorExperiencePolicy
                        .hash("session-$index")
                        .take(16),
                    tools = listOf("workspace.list", "file.read"),
                    targets = listOf("", "path=$index"),
                    evidenceIds = listOf("e-$index"),
                    updatedAt = index.toLong(),
                    surprise = 0.4,
                    text = "fixture $index"
                )
            }

        val state = CoordinatorExperiencePolicy.record(
            CoordinatorEpisodeState(
                learnedExamples = learned
            ),
            event(
                id = "new-event",
                session = "new-session",
                tool = "workspace.list",
                target = "",
                ok = true,
                at = 10_000,
                surprise = 0.8
            )
        )

        assertTrue(
            state.learnedExamples.size <=
                CoordinatorExperiencePolicy.MAX_LEARNED_EXAMPLES
        )
    }

    @Test
    fun sourceSessionFilterAppliesBeforeLimit() {
        val targetHash = CoordinatorExperiencePolicy
            .hash("target|task-target")
            .take(16)

        val noisy = (1..80).map { index ->
            CoordinatorExecutionExample(
                id = "noise-$index",
                kind = CoordinatorExampleKind.VERIFIED_SEQUENCE,
                sourceSessionHash = CoordinatorExperiencePolicy
                    .hash("noise-$index")
                    .take(16),
                tools = listOf("workspace.list", "file.read"),
                targets = listOf("", "path=noise-$index"),
                evidenceIds = listOf("noise-e-$index"),
                updatedAt = (1_000 + index).toLong(),
                surprise = 1.0,
                text = "noise"
            )
        }
        val target = CoordinatorExecutionExample(
            id = "target",
            kind = CoordinatorExampleKind.RECOVERY,
            sourceSessionHash = targetHash,
            tools = listOf("file.read", "workspace.list", "file.read"),
            targets = listOf("path=a", "", "path=a"),
            evidenceIds = listOf("e1", "e2", "e3"),
            updatedAt = 10,
            surprise = 0.1,
            text = "target"
        )
        val state = CoordinatorEpisodeState(
            learnedExamples = noisy + target
        )

        val filtered = CoordinatorExperiencePolicy.examples(
            state = state,
            query = "",
            limit = 1,
            sourceSessionHash = targetHash
        )

        assertEquals(listOf("target"), filtered.map { it.id })
    }


    @Test
    fun recoveryExampleCarriesAllContributingModelIds() {
        var state = CoordinatorEpisodeState()
        state = CoordinatorExperiencePolicy.record(
            state,
            event(
                "m1",
                "project",
                "file.read",
                "path=a.txt",
                false,
                100,
                modelId = "model-a"
            ).copy(taskId = "task-a")
        )
        state = CoordinatorExperiencePolicy.record(
            state,
            event(
                "m2",
                "project",
                "workspace.list",
                "",
                true,
                200,
                modelId = "model-b"
            ).copy(taskId = "task-a")
        )
        state = CoordinatorExperiencePolicy.record(
            state,
            event(
                "m3",
                "project",
                "file.read",
                "path=a.txt",
                true,
                300,
                modelId = "model-b"
            ).copy(taskId = "task-a")
        )

        val recovery = CoordinatorExperiencePolicy.examples(
            state = state,
            query = "",
            limit = 8
        ).first { it.kind == CoordinatorExampleKind.RECOVERY }

        assertEquals(
            listOf("model-a", "model-b"),
            recovery.contributorModelIds
        )
    }


    @Test
    fun failedAttemptIsRetainedEvenWhenLaterAttemptRecovers() {
        val state = listOf(
            event("f1", "s", "web.search", "query=x", false, 1),
            event("f2", "s", "web.search", "query=x", false, 2),
            event("ok", "s", "web.search", "query=x", true, 3)
        ).fold(CoordinatorEpisodeState(), CoordinatorExperiencePolicy::record)
        val examples = CoordinatorExperiencePolicy.examples(state, "", 64)
        assertEquals(1, examples.count { it.kind.name == "FAILED_RECOVERY" })
        assertEquals(1, examples.count { it.kind == CoordinatorExampleKind.RECOVERY })
        assertTrue(examples.all { it.tools.size == 2 })
    }

    @Test
    fun missingIntermediateEvidenceCannotBecomeVerifiedRecovery() {
        val state = listOf(
            event("f", "s", "file.read", "path=x", false, 1),
            event("inspect", "s", "workspace.list", "", true, 2, evidence = null),
            event("ok", "s", "file.read", "path=x", true, 3)
        ).fold(CoordinatorEpisodeState(), CoordinatorExperiencePolicy::record)
        assertTrue(CoordinatorExperiencePolicy.examples(state, "", 64).isEmpty())
    }

}
