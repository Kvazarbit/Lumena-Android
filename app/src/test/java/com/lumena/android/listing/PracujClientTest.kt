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
            override fun read(): Int {
                calls++
                return 65
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                calls++
                java.util.Arrays.fill(buffer, offset, offset + length, 65.toByte())
                return length
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
