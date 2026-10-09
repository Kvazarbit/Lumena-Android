package com.lumena.android.agent.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The charter is only golden if it cannot rot: every named guard and
 * regression test must exist, every hard invariant and capsule line must
 * belong to an article, and an article may claim ENFORCED only with proof.
 */
class ConstitutionCharterTest {
    private val appDir: File = listOf(File("."), File("app"))
        .map { it.canonicalFile }
        .first { File(it, "src/test/java").isDirectory }
    private val repoRoot: File = requireNotNull(appDir.parentFile)

    private val testSources: Map<String, List<String>> by lazy {
        File(appDir, "src/test/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .groupBy({ it.nameWithoutExtension }, { it.readText() })
    }
    private val mainSources: String by lazy {
        File(appDir, "src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }
    }

    private fun testExists(ref: String): Boolean {
        val owner = ref.substringBefore('.')
        val method = ref.substringAfter('.', missingDelimiterValue = "")
        if (method.isEmpty()) return false
        val pattern = Regex("""fun\s+`?${Regex.escape(method)}`?\s*\(""")
        return testSources[owner].orEmpty().any { pattern.containsMatchIn(it) }
    }

    private fun guardExists(point: String): Boolean {
        if ('/' in point) return File(repoRoot, point).isFile
        val name = point.substringBefore('.')
        return Regex("""\b(class|object|interface)\s+${Regex.escape(name)}\b""").containsMatchIn(mainSources)
    }

    @Test fun articleIdsAreUniqueAndStatementsAreShort() {
        val ids = ConstitutionCharter.articles.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        ConstitutionCharter.articles.forEach { article ->
            assertTrue(article.id, article.statement.isNotBlank() && article.rationale.isNotBlank())
            assertTrue(
                "${article.id} statement is too long for phone context",
                article.statement.length <= ConstitutionCharter.MAX_STATEMENT_CHARS
            )
        }
    }

    @Test fun enforcedArticlesNameRealGuardsAndRealTests() {
        ConstitutionCharter.articles
            .filter { it.enforcement != CharterEnforcement.PENDING }
            .forEach { article ->
                assertTrue("${article.id} has no guard", article.enforcementPoints.isNotEmpty())
                assertTrue("${article.id} has no regression test", article.testRefs.isNotEmpty())
                article.enforcementPoints.forEach { point ->
                    assertTrue("${article.id}: guard $point does not exist", guardExists(point))
                }
                article.testRefs.forEach { ref ->
                    assertTrue("${article.id}: test $ref does not exist", testExists(ref))
                }
            }
    }

    @Test fun statusIsHonest() {
        ConstitutionCharter.articles.forEach { article ->
            when (article.enforcement) {
                CharterEnforcement.ENFORCED -> assertNull("${article.id} is enforced but names a gap", article.gap)
                CharterEnforcement.PARTIAL -> assertFalse("${article.id} must name its gap", article.gap.isNullOrBlank())
                CharterEnforcement.PENDING -> {
                    assertFalse("${article.id} must name its gap", article.gap.isNullOrBlank())
                    assertTrue("${article.id} is pending but claims tests", article.testRefs.isEmpty())
                }
            }
        }
    }

    @Test fun everyHardInvariantBelongsToExactlyOneArticle() {
        val linked = ConstitutionCharter.articles.flatMap { it.invariantIds }
        val hard = ConstitutionDnaManifest.hardInvariants().map { it.id }
        assertEquals(hard.toSet(), linked.toSet())
        assertEquals("an invariant is linked twice", linked.size, linked.toSet().size)
    }

    @Test fun hardInvariantReferencesStayAlive() {
        // The manifest test only checks the lists are non-empty; a renamed
        // test or class would silently detach a hard law from its proof.
        ConstitutionDnaManifest.hardInvariants().forEach { rule ->
            rule.testRefs.forEach { assertTrue("${rule.id}: test $it does not exist", testExists(it)) }
            rule.enforcementPoints.forEach { assertTrue("${rule.id}: guard $it does not exist", guardExists(it)) }
        }
    }

    @Test fun everyCapsuleLineIsAnArticle() {
        val capsuleKeys = ConstitutionCapsule.prompt().lines()
            .mapNotNull { Regex("""^([A-Z]):""").find(it.trim())?.groupValues?.get(1) }
            .toSet()
        val mapped = ConstitutionCharter.articles.flatMap { it.capsuleKeys }.toSet()
        assertTrue(capsuleKeys.isNotEmpty())
        assertEquals(capsuleKeys, mapped)
    }

    @Test fun promptCarriesModelFacingLawWithinBudget() {
        val prompt = ConstitutionCharter.prompt()
        assertTrue(prompt.startsWith("LUMENA CHARTER ${ConstitutionCharter.VERSION}"))
        assertTrue(prompt.length <= ConstitutionCharter.MAX_PROMPT_CHARS)
        ConstitutionCharter.articles.forEach { article ->
            assertEquals(article.id, article.modelFacing, prompt.contains("${article.id}: "))
        }
        assertTrue(prompt.contains("ART-9"))
    }

    @Test fun everyModelSeesTheCharterInTheSystemPrompt() {
        assertTrue(
            com.lumena.android.ollama.LocalWorkflowAgent.systemPrompt
                .contains(ConstitutionCharter.prompt())
        )
    }

    @Test fun externalCoordinatorReceivesTheSameCharter() {
        val handshake = com.lumena.android.companion.CompanionProtocol.handshakeText
        assertTrue(handshake.contains(ConstitutionCharter.prompt()))
        assertTrue(handshake.contains(ConstitutionCharter.hash()))
    }

    @Test fun fingerprintIsStableAndSummaryCountsEveryArticle() {
        val hash = ConstitutionCharter.hash()
        assertEquals(hash, ConstitutionCharter.hash())
        assertTrue(Regex("^[0-9a-f]{16}$").matches(hash))
        val summary = ConstitutionCharter.summary()
        assertEquals(ConstitutionCharter.articles.size, summary.enforced + summary.partial + summary.pending)
        assertTrue(ConstitutionCharter.statusLine().contains(hash))
        assertNotNull(ConstitutionCharter.article("ART-1"))
        assertNull(ConstitutionCharter.article("ART-404"))
    }

    /**
     * Charter lock (ART-1): any change to a law changes the fingerprint, so CI
     * fails until the lock is updated on purpose together with an
     * owner-reviewed charter change.
     */
    @Test fun charterChangesRequireAnExplicitOwnerLockUpdate() {
        assertEquals(
            "The charter changed. Only if the owner approved it, update CHARTER_LOCK in this test.",
            CHARTER_LOCK,
            ConstitutionCharter.hash()
        )
    }

    @Test fun ownerSovereigntyAndLawmakingAreBothPresent() {
        val parts = ConstitutionCharter.articles.map { it.part }.toSet()
        assertEquals(CharterPart.entries.toSet(), parts)
        assertEquals(CharterPart.SOVEREIGNTY, ConstitutionCharter.article("ART-1")?.part)
    }

    private companion object {
        const val CHARTER_LOCK = "44ef4c47b90d03a9"
    }
}
