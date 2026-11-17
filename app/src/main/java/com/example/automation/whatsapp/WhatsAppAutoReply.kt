package com.example.automation.whatsapp

import android.app.Notification
import android.app.RemoteInput
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.example.core.security.ElevenLabsCredentials
import com.example.core.security.GeminiCredentials
import java.security.MessageDigest

data class WhatsAppIncomingMessage(
    val notificationKey: String,
    val packageName: String,
    val conversationKey: String,
    val conversationTitle: String,
    val senderName: String,
    val messageText: String,
    val isGroupConversation: Boolean,
    val timestamp: Long,
    val fingerprint: String
)

data class ParsedWhatsAppNotification(
    val conversationKey: String,
    val conversationTitle: String,
    val isGroupConversation: Boolean,
    val messages: List<WhatsAppIncomingMessage>,
    val replyTarget: ActiveReplyTarget?
)

sealed interface SafetyValidationResult {
    data class Safe(val sanitizedReply: String) : SafetyValidationResult
    data class Blocked(val reason: String) : SafetyValidationResult
}

/**
 * Handles WhatsApp notification parsing (MessagingStyle, bundled messages, inline RemoteInput
 * extraction), message fingerprinting, spam screening, and reply safety validation.
 */
object WhatsAppAutoReply {

    val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

    private val IGNORED_SUMMARY_PATTERNS = listOf(
        "messages from",
        "checking for new messages",
        "missed voice call",
        "missed video call",
        "incoming voice call",
        "incoming video call",
        "ongoing voice call",
        "ongoing video call",
        "backing up messages",
        "new messages"
    )

    private val SPAM_OR_PHISHING_INDICATORS = listOf(
        "your otp is",
        "one time password",
        "verification code",
        "do not share otp",
        "account suspended",
        "kyc update",
        "click this link",
        "won a prize",
        "claim your reward",
        "lottery winner",
        "upi pin",
        "share your cvv",
        "bank password"
    )

    private val PAYMENT_OR_SECRET_PATTERNS = listOf(
        "i will pay",
        "payment approved",
        "sending money",
        "upi pin",
        "my password is",
        "api key",
        "aiza",
        "xi-"
    )

    fun parseStatusBarNotification(sbn: StatusBarNotification?): ParsedWhatsAppNotification? {
        if (sbn == null) return null
        if (sbn.packageName !in WHATSAPP_PACKAGES) return null

        val notification = sbn.notification ?: return null
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            return null
        }

        val extras = notification.extras
        val rawTitle = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val rawConvTitle = extras?.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim().orEmpty()

        val messagingStyle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        val isGroup = messagingStyle?.isGroupConversation == true ||
            (rawConvTitle.isNotBlank() && !rawConvTitle.equals(rawTitle, ignoreCase = true))

        val conversationTitle = when {
            messagingStyle?.conversationTitle?.isNotBlank() == true -> messagingStyle.conversationTitle.toString().trim()
            rawConvTitle.isNotBlank() -> rawConvTitle
            rawTitle.isNotBlank() -> rawTitle
            else -> return null
        }

        if (isIgnoredSystemAlert(conversationTitle)) return null

        val conversationKey = buildConversationKey(sbn.packageName, conversationTitle, sbn.key)
        val parsedMessages = mutableListOf<WhatsAppIncomingMessage>()

        if (messagingStyle != null && messagingStyle.messages.isNotEmpty()) {
            val selfName = messagingStyle.user.name?.toString()?.trim().orEmpty()
            messagingStyle.messages.forEach { msg ->
                val text = msg.text?.toString()?.trim().orEmpty()
                if (text.isBlank() || isIgnoredSystemAlert(text)) return@forEach

                val sender = msg.person?.name?.toString()?.trim()
                    .orEmpty()
                    .ifBlank { conversationTitle }

                // Filter own outgoing echoes posted in MessagingStyle notifications
                if (isSelfSender(sender, selfName)) return@forEach

                val ts = if (msg.timestamp > 0L) msg.timestamp else sbn.postTime
                val fp = buildMessageFingerprint(conversationKey, sender, text, ts)
                parsedMessages.add(
                    WhatsAppIncomingMessage(
                        notificationKey = sbn.key,
                        packageName = sbn.packageName,
                        conversationKey = conversationKey,
                        conversationTitle = conversationTitle,
                        senderName = sender,
                        messageText = text,
                        isGroupConversation = isGroup,
                        timestamp = ts,
                        fingerprint = fp
                    )
                )
            }
        } else {
            val textLines = extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) }
                .orEmpty()
            val bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
            val bodyText = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()

            val rawEntries = if (textLines.isNotEmpty()) {
                textLines
            } else {
                listOfNotNull(bigText.ifBlank { bodyText }.takeIf { it.isNotBlank() })
            }

            rawEntries.forEachIndexed { idx, entry ->
                if (isIgnoredSystemAlert(entry)) return@forEachIndexed
                val (sender, actualText) = extractSenderFromFallbackText(
                    defaultTitle = rawTitle.ifBlank { conversationTitle },
                    rawBody = entry,
                    allowSenderPrefixSplit = isGroup || textLines.isNotEmpty()
                )
                if (!isSelfSender(sender, "") && actualText.isNotBlank()) {
                    val baseTs = sbn.postTime.takeIf { it > 0L } ?: System.currentTimeMillis()
                    val ts = baseTs + idx
                    val fp = buildMessageFingerprint(conversationKey, sender, actualText, ts)
                    parsedMessages.add(
                        WhatsAppIncomingMessage(
                            notificationKey = sbn.key,
                            packageName = sbn.packageName,
                            conversationKey = conversationKey,
                            conversationTitle = conversationTitle,
                            senderName = sender,
                            messageText = actualText,
                            isGroupConversation = isGroup,
                            timestamp = ts,
                            fingerprint = fp
                        )
                    )
                }
            }
        }

        if (parsedMessages.isEmpty()) return null

        val replyTarget = extractReplyTarget(
            sbn = sbn,
            notification = notification,
            conversationKey = conversationKey,
            conversationTitle = conversationTitle
        )

        return ParsedWhatsAppNotification(
            conversationKey = conversationKey,
            conversationTitle = conversationTitle,
            isGroupConversation = isGroup,
            messages = parsedMessages,
            replyTarget = replyTarget
        )
    }

    fun extractReplyTarget(
        sbn: StatusBarNotification,
        notification: Notification,
        conversationKey: String,
        conversationTitle: String
    ): ActiveReplyTarget? {
        val actions = notification.actions ?: return null
        val candidates = mutableListOf<Pair<Notification.Action, Array<RemoteInput>>>()

        for (action in actions) {
            val inputs = action.remoteInputs
            val actionIntent = action.actionIntent
            if (inputs != null && inputs.isNotEmpty() && actionIntent != null) {
                candidates.add(action to inputs)
            }
        }
        if (candidates.isEmpty()) return null

        val preferred = candidates.firstOrNull { (action, _) ->
            val title = action.title?.toString()?.lowercase().orEmpty()
            title.contains("reply") ||
                title.contains("jawab") ||
                title.contains("उत्तर") ||
                title.contains("जवाब") ||
                title.contains("send") ||
                title.contains("respond")
        } ?: candidates.first()

        return ActiveReplyTarget(
            notificationKey = sbn.key,
            conversationKey = conversationKey,
            conversationTitle = conversationTitle,
            packageName = sbn.packageName,
            remoteInputs = preferred.second,
            actionIntent = preferred.first.actionIntent,
            postedAt = sbn.postTime
        )
    }

    fun checkIncomingSafety(messageText: String): SafetyValidationResult {
        val lower = messageText.lowercase()
        val suspiciousHit = SPAM_OR_PHISHING_INDICATORS.firstOrNull { lower.contains(it) }
        if (suspiciousHit != null) {
            return SafetyValidationResult.Blocked("Skipped spam/OTP/sensitive message ($suspiciousHit).")
        }
        if (lower.contains("http://") || lower.contains("https://")) {
            val scamVerb = listOf("claim", "verify", "reward", "lottery", "bank", "kyc").any { lower.contains(it) }
            if (scamVerb) {
                return SafetyValidationResult.Blocked("Skipped suspicious phishing link message.")
            }
        }
        return SafetyValidationResult.Safe(messageText)
    }

    fun validateGeneratedReply(
        replyText: String,
        recentOutgoingReplies: List<String>,
        geminiCreds: GeminiCredentials,
        elevenCreds: ElevenLabsCredentials
    ): SafetyValidationResult {
        val trimmed = replyText.trim()
        if (trimmed.isEmpty()) {
            return SafetyValidationResult.Blocked("Generated reply was empty.")
        }
        if (trimmed.length > 600) {
            return SafetyValidationResult.Safe(trimmed.take(597) + "...")
        }

        val lower = trimmed.lowercase()
        if (geminiCreds.apiKey.isNotBlank() && trimmed.contains(geminiCreds.apiKey)) {
            return SafetyValidationResult.Blocked("Blocked reply containing Gemini API key.")
        }
        if (elevenCreds.apiKey.isNotBlank() && trimmed.contains(elevenCreds.apiKey)) {
            return SafetyValidationResult.Blocked("Blocked reply containing ElevenLabs API key.")
        }

        val forbiddenPattern = PAYMENT_OR_SECRET_PATTERNS.firstOrNull { lower.contains(it) }
        if (forbiddenPattern != null) {
            return SafetyValidationResult.Blocked("Blocked unsafe financial/secret content ($forbiddenPattern).")
        }

        val normalizedNew = normalizeForComparison(trimmed)
        if (recentOutgoingReplies.any { normalizeForComparison(it) == normalizedNew }) {
            return SafetyValidationResult.Blocked("Prevented repetitive verbatim reply.")
        }

        return SafetyValidationResult.Safe(trimmed)
    }

    fun buildConversationKey(packageName: String, conversationTitle: String, fallbackKey: String): String {
        val cleanTitle = conversationTitle.trim().lowercase().replace("\\s+".toRegex(), " ")
        return if (cleanTitle.isNotEmpty()) {
            "$packageName::$cleanTitle"
        } else {
            "$packageName::$fallbackKey"
        }
    }

    fun buildMessageFingerprint(
        conversationKey: String,
        senderName: String,
        messageText: String,
        timestamp: Long
    ): String {
        val bucketSec = timestamp / 2000L
        val raw = "${conversationKey.lowercase()}|${senderName.trim().lowercase()}|${messageText.trim()}|$bucketSec"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    private fun extractSenderFromFallbackText(
        defaultTitle: String,
        rawBody: String,
        allowSenderPrefixSplit: Boolean
    ): Pair<String, String> {
        if (allowSenderPrefixSplit && rawBody.contains(": ")) {
            val idx = rawBody.indexOf(": ")
            if (idx in 1..40) {
                val sender = rawBody.substring(0, idx).trim()
                val body = rawBody.substring(idx + 2).trim()
                val lowerSender = sender.lowercase()
                if (lowerSender != "http" && lowerSender != "https" && body.isNotEmpty()) {
                    return sender to body
                }
            }
        }
        return defaultTitle to rawBody
    }

    private fun isSelfSender(sender: String, selfName: String): Boolean {
        val s = sender.trim()
        if (s.equals("You", ignoreCase = true) || s.equals("आप", ignoreCase = true)) return true
        if (selfName.isNotBlank() && s.equals(selfName, ignoreCase = true)) return true
        return false
    }

    private fun isIgnoredSystemAlert(text: String): Boolean {
        val lower = text.trim().lowercase()
        if (lower.isEmpty()) return true
        return IGNORED_SUMMARY_PATTERNS.any { lower.contains(it) }
    }

    private fun normalizeForComparison(text: String): String =
        text.lowercase().replace("[^\\p{L}\\p{Nd}]".toRegex(), "")
}
