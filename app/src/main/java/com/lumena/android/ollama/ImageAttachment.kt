package com.lumena.android.ollama

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

data class WorkflowImage(
    val title: String = "",
    val thumbnailUrl: String,
    val sourcePage: String = "",
    val source: String = ""
)

private data class ImageSearchItem(
    val title: String = "",
    val thumbnail_url: String = "",
    val source_page: String = "",
    val source: String = ""
)

private data class ImageSearchEnvelope(
    val provider: String = "",
    val query: String = "",
    val display_ready: Boolean = false,
    val images: List<ImageSearchItem> = emptyList()
)

object ImageSearchResultParser {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
    private val adapter = moshi.adapter(ImageSearchEnvelope::class.java)

    fun parse(stdout: String): List<WorkflowImage> {
        val parsed = runCatching { adapter.fromJson(stdout) }.getOrNull()
            ?: return emptyList()
        if (!parsed.display_ready) return emptyList()

        return parsed.images
            .asSequence()
            .mapNotNull { item ->
                val thumbnail = item.thumbnail_url.trim()
                if (!isAllowedWikimediaHttps(thumbnail)) return@mapNotNull null

                val sourcePage = item.source_page.trim()
                    .takeIf(::isAllowedWikimediaHttps)
                    .orEmpty()

                WorkflowImage(
                    title = item.title.trim().take(300),
                    thumbnailUrl = thumbnail,
                    sourcePage = sourcePage,
                    source = item.source.trim().ifBlank { parsed.provider.trim() }.take(120)
                )
            }
            .distinctBy { it.thumbnailUrl }
            .take(8)
            .toList()
    }

    private fun isAllowedWikimediaHttps(raw: String): Boolean {
        if (!raw.startsWith("https://", ignoreCase = true)) return false
        val authority = raw
            .substringAfter("https://", "")
            .substringBefore('/')
            .substringBefore(':')
            .lowercase()
        return authority == "wikimedia.org" || authority.endsWith(".wikimedia.org")
    }
}
