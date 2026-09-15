package com.lumena.android.agent.local

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class TermuxBridgeClient(
    baseUrl: String,
    private val token: String
) {
    private val endpoint = baseUrl.trim().trimEnd('/') + "/tool"
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(130, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
    private val requestAdapter = moshi.adapter(ToolRequest::class.java)
    private val resultAdapter = moshi.adapter(ToolResult::class.java)
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun execute(toolRequest: ToolRequest): ToolResult = withContext(Dispatchers.IO) {
        try {
            val json = requestAdapter.toJson(toolRequest)
            val request = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $token")
                .post(json.toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val parsed = body.takeIf { it.isNotBlank() }?.let(resultAdapter::fromJson)
                parsed ?: ToolResult(
                    ok = false,
                    error = "Bridge returned HTTP ${response.code} without a readable result."
                )
            }
        } catch (t: Throwable) {
            ToolResult(ok = false, error = "${t::class.simpleName}: ${t.message}")
        }
    }
}
