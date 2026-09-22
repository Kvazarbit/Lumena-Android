package com.lumena.android.settings

import android.content.Context
import android.util.AtomicFile
import com.lumena.android.agent.core.ConstitutionAuthority
import com.lumena.android.agent.core.ConstitutionDnaManifest
import com.lumena.android.agent.core.ConstitutionEvidenceRef
import com.lumena.android.agent.core.ConstitutionGenomePolicy
import com.lumena.android.agent.core.ConstitutionGenomeState
import com.lumena.android.agent.core.ConstitutionProvenance
import com.lumena.android.agent.core.ConstitutionRule
import com.lumena.android.agent.core.ConstitutionRuleKind
import com.lumena.android.agent.core.ConstitutionRuleStatus
import com.lumena.android.agent.core.ConstitutionScope
import com.lumena.android.agent.core.ConstitutionStance
import com.lumena.android.agent.core.ConstitutionScopeKind
import com.lumena.android.agent.core.TaskState
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File

data class ConstitutionGenomeStats(
    val total: Int,
    val hard: Int,
    val userConstraints: Int,
    val learned: Int,
    val contested: Int,
    val observations: Int,
    val candidates: Int,
    val revision: Long
)

object ConstitutionGenomeCodec {
    private val adapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(ConstitutionGenomeState::class.java)

    fun encode(state: ConstitutionGenomeState): String =
        adapter.toJson(state)

    fun decode(raw: String): ConstitutionGenomeState =
        requireNotNull(adapter.fromJson(raw)) {
            "Constitution genome is empty or invalid JSON"
        }
}

object ConstitutionGenomeRuntime {
    fun hydrate(
        persisted: ConstitutionGenomeState
    ): ConstitutionGenomeState {
        require(
            persisted.schemaVersion ==
                ConstitutionGenomePolicy.SCHEMA_VERSION
        ) {
            "Unsupported constitution genome schema " +
                persisted.schemaVersion
        }

        val hardSeeds = ConstitutionDnaManifest.hardInvariants()
        val hardIds = hardSeeds.map { it.id }.toSet()

        val nonHard = persisted.rules
            .asSequence()
            .filter { it.id !in hardIds }
            .filter {
                it.authority != ConstitutionAuthority.HARD_GUARD &&
                    it.status != ConstitutionRuleStatus.HARD_INVARIANT
            }
            .toList()

        val base = ConstitutionGenomeState(
            schemaVersion = ConstitutionGenomePolicy.SCHEMA_VERSION,
            rules = (hardSeeds + nonHard)
                .distinctBy { it.id }
                .take(ConstitutionGenomePolicy.MAX_RULES),
            revision = persisted.revision
        )

        val reconciled = ConstitutionGenomePolicy.view(base)
        return base.copy(rules = reconciled.rules)
    }

    fun scopeForTask(task: TaskState): ConstitutionScope =
        task.projectId
            ?.takeIf { it.isNotBlank() }
            ?.let {
                ConstitutionScope(
                    kind = ConstitutionScopeKind.PROJECT,
                    key = it.take(160)
                )
            }
            ?: ConstitutionScope(
                kind = ConstitutionScopeKind.GLOBAL,
                key = "lumena"
            )

    fun promptLines(
        state: ConstitutionGenomeState,
        task: TaskState,
        limit: Int = 6
    ): List<String> {
        val scope = scopeForTask(task)
        val effective = ConstitutionGenomePolicy.effectiveRules(
            hydrate(state),
            scope
        )

        val ordered = buildList {
            addAll(
                effective.filter {
                    it.authority ==
                        ConstitutionAuthority.USER_CONSTRAINT
                }
            )
            addAll(
                effective.filter {
                    it.status == ConstitutionRuleStatus.LEARNED
                }
            )
            addAll(
                effective.filter {
                    it.authority == ConstitutionAuthority.HARD_GUARD
                }
            )
        }

        return ordered
            .distinctBy { it.id }
            .take(limit.coerceIn(1, 16))
            .map(::formatRule)
    }

    private fun formatRule(rule: ConstitutionRule): String {
        val why = rule.rationale
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
            .take(360)

        val statement = rule.statement
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
            .take(420)

        return when (rule.authority) {
            ConstitutionAuthority.HARD_GUARD ->
                "HARD DNA [${rule.id}] · $statement · WHY: $why"

            ConstitutionAuthority.USER_CONSTRAINT ->
                "USER CONSTRAINT [${rule.id}] · $statement · WHY: $why"

            ConstitutionAuthority.ADVISORY ->
                "LEARNED CONSTITUTION [${rule.id}] " +
                    "(verified local evidence=${rule.evidenceRefs.count { it.promotionEligible() }}; " +
                    "advisory, not permission) · $statement · WHY: $why"
        }.take(900)
    }
}

/**
 * App-private, atomic store for the evolving Constitution Genome.
 *
 * Hard invariants are never trusted from disk. Every load rehydrates the
 * current code-owned ConstitutionDnaManifest and then appends only non-hard
 * persisted rules. A corrupt file fails closed instead of being silently
 * replaced with an empty constitution.
 */
object ConstitutionGenomeStore {
    private const val FILE_NAME =
        "lumena_constitution_genome.json"

    private val lock = Any()

    fun load(context: Context): ConstitutionGenomeState =
        synchronized(lock) {
            val file = atomicFile(context)

            if (
                !file.baseFile.exists() &&
                !File(file.baseFile.path + ".bak").exists()
            ) {
                return@synchronized ConstitutionGenomeRuntime.hydrate(
                    ConstitutionGenomeState()
                )
            }

            val parsed = try {
                ConstitutionGenomeCodec.decode(
                    file.openRead()
                        .bufferedReader()
                        .use { it.readText() }
                )
            } catch (failure: Exception) {
                throw IllegalStateException(
                    "Constitution genome is unreadable; " +
                        "refusing to replace constitutional history.",
                    failure
                )
            }

            ConstitutionGenomeRuntime.hydrate(parsed)
        }

    fun save(
        context: Context,
        state: ConstitutionGenomeState
    ) = synchronized(lock) {
        val hydrated =
            ConstitutionGenomeRuntime.hydrate(state)

        val file = atomicFile(context)
        val out = file.startWrite()
        try {
            out.write(
                ConstitutionGenomeCodec
                    .encode(hydrated)
                    .toByteArray(Charsets.UTF_8)
            )
            file.finishWrite(out)
        } catch (failure: Exception) {
            file.failWrite(out)
            throw failure
        }
    }

    fun addRule(
        context: Context,
        rule: ConstitutionRule
    ): ConstitutionGenomeState =
        synchronized(lock) {
            val current = load(context)
            val next = ConstitutionGenomePolicy.add(
                current,
                rule
            )
            save(context, next)
            next
        }

    fun recordEvidence(
        context: Context,
        ruleId: String,
        evidence: ConstitutionEvidenceRef,
        provenance: ConstitutionProvenance
    ): ConstitutionGenomeState =
        synchronized(lock) {
            val current = load(context)
            val next = ConstitutionGenomePolicy.recordEvidence(
                state = current,
                ruleId = ruleId,
                evidence = evidence,
                provenance = provenance
            )
            if (next != current) save(context, next)
            next
        }

    fun ingestVerifiedRecoveryExamples(
        context: Context,
        task: TaskState,
        examples: List<CoordinatorExecutionExample>
    ): ConstitutionGenomeState =
        synchronized(lock) {
            val current = load(context)
            val next =
                ConstitutionContributionPolicy.ingestVerifiedRecoveryExamples(
                    state = current,
                    task = task,
                    examples = examples
                )
            if (next != current) save(context, next)
            next
        }

    fun recordExplicitUserConstraint(
        context: Context,
        task: TaskState,
        sourceId: String,
        claimKey: String,
        statement: String,
        rationale: String,
        at: Long = System.currentTimeMillis(),
        stance: ConstitutionStance = ConstitutionStance.AFFIRM
    ): ConstitutionRule =
        synchronized(lock) {
            val rule =
                ConstitutionContributionPolicy.explicitUserConstraint(
                    task = task,
                    sourceId = sourceId,
                    claimKey = claimKey,
                    statement = statement,
                    rationale = rationale,
                    at = at,
                    stance = stance
                )
            val current = load(context)
            val next = ConstitutionGenomePolicy.add(
                state = current,
                rule = rule
            )
            save(context, next)
            next.rules.first { it.id == rule.id }
        }

    fun recordModelObservation(
        context: Context,
        task: TaskState,
        modelId: String,
        sourceId: String,
        claimKey: String,
        kind: ConstitutionRuleKind,
        statement: String,
        rationale: String,
        at: Long = System.currentTimeMillis(),
        stance: ConstitutionStance = ConstitutionStance.AFFIRM
    ): ConstitutionRule =
        synchronized(lock) {
            val rule =
                ConstitutionContributionPolicy.modelObservation(
                    task = task,
                    modelId = modelId,
                    sourceId = sourceId,
                    claimKey = claimKey,
                    kind = kind,
                    statement = statement,
                    rationale = rationale,
                    at = at,
                    stance = stance
                )
            val current = load(context)
            val next = ConstitutionGenomePolicy.add(
                state = current,
                rule = rule
            )
            save(context, next)
            next.rules.first { it.id == rule.id }
        }

    fun supersedeAdvisory(
        context: Context,
        oldRuleId: String,
        replacementRuleId: String,
        at: Long = System.currentTimeMillis()
    ): ConstitutionGenomeState =
        synchronized(lock) {
            val current = load(context)
            val next =
                ConstitutionGenomePolicy.supersedeAdvisory(
                    state = current,
                    oldRuleId = oldRuleId,
                    replacementRuleId = replacementRuleId,
                    at = at
                )
            save(context, next)
            next
        }

    fun relevant(
        context: Context,
        task: TaskState,
        limit: Int = 6
    ): List<String> =
        synchronized(lock) {
            ConstitutionGenomeRuntime.promptLines(
                state = load(context),
                task = task,
                limit = limit
            )
        }

    fun stats(context: Context): ConstitutionGenomeStats =
        synchronized(lock) {
            val state = load(context)
            val rules = ConstitutionGenomePolicy.view(state).rules

            ConstitutionGenomeStats(
                total = rules.size,
                hard = rules.count {
                    it.status ==
                        ConstitutionRuleStatus.HARD_INVARIANT
                },
                userConstraints = rules.count {
                    it.status ==
                        ConstitutionRuleStatus.ACTIVE_USER_CONSTRAINT
                },
                learned = rules.count {
                    it.status ==
                        ConstitutionRuleStatus.LEARNED
                },
                contested = rules.count {
                    it.status ==
                        ConstitutionRuleStatus.CONTESTED
                },
                observations = rules.count {
                    it.status ==
                        ConstitutionRuleStatus.OBSERVATION
                },
                candidates = rules.count {
                    it.status ==
                        ConstitutionRuleStatus.CANDIDATE
                },
                revision = state.revision
            )
        }

    private fun atomicFile(context: Context) =
        AtomicFile(
            File(
                context.applicationContext.filesDir,
                FILE_NAME
            )
        )
}
