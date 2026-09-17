package com.lumena.android.agent.local

import com.lumena.android.network.awaitBody
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

data class BridgeJobStatus(
    val requestId: String = "", val status: String = "unknown", val cancelRequested: Boolean = false,
    val stdout: String = "", val stderr: String = "", val result: ToolResult? = null
)
class TermuxBridgeClient(baseUrl: String, private val token: String) {
    private val base = baseUrl.trim().trimEnd('/').toHttpUrlOrNull()?.takeIf {
        it.scheme == "http" && it.host in setOf("127.0.0.1", "localhost", "::1") &&
            it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null
    } ?: throw IllegalArgumentException("Bridge must use an HTTP loopback URL")
    private val client = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(1850, TimeUnit.SECONDS).callTimeout(1860, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS).followRedirects(false)
        .retryOnConnectionFailure(false).build()
    private val controlClient = client.newBuilder().readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS).build()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val requestAdapter = moshi.adapter(ToolRequest::class.java)
    private val resultAdapter = moshi.adapter(ToolResult::class.java)
    private val statusAdapter = moshi.adapter(BridgeJobStatus::class.java)
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun execute(toolRequest: ToolRequest, requestId: String? = null): ToolResult = try {
        val builder = Request.Builder().url(base.newBuilder().addPathSegment("tool").build())
            .header("Authorization", "Bearer ${token.trim()}")
            .post(requestAdapter.toJson(toolRequest).toRequestBody(jsonType))
        requestId?.let { builder.header("X-Lumena-Request-Id", it) }
        client.awaitBody(builder.build()) { response ->
            val parsed = resultAdapter.fromJson(response.body?.string().orEmpty())
            parsed ?: ToolResult(false, error = "Unreadable bridge result: HTTP ${response.code}")
        }
    } catch (e: CancellationException) { throw e
    } catch (e: Exception) {
        ToolResult(false, error = "Transport error (${e.javaClass.simpleName}). Tool outcome is UNKNOWN; do not repeat a write without checking.")
    }

    /** Administrative endpoints; not exposed to the model's ToolRegistry. */
    suspend fun jobStatus(requestId: String, cancel: Boolean = false): BridgeJobStatus {
        require(Regex("^[A-Za-z0-9_-]{16,80}$").matches(requestId))
        val payload = "{\"requestId\":\"$requestId\"}"
        val request = Request.Builder().url(base.newBuilder()
            .addPathSegments(if (cancel) "jobs/cancel" else "jobs/status").build())
            .header("Authorization", "Bearer ${token.trim()}")
            .post(payload.toRequestBody(jsonType)).build()
        return controlClient.awaitBody(request) { response ->
            if (response.code == 404) throw IOException("Bridge job control unavailable; update the Termux bridge")
            if (!response.isSuccessful) throw IOException("Bridge control HTTP ${response.code}")
            statusAdapter.fromJson(response.body?.string().orEmpty()) ?: throw IOException("Invalid job status")
        }
    }
}
