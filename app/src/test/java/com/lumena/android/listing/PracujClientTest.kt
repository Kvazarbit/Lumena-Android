package com.lumena.android.listing

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class PracujClientTest {
    @Test fun noAutomaticRedirectsOrRetries() {
        assertFalse(PracujClient.client.followRedirects)
        assertFalse(PracujClient.client.followSslRedirects)
        assertFalse(PracujClient.client.retryOnConnectionFailure)
    }

    @Test fun unknownContentLengthCannotExceedBound() {
        val stream = object : InputStream() {
            var calls = 0
            var remaining = 16 * 1024
            override fun read(): Int {
                calls++
                return if (remaining-- > 0) 65 else -1
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                calls++
                if (remaining <= 0) return -1
                val count = minOf(remaining, length)
                remaining -= count
                java.util.Arrays.fill(buffer, offset, offset + count, 65.toByte())
                return count
            }
        }
        assertThrows(IllegalStateException::class.java) {
            PracujClient.readBounded(stream, limit = 100)
        }
        org.junit.Assert.assertTrue("reader must abort quickly", stream.calls <= 2)
    }

    @Test fun boundedReaderAcceptsExactLimitAndRejectsOneExtraByte() {
        assertArrayEquals(
            byteArrayOf(1, 2, 3, 4),
            PracujClient.readBounded(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), 4)
        )
        assertThrows(IllegalStateException::class.java) {
            PracujClient.readBounded(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)), 4)
        }
    }
}
