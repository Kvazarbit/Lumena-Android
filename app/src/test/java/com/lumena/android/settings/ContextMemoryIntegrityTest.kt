package com.lumena.android.settings

import org.junit.Assert.*
import org.junit.Test

class ContextMemoryIntegrityTest {
    private fun record(
        id: String, tools: List<String> = listOf("file.read", "python.run"),
        outcomes: List<Boolean> = listOf(true, true), dependency: String? = null
    ) = FractalExampleRecord(
        id, CoordinatorExampleKind.VERIFIED_SEQUENCE, "task-$id", tools,
        List(tools.size) { "path=private.py" }, outcomes, listOf("e-$id"),
        emptyList(), "project-a", 1000L, "UNTRUSTED private prose",
        dependencies = List(tools.size) { dependency }
    )

    @Test fun globalLegacyHashAndNullShareOneScopeWithoutCrossProjectTransfer() {
        val legacy = CoordinatorExperiencePolicy.hash("global")
        assertNull(ExperienceScope.normalized(legacy))
        assertTrue(ExperienceScope.visible(legacy, null))
        assertTrue(ExperienceScope.visible(null, "project-a"))
        assertFalse(ExperienceScope.visible("project-a", null))
        assertFalse(ExperienceScope.visible("project-a", "project-b"))
        val rules = CognitiveGenomePolicy.derive(FractalExperienceCanvasState(
            records = listOf(record("global").copy(scopeHash = legacy))
        ))
        assertTrue(rules.all { it.scopeHash == null })
    }

    @Test fun indexingEventuallyCoversEveryDocumentAndRequeuesChangedRevision() {
        val template = CognitiveGenomePolicy.contractCatalog().first()
        val documents = SemanticMemoryPolicy.documents((1..145).map {
            template.copy(id = "rule-$it")
        }, limit = 200)
        val cached = mutableMapOf<String, String>()
        repeat(4) {
            val batch = SemanticMemoryPolicy.pending(documents, cached)
            assertTrue(batch.none { cached[it.id] == it.revision })
            batch.forEach { cached[it.id] = it.revision }
        }
        assertEquals(documents.size, cached.size)
        assertTrue(SemanticMemoryPolicy.pending(documents, cached).isEmpty())
        val changed = documents.first().copy(revision = SemanticMemoryPolicy.hash("new conditions"))
        assertEquals(listOf(changed), SemanticMemoryPolicy.pending(listOf(changed) + documents.drop(1), cached))
    }

    @Test fun dependencyAndApplicabilityChangesInvalidateEvenTruncatedText() {
        val before = CognitiveGenomePolicy.contractCatalog().first().copy(mechanism = "x".repeat(900))
        val original = SemanticMemoryPolicy.documents(listOf(before)).single()
        val mutations = listOf(
            before.copy(dependencyFingerprints = listOf("changed")),
            before.copy(applicabilityFingerprint = "changed"),
            before.copy(conditions = ExperienceConditions(environmentFingerprint = "a".repeat(64)))
        )
        mutations.forEach { changed ->
            val doc = SemanticMemoryPolicy.documents(listOf(changed)).single()
            assertNotEquals(original.revision, doc.revision)
            assertTrue(SemanticMemoryPolicy.rank(floatArrayOf(1f, 0f),
                listOf(SemanticVector(original.id, original.revision, floatArrayOf(1f, 0f))),
                listOf(doc)).isEmpty())
        }
    }

    @Test fun contradictionIsDetectedAcrossDifferentSequencesForSameActionConditions() {
        val failure = record("failed", outcomes = listOf(true, false))
        val success = record("ok", tools = listOf("python.run"), outcomes = listOf(true))
        val rules = CognitiveGenomePolicy.derive(FractalExperienceCanvasState(records = listOf(failure, success)))
        val conflict = rules.single { it.stage == CognitivePrincipleStage.CONTESTED }
        assertEquals(listOf("failed"), conflict.sourceRecordIds)
        assertEquals(listOf("ok"), conflict.counterexampleRecordIds)
        assertTrue(rules.none { it.stage == CognitivePrincipleStage.POSITIVE_CANDIDATE })
    }

    @Test fun changingDependencyOrArgumentsIsNotAContradictionUnderSameConditions() {
        val failure = record("failed", outcomes = listOf(true, false), dependency = "missing dependency")
        val repaired = record("repaired", dependency = "installed dependency")
        val rules = CognitiveGenomePolicy.derive(FractalExperienceCanvasState(records = listOf(failure, repaired)))
        assertTrue(rules.none { it.stage == CognitivePrincipleStage.CONTESTED })
        assertTrue(rules.any { it.stage == CognitivePrincipleStage.NEGATIVE_DEAD_END })
        assertTrue(rules.any { it.stage == CognitivePrincipleStage.POSITIVE_CANDIDATE })
        val argsFailure = failure.copy(dependencies = emptyList(), argumentFingerprints = List(2) { "a".repeat(64) })
        val argsSuccess = repaired.copy(dependencies = emptyList(), argumentFingerprints = List(2) { "b".repeat(64) })
        assertTrue(CognitiveGenomePolicy.derive(FractalExperienceCanvasState(records = listOf(argsFailure, argsSuccess)))
            .none { it.stage == CognitivePrincipleStage.CONTESTED })
    }

    @Test fun structuredContextReachesEmbeddingWithoutPrivatePathsOrInventedCauses() {
        val source = record("typed").copy(conditions = ExperienceConditions(
            problemClass = "CODE_WORK", requiredPostconditions = listOf("PYTHON_TARGET_VERIFIED")
        ))
        val rule = CognitiveGenomePolicy.derive(FractalExperienceCanvasState(records = listOf(source)))
            .single { it.stage == CognitivePrincipleStage.POSITIVE_CANDIDATE }
        val text = SemanticMemoryPolicy.documents(listOf(rule)).single().text
        assertTrue("CODE_WORK" in text && "PYTHON_TARGET_VERIFIED" in text && "FILE_PY" in text)
        assertTrue("cause=UNKNOWN" in text)
        assertFalse("private.py" in text || "UNTRUSTED" in text)
        assertEquals(0, rule.independentGoalProofs)
    }

    @Test fun archiveRetainsOldNegativeBeyondWorkingCanvasAndPreservesRevisionHistory() {
        val negative = record("old-negative", outcomes = listOf(true, false))
        val positives = (1..600).map { record("positive-$it").copy(updatedAt = 1000L + it) }
        val archived = FractalEvidenceLedgerPolicy.append(FractalEvidenceLedgerState(), listOf(negative) + positives)
        val working = FractalExperienceCanvasPolicy.normalize(FractalExperienceCanvasState(
            records = listOf(negative) + positives))
        assertEquals(512, working.records.size)
        assertFalse(working.records.any { it.id == negative.id })
        assertEquals(601, archived.entries.size)
        val restored = FractalEvidenceLedgerCodec.decode(FractalEvidenceLedgerCodec.encode(archived))
        assertEquals(archived, restored)
        assertEquals(archived, FractalEvidenceLedgerPolicy.append(restored, listOf(negative)))
        val amended = negative.copy(updatedAt = 9000, dependencies = List(2) { "new dependency" })
        val revisions = FractalEvidenceLedgerPolicy.append(restored, listOf(amended))
        assertEquals(602, revisions.entries.size)
        assertEquals(negative, revisions.entries.first().record)
        assertEquals(amended, FractalEvidenceLedgerPolicy.latestRecords(revisions).first { it.id == negative.id })
        val rules = CognitiveGenomePolicy.derive(FractalExperienceCanvasState(
            records = FractalEvidenceLedgerPolicy.latestRecords(restored)))
        assertTrue(rules.any { negative.id in it.sourceRecordIds })
    }

    @Test fun corruptedLedgerAndCapacityFailWithoutErasingPreviousEvidence() {
        val state = FractalEvidenceLedgerPolicy.append(FractalEvidenceLedgerState(), listOf(record("source")))
        assertThrows(IllegalArgumentException::class.java) {
            FractalEvidenceLedgerPolicy.append(state,
                listOf(record("source", outcomes = listOf(true, false))))
        }
        val json = FractalEvidenceLedgerCodec.encode(state)
        assertThrows(IllegalArgumentException::class.java) {
            FractalEvidenceLedgerCodec.decode(json.replace("UNTRUSTED private prose", "edited prose"))
        }
        val full = state.copy(entries = List(FractalEvidenceLedgerPolicy.MAX_REVISIONS) { state.entries.single() })
        assertThrows(IllegalArgumentException::class.java) {
            FractalEvidenceLedgerPolicy.append(full, listOf(record("new")))
        }
        assertEquals("source", state.entries.single().record.id)
    }

    @Test fun stateVaultRoundTripIncludesLedgerAndOldArchivesRemainReadable() {
        val state = FractalEvidenceLedgerPolicy.append(FractalEvidenceLedgerState(), listOf(record("source")))
        val payload = mapOf("preferences.json" to "{}".toByteArray(),
            "files/lumena_fractal_evidence_ledger_v1.json" to FractalEvidenceLedgerCodec.encode(state).toByteArray())
        val copy = StateArchive.decode(StateArchive.encode(payload, 60, 1000), 60)
        assertEquals(state, FractalEvidenceLedgerCodec.decode(
            copy.entries.getValue("files/lumena_fractal_evidence_ledger_v1.json").toString(Charsets.UTF_8)))
        val old = payload - "files/lumena_fractal_evidence_ledger_v1.json"
        assertEquals(old.keys, StateArchive.decode(StateArchive.encode(old, 59, 1000), 60).entries.keys)
    }

    @Test fun loneFailureIsArchivedWithoutRequiringARecoverySequence() {
        val failure = CoordinatorEpisodeEvent("failure", "session", "task", "python.run",
            "script=demo.py", false, "e-failure", 1000, 0.8, failureClass = "TIMEOUT")
        val archived = FractalEvidenceLedgerPolicy.captureEvent(FractalEvidenceLedgerState(), failure)
        assertEquals(listOf(false), archived.entries.single().record.outcomes)
        val principles = CognitiveGenomePolicy.derive(FractalExperienceCanvasState(
            records = FractalEvidenceLedgerPolicy.latestRecords(archived)))
        assertTrue(principles.any { it.stage == CognitivePrincipleStage.NEGATIVE_DEAD_END })
        assertEquals(archived, FractalEvidenceLedgerPolicy.captureEvent(archived, failure))
        val migrated = FractalEvidenceLedgerPolicy.backfillCoordinator(FractalEvidenceLedgerState(), listOf(failure))
        assertEquals(1, migrated.entries.size)
        assertEquals(1, migrated.coordinatorBackfillVersion)
        assertEquals(migrated, FractalEvidenceLedgerPolicy.backfillCoordinator(migrated, listOf(failure)))
    }

    @Test fun unknownOutcomeSurvivesBackupButCannotBecomePositiveOrNegativeLearning() {
        val unknown = CoordinatorEpisodeEvent("unknown", "session", "task", "python.run",
            "script=demo.py", false, null, 1000, 0.8, outcomeKnown = false)
        val archived = FractalEvidenceLedgerPolicy.captureEvent(FractalEvidenceLedgerState(), unknown)
        assertTrue(archived.entries.isEmpty())
        assertEquals("UNKNOWN", archived.unresolved.single().observedOutcome)
        val restored = FractalEvidenceLedgerCodec.decode(FractalEvidenceLedgerCodec.encode(archived))
        assertEquals(archived, restored)
        assertEquals(4, CognitiveGenomePolicy.derive(FractalExperienceCanvasState(
            records = FractalEvidenceLedgerPolicy.latestRecords(restored))).size)
    }
}
