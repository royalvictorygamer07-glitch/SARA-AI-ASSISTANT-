package com.example.commands

import com.example.memory.MemoryCategory
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi

@JsonClass(generateAdapter = true)
internal data class RawGeminiCommandJson(
    val action: String? = null,
    val reply: String? = null,
    @Json(name = "spoken_reply") val spokenReply: String? = null,
    val target: String? = null,
    val query: String? = null,
    val message: String? = null,
    val channel: String? = null,
    @Json(name = "requires_confirmation") val requiresConfirmation: Boolean? = null,
    @Json(name = "memory_note") val memoryNote: String? = null,
    @Json(name = "memory_category") val memoryCategory: String? = null
)

object CommandParser {

    private val moshi: Moshi by lazy { Moshi.Builder().build() }
    private val adapter by lazy { moshi.adapter(RawGeminiCommandJson::class.java) }

    fun parseModelOutput(rawText: String): AssistantCommand {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) {
            return AssistantCommand(
                category = ActionCategory.CHAT,
                spokenReply = "Sir, main sun rahi hoon."
            )
        }

        val jsonCandidate = extractJsonObject(trimmed)
        if (jsonCandidate != null) {
            runCatching {
                val parsed = adapter.fromJson(jsonCandidate) ?: return@runCatching
                val reply = parsed.reply?.takeIf { it.isNotBlank() }
                    ?: parsed.spokenReply?.takeIf { it.isNotBlank() }
                    ?: trimmed
                val actionRaw = parsed.action?.uppercase()?.trim().orEmpty().ifBlank { "CHAT" }
                val category = runCatching { ActionCategory.valueOf(actionRaw) }
                    .getOrDefault(ActionCategory.CHAT)
                val target = parsed.target.orEmpty().trim()
                val query = parsed.query.orEmpty().trim()
                val messageBody = parsed.message.orEmpty().trim()
                val channel = parsed.channel.orEmpty().trim()
                val requiresConfirmation = parsed.requiresConfirmation
                    ?: (category == ActionCategory.CALL_CONTACT || category == ActionCategory.SEND_MESSAGE)
                val memoryNote = parsed.memoryNote.orEmpty().trim()
                val memCatRaw = parsed.memoryCategory?.uppercase()?.trim().orEmpty().ifBlank { "CONVERSATION" }
                val memoryCategory = runCatching { MemoryCategory.valueOf(memCatRaw) }
                    .getOrDefault(MemoryCategory.CONVERSATION)

                return AssistantCommand(
                    category = category,
                    spokenReply = reply,
                    target = target,
                    query = query,
                    messageBody = messageBody,
                    channel = channel,
                    requiresConfirmation = requiresConfirmation,
                    memoryNote = memoryNote,
                    memoryCategory = memoryCategory
                )
            }
        }

        return AssistantCommand(
            category = ActionCategory.CHAT,
            spokenReply = trimmed
        )
    }

    private fun extractJsonObject(text: String): String? {
        val unfenced = text
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = unfenced.indexOf('{')
        val end = unfenced.lastIndexOf('}')
        if (start in 0 until end) {
            return unfenced.substring(start, end + 1)
        }
        return null
    }

    fun isAffirmativeConfirmation(utterance: String): Boolean {
        val norm = utterance.trim().lowercase()
        val yesTokens = setOf(
            "yes", "yeah", "yep", "yup", "confirm", "confirmed", "ok", "okay",
            "sure", "do it", "go ahead", "haan", "ha", "han", "hanji", "karo",
            "kar do", "kardo", "bhej do", "call karo", "proceed"
        )
        return yesTokens.any { token ->
            norm == token || norm.startsWith("$token ") || norm.contains(" $token")
        }
    }

    fun isNegativeConfirmation(utterance: String): Boolean {
        val norm = utterance.trim().lowercase()
        val noTokens = setOf(
            "no", "nope", "cancel", "stop", "don't", "do not", "abort",
            "nahi", "na", "mat karo", "ruko", "cancel karo"
        )
        return noTokens.any { token ->
            norm == token || norm.startsWith("$token ") || norm.contains(" $token")
        }
    }
}
