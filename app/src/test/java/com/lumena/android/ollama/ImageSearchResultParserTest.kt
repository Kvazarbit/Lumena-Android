package com.lumena.android.ollama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageSearchResultParserTest {
    @Test
    fun acceptsOnlyDisplayReadyWikimediaHttpsImages() {
        val json = """
            {
              "provider":"Wikimedia Commons",
              "query":"woman portrait",
              "display_ready":true,
              "images":[
                {
                  "title":"Portrait",
                  "thumbnail_url":"https://upload.wikimedia.org/example.jpg",
                  "source_page":"https://commons.wikimedia.org/wiki/File:Example.jpg",
                  "source":"Wikimedia Commons"
                },
                {
                  "title":"Blocked",
                  "thumbnail_url":"https://evil.example/image.jpg",
                  "source_page":"https://evil.example/source"
                }
              ]
            }
        """.trimIndent()

        val images = ImageSearchResultParser.parse(json)

        assertEquals(1, images.size)
        assertEquals("Portrait", images.single().title)
        assertTrue(images.single().thumbnailUrl.startsWith("https://upload.wikimedia.org/"))
        assertTrue(images.single().sourcePage.startsWith("https://commons.wikimedia.org/"))
    }

    @Test
    fun rejectsNonDisplayReadyPayloads() {
        val json = """
            {
              "provider":"Wikimedia Commons",
              "query":"test",
              "display_ready":false,
              "images":[
                {"thumbnail_url":"https://upload.wikimedia.org/example.jpg"}
              ]
            }
        """.trimIndent()

        assertTrue(ImageSearchResultParser.parse(json).isEmpty())
    }

    @Test
    fun deduplicatesAndBoundsImageAttachments() {
        val items = (0 until 12).joinToString(",") { index ->
            """{"title":"$index","thumbnail_url":"https://upload.wikimedia.org/img-$index.jpg"}"""
        }
        val json = """{"display_ready":true,"images":[$items]}"""

        val images = ImageSearchResultParser.parse(json)

        assertEquals(8, images.size)
    }
}
