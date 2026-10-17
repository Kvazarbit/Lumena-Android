package com.lumena.android.llama

import org.junit.Assert.*
import org.junit.Test

class LiteRtTextImportPolicyTest {
    private fun sample(
        text: Boolean = true,
        vision: Boolean = false,
        audio: Boolean = false,
        video: Boolean = false,
        dimension: Int? = 768,
        cpu: Boolean? = true,
        embedding: Boolean = true
    ) = LiteRtTextImportPolicy.Capabilities(
        embedding, text, vision, audio, video, dimension, cpu
    )

    private fun refused(code: LiteRtImportCode, data: LiteRtTextImportPolicy.Capabilities) {
        try {
            LiteRtTextImportPolicy.validate(data)
            fail("Expected " + code.name)
        } catch (problem: LiteRtImportRefusal) {
            assertEquals(code, problem.code)
        }
    }

    @Test fun textOnly270MPassesMetadataGateButNotYetInference() {
        assertEquals(768, LiteRtTextImportPolicy.validate(sample()).dimension)
    }

    @Test fun additionalModalitiesCannotBlockTextOnlyInferenceUse() {
        assertTrue(LiteRtTextImportPolicy.validate(
            sample(vision = true, audio = true, video = true)
        ).cpuDeclared)
    }

    @Test fun absentCpuMetadataStaysUnknownUntilRealCanary() {
        assertFalse(LiteRtTextImportPolicy.validate(sample(cpu = null)).cpuDeclared)
    }

    @Test fun explicitNpuOnlyTextRejectsCpuUse() {
        refused(LiteRtImportCode.CPU_UNSUPPORTED, sample(cpu = false))
    }

    @Test fun nonEmbeddingRejected() {
        refused(LiteRtImportCode.NOT_EMBEDDING, sample(embedding = false))
    }

    @Test fun noTextRejectedEvenIfVisionAvailable() {
        refused(LiteRtImportCode.NO_TEXT_ENCODER, sample(text = false, vision = true))
    }

    @Test fun dimensionMustBeKnownAndBounded() {
        refused(LiteRtImportCode.INVALID_DIMENSION, sample(dimension = null))
        refused(LiteRtImportCode.INVALID_DIMENSION, sample(dimension = 0))
        refused(LiteRtImportCode.INVALID_DIMENSION, sample(dimension = 4097))
    }

    @Test fun nativePathsNeverAppearInPublicStatus() {
        val error = LiteRtImportRefusal(
            LiteRtImportCode.METADATA_UNAVAILABLE,
            IllegalArgumentException("/data/user/0/private/secret/model.litertlm")
        )
        assertTrue(error.safeStatus().contains("METADATA_UNAVAILABLE"))
        assertFalse(error.safeStatus().contains("/data/user"))
        assertFalse(error.safeStatus().contains("secret"))
    }
}
