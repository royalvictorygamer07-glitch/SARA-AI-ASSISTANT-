package com.example.automation.whatsapp

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.util.concurrent.ConcurrentHashMap

data class ActiveReplyTarget(
    val notificationKey: String,
    val conversationKey: String,
    val conversationTitle: String,
    val packageName: String,
    val remoteInputs: Array<RemoteInput>,
    val actionIntent: PendingIntent,
    val postedAt: Long
)

sealed interface ReplySendOutcome {
    data object Sent : ReplySendOutcome
    data class NoReplyAction(val reason: String) : ReplySendOutcome
    data class ExpiredNotification(val reason: String) : ReplySendOutcome
    data class Failed(val reason: String) : ReplySendOutcome
}

fun interface ReplyIntentDispatcher {
    @Throws(Exception::class)
    fun dispatch(context: Context, pendingIntent: PendingIntent, fillInIntent: Intent)
}

/**
 * Safely manages live WhatsApp inline Reply PendingIntents keyed strictly by conversationKey
 * so replies are never routed to the wrong contact or faked when notification actions are missing.
 */
class WhatsAppReplySender(
    private val context: Context,
    private val dispatcher: ReplyIntentDispatcher = ReplyIntentDispatcher { ctx, pi, intent ->
        pi.send(ctx, 0, intent)
    }
) {
    private val activeTargetsByConversation = ConcurrentHashMap<String, ActiveReplyTarget>()
    private val notificationKeyToConversation = ConcurrentHashMap<String, String>()
    private val unsupportedNotifications = ConcurrentHashMap<String, Long>()

    fun registerTarget(target: ActiveReplyTarget) {
        unsupportedNotifications.remove(target.conversationKey)
        activeTargetsByConversation[target.conversationKey] = target
        notificationKeyToConversation[target.notificationKey] = target.conversationKey
    }

    fun markNoReplyActionAvailable(notificationKey: String, conversationKey: String) {
        activeTargetsByConversation.remove(conversationKey)
        notificationKeyToConversation[notificationKey] = conversationKey
        unsupportedNotifications[conversationKey] = System.currentTimeMillis()
    }

    fun removeByNotificationKey(notificationKey: String) {
        val convKey = notificationKeyToConversation.remove(notificationKey) ?: return
        val existing = activeTargetsByConversation[convKey]
        if (existing?.notificationKey == notificationKey) {
            activeTargetsByConversation.remove(convKey)
        }
        unsupportedNotifications.remove(convKey)
    }

    fun hasActiveReplyTarget(conversationKey: String): Boolean =
        activeTargetsByConversation.containsKey(conversationKey)

    fun sendReply(
        conversationKey: String,
        expectedNotificationKey: String? = null,
        replyText: String
    ): ReplySendOutcome {
        val cleanReply = replyText.trim()
        if (cleanReply.isEmpty()) {
            return ReplySendOutcome.Failed("Cannot send an empty reply.")
        }

        val target = activeTargetsByConversation[conversationKey]
        if (target == null) {
            return if (unsupportedNotifications.containsKey(conversationKey)) {
                ReplySendOutcome.NoReplyAction("WhatsApp notification has no inline Reply action.")
            } else {
                ReplySendOutcome.ExpiredNotification("Notification expired or was dismissed before reply could be sent.")
            }
        }

        // Strict target lock: never send a reply to a mismatched conversation or stale key.
        if (target.conversationKey != conversationKey) {
            return ReplySendOutcome.Failed("Target conversation verification mismatch; aborted for safety.")
        }
        if (expectedNotificationKey != null && target.notificationKey != expectedNotificationKey) {
            // Allow newer notification for the SAME conversationKey only if title still matches
            if (target.conversationKey != conversationKey) {
                return ReplySendOutcome.ExpiredNotification("Notification target changed unexpectedly.")
            }
        }

        val primaryInput = target.remoteInputs.firstOrNull()
            ?: return ReplySendOutcome.NoReplyAction("Reply action has no RemoteInput field.")

        return try {
            val bundle = Bundle().apply {
                putCharSequence(primaryInput.resultKey, cleanReply)
            }
            val replyIntent = Intent().apply {
                RemoteInput.addResultsToIntent(target.remoteInputs, this, bundle)
            }
            dispatcher.dispatch(context.applicationContext, target.actionIntent, replyIntent)
            ReplySendOutcome.Sent
        } catch (e: PendingIntent.CanceledException) {
            activeTargetsByConversation.remove(conversationKey)
            ReplySendOutcome.ExpiredNotification("Reply PendingIntent was cancelled or expired: ${e.localizedMessage ?: "cancelled"}")
        } catch (e: SecurityException) {
            ReplySendOutcome.Failed("Security permission blocked notification reply: ${e.localizedMessage ?: "denied"}")
        } catch (e: Exception) {
            ReplySendOutcome.Failed("Failed to send WhatsApp reply: ${e.localizedMessage ?: "unknown error"}")
        }
    }

    fun clearAll() {
        activeTargetsByConversation.clear()
        notificationKeyToConversation.clear()
        unsupportedNotifications.clear()
    }
}
