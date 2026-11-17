package com.example.automation.whatsapp

import android.util.Log
import com.example.core.network.NetworkMonitor
import com.example.core.security.CredentialStore
import com.example.core.security.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed interface GeminiReplyOutcome {
    data class Generated(
        val replyText: String,
        val detectedLanguage: String,
        val intent: String
    ) : GeminiReplyOutcome

    data class SkippedBySafety(val reason: String) : GeminiReplyOutcome

    data class Failure(
        val reason: String,
        val isRateLimited: Boolean = false,
        val isNetworkError: Boolean = false
    ) : GeminiReplyOutcome
}

/**
 * Gemini AI Brain dedicated to context-aware, multilingual WhatsApp auto-replies.
 * Reuses the Gemini API key & model configured in CredentialStore without hardcoding keys.
 */
class GeminiReplyEngine(
    private val credentialStore: CredentialStore,
    private val networkMonitor: NetworkMonitor,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    @Volatile
    private var rateLimitBlockedUntilMs: Long = 0L

    suspend fun generateWhatsAppReply(
        senderName: String,
        conversationTitle: String,
        isGroupConversation: Boolean,
        incomingMessage: String,
        contextBundle: ConversationContextBundle,
        ruleInstruction: String?,
        settings: AutomationSettings
    ): GeminiReplyOutcome = withContext(Dispatchers.IO) {
        if (!networkMonitor.isOnline()) {
            return@withContext GeminiReplyOutcome.Failure(
                reason = "Internet unavailable — cannot reach Gemini AI.",
                isNetworkError = true
            )
        }

        val creds = credentialStore.geminiCredentials.value
        if (!creds.isConfigured) {
            return@withContext GeminiReplyOutcome.Failure(
                reason = "Gemini API key not configured in Settings."
            )
        }

        val now = System.currentTimeMillis()
        if (now < rateLimitBlockedUntilMs) {
            val waitSec = ((rateLimitBlockedUntilMs - now) / 1000L).coerceAtLeast(1L)
            return@withContext GeminiReplyOutcome.Failure(
                reason = "Gemini rate limit cooldown active (${waitSec}s remaining).",
                isRateLimited = true
            )
        }

        val userProfile = runCatching { credentialStore.userProfile.first() }
            .getOrDefault(UserProfile())

        val systemInstruction = buildSystemPrompt(
            userProfile = userProfile,
            senderName = senderName,
            conversationTitle = conversationTitle,
            isGroupConversation = isGroupConversation,
            contextBundle = contextBundle,
            ruleInstruction = ruleInstruction,
            settings = settings
        )

        val contents = JSONArray().apply {
            // Feed only this specific contact's prior turns so cross-contact privacy is preserved.
            contextBundle.recentMessagesAsc
                .takeLast(10)
                .filter { it.messageText != incomingMessage || it.isOutgoing }
                .forEach { msg ->
                    put(
                        JSONObject().apply {
                            put("role", if (msg.isOutgoing) "model" else "user")
                            val prefix = if (msg.isOutgoing) "" else "${msg.senderName}: "
                            put("parts", JSONArray().put(JSONObject().put("text", "$prefix${msg.messageText}")))
                        }
                    )
                }

            put(
                JSONObject().apply {
                    put("role", "user")
                    put(
                        "parts",
                        JSONArray().put(
                            JSONObject().put(
                                "text",
                                buildIncomingTurnPrompt(senderName, incomingMessage, settings.replyLanguageMode)
                            )
                        )
                    )
                }
            )
        }

        val payload = JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
            )
            put("contents", contents)
            put(
                "generationConfig",
                JSONObject().apply {
                    put("responseMimeType", "application/json")
                    put("temperature", 0.65)
                    put("maxOutputTokens", 320)
                }
            )
        }

        executeWithRateLimitRetry(
            apiKey = creds.apiKey,
            modelName = creds.modelName,
            payload = payload
        )
    }

    private suspend fun executeWithRateLimitRetry(
        apiKey: String,
        modelName: String,
        payload: JSONObject
    ): GeminiReplyOutcome {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent"
        var lastError = GeminiReplyOutcome.Failure("Unknown Gemini error")

        for (attempt in 0..1) {
            val request = Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", apiKey)
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            try {
                val outcome = withTimeout(35_000L) {
                    httpClient.newCall(request).execute().use { response ->
                        val bodyString = response.body?.string().orEmpty()
                        if (response.code == 429) {
                            val retryAfterSec = response.header("Retry-After")?.toLongOrNull() ?: 15L
                            rateLimitBlockedUntilMs = System.currentTimeMillis() + (retryAfterSec * 1000L)
                            return@withTimeout GeminiReplyOutcome.Failure(
                                reason = "Gemini API rate limit (HTTP 429). Retry after ${retryAfterSec}s.",
                                isRateLimited = true
                            )
                        }
                        if (!response.isSuccessful) {
                            val msg = parseGoogleError(bodyString)
                            return@withTimeout GeminiReplyOutcome.Failure(
                                reason = "Gemini HTTP ${response.code}: $msg"
                            )
                        }

                        val rawText = extractCandidateText(bodyString)
                            ?: return@withTimeout GeminiReplyOutcome.Failure("Empty reply from Gemini.")

                        parseModelResponse(rawText)
                    }
                }

                if (outcome is GeminiReplyOutcome.Failure && outcome.isRateLimited && attempt == 0) {
                    delay(1_500L)
                    lastError = outcome
                    continue
                }
                return outcome
            } catch (e: IOException) {
                Log.e(TAG, "Gemini network error", e)
                return GeminiReplyOutcome.Failure(
                    reason = "Network failure: ${e.localizedMessage ?: "unreachable"}",
                    isNetworkError = true
                )
            } catch (e: Exception) {
                Log.e(TAG, "Gemini error", e)
                lastError = GeminiReplyOutcome.Failure(
                    reason = e.localizedMessage ?: "Gemini generation failed"
                )
            }
        }
        return lastError
    }

    companion object {
        private const val TAG = "GeminiReplyEngine"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        const val CORE_SYSTEM_PROMPT =
            """You are Sara AI, an intelligent personal WhatsApp assistant.
Your responsibility is to understand incoming WhatsApp messages and generate natural, human-like, context-aware responses on behalf of the owner.

Always:
• Identify the sender.
• Understand the conversation.
• Detect the sender's language.
• Respond in the same language and communication style.
• Follow contact-specific instructions.
• Keep responses relevant and natural.
• Avoid repeating previous replies.
• Never invent facts about the owner.
• Never pretend to have completed an action that was not completed.

You are not a fixed auto-reply bot. You are a context-aware conversational assistant.
Use the recent conversation history and personalized sender rules to determine the best response.
If the message requires information you do not know, do not guess. Generate a safe response explaining that the owner will respond personally."""

        fun buildSystemPrompt(
            userProfile: UserProfile,
            senderName: String,
            conversationTitle: String,
            isGroupConversation: Boolean,
            contextBundle: ConversationContextBundle,
            ruleInstruction: String?,
            settings: AutomationSettings
        ): String = buildString {
            appendLine(CORE_SYSTEM_PROMPT)
            appendLine()
            appendLine("STRICT SAFETY & PRIVACY RULES:")
            appendLine("- NEVER reveal API keys, tokens, passwords, OTPs, PINs, or private credentials.")
            appendLine("- NEVER disclose private information from other conversations.")
            appendLine("- NEVER make financial commitments, approve payments, or accept account-changing requests.")
            appendLine("- If the message is spam, phishing, OTP alert, or promotional marketing, set should_reply=false.")
            appendLine()
            if (userProfile.name.isNotBlank()) {
                appendLine("Owner Name: ${userProfile.name}")
            }
            if (userProfile.preferencesNote.isNotBlank()) {
                appendLine("Owner General Preferences: ${userProfile.preferencesNote}")
            }
            appendLine("Active Sender: $senderName (Chat: $conversationTitle, Group: $isGroupConversation)")
            if (contextBundle.profile.senderPreferences.isNotBlank()) {
                appendLine("Sender Preferences / Relationship: ${contextBundle.profile.senderPreferences}")
            }
            if (contextBundle.profile.customInstructions.isNotBlank()) {
                appendLine("Contact Custom Instructions: ${contextBundle.profile.customInstructions}")
            }
            if (!ruleInstruction.isNullOrBlank()) {
                appendLine("Priority Rule Instruction (MUST FOLLOW): $ruleInstruction")
            }
            if (contextBundle.recentOutgoingReplies.isNotEmpty()) {
                appendLine("Recent outgoing replies you already sent to $senderName (DO NOT repeat these verbatim):")
                contextBundle.recentOutgoingReplies.forEach { prev ->
                    appendLine("- \"$prev\"")
                }
            }
            if (settings.replyLanguageMode == AutomationSettings.LANGUAGE_AUTOMATIC) {
                appendLine("Language Mode: AUTOMATIC — detect the exact language of the latest incoming message (Hindi, English, Hinglish, Bengali, Urdu, etc.) and write 'reply' naturally in that same language.")
            } else {
                appendLine("Language Mode Override: Respond in ${settings.replyLanguageMode}.")
            }
            appendLine()
            appendLine("Return ONLY valid JSON with this schema:")
            append("""{"detected_language":"Hindi|English|Hinglish|Bengali|Urdu|Other","intent":"brief intent","should_reply":true,"safety_reason":"","reply":"natural concise WhatsApp reply"}""")
        }

        private fun buildIncomingTurnPrompt(
            senderName: String,
            incomingMessage: String,
            languageMode: String
        ): String = buildString {
            appendLine("New incoming WhatsApp message from $senderName:")
            appendLine("\"\"\"")
            appendLine(incomingMessage)
            appendLine("\"\"\"")
            append("Reply language mode: $languageMode. Generate the JSON response.")
        }

        fun parseModelResponse(rawText: String): GeminiReplyOutcome {
            val cleaned = rawText.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            return runCatching {
                val json = JSONObject(cleaned)
                val shouldReply = json.optBoolean("should_reply", true)
                val safetyReason = json.optString("safety_reason", "").trim()
                val detectedLanguage = json.optString("detected_language", "Automatic").trim()
                val intent = json.optString("intent", "").trim()
                val reply = json.optString("reply", "").trim()

                if (!shouldReply) {
                    return GeminiReplyOutcome.SkippedBySafety(
                        safetyReason.ifBlank { "Skipped by Gemini safety/relevance check." }
                    )
                }
                if (reply.isEmpty()) {
                    return GeminiReplyOutcome.Failure("Gemini returned an empty reply.")
                }
                GeminiReplyOutcome.Generated(
                    replyText = reply,
                    detectedLanguage = detectedLanguage.ifBlank { "Automatic" },
                    intent = intent
                )
            }.getOrElse {
                // If model returned plain text despite JSON MIME type, use safely if non-empty
                if (cleaned.isNotBlank() && !cleaned.startsWith("{")) {
                    GeminiReplyOutcome.Generated(
                        replyText = cleaned,
                        detectedLanguage = "Automatic",
                        intent = "conversational"
                    )
                } else {
                    GeminiReplyOutcome.Failure("Failed to parse Gemini JSON reply.")
                }
            }
        }

        private fun extractCandidateText(bodyString: String): String? {
            return runCatching {
                val root = JSONObject(bodyString)
                val candidates = root.optJSONArray("candidates") ?: return null
                if (candidates.length() == 0) return null
                val content = candidates.getJSONObject(0).optJSONObject("content") ?: return null
                val parts = content.optJSONArray("parts") ?: return null
                val sb = StringBuilder()
                for (i in 0 until parts.length()) {
                    sb.append(parts.getJSONObject(i).optString("text", ""))
                }
                sb.toString().ifBlank { null }
            }.getOrNull()
        }

        private fun parseGoogleError(bodyString: String): String =
            runCatching { JSONObject(bodyString).optJSONObject("error")?.optString("message") }
                .getOrNull().orEmpty().ifBlank { "Request failed" }
    }
}
