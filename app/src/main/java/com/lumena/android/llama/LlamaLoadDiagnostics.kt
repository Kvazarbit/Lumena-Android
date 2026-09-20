package com.lumena.android.llama

data class LlamaModelProbeInfo(
    val ok: Boolean,
    val architecture: String? = null,
    val description: String? = null,
    val modelSizeBytes: Long? = null,
    val parameters: Long? = null,
    val raw: String = ""
)

enum class LlamaLoadFailureKind {
    METADATA_INCOMPATIBLE,
    ALLOCATION_OR_MMAP,
    TENSOR_LAYOUT,
    FULL_LOAD_FAILED
}

data class LlamaLoadFailure(
    val kind: LlamaLoadFailureKind,
    val userMessage: String,
    val technicalSummary: String
)

object LlamaLoadDiagnostics {
    private val keyValue = Regex("^([A-Za-z0-9_.-]+)=(.*)$")
    private val tensorName = Regex(
        "(?:loading tensor|tensor)\\s+([A-Za-z0-9_.-]+)",
        RegexOption.IGNORE_CASE
    )

    fun parseProbe(raw: String): LlamaModelProbeInfo {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) {
            return LlamaModelProbeInfo(
                ok = false,
                raw = ""
            )
        }

        val lines = trimmed.lineSequence().toList()
        val ok = lines.firstOrNull()?.trim() == "OK"
        val values = buildMap {
            lines.drop(1).forEach { line ->
                val match = keyValue.matchEntire(line.trim()) ?: return@forEach
                put(match.groupValues[1], match.groupValues[2].trim())
            }
        }

        return LlamaModelProbeInfo(
            ok = ok,
            architecture = values["architecture"]?.takeIf { it.isNotBlank() && it != "(unknown)" },
            description = values["description"]?.takeIf { it.isNotBlank() && it != "(unknown)" },
            modelSizeBytes = values["model_size_bytes"]?.toLongOrNull(),
            parameters = values["parameters"]?.toLongOrNull(),
            raw = trimmed.take(8_000)
        )
    }

    fun classify(
        probe: LlamaModelProbeInfo,
        nativeLog: String,
        availableRamGb: Double,
        fileBytes: Long
    ): LlamaLoadFailure {
        val normalized = nativeLog
            .replace('\u0000', ' ')
            .replace(Regex("[\\r\\t]+"), " ")
            .trim()
        val lower = normalized.lowercase()

        val kind = when {
            !probe.ok ->
                LlamaLoadFailureKind.METADATA_INCOMPATIBLE

            listOf(
                "out of memory",
                "cannot allocate memory",
                "failed to allocate",
                "allocation failed",
                "mmap failed",
                "failed to mmap",
                "cannot mmap",
                "not enough memory"
            ).any(lower::contains) ->
                LlamaLoadFailureKind.ALLOCATION_OR_MMAP

            listOf(
                "wrong shape",
                "unexpected tensor",
                "unknown tensor",
                "unsupported tensor",
                "tensor type",
                "tensor mismatch",
                "expected tensor"
            ).any(lower::contains) ->
                LlamaLoadFailureKind.TENSOR_LAYOUT

            else ->
                LlamaLoadFailureKind.FULL_LOAD_FAILED
        }

        val lastTensor = tensorName
            .findAll(normalized)
            .map { it.groupValues[1] }
            .lastOrNull()

        val modelGb = when {
            fileBytes > 0L -> fileBytes / (1024.0 * 1024.0 * 1024.0)
            probe.modelSizeBytes != null -> probe.modelSizeBytes / (1024.0 * 1024.0 * 1024.0)
            else -> null
        }

        val modelLabel = buildString {
            probe.architecture?.let {
                append(it)
                append(" · ")
            }
            modelGb?.let {
                append("%.2f GB".format(it))
            }
        }.trim().trimEnd('·').trim()

        val headline = when (kind) {
            LlamaLoadFailureKind.METADATA_INCOMPATIBLE ->
                "GGUF metadata could not be parsed by this embedded llama.cpp build."
            LlamaLoadFailureKind.ALLOCATION_OR_MMAP ->
                "GGUF metadata is readable, but full model allocation/mmap failed."
            LlamaLoadFailureKind.TENSOR_LAYOUT ->
                "GGUF metadata is readable, but the full tensor layout is not accepted by this loader."
            LlamaLoadFailureKind.FULL_LOAD_FAILED ->
                "GGUF metadata is readable, but the full model load failed before inference started."
        }

        val userMessage = buildString {
            append("Embedded model load failed. ")
            append(headline)
            if (modelLabel.isNotBlank()) {
                append(" Model: ")
                append(modelLabel)
                append('.')
            }
            append(" Available RAM at load: ")
            append("%.1f GB".format(availableRamGb.coerceAtLeast(0.0)))
            append('.')
            lastTensor?.let {
                append(" Last tensor seen: ")
                append(it)
                append('.')
            }
            if (kind == LlamaLoadFailureKind.FULL_LOAD_FAILED) {
                append(
                    " This can be caused by peak memory pressure or a GGUF/tensor-layout compatibility issue; " +
                        "repeating the same load unchanged will not help."
                )
            }
        }

        val technicalLines = normalized
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .filter { line ->
                val l = line.lowercase()
                "error" in l ||
                    "failed" in l ||
                    "invalid" in l ||
                    "unsupported" in l ||
                    "wrong" in l ||
                    "cannot" in l ||
                    "out of memory" in l ||
                    "mmap" in l ||
                    "returned null" in l ||
                    "loading tensor" in l
            }
            .toList()
            .takeLast(10)

        val technicalSummary = buildString {
            append("kind=")
            append(kind)
            probe.architecture?.let { append("\narchitecture=").append(it) }
            probe.parameters?.let { append("\nparameters=").append(it) }
            probe.modelSizeBytes?.let { append("\nprobe_model_size_bytes=").append(it) }
            if (technicalLines.isNotEmpty()) {
                append("\nlast_native_log_lines:")
                technicalLines.forEach { line ->
                    append("\n- ")
                    append(line.take(500))
                }
            }
        }.take(6_000)

        return LlamaLoadFailure(
            kind = kind,
            userMessage = userMessage,
            technicalSummary = technicalSummary
        )
    }
}
