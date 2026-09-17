package com.lumena.android.agent.local

import com.lumena.android.ollama.LocalHttpException
import com.lumena.android.ollama.awaitText
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class TermuxBridgeClient(baseUrl: String, private val token: String) {
    private val endpoint = baseUrl.trim().trimEnd('/').toHttpUrlOrNull()?.takeIf {
        it.scheme == "http" && it.host in setOf("127.0.0.1", "localhost", "::1") &&
            it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null
    }?.newBuilder()?.addPathSegment("tool")?.build()
        ?: throw IllegalArgumentException("Bridge URL must use loopback HTTP")
    private val client = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(610, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(620, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val requestAdapter = moshi.adapter(ToolRequest::class.java)
    private val resultAdapter = moshi.adapter(ToolResult::class.java)
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun execute(toolRequest: ToolRequest): ToolResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(endpoint)
                .header("Authorization", "Bearer ${token.trim()}")
                .post(requestAdapter.toJson(toolRequest).toRequestBody(jsonType)).build()
            resultAdapter.fromJson(client.newCall(request).awaitText())
                ?: ToolResult(false, error = "TRANSPORT_UNKNOWN: bridge result could not be decoded")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: LocalHttpException) {
            if (e.status in 400..499) ToolResult(false, error = "Bridge HTTP ${e.status}: ${e.detail.take(500)}")
            else ToolResult(false, error = "TRANSPORT_UNKNOWN: bridge HTTP ${e.status}")
        } catch (e: Exception) {
            ToolResult(false, error = "TRANSPORT_UNKNOWN: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
