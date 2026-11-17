package com.example.automation.whatsapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.SaraApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Restores WorkManager watchdog and requests NotificationListenerService rebind
 * after device reboot or app update when WhatsApp Automation is enabled.
 */
class SaraBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        val app = context.applicationContext as? SaraApplication ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val manager = app.container.whatsAppAutomationManager
                val settings = manager.settingsStore.settings.first()
                if (settings.automationEnabled) {
                    manager.requestListenerRebindIfNeeded()
                    manager.scheduleWatchdogWorker()
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
