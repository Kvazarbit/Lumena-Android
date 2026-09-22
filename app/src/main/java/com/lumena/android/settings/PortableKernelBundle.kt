package com.lumena.android.settings

import android.content.Context
import android.os.Build
import com.lumena.android.agent.core.ConstitutionCapsule
import com.lumena.android.agent.core.CoreDna
import com.lumena.android.agent.core.ToolRegistry
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min

data class PortableExperienceSeed(
    val signature: String,
    val tool: String,
    val target: String,
    val occurrences: Int,
    val firstSeenAt: Long,
    val lastSeenAt: Long
)

data class PortableRuleSeed(
    val id: String,
    val kind: String,
    val text: String,
    val intent: String,
    val sourceScopeHash: String
)

data class PortableExecutionExampleSeed(
    val id: String,
    val kind: String,
    val sourceSessionHash: String,
    val tools: List<String>,
    val targets: List<String>,
    val evidenceIds: List<String>,
    val updatedAt: Long,
    val surprise: Double
)

data class PortableKernelPayload(
    val schemaVersion: Int = PortableKernelPolicy.SCHEMA_VERSION,
    val coreDnaVersion: String,
    val constitutionCapsuleVersion: String,
    val coordinatorContractVersion: String,
    val exportedAt: Long,
    val sourceAppVersionCode: Long,
    val sourceDeviceHash: String,
    val requiresLocalRevalidation: Boolean = true,
    val positiveExperience: List<PortableExperienceSeed> = emptyList(),
    val dormantRules: List<PortableRuleSeed> = emptyList(),
    val executionExamples: List<PortableExecutionExampleSeed> = emptyList()
)

data class PortableKernelEnvelope(
    val payload: PortableKernelPayload,
    val payloadSha256: String
)

data class PortableKernelImportResult(
    val positiveExperience: Int,
    val dormantRules: Int,
    val executionExamples: Int,
    val sourceDeviceHash: String,
    val sourceCoreDnaVersion: String,
    val sourceCapsuleVersion: String,
    val versionsMatchCurrentRuntime: Boolean
)

object PortableKernelPolicy {
    const val SCHEMA_VERSION = 2
    val SUPPORTED_SCHEMA_VERSIONS = setOf(1, 2)
    const val COORDINATOR_CONTRACT_VERSION = "lumena-coordinator-v2"
    const val MAX_EXPERIENCE = 256
    const val MAX_DORMANT_RULES = 128
    const val MAX_EXECUTION_EXAMPLES = 128

    fun buildPayload(
        localAnchors: List<ExperienceAnchor>,
        localRules: List<LandscapeRule>,
        imported: PortableKernelPayload?,
        exportedAt: Long,
        sourceAppVersionCode: Long,
        sourceDeviceHash: String,
        localExecutionExamples: List<CoordinatorExecutionExample> = emptyList()
    ): PortableKernelPayload {
        val localPositive = localAnchors
            .asSequence()
            .filter { it.valence == ExperienceValence.POSITIVE }
            .filter { ToolRegistry.get(it.tool) != null }
            .map {
                PortableExperienceSeed(
                    signature = it.signature,
                    tool = ToolRegistry.canonicalize(it.tool),
                    target = it.target.take(512),
                    occurrences = it.occurrences.coerceAtLeast(1),
                    firstSeenAt = it.firstSeenAt,
                    lastSeenAt = it.lastSeenAt
                )
            }
            .toList()

        val experience = mergeExperience(
            imported.orEmptyExperience() + localPositive
        ).take(MAX_EXPERIENCE)

        val localDormant = localRules
            .asSequence()
            .filter { it.status == LandscapeRuleStatus.ACTIVE }
            .filter { it.kind == "PREFER" }
            .map {
                PortableRuleSeed(
                    id = it.id,
                    kind = it.kind,
                    text = it.text.take(700),
                    intent = it.intent.take(120),
                    sourceScopeHash = it.scope.take(120)
                )
            }
            .toList()

        val dormant = (imported?.dormantRules.orEmpty() + localDormant)
            .filter { it.id.isNotBlank() && it.kind == "PREFER" }
            .distinctBy { it.id }
            .take(MAX_DORMANT_RULES)

        val localPortableExamples = localExecutionExamples
            .asSequence()
            .filter { it.tools.isNotEmpty() }
            .filter { it.evidenceIds.isNotEmpty() }
            .filter { it.tools.all { tool -> ToolRegistry.get(tool) != null } }
            .map { example ->
                PortableExecutionExampleSeed(
                    id = example.id.take(128),
                    kind = example.kind.name,
                    sourceSessionHash = example.sourceSessionHash.take(64),
                    tools = example.tools.map(ToolRegistry::canonicalize).take(6),
                    targets = example.targets.map { it.take(220) }.take(6),
                    evidenceIds = example.evidenceIds.map { it.take(160) }.take(16),
                    updatedAt = example.updatedAt,
                    surprise = example.surprise.coerceIn(0.0, 1.0)
                )
            }
            .toList()

        val executionExamples = mergeExecutionExamples(
            imported?.executionExamples.orEmpty() + localPortableExamples
        ).take(MAX_EXECUTION_EXAMPLES)

        return PortableKernelPayload(
            coreDnaVersion = CoreDna.VERSION,
            constitutionCapsuleVersion = ConstitutionCapsule.VERSION,
            coordinatorContractVersion = COORDINATOR_CONTRACT_VERSION,
            exportedAt = exportedAt,
            sourceAppVersionCode = sourceAppVersionCode,
            sourceDeviceHash = sourceDeviceHash,
            requiresLocalRevalidation = true,
            positiveExperience = experience,
            dormantRules = dormant,
            executionExamples = executionExamples
        )
    }

    fun advice(
        payload: PortableKernelPayload,
        query: String,
        limit: Int = 3
    ): List<String> {
        val tokens = tokenize(query)

        data class AdviceCandidate(
            val line: String,
            val overlap: Int,
            val updatedAt: Long,
            val weight: Double
        )

        val experienceCandidates = payload.positiveExperience
            .asSequence()
            .filter { ToolRegistry.get(it.tool) != null }
            .map { seed ->
                val searchable = tokenize(seed.tool + " " + seed.target)
                val overlap = searchable.count { it in tokens }
                AdviceCandidate(
                    line = "PORTABLE VERIFIED EXPERIENCE (source-device evidence; revalidate locally; not permission) · " +
                        "${seed.tool} · target=${sanitize(seed.target, 180)} · seen=${seed.occurrences}x",
                    overlap = overlap,
                    updatedAt = seed.lastSeenAt,
                    weight = seed.occurrences.coerceAtMost(20) / 20.0
                )
            }

        val executionCandidates = payload.executionExamples
            .asSequence()
            .filter { seed ->
                seed.tools.isNotEmpty() &&
                    seed.tools.all { ToolRegistry.get(it) != null }
            }
            .map { seed ->
                val searchable = tokenize(
                    seed.tools.joinToString(" ") + " " +
                        seed.targets.joinToString(" ")
                )
                val overlap = searchable.count { it in tokens }
                AdviceCandidate(
                    line = formatExecutionExample(seed),
                    overlap = overlap,
                    updatedAt = seed.updatedAt,
                    weight = seed.surprise
                )
            }

        return (experienceCandidates + executionCandidates)
            .filter { tokens.isEmpty() || it.overlap > 0 }
            .sortedWith(
                compareByDescending<AdviceCandidate> {
                    it.overlap * 100 + (it.weight * 20).toInt()
                }.thenByDescending { it.updatedAt }
            )
            .take(limit.coerceIn(1, 8))
            .map { it.line }
            .toList()
    }

    fun validate(payload: PortableKernelPayload) {
        require(payload.schemaVersion in SUPPORTED_SCHEMA_VERSIONS) {
            "Unsupported portable-kernel schema ${payload.schemaVersion}; supported=$SUPPORTED_SCHEMA_VERSIONS"
        }
        require(payload.requiresLocalRevalidation) {
            "Portable experience must require local revalidation"
        }
        require(payload.coreDnaVersion.isNotBlank())
        require(payload.constitutionCapsuleVersion.isNotBlank())
        require(payload.coordinatorContractVersion.isNotBlank())
        require(payload.exportedAt > 0)
        require(payload.sourceAppVersionCode > 0)
        require(payload.sourceDeviceHash.matches(Regex("[0-9a-f]{16,64}"))) {
            "Invalid source-device hash"
        }
        require(payload.positiveExperience.size <= MAX_EXPERIENCE)
        require(payload.dormantRules.size <= MAX_DORMANT_RULES)
        require(payload.executionExamples.size <= MAX_EXECUTION_EXAMPLES)

        payload.positiveExperience.forEach { seed ->
            require(seed.signature.length in 8..128)
            require(seed.tool.length in 1..128)
            require(ToolRegistry.get(seed.tool) != null) {
                "Portable experience references unknown tool: ${seed.tool}"
            }
            require(seed.target.length <= 512)
            require(seed.occurrences in 1..1_000_000)
            require(seed.firstSeenAt > 0)
            require(seed.lastSeenAt >= seed.firstSeenAt)
        }

        payload.dormantRules.forEach { rule ->
            require(rule.id.length in 1..128)
            require(rule.kind == "PREFER") {
                "Only positive PREFER rules are portable in schema v$SCHEMA_VERSION"
            }
            require(rule.text.length <= 700)
            require(rule.intent.length <= 120)
            require(rule.sourceScopeHash.length <= 120)
        }

        payload.executionExamples.forEach { example ->
            require(example.id.length in 1..128)
            require(
                example.kind == CoordinatorExampleKind.RECOVERY.name ||
                    example.kind == CoordinatorExampleKind.VERIFIED_SEQUENCE.name
            )
            require(example.sourceSessionHash.matches(Regex("[0-9a-f]{8,64}")))
            require(example.tools.isNotEmpty() && example.tools.size <= 6)
            require(example.tools.all { ToolRegistry.get(it) != null }) {
                "Portable execution example references unknown tool"
            }
            require(example.targets.size <= 6)
            require(example.targets.all { it.length <= 220 })
            require(example.evidenceIds.size <= 16)
            require(example.evidenceIds.all { it.length <= 160 })
            require(example.updatedAt > 0)
            require(example.surprise in 0.0..1.0)
        }
    }

    fun versionsMatchCurrentRuntime(payload: PortableKernelPayload): Boolean =
        payload.coreDnaVersion == CoreDna.VERSION &&
            payload.constitutionCapsuleVersion == ConstitutionCapsule.VERSION &&
            payload.coordinatorContractVersion == COORDINATOR_CONTRACT_VERSION

    fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun mergeExperience(
        seeds: List<PortableExperienceSeed>
    ): List<PortableExperienceSeed> {
        val merged = linkedMapOf<String, PortableExperienceSeed>()
        seeds.forEach { seed ->
            if (ToolRegistry.get(seed.tool) == null) return@forEach
            val key = "${seed.signature}|${ToolRegistry.canonicalize(seed.tool)}|${seed.target}"
            val previous = merged[key]
            merged[key] = if (previous == null) {
                seed.copy(
                    tool = ToolRegistry.canonicalize(seed.tool),
                    occurrences = seed.occurrences.coerceAtLeast(1)
                )
            } else {
                previous.copy(
                    occurrences = max(previous.occurrences, seed.occurrences),
                    firstSeenAt = min(previous.firstSeenAt, seed.firstSeenAt),
                    lastSeenAt = max(previous.lastSeenAt, seed.lastSeenAt)
                )
            }
        }
        return merged.values
            .sortedWith(
                compareByDescending<PortableExperienceSeed> { it.lastSeenAt }
                    .thenByDescending { it.occurrences }
            )
    }

    private fun mergeExecutionExamples(
        seeds: List<PortableExecutionExampleSeed>
    ): List<PortableExecutionExampleSeed> =
        seeds
            .filter { seed ->
                seed.id.isNotBlank() &&
                    seed.tools.isNotEmpty() &&
                    seed.tools.all { ToolRegistry.get(it) != null }
            }
            .groupBy { it.id }
            .values
            .map { same ->
                same.maxWithOrNull(
                    compareBy<PortableExecutionExampleSeed> { it.updatedAt }
                        .thenBy { it.surprise }
                ) ?: same.first()
            }
            .sortedWith(
                compareByDescending<PortableExecutionExampleSeed> { it.updatedAt }
                    .thenByDescending { it.surprise }
            )

    private fun formatExecutionExample(
        seed: PortableExecutionExampleSeed
    ): String {
        val steps = seed.tools.mapIndexed { index, tool ->
            val target = seed.targets.getOrNull(index)
                ?.takeIf { it.isNotBlank() }
                ?.let { " target=${sanitize(it, 120)}" }
                .orEmpty()
            "$tool$target"
        }.joinToString(" -> ")
        val kind = if (seed.kind == CoordinatorExampleKind.RECOVERY.name) {
            "PORTABLE RECOVERY EXAMPLE"
        } else {
            "PORTABLE VERIFIED EXECUTION SEQUENCE"
        }
        return "$kind (source-device verified tool outcomes; revalidate locally; not whole-goal proof; not permission) · $steps"
    }

    private fun PortableKernelPayload?.orEmptyExperience(): List<PortableExperienceSeed> =
        this?.positiveExperience.orEmpty()

    private fun tokenize(value: String): Set<String> {
        val lower = value.lowercase()
        val compound = lower
            .split(Regex("[^\\p{L}\\p{N}._:@/=-]+"))
            .filter { it.length >= 2 }
        val components = lower
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 }
        return (compound + components).toSet()
    }

    private fun sanitize(value: String, maxChars: Int): String = value
        .replace('\u0000', ' ')
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .replace(Regex("\\s{2,}"), " ")
        .trim()
        .take(maxChars)
}

object PortableKernelCodec {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
    private val payloadAdapter = moshi.adapter(PortableKernelPayload::class.java)
    private val envelopeAdapter = moshi.adapter(PortableKernelEnvelope::class.java)

    fun encode(payload: PortableKernelPayload): String {
        PortableKernelPolicy.validate(payload)
        val payloadJson = payloadAdapter.toJson(payload)
        return envelopeAdapter.toJson(
            PortableKernelEnvelope(
                payload = payload,
                payloadSha256 = PortableKernelPolicy.hash(payloadJson)
            )
        )
    }

    fun decode(raw: String): PortableKernelPayload {
        val envelope = requireNotNull(envelopeAdapter.fromJson(raw)) {
            "Portable kernel is empty or invalid JSON"
        }
        val payloadJson = payloadAdapter.toJson(envelope.payload)
        val expected = PortableKernelPolicy.hash(payloadJson)
        require(expected == envelope.payloadSha256) {
            "Portable kernel integrity check failed"
        }
        PortableKernelPolicy.validate(envelope.payload)
        return envelope.payload
    }
}

object PortableKernelStore {
    private const val FILE_NAME = "lumena_portable_kernel.json"
    private val lock = Any()

    fun exportJson(
        context: Context,
        now: Long = System.currentTimeMillis()
    ): String = synchronized(lock) {
        val app = context.applicationContext
        val localAnchors = ExperienceMemoryStore.load(app).anchors
        val landscape = ExperienceLandscapeStore.snapshot(app)
        val executionExamples = CoordinatorExperienceStore.examples(
            context = app,
            query = "",
            limit = 32
        )
        val imported = loadImportedOrNull(app)
        val versionCode = app.packageManager
            .getPackageInfo(app.packageName, 0)
            .longVersionCode
        val sourceDeviceHash = PortableKernelPolicy.hash(Build.FINGERPRINT)

        PortableKernelCodec.encode(
            PortableKernelPolicy.buildPayload(
                localAnchors = localAnchors,
                localRules = landscape.view.rules,
                imported = imported,
                exportedAt = now,
                sourceAppVersionCode = versionCode,
                sourceDeviceHash = sourceDeviceHash,
                localExecutionExamples = executionExamples
            )
        )
    }

    fun importJson(
        context: Context,
        raw: String
    ): PortableKernelImportResult = synchronized(lock) {
        val payload = PortableKernelCodec.decode(raw)
        val app = context.applicationContext
        val normalized = PortableKernelCodec.encode(payload)
        atomicWrite(file(app), normalized)

        PortableKernelImportResult(
            positiveExperience = payload.positiveExperience.size,
            dormantRules = payload.dormantRules.size,
            executionExamples = payload.executionExamples.size,
            sourceDeviceHash = payload.sourceDeviceHash,
            sourceCoreDnaVersion = payload.coreDnaVersion,
            sourceCapsuleVersion = payload.constitutionCapsuleVersion,
            versionsMatchCurrentRuntime =
                PortableKernelPolicy.versionsMatchCurrentRuntime(payload)
        )
    }

    fun advice(
        context: Context,
        query: String,
        limit: Int = 3
    ): List<String> = synchronized(lock) {
        val payload = loadImportedOrNull(context.applicationContext)
            ?: return@synchronized emptyList()
        PortableKernelPolicy.advice(payload, query, limit)
    }

    fun importedPayload(context: Context): PortableKernelPayload? = synchronized(lock) {
        loadImportedOrNull(context.applicationContext)
    }

    fun clearImported(context: Context) = synchronized(lock) {
        val target = file(context.applicationContext)
        if (target.exists()) target.delete()
    }

    private fun loadImportedOrNull(context: Context): PortableKernelPayload? {
        val target = file(context)
        if (!target.exists()) return null
        return runCatching {
            PortableKernelCodec.decode(target.readText())
        }.getOrNull()
    }

    private fun atomicWrite(file: File, content: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
}
