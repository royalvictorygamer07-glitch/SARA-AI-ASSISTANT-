package com.example.automation.whatsapp

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.SaraApplication

/**
 * Event-driven NotificationListenerService that captures incoming WhatsApp & WhatsApp Business
 * notifications without polling, extracts MessagingStyle & inline Reply RemoteInput actions,
 * and forwards them to WhatsAppAutomationManager.
 */
class SaraNotificationService : NotificationListenerService() {

    private val automationManager: WhatsAppAutomationManager? by lazy {
        (application as? SaraApplication)?.container?.whatsAppAutomationManager
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "SaraNotificationService connected.")
        automationManager?.onListenerConnected()
        val recentCutoffMs = System.currentTimeMillis() - 120_000L
        runCatching {
            activeNotifications
                ?.filter { it.packageName in WhatsAppAutoReply.WHATSAPP_PACKAGES }
                ?.forEach { sbn ->
                    val parsed = WhatsAppAutoReply.parseStatusBarNotification(sbn) ?: return@forEach
                    val latestTs = parsed.messages.maxOfOrNull { it.timestamp } ?: sbn.postTime
                    if (latestTs >= recentCutoffMs) {
                        automationManager?.onIncomingParsedNotification(parsed)
                    } else if (parsed.replyTarget != null) {
                        automationManager?.replySender?.registerTarget(parsed.replyTarget)
                    }
                }
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "SaraNotificationService disconnected.")
        automationManager?.onListenerDisconnected()
        runCatching {
            requestRebind(ComponentName(this, SaraNotificationService::class.java))
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.packageName !in WhatsAppAutoReply.WHATSAPP_PACKAGES) return

        val parsed = WhatsAppAutoReply.parseStatusBarNotification(sbn) ?: return
        automationManager?.onIncomingParsedNotification(parsed)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.packageName !in WhatsAppAutoReply.WHATSAPP_PACKAGES) return
        automationManager?.onNotificationRemoved(sbn.key)
    }

    companion object {
        private const val TAG = "SaraNotificationService"
    }
}
