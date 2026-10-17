package com.lumena.android.ollama

import org.junit.Assert.*
import org.junit.Test

class LocalEmbeddingProtocolTest {
    @Test fun rejectsCloudAndUrlModelNames() {
        listOf("gemma4:31b-cloud", "abc:cloud", "remote/model", "https://h.example/x").forEach {
            assertThrows(IllegalArgumentException::class.java) {
                LocalEmbeddingProtocol.modelName(it)
            }
        }
    }

    @Test fun requiresLocallyStoredModelWithStableDigest() {
        assertThrows(IllegalStateException::class.java) {
            LocalEmbeddingProtocol.select("embeddinggemma-2", emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalEmbeddingProtocol.select("embeddinggemma-2",
                listOf(EmbeddingModelTag("embeddinggemma-2", 0L, "")))
        }
        val selected = LocalEmbeddingProtocol.select("embeddinggemma-2",
            listOf(EmbeddingModelTag("embeddinggemma-2", 10_000_000L, "a".repeat(64))))
        assertEquals("embeddinggemma-2@" + "a".repeat(64), selected.cacheKey)
    }

    @Test fun rejectsWrongCountModelAndNonfiniteEmbeddings() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalEmbeddingProtocol.vectors("embeddinggemma-2",
                EmbedResponse(model = "another-model", embeddings = listOf(listOf(1.0, 0.0))), 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalEmbeddingProtocol.vectors("embeddinggemma-2",
                EmbedResponse(model = "embeddinggemma-2", embeddings = listOf(listOf(1.0, 0.0))), 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalEmbeddingProtocol.vectors("embeddinggemma-2",
                EmbedResponse(embeddings = listOf(listOf(Double.NaN, 1.0))), 1)
        }
    }

    @Test fun acceptsValidTextEmbeddingsWithoutInferringProof() {
        val vectors = LocalEmbeddingProtocol.vectors("embeddinggemma-2",
            EmbedResponse("embeddinggemma-2",
                embeddings = listOf(listOf(3.0, 4.0), listOf(0.0, 10.0))), 2)
        assertEquals(2, vectors.size)
        assertEquals(0.6f, vectors.first()[0], 0.0001f)
        assertEquals(1f, vectors.last()[1], 0.0001f)
    }

    @Test fun localhostIsRequired() {
        for (url in listOf("https://127.0.0.1:11434", "http://example.com:11434",
            "http://127.0.0.1:11434/api/embed", "http://localhost:11434/?foo=1")) {
            assertThrows(IllegalArgumentException::class.java) {
                LocalEmbeddingClient(url)
            }
        }
        LocalEmbeddingClient("http://127.0.0.1:11434")
        LocalEmbeddingClient("http://localhost:11434")
    }
}
