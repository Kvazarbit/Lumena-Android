package com.lumena.android.agent.local

import android.content.Context
import com.lumena.android.settings.LumenaPreferences
import com.lumena.android.agent.core.BridgeTransportPolicy
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class TermuxBridgeClient(
    baseUrl: String,
    token: String,
    private val context: Context? = null
) : ToolExecutor {
    private val token = LumenaPreferences.normalizeBridgeToken(token)
    private val base: HttpUrl = normalizeLoopbackBaseUrl(baseUrl)
        ?: throw IllegalArgumentException("Bridge URL must use localhost/127.0.0.1 over http")
    private val endpoint = base.newBuilder().addPathSegment("tool").build()
    private val cancelEndpoint = base.newBuilder().addPathSegment("cancel").build()
    private val shutdownEndpoint = base.newBuilder().addPathSegment("shutdown").build()

    private val client = OkHttpClient.Builder()
        // Tool POSTs can mutate state. A lost response must never cause an
        // implicit transport replay or a redirect to another endpoint.
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(11, TimeUnit.MINUTES)
        .build()

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
    private val requestAdapter = moshi.adapter(ToolRequest::class.java)
    private val resultAdapter = moshi.adapter(ToolResult::class.java)
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun execute(toolRequest: ToolRequest): ToolResult {
        context?.let { appContext ->
            val started = TermuxBridgeAutoStarter.ensureRunning(appContext, base)
            if (started.isFailure) {
                val error = started.exceptionOrNull()
                return ToolResult(
                    ok = false,
                    error = error?.message ?: "Could not start the Termux bridge",
                    errorCode = "BRIDGE_START_FAILED",
                    failureClass = "AUTH_OR_CONFIG",
                    retryable = false,
                    dependency = "termux_bridge"
                )
            }
        }
        val first = executeOnce(toolRequest)
        return if (first.transportFailure && BridgeTransportPolicy.canRetry(toolRequest.tool)) {
            executeOnce(toolRequest).result
        } else first.result
    }

    private data class Attempt(val result: ToolResult, val transportFailure: Boolean = false)

    private fun transportFailure(tool: String, message: String): Attempt {
        val unknown = BridgeTransportPolicy.outcomeUnknown(tool)
        return Attempt(
            ToolResult(
                ok = false,
                error = "Bridge transport: $message",
                outcomeUnknown = unknown,
                errorCode = "BRIDGE_TRANSPORT",
                failureClass = if (unknown) "UNKNOWN_EFFECT" else "TRANSIENT_TRANSPORT",
                retryable = !unknown,
                dependency = "termux_bridge"
            ),
            transportFailure = true
        )
    }

    private suspend fun executeOnce(toolRequest: ToolRequest): Attempt = suspendCancellableCoroutine { continuation ->
        val json = requestAdapter.toJson(toolRequest)
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $token")
            .header("Connection", "close")
            .post(json.toRequestBody(jsonMediaType))
            .build()
        val call = client.newCall(request)

        continuation.invokeOnCancellation {
            call.cancel()
            toolRequest.requestId?.takeIf { id -> id.isNotBlank() }?.let(::sendCancelBestEffort)
        }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resume(
                        transportFailure(toolRequest.tool, "${e::class.simpleName}: ${e.message}")
                    )
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use {
                        val body = it.body?.string().orEmpty()
                        val parsed = body.takeIf { value -> value.isNotBlank() }?.let(resultAdapter::fromJson)
                        Attempt(parsed ?: ToolResult(
                            ok = false,
                            error = "Bridge returned HTTP ${it.code} without a readable result.",
                            outcomeUnknown = BridgeTransportPolicy.outcomeUnknown(toolRequest.tool)
                        ))
                    }
                } catch (e: IOException) {
                    transportFailure(toolRequest.tool, "${e::class.simpleName}: ${e.message}")
                } catch (e: Exception) {
                    Attempt(ToolResult(ok = false, error = "Bridge result unreadable: ${e.message}",
                        outcomeUnknown = BridgeTransportPolicy.outcomeUnknown(toolRequest.tool)))
                }
                if (continuation.isActive) continuation.resume(result)
            }
        })
    }

    suspend fun shutdownBridge(): ToolResult = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder()
            .url(shutdownEndpoint)
            .header("Authorization", "Bearer $token")
            .header("Connection", "close")
            .post("{}".toRequestBody(jsonMediaType))
            .build()
        val call = client.newCall(request)

        continuation.invokeOnCancellation { call.cancel() }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resume(
                        ToolResult(
                            ok = false,
                            tool = "bridge.shutdown",
                            error = "Bridge shutdown failed: " + e::class.simpleName + ": " + e.message
                        )
                    )
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use {
                        val body = it.body?.string().orEmpty()
                        val parsed = body.takeIf { value -> value.isNotBlank() }
                            ?.let(resultAdapter::fromJson)
                        (parsed ?: ToolResult(
                            ok = it.isSuccessful,
                            tool = "bridge.shutdown",
                            error = if (it.isSuccessful) null else "Bridge returned HTTP " + it.code
                        )).copy(tool = "bridge.shutdown")
                    }
                } catch (e: Exception) {
                    ToolResult(
                        ok = false,
                        tool = "bridge.shutdown",
                        error = "Bridge shutdown result unreadable: " + e.message
                    )
                }
                if (continuation.isActive) continuation.resume(result)
            }
        })
    }
    /**
     * STOP is best effort because the HTTP call itself may already be cancelled. The Termux
     * bridge tracks requestId -> child process and terminates the matching process group.
     */
    private fun sendCancelBestEffort(requestId: String) {
        val body = "{\"requestId\":${jsonString(requestId)}}"
        val request = Request.Builder()
            .url(cancelEndpoint)
            .header("Authorization", "Bearer $token")
            .post(body.toRequestBody(jsonMediaType))
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = Unit
            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }

    private fun normalizeLoopbackBaseUrl(raw: String) = raw.trim().trimEnd('/')
        .toHttpUrlOrNull()
        ?.takeIf { url ->
            url.scheme == "http" &&
                (url.host == "127.0.0.1" || url.host == "localhost" || url.host == "::1")
        }
}
