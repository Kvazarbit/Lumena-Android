package com.lumena.android.llama

import com.lumena.android.ollama.LocalEmbeddingIdentity
import org.junit.Assert.*
import org.junit.Test

/** Pure JVM regression: bridge/Ollama and LiteRT caches cannot alias. */
class SemanticLiteRtIdentityTest {
    @Test fun backendAndBundleDigestArePartOfTheVectorSpace() {
        val digest = "a".repeat(64)
        val cpu = LocalEmbeddingIdentity(LiteRtLmEmbeddingProvider.MODEL_ID, digest, 280000000)
        val ollama = LocalEmbeddingIdentity("embeddinggemma-2:270m-bf16-text", digest, 280000000)
        assertNotEquals(cpu.cacheKey, ollama.cacheKey)
        assertNotEquals(
            cpu.cacheKey,
            cpu.copy(digest = "b".repeat(64)).cacheKey
        )
    }

    @Test fun runtimeModelIdIsPinned() {
        assertEquals(
            "litertlm-0.18.0-cpu-text-embedding-v1",
            LiteRtLmEmbeddingProvider.MODEL_ID
        )
    }
}
