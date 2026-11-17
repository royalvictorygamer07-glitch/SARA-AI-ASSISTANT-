package com.example.automation.whatsapp

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.whatsAppAutomationPrefs: DataStore<Preferences> by preferencesDataStore(
    name = "sara_whatsapp_automation_prefs"
)

enum class AutomationServiceStatus(val label: String) {
    RUNNING("Running"),
    STOPPED("Stopped"),
    WAITING_FOR_NOTIFICATION("Waiting for notification"),
    PROCESSING_MESSAGE("Processing message"),
    GENERATING_REPLY("Generating reply"),
    REPLY_SENT("Reply sent"),
    REPLY_FAILED("Reply failed")
}

data class AutomationSettings(
    val automationEnabled: Boolean = false,
    val geminiBrainEnabled: Boolean = true,
    val autoReplyEnabled: Boolean = true,
    val voiceAnnouncementEnabled: Boolean = false,
    val readIncomingMessagesAloud: Boolean = false,
    val replyLanguageMode: String = LANGUAGE_AUTOMATIC,
    val aiReplyDelaySeconds: Int = 2,
    val maxMessagesPerConversation: Int = 15,
    val replyToGroupChats: Boolean = false
) {
    companion object {
        const val LANGUAGE_AUTOMATIC = "Automatic"
        val SUPPORTED_LANGUAGE_MODES = listOf(
            LANGUAGE_AUTOMATIC,
            "English",
            "Hindi",
            "Hinglish",
            "Bengali",
            "Urdu"
        )
    }
}

class AutomationSettingsStore(context: Context) {
    private val appContext = context.applicationContext

    val settings: Flow<AutomationSettings> = appContext.whatsAppAutomationPrefs.data.map { prefs ->
        AutomationSettings(
            automationEnabled = prefs[KEY_AUTOMATION_ENABLED] ?: false,
            geminiBrainEnabled = prefs[KEY_GEMINI_BRAIN_ENABLED] ?: true,
            autoReplyEnabled = prefs[KEY_AUTO_REPLY_ENABLED] ?: true,
            voiceAnnouncementEnabled = prefs[KEY_VOICE_ANNOUNCEMENT] ?: false,
            readIncomingMessagesAloud = prefs[KEY_READ_INCOMING_ALOUD] ?: false,
            replyLanguageMode = prefs[KEY_REPLY_LANGUAGE_MODE] ?: AutomationSettings.LANGUAGE_AUTOMATIC,
            aiReplyDelaySeconds = (prefs[KEY_AI_REPLY_DELAY_SECONDS] ?: 2).coerceIn(0, 15),
            maxMessagesPerConversation = (prefs[KEY_MAX_MESSAGES_PER_CONV] ?: 15).coerceIn(1, 100),
            replyToGroupChats = prefs[KEY_REPLY_GROUP_CHATS] ?: false
        )
    }

    suspend fun setAutomationEnabled(enabled: Boolean) {
        appContext.whatsAppAutomationPrefs.edit { it[KEY_AUTOMATION_ENABLED] = enabled }
    }

    suspend fun setGeminiBrainEnabled(enabled: Boolean) {
        appContext.whatsAppAutomationPrefs.edit { it[KEY_GEMINI_BRAIN_ENABLED] = enabled }
    }

    suspend fun setAutoReplyEnabled(enabled: Boolean) {
        appContext.whatsAppAutomationPrefs.edit { it[KEY_AUTO_REPLY_ENABLED] = enabled }
    }

    suspend fun setVoiceAnnouncementEnabled(enabled: Boolean) {
        appContext.whatsAppAutomationPrefs.edit { it[KEY_VOICE_ANNOUNCEMENT] = enabled }
    }

    suspend fun setReadIncomingMessagesAloud(enabled: Boolean) {
        appContext.whatsAppAutomationPrefs.edit { it[KEY_READ_INCOMING_ALOUD] = enabled }
    }

    suspend fun setReplyLanguageMode(languageMode: String) {
        appContext.whatsAppAutomationPrefs.edit {
            it[KEY_REPLY_LANGUAGE_MODE] = languageMode.ifBlank { AutomationSettings.LANGUAGE_AUTOMATIC }
        }
    }

    suspend fun setAiReplyDelaySeconds(seconds: Int) {
        appContext.whatsAppAutomationPrefs.edit { it[KEY_AI_REPLY_DELAY_SECONDS] = seconds.coerceIn(0, 15) }
    }

    suspend fun setMaxMessagesPerConversation(maxCount: Int) {
        appContext.whatsAppAutomationPrefs.edit { it[KEY_MAX_MESSAGES_PER_CONV] = maxCount.coerceIn(1, 100) }
    }

    suspend fun setReplyToGroupChats(enabled: Boolean) {
        appContext.whatsAppAutomationPrefs.edit { it[KEY_REPLY_GROUP_CHATS] = enabled }
    }

    companion object {
        private val KEY_AUTOMATION_ENABLED = booleanPreferencesKey("wa_automation_enabled")
        private val KEY_GEMINI_BRAIN_ENABLED = booleanPreferencesKey("wa_gemini_brain_enabled")
        private val KEY_AUTO_REPLY_ENABLED = booleanPreferencesKey("wa_auto_reply_enabled")
        private val KEY_VOICE_ANNOUNCEMENT = booleanPreferencesKey("wa_voice_announcement")
        private val KEY_READ_INCOMING_ALOUD = booleanPreferencesKey("wa_read_incoming_aloud")
        private val KEY_REPLY_LANGUAGE_MODE = stringPreferencesKey("wa_reply_language_mode")
        private val KEY_AI_REPLY_DELAY_SECONDS = intPreferencesKey("wa_ai_reply_delay_seconds")
        private val KEY_MAX_MESSAGES_PER_CONV = intPreferencesKey("wa_max_messages_per_conv")
        private val KEY_REPLY_GROUP_CHATS = booleanPreferencesKey("wa_reply_group_chats")

        fun isNotificationAccessGranted(context: Context): Boolean {
            val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(context)
            if (enabledPackages.contains(context.packageName)) return true
            val flat = runCatching {
                Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            }.getOrNull().orEmpty()
            val expectedComponent = ComponentName(context, SaraNotificationService::class.java).flattenToString()
            return flat.split(':').any { it.equals(expectedComponent, ignoreCase = true) || it.startsWith("${context.packageName}/") }
        }
    }
}
