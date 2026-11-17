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
                    put("maxOutputTokens", 1024)
                }
            )
        }

        executeWithFallbackAndRetry(
            apiKey = creds.apiKey,
            modelName = creds.modelName,
            payload = payload
        )
    }

    private suspend fun executeWithFallbackAndRetry(
        apiKey: String,
        modelName: String,
        payload: JSONObject
    ): GeminiReplyOutcome {
        val candidateModels = listOf(
            com.example.core.security.GeminiCredentials.normalizeStandardModel(modelName),
            com.example.core.security.GeminiCredentials.DEFAULT_GEMINI_MODEL,
            com.example.core.security.GeminiCredentials.FALLBACK_GEMINI_MODEL
        ).distinct()

        var lastError: GeminiReplyOutcome = GeminiReplyOutcome.Failure("Unknown Gemini error")

        for (candidateModel in candidateModels) {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$candidateModel:generateContent"
            var shouldTryNextModel = false

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
                                return@withTimeout AttemptResult(
                                    outcome = GeminiReplyOutcome.Failure(
                                        reason = "Gemini API rate limit (HTTP 429). Retry after ${retryAfterSec}s.",
                                        isRateLimited = true
                                    ),
                                    tryNextModel = false
                                )
                            }
                            if (!response.isSuccessful) {
                                val msg = parseGoogleError(bodyString)
                                return@withTimeout AttemptResult(
                                    outcome = GeminiReplyOutcome.Failure(
                                        reason = "Gemini HTTP ${response.code}: $msg"
                                    ),
                                    tryNextModel = response.code == 404
                                )
                            }

                            val rawText = extractCandidateText(bodyString)
                                ?: return@withTimeout AttemptResult(
                                    outcome = GeminiReplyOutcome.Failure("Empty reply from Gemini."),
                                    tryNextModel = false
                                )

                            AttemptResult(
                                outcome = parseModelResponse(rawText),
                                tryNextModel = false
                            )
                        }
                    }

                    if (outcome.tryNextModel) {
                        lastError = outcome.outcome
                        shouldTryNextModel = true
                        break
                    }
                    if (outcome.outcome is GeminiReplyOutcome.Failure && outcome.outcome.isRateLimited && attempt == 0) {
                        delay(1_500L)
                        lastError = outcome.outcome
                        continue
                    }
                    return outcome.outcome
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
            if (!shouldTryNextModel) return lastError
        }
        return lastError
    }

    private data class AttemptResult(
        val outcome: GeminiReplyOutcome,
        val tryNextModel: Boolean
    )

    companion object {
        private const val TAG = "GeminiReplyEngine"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        const val CORE_SYSTEM_PROMPT =
            """You are Sara AI, an intelligent personal WhatsApp assistant.
Your responsibility is to understand incoming WhatsApp messages and generate natural, human-like, context-aware responses on behalf of the owner.

Always:
• Identify the sender and their relationship/tone.
• Understand the full intent, question, or casual vibe of the message.
• Detect the sender's language accurately.
• Respond in the same language and communication style.
• Follow contact-specific instructions whenever present.
• Keep responses helpful, relevant, polite, and natural (1 to 3 concise sentences).
• Avoid repeating previous replies verbatim; vary wording naturally.
• Never invent facts about the owner.
• Never pretend to have completed an action that was not completed.

You are not a fixed auto-reply bot. You are a context-aware conversational assistant.
When ANY ordinary person sends a normal greeting, question, work query, or follow-up (even a short message like "hi", "kahan ho", "call karo", "what's up"), understand what they want from context and generate a thoughtful, warm, natural reply immediately.
If the message asks specific personal details you do not know, acknowledge it intelligently and say the owner will check and respond shortly."""

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
            appendLine("- ONLY set should_reply=false if the message is automated OTP/bank alert, obvious phishing/scam, or commercial spam. For all real human messages, set should_reply=true.")
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
                appendLine("Recent outgoing replies you already sent to $senderName (DO NOT repeat these verbatim — phrase freshly):")
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
            append("Reply language mode: $languageMode. Understand the message intent and generate the JSON response.")
        }

        fun parseModelResponse(rawText: String): GeminiReplyOutcome {
            val cleaned = rawText.trim()
                .removePrefix("```json")
                .removePrefix("```JSON")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            val jsonSlice = extractJsonObject(cleaned) ?: cleaned

            return runCatching {
                val json = JSONObject(jsonSlice)
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
                if (cleaned.isNotBlank() && !cleaned.contains("{") && !cleaned.contains("}")) {
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

        private fun extractJsonObject(text: String): String? {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            return if (start in 0 until end) {
                text.substring(start, end + 1)
            } else {
                null
            }
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

        private fun parseGoogleError(bodyString: String): String =
            runCatching { JSONObject(bodyString).optJSONObject("error")?.optString("message") }
                .getOrNull().orEmpty().ifBlank { "Request failed" }
    }
}
