package com.lumena.android.llama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaLoadDiagnosticsTest {
    @Test
    fun failedProbePreservesTailAndClassifiesAllocationBeforeMetadata() {
        val probe = LlamaLoadDiagnostics.parseProbe(
            "ERROR\n" + "metadata spam\n".repeat(2000) + "failed to allocate tokenizer memory"
        )
        val failure = LlamaLoadDiagnostics.classify(probe, probe.raw, 1.0, 0)
        assertTrue(probe.raw.length <= 8_000)
        assertEquals(LlamaLoadFailureKind.ALLOCATION_OR_MMAP, failure.kind)
        assertFalse(failure.userMessage.contains("metadata is readable"))
        assertTrue(failure.technicalSummary.contains("failed to allocate tokenizer memory"))
    }

    @Test
    fun inaccessibleFileIsNotCalledIncompatibleMetadata() {
        val probe = LlamaLoadDiagnostics.parseProbe("ERROR\nGGUF file descriptor is not seekable.")
        assertEquals(LlamaLoadFailureKind.FILE_ACCESS,
            LlamaLoadDiagnostics.classify(probe, probe.raw, 8.0, 0).kind)
    }

    @Test
    fun informationalTensorTypeDoesNotProveLayoutFailureOrEvictError() {
        val log = "tensor type Q4_K\nerror: backend initialization failed\n" +
            "create_tensor: loading tensor 'blk.7.weight'\n".repeat(100)
        val failure = LlamaLoadDiagnostics.classify(LlamaModelProbeInfo(ok = true), log, 8.0, 0)
        assertEquals(LlamaLoadFailureKind.FULL_LOAD_FAILED, failure.kind)
        assertTrue(failure.userMessage.contains("blk.7.weight"))
        assertTrue(failure.technicalSummary.contains("backend initialization failed"))
    }

    @Test
    fun parsesSuccessfulGemma4Probe() {
        val probe = LlamaLoadDiagnostics.parseProbe(
            """
            OK
            architecture=gemma4
            description=Gemma 4 E4B Q4_K - Medium
            model_size_bytes=5111011082
            parameters=7520000000
            """.trimIndent()
        )

        assertTrue(probe.ok)
        assertEquals("gemma4", probe.architecture)
        assertEquals(5_111_011_082L, probe.modelSizeBytes)
        assertEquals(7_520_000_000L, probe.parameters)
    }

    @Test
    fun fullLoadFailureCompactsTensorSpam() {
        val probe = LlamaModelProbeInfo(
            ok = true,
            architecture = "gemma4",
            description = "Gemma 4 E4B",
            modelSizeBytes = 5_111_011_082L,
            parameters = 7_520_000_000L
        )
        val nativeLog = buildString {
            repeat(80) { index ->
                appendLine("create_tensor: loading tensor blk.$index.ffn_up.input_scale")
            }
            appendLine("llama_model_load_from_file_ptr returned null.")
        }

        val failure = LlamaLoadDiagnostics.classify(
            probe = probe,
            nativeLog = nativeLog,
            availableRamGb = 7.4,
            fileBytes = 5_111_011_082L
        )

        assertEquals(LlamaLoadFailureKind.FULL_LOAD_FAILED, failure.kind)
        assertTrue(failure.userMessage.contains("gemma4"))
        assertTrue(failure.userMessage.contains("7.4 GB"))
        assertTrue(failure.userMessage.contains("blk.79.ffn_up.input_scale"))
        assertTrue(failure.technicalSummary.length < 6_000)
        assertFalse(failure.userMessage.contains("blk.0.ffn_up.input_scale"))
    }

    @Test
    fun explicitAllocationErrorIsClassified() {
        val failure = LlamaLoadDiagnostics.classify(
            probe = LlamaModelProbeInfo(ok = true, architecture = "gemma4"),
            nativeLog = "ggml_backend_buffer_alloc_buffer: failed to allocate 2147483648 bytes",
            availableRamGb = 2.3,
            fileBytes = 5_000_000_000L
        )

        assertEquals(LlamaLoadFailureKind.ALLOCATION_OR_MMAP, failure.kind)
        assertTrue(failure.userMessage.contains("allocation/mmap"))
    }

    @Test
    fun failedProbeIsMetadataIncompatible() {
        val probe = LlamaLoadDiagnostics.parseProbe(
            "ERROR\nunknown model architecture"
        )

        val failure = LlamaLoadDiagnostics.classify(
            probe = probe,
            nativeLog = probe.raw,
            availableRamGb = 8.0,
            fileBytes = 4_000_000_000L
        )

        assertFalse(probe.ok)
        assertEquals(LlamaLoadFailureKind.METADATA_INCOMPATIBLE, failure.kind)
    }
}
