package com.lumena.android.ollama

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class LocalHttpException(val status: Int, val detail: String) : IOException("HTTP $status: ${detail.take(700)}")

/** Cancels only the HTTP request, not a Python process already accepted by the bridge. */
suspend fun Call.awaitText(): String = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                val text = response.use {
                    val body = it.body ?: throw IOException("Empty HTTP response")
                    if (body.contentLength() > 2_097_152) throw IOException("Response exceeds 2 MiB")
                    val value = body.string()
                    if (value.length > 2_097_152) throw IOException("Response exceeds 2 MiB")
                    if (!it.isSuccessful) throw LocalHttpException(it.code, value)
                    value
                }
                if (continuation.isActive) continuation.resume(text)
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }
    })
}
