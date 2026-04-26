package com.example.asrapp.network

import android.util.Log
import com.example.asrapp.network.model.CorrectionRequest
import com.example.asrapp.network.model.CorrectionResponse
import com.example.asrapp.network.model.SmartHomeRequest
import com.example.asrapp.network.model.SmartHomeResponse
import com.example.asrapp.network.model.CompletionRequest
import com.example.asrapp.network.model.CompletionResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Wordfiller服务端API客户端
 * 负责与纠错服务通信
 */
class WordfillerApiClient(
    private val config: NetworkConfig = NetworkConfig()
) {
    private val TAG = "WordfillerApiClient"

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val client: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)

        // 添加日志拦截器（始终启用）
        val loggingInterceptor = HttpLoggingInterceptor { message ->
            Log.d(TAG, message)
        }.apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        builder.addInterceptor(loggingInterceptor)

        builder.build()
    }

    private val requestAdapter by lazy {
        moshi.adapter(CorrectionRequest::class.java)
    }

    private val responseAdapter by lazy {
        moshi.adapter(CorrectionResponse::class.java)
    }

    private val smartHomeRequestAdapter by lazy {
        moshi.adapter(SmartHomeRequest::class.java)
    }

    private val smartHomeResponseAdapter by lazy {
        moshi.adapter(SmartHomeResponse::class.java)
    }

    private val completionRequestAdapter by lazy {
        moshi.adapter(CompletionRequest::class.java)
    }

    private val completionResponseAdapter by lazy {
        moshi.adapter(CompletionResponse::class.java)
    }

    private val jsonMediaType = "application/json".toMediaType()

    /**
     * 发送纠错请求
     *
     * @param text ASR识别的原始文本
     * @return CorrectionResponse 纠错结果
     * @throws IOException 网络错误
     */
    suspend fun correct(text: String): Result<CorrectionResponse> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Sending correction request: '$text'")

            val request = CorrectionRequest(text = text)
            val jsonBody = requestAdapter.toJson(request)

            val httpRequest: Request = Request.Builder()
                .url(config.getCorrectionEndpoint())
                .post(jsonBody.toRequestBody(jsonMediaType))
                .build()

            val response = client.newCall(httpRequest).execute()

            if (!response.isSuccessful) {
                Log.e(TAG, "Request failed: ${response.code} ${response.message}")
                return@withContext Result.failure(
                    IOException("Request failed: ${response.code} ${response.message}")
                )
            }

            val responseBody = response.body?.string()
            if (responseBody == null) {
                Log.e(TAG, "Empty response body")
                return@withContext Result.failure(
                    IOException("Empty response body")
                )
            }

            Log.d(TAG, "Received response: $responseBody")

            val correctionResponse = responseAdapter.fromJson(responseBody)
            if (correctionResponse == null) {
                Log.e(TAG, "Failed to parse response")
                return@withContext Result.failure(
                    IOException("Failed to parse response")
                )
            }

            Log.d(TAG, "Correction successful: '${correctionResponse.corrected}' " +
                      "(${correctionResponse.processingTimeMs}ms)")

            Result.success(correctionResponse)

        } catch (e: Exception) {
            Log.e(TAG, "Correction request failed", e)
            Result.failure(e)
        }
    }

    /**
     * 智能家居指令解析
     *
     * 使用 /smart-home 端点，返回填充词过滤后的结果
     *
     * @param text 原始ASR文本
     * @param useGlm 是否使用GLM解析（默认true）
     * @return SmartHomeResponse 解析结果
     */
    suspend fun parseSmartHome(text: String, useGlm: Boolean = true): Result<SmartHomeResponse> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Parsing smart home command: '$text'")

            val request = SmartHomeRequest(text = text, useGlm = useGlm)
            val jsonBody = smartHomeRequestAdapter.toJson(request)

            val httpRequest: Request = Request.Builder()
                .url(config.getSmartHomeEndpoint())
                .post(jsonBody.toRequestBody(jsonMediaType))
                .build()

            val response = client.newCall(httpRequest).execute()

            if (!response.isSuccessful) {
                Log.e(TAG, "Smart home parse failed: ${response.code} ${response.message}")
                return@withContext Result.failure(
                    IOException("Request failed: ${response.code} ${response.message}")
                )
            }

            val responseBody = response.body?.string()
            if (responseBody == null) {
                Log.e(TAG, "Empty response body")
                return@withContext Result.failure(IOException("Empty response body"))
            }

            Log.d(TAG, "Smart home response: $responseBody")

            val smartHomeResponse = smartHomeResponseAdapter.fromJson(responseBody)
            if (smartHomeResponse == null) {
                Log.e(TAG, "Failed to parse smart home response")
                return@withContext Result.failure(IOException("Failed to parse response"))
            }

            Log.d(TAG, "Smart home parse successful: '${smartHomeResponse.filtered}' " +
                      "(${smartHomeResponse.stats?.latencyMs}ms)")

            Result.success(smartHomeResponse)

        } catch (e: Exception) {
            Log.e(TAG, "Smart home parse failed", e)
            Result.failure(e)
        }
    }

    /**
     * 智能补全
     *
     * 根据部分输入推测完整意图
     *
     * @param partial 部分输入文本
     * @param maxResults 最多返回候选数（默认5）
     * @return CompletionResponse 补全结果
     */
    suspend fun complete(partial: String, maxResults: Int = 5): Result<CompletionResponse> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Requesting completion for: '$partial'")

            val request = CompletionRequest(partial = partial, maxResults = maxResults)
            val jsonBody = completionRequestAdapter.toJson(request)

            val httpRequest: Request = Request.Builder()
                .url(config.getCompletionEndpoint())
                .post(jsonBody.toRequestBody(jsonMediaType))
                .build()

            val response = client.newCall(httpRequest).execute()

            if (!response.isSuccessful) {
                Log.e(TAG, "Completion failed: ${response.code} ${response.message}")
                return@withContext Result.failure(
                    IOException("Request failed: ${response.code} ${response.message}")
                )
            }

            val responseBody = response.body?.string()
            if (responseBody == null) {
                Log.e(TAG, "Empty response body")
                return@withContext Result.failure(IOException("Empty response body"))
            }

            val completionResponse = completionResponseAdapter.fromJson(responseBody)
            if (completionResponse == null) {
                Log.e(TAG, "Failed to parse completion response")
                return@withContext Result.failure(IOException("Failed to parse response"))
            }

            Log.d(TAG, "Completion successful: ${completionResponse.completions?.size} candidates")

            Result.success(completionResponse)

        } catch (e: Exception) {
            Log.e(TAG, "Completion failed", e)
            Result.failure(e)
        }
    }

    /**
     * 测试服务端连接
     *
     * @return true 如果服务端可用
     */
    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            val primaryRequest = Request.Builder()
                .url(config.getGatewayHealthEndpoint())
                .get()
                .build()
            val primary = client.newCall(primaryRequest).execute()
            if (primary.isSuccessful) {
                return@withContext true
            }

            val fallbackRequest = Request.Builder()
                .url("${config.serverUrl}/health")
                .get()
                .build()
            val fallback = client.newCall(fallbackRequest).execute()
            fallback.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "Connection test failed", e)
            false
        }
    }

    /**
     * 更新配置
     */
    fun updateConfig(newConfig: NetworkConfig) {
        (this as? WordfillerApiClient)?.let {
            // 注意：由于client是lazy初始化，这里需要重新创建实例
            // 实际使用时建议通过依赖注入或单例管理
        }
    }
}

/**
 * API客户端单例
 */
object WordfillerApi {
    private var client: WordfillerApiClient? = null

    fun getInstance(config: NetworkConfig = NetworkConfig()): WordfillerApiClient {
        return client ?: WordfillerApiClient(config).also {
            client = it
        }
    }

    fun reset() {
        client = null
    }
}
