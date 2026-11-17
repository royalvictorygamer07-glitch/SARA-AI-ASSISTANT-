package com.example.ai.gemini

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.example.commands.AssistantCommand
import com.example.commands.CommandParser
import com.example.core.network.NetworkMonitor
import com.example.core.security.CredentialStore
import com.example.core.security.UserProfile
import com.example.memory.MemoryRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ChatTurn(
    val isUser: Boolean,
    val text: String
)

sealed interface GeminiResult {
    data class Success(val command: AssistantCommand, val rawText: String) : GeminiResult
    data class Failure(val userFacingMessage: String, val diagnostic: String, val isInvalidKey: Boolean = false) : GeminiResult
}

/**
 * Modular AI reasoning interface so Gemini can be swapped, upgraded, or streamed
 * independently of UI, STT, and TTS modules.
 */
interface GeminiClient {
    suspend fun generateAssistantResponse(
        userMessage: String,
        history: List<ChatTurn>,
        memories: List<MemoryRecord>,
        userProfile: UserProfile = UserProfile(),
        liveCallMode: Boolean
    ): GeminiResult

    suspend fun analyzeImage(
        bitmap: Bitmap,
        prompt: String,
        memories: List<MemoryRecord>
    ): GeminiResult

    suspend fun verifyApiKey(apiKey: String, modelName: String): Result<String>
}

class RestGeminiClient(
    private val credentialStore: CredentialStore,
    private val networkMonitor: NetworkMonitor
) : GeminiClient {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    override suspend fun generateAssistantResponse(
        userMessage: String,
        history: List<ChatTurn>,
        memories: List<MemoryRecord>,
        userProfile: UserProfile,
        liveCallMode: Boolean
    ): GeminiResult = withContext(Dispatchers.IO) {
        if (!networkMonitor.isOnline()) {
            return@withContext GeminiResult.Failure(
                userFacingMessage = "Internet connection check kijiye.",
                diagnostic = "Offline: no active internet transport"
            )
        }

        val creds = credentialStore.geminiCredentials.value
        if (!creds.isConfigured) {
            return@withContext GeminiResult.Failure(
                userFacingMessage = "Sir, Gemini se response nahi aa raha. Settings mein Gemini API key configure kijiye.",
                diagnostic = "Gemini API key missing",
                isInvalidKey = true
            )
        }

        val systemPrompt = SaraPersonaPromptBuilder.buildStructuredRestSystemInstruction(
            userProfile = userProfile,
            memories = memories,
            liveCallMode = liveCallMode
        )
        val contents = JSONArray().apply {
            history.takeLast(12).forEach { turn ->
                put(
                    JSONObject().apply {
                        put("role", if (turn.isUser) "user" else "model")
                        put("parts", JSONArray().put(JSONObject().put("text", turn.text)))
                    }
                )
            }
            put(
                JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", userMessage)))
                }
            )
        }

        val payload = JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            )
            put("contents", contents)
            put(
                "generationConfig",
                JSONObject().apply {
                    put("responseMimeType", "application/json")
                    put("temperature", if (liveCallMode) 0.55 else 0.7)
                    put("maxOutputTokens", if (liveCallMode) 768 else 1200)
                }
            )
        }

        executeGenerateContent(creds.apiKey, creds.modelName, payload, parseAsCommand = true)
    }

    override suspend fun analyzeImage(
        bitmap: Bitmap,
        prompt: String,
        memories: List<MemoryRecord>
    ): GeminiResult = withContext(Dispatchers.IO) {
        if (!networkMonitor.isOnline()) {
            return@withContext GeminiResult.Failure(
                userFacingMessage = "Internet connection check kijiye.",
                diagnostic = "Offline: no active internet transport"
            )
        }

        val creds = credentialStore.geminiCredentials.value
        if (!creds.isConfigured) {
            return@withContext GeminiResult.Failure(
                userFacingMessage = "Sir, Gemini se response nahi aa raha. Settings mein Gemini API key configure kijiye.",
                diagnostic = "Gemini API key missing",
                isInvalidKey = true
            )
        }

        val base64Jpeg = encodeBitmapForVision(bitmap)
        val systemText = buildString {
            append("You are Sara, a warm, friendly female JARVIS-style personal AI assistant analyzing visual input. ")
            append("Respond clearly, accurately, and concisely in the user's language (English or Hinglish as prompted). ")
            append("Never invent details not visible in the image.")
            if (memories.isNotEmpty()) {
                append("\nKnown user context: ")
                append(memories.take(5).joinToString("; ") { it.content })
            }
        }

        val userParts = JSONArray().apply {
            put(
                JSONObject().put(
                    "inlineData",
                    JSONObject().apply {
                        put("mimeType", "image/jpeg")
                        put("data", base64Jpeg)
                    }
                )
            )
            put(JSONObject().put("text", prompt.ifBlank { "Describe what is in this image clearly." }))
        }

        val payload = JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemText)))
            )
            put(
                "contents",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("parts", userParts)
                    }
                )
            )
            put(
                "generationConfig",
                JSONObject().apply {
                    put("temperature", 0.4)
                    put("maxOutputTokens", 1024)
                }
            )
        }

        executeGenerateContent(creds.apiKey, creds.modelName, payload, parseAsCommand = false)
    }

    override suspend fun verifyApiKey(apiKey: String, modelName: String): Result<String> =
        withContext(Dispatchers.IO) {
            val cleanKey = apiKey.trim()
            if (cleanKey.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Gemini API key is empty."))
            }
            if (!networkMonitor.isOnline()) {
                return@withContext Result.failure(IOException("Internet connection check kijiye."))
            }
            val model = com.example.core.security.GeminiCredentials.normalizeStandardModel(modelName)
            val payload = JSONObject().apply {
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().put(JSONObject().put("text", "Reply with OK.")))
                        }
                    )
                )
                put("generationConfig", JSONObject().put("maxOutputTokens", 256))
            }

            when (val result = executeGenerateContent(cleanKey, model, payload, parseAsCommand = false)) {
                is GeminiResult.Success -> Result.success("Connected ($model)")
                is GeminiResult.Failure -> Result.failure(IllegalStateException(result.diagnostic))
            }
        }

    private suspend fun executeGenerateContent(
        apiKey: String,
        modelName: String,
        payload: JSONObject,
        parseAsCommand: Boolean
    ): GeminiResult {
        val candidateModels = buildModelFallbackChain(modelName)
        var lastFailure: GeminiResult.Failure? = null

        for (candidateModel in candidateModels) {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$candidateModel:generateContent"
            val request = Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", apiKey)
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            try {
                val outcome = withTimeout(45_000L) {
                    httpClient.newCall(request).execute().use { response ->
                        val bodyString = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            val apiMessage = extractGoogleErrorMessage(bodyString)
                            Log.e(TAG, "Gemini HTTP ${response.code} ($candidateModel): $apiMessage")
                            val isAuthErr = response.code == 400 && apiMessage.contains("API key", ignoreCase = true) ||
                                response.code == 401 || response.code == 403
                            val diagnostic = when {
                                isAuthErr -> "Invalid Gemini API key (HTTP ${response.code})"
                                response.code == 429 -> "Gemini quota/rate limit reached (HTTP 429)"
                                else -> "Gemini HTTP ${response.code}: $apiMessage"
                            }
                            return@withTimeout GeminiOutcomeOrRetry(
                                result = GeminiResult.Failure(
                                    userFacingMessage = "Sir, Gemini se response nahi aa raha.",
                                    diagnostic = diagnostic,
                                    isInvalidKey = isAuthErr
                                ),
                                shouldTryFallbackModel = response.code == 404
                            )
                        }

                        val text = extractCandidateText(bodyString)
                        if (text.isNullOrBlank()) {
                            return@withTimeout GeminiOutcomeOrRetry(
                                result = GeminiResult.Failure(
                                    userFacingMessage = "Sir, Gemini se response nahi aa raha.",
                                    diagnostic = "Empty candidate response from Gemini"
                                ),
                                shouldTryFallbackModel = false
                            )
                        }

                        val command = if (parseAsCommand) {
                            CommandParser.parseModelOutput(text)
                        } else {
                            com.example.commands.AssistantCommand(
                                category = com.example.commands.ActionCategory.IMAGE_ANALYSIS,
                                spokenReply = text.trim()
                            )
                        }
                        GeminiOutcomeOrRetry(
                            result = GeminiResult.Success(command = command, rawText = text),
                            shouldTryFallbackModel = false
                        )
                    }
                }

                when (val r = outcome.result) {
                    is GeminiResult.Success -> return r
                    is GeminiResult.Failure -> {
                        lastFailure = r
                        if (!outcome.shouldTryFallbackModel) return r
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Gemini network failure", e)
                return GeminiResult.Failure(
                    userFacingMessage = "Sir, Gemini se response nahi aa raha.",
                    diagnostic = "Network error: ${e.localizedMessage ?: "unreachable"}"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Gemini unexpected failure", e)
                return GeminiResult.Failure(
                    userFacingMessage = "Sir, Gemini se response nahi aa raha.",
                    diagnostic = e.localizedMessage ?: "Unexpected Gemini failure"
                )
            }
        }
        return lastFailure ?: GeminiResult.Failure(
            userFacingMessage = "Sir, Gemini se response nahi aa raha.",
            diagnostic = "No compatible Gemini model responded"
        )
    }

    private data class GeminiOutcomeOrRetry(
        val result: GeminiResult,
        val shouldTryFallbackModel: Boolean
    )

    private fun buildModelFallbackChain(requestedModel: String): List<String> {
        val primary = com.example.core.security.GeminiCredentials.normalizeStandardModel(requestedModel)
        return listOf(
            primary,
            com.example.core.security.GeminiCredentials.DEFAULT_GEMINI_MODEL,
            com.example.core.security.GeminiCredentials.FALLBACK_GEMINI_MODEL
        ).distinct()
    }

    private fun extractCandidateText(bodyString: String): String? {
        return runCatching {
            val root = JSONObject(bodyString)
            val candidates = root.optJSONArray("candidates") ?: return null
            if (candidates.length() == 0) return null
            val content = candidates.getJSONObject(0).optJSONObject("content") ?: return null
            val parts = content.optJSONArray("parts") ?: return null
            val regularSb = StringBuilder()
            val anySb = StringBuilder()
            for (i in 0 until parts.length()) {
                val partObj = parts.optJSONObject(i) ?: continue
                val partText = partObj.optString("text", "")
                if (partText.isNotEmpty()) {
                    anySb.append(partText)
                    if (!partObj.optBoolean("thought", false)) {
                        regularSb.append(partText)
                    }
                }
            }
            regularSb.toString().ifBlank { anySb.toString() }.ifBlank { null }
        }.getOrNull()
    }

    private fun extractGoogleErrorMessage(bodyString: String): String {
        return runCatching {
            JSONObject(bodyString).optJSONObject("error")?.optString("message")
        }.getOrNull().orEmpty().ifBlank { "Request failed" }
    }

    private fun encodeBitmapForVision(bitmap: Bitmap): String {
        val maxDim = 1024
        val scale = minOf(
            1f,
            maxDim.toFloat() / maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)
        )
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else {
            bitmap
        }
        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 82, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    companion object {
        private const val TAG = "RestGeminiClient"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
