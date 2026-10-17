package com.lumena.android.settings

import org.junit.Assert.*
import org.junit.Test

class SemanticMemoryPolicyTest {
    private fun rule(
        id: String, stage: CognitivePrincipleStage, scope: String? = null
    ) = CognitivePrinciple(
        id = id, stage = stage, codons = "CG0:OAV",
        mechanism = "Tool receipt does not prove goal success",
        condition = "Check independent postconditions", scopeHash = scope,
        toolPattern = "file.read>python.tests", evidenceIds = listOf("e-" + id),
        sourceTaskHashes = listOf("task-" + id)
    )

    @Test fun invalidNumbersAndZeroVectorsAreRejected() {
        listOf(listOf(0.0, 0.0), listOf(Double.NaN, 1.0),
            listOf(Double.POSITIVE_INFINITY, 1.0), listOf(1e12, 0.0),
            listOf(1.0)).forEach { values ->
            assertThrows(IllegalArgumentException::class.java) {
                SemanticMemoryPolicy.normalized(values)
            }
        }
    }

    @Test fun vectorsRoundTripAndNormalize() {
        val v = SemanticMemoryPolicy.normalized(listOf(3.0, 4.0, 0.0))
        assertArrayEquals(v, SemanticMemoryPolicy.unpack(SemanticMemoryPolicy.pack(v), 3), 0.00001f)
        assertEquals(1.0, v.sumOf { it.toDouble() * it }.let { kotlin.math.sqrt(it) }, 0.00001)
    }

    @Test fun negativeIsPreservedWhenPositiveRecordsDominate() {
        val many = (0..120).map { rule("p" + it, CognitivePrincipleStage.POSITIVE_CANDIDATE) }
        val chosen = SemanticMemoryPolicy.documents(
            many + rule("fail", CognitivePrincipleStage.NEGATIVE_DEAD_END), 5
        )
        assertTrue(chosen.any { it.id == "fail" })
        assertEquals(5, chosen.size)
    }

    @Test fun staleAndDeletedSourcesCannotBecomeResults() {
        val doc = SemanticMemoryPolicy.documents(listOf(
            rule("x", CognitivePrincipleStage.NEGATIVE_DEAD_END)
        )).single()
        val vector = SemanticVector(doc.id, doc.revision, floatArrayOf(1f, 0f))
        assertEquals(1, SemanticMemoryPolicy.rank(floatArrayOf(1f, 0f), listOf(vector), listOf(doc)).size)
        assertTrue(SemanticMemoryPolicy.rank(floatArrayOf(1f, 0f), listOf(vector),
            listOf(doc.copy(revision = "stale"))).isEmpty())
        assertTrue(SemanticMemoryPolicy.rank(floatArrayOf(1f, 0f), listOf(vector), emptyList()).isEmpty())
    }

    @Test fun scopedKnowledgeNeverLeaksToAnotherProject() {
        val doc = SemanticMemoryPolicy.documents(listOf(
            rule("x", CognitivePrincipleStage.CONTESTED, "project1")
        )).single()
        val v = SemanticVector(doc.id, doc.revision, floatArrayOf(1f, 0f))
        assertTrue(SemanticMemoryPolicy.rank(floatArrayOf(1f, 0f), listOf(v), listOf(doc)).isEmpty())
        assertTrue(SemanticMemoryPolicy.rank(floatArrayOf(1f, 0f), listOf(v),
            listOf(doc), scopeHash = "project2").isEmpty())
        assertEquals(1, SemanticMemoryPolicy.rank(floatArrayOf(1f, 0f), listOf(v),
            listOf(doc), scopeHash = "project1").size)
    }

    @Test fun evidenceOrCounterexamplesChangeIndexRevision() {
        val r = rule("x", CognitivePrincipleStage.CONTESTED)
        val before = SemanticMemoryPolicy.documents(listOf(r)).single()
        val after = SemanticMemoryPolicy.documents(
            listOf(r.copy(counterexampleRecordIds = listOf("negative-evidence")))
        ).single()
        assertNotEquals(before.revision, after.revision)
    }

    @Test fun noModelProseOrPrivateRecordIdsEnterEmbeddableText() {
        val p = rule("x", CognitivePrincipleStage.NEGATIVE_DEAD_END)
            .copy(sourceRecordIds = listOf("secret-record"), failureClasses = listOf("TIMEOUT"))
        val d = SemanticMemoryPolicy.documents(listOf(p)).single()
        assertTrue("TIMEOUT" in d.text)
        assertFalse("secret-record" in d.text)
    }

    @Test fun perfectMatchIsStillJustAConfidenceFreeShadowHint() {
        val doc = SemanticMemoryPolicy.documents(listOf(
            rule("x", CognitivePrincipleStage.CONTESTED)
        )).single()
        val hit = SemanticMemoryPolicy.rank(
            floatArrayOf(0.8f, 0.6f),
            listOf(SemanticVector(doc.id, doc.revision, floatArrayOf(0.8f, 0.6f))),
            listOf(doc)
        ).single()
        assertTrue(hit.score > 0.99)
        assertEquals(CognitivePrincipleStage.CONTESTED, hit.stage)
    }
}
