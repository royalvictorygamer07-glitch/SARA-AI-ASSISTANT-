package com.example.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

private val Context.saraAssistantPrefs: DataStore<Preferences> by preferencesDataStore(
    name = "sara_assistant_behavior_prefs"
)

data class GeminiCredentials(
    val apiKey: String = "",
    val modelName: String = DEFAULT_GEMINI_MODEL,
    val liveModelName: String = DEFAULT_GEMINI_LIVE_MODEL,
    val liveVoiceName: String = DEFAULT_LIVE_VOICE
) {
    val isConfigured: Boolean get() = apiKey.isNotBlank()

    companion object {
        const val DEFAULT_GEMINI_MODEL = "gemini-2.5-flash"
        const val DEFAULT_GEMINI_LIVE_MODEL = "gemini-2.5-flash-native-audio-preview-12-2025"
        const val DEFAULT_LIVE_VOICE = "Aoede"
        val FEMALE_LIVE_VOICES = listOf("Aoede", "Kore", "Leda", "Zephyr")
    }
}

data class ElevenLabsCredentials(
    val apiKey: String = "",
    val voiceId: String = ""
) {
    val isConfigured: Boolean get() = apiKey.isNotBlank() && voiceId.isNotBlank()
}

data class UserProfile(
    val name: String = "",
    val preferredLanguage: String = AUTO_LANGUAGE,
    val preferencesNote: String = ""
) {
    fun sttLocaleTag(): String = when (preferredLanguage) {
        "English" -> "en-US"
        "Hindi" -> "hi-IN"
        "Hinglish" -> "en-IN"
        "Spanish" -> "es-ES"
        "French" -> "fr-FR"
        else -> "en-IN"
    }

    companion object {
        const val AUTO_LANGUAGE = "Auto-Match"
        val LANGUAGE_OPTIONS = listOf("Auto-Match", "English", "Hindi", "Hinglish", "Spanish", "French")
    }
}

data class AssistantBehaviorSettings(
    val continuousConversationEnabled: Boolean = true,
    val liveCallModeEnabled: Boolean = false,
    val geminiLiveEnabled: Boolean = true,
    val memoryEnabled: Boolean = true,
    val confirmBeforeExternalAction: Boolean = true,
    val autoSpeakResponses: Boolean = true,
    val bargeInEnabled: Boolean = true,
    val wakeUpDetectionEnabled: Boolean = false,
    val wakeEnrolledCount: Int = 0,
    val wakeSensitivity: Float = 0.65f
)

/**
 * Stores Gemini credentials and ElevenLabs credentials in strictly separate
 * Keystore-encrypted SharedPreferences files with distinct Keystore master keys.
 * Never logs any API keys.
 */
class CredentialStore(context: Context) {

    private val appContext = context.applicationContext

    // Isolated storage #1: Gemini credentials
    private val geminiPrefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_GEMINI_ISOLATED, Context.MODE_PRIVATE)
    private val geminiCrypto = KeystoreCryptoManager(KEYSTORE_ALIAS_GEMINI)

    // Isolated storage #2: ElevenLabs credentials
    private val elevenLabsPrefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_ELEVENLABS_ISOLATED, Context.MODE_PRIVATE)
    private val elevenLabsCrypto = KeystoreCryptoManager(KEYSTORE_ALIAS_ELEVENLABS)

    private val _geminiCredentials = MutableStateFlow(readGeminiCredentials())
    val geminiCredentials: StateFlow<GeminiCredentials> = _geminiCredentials.asStateFlow()

    private val _elevenLabsCredentials = MutableStateFlow(readElevenLabsCredentials())
    val elevenLabsCredentials: StateFlow<ElevenLabsCredentials> = _elevenLabsCredentials.asStateFlow()

    val behaviorSettings: Flow<AssistantBehaviorSettings> =
        appContext.saraAssistantPrefs.data.map { prefs ->
            AssistantBehaviorSettings(
                continuousConversationEnabled = prefs[KEY_CONTINUOUS_CONVERSATION] ?: true,
                liveCallModeEnabled = prefs[KEY_LIVE_CALL_MODE] ?: false,
                geminiLiveEnabled = prefs[KEY_GEMINI_LIVE_ENABLED] ?: true,
                memoryEnabled = prefs[KEY_MEMORY_ENABLED] ?: true,
                confirmBeforeExternalAction = prefs[KEY_CONFIRM_BEFORE_ACTION] ?: true,
                autoSpeakResponses = prefs[KEY_AUTO_SPEAK] ?: true,
                bargeInEnabled = prefs[KEY_BARGE_IN_ENABLED] ?: true,
                wakeUpDetectionEnabled = prefs[KEY_WAKE_UP_ENABLED] ?: false,
                wakeEnrolledCount = prefs[KEY_WAKE_ENROLLED_COUNT] ?: 0,
                wakeSensitivity = prefs[KEY_WAKE_SENSITIVITY] ?: 0.65f
            )
        }

    val userProfile: Flow<UserProfile> =
        appContext.saraAssistantPrefs.data.map { prefs ->
            UserProfile(
                name = prefs[KEY_USER_NAME].orEmpty(),
                preferredLanguage = prefs[KEY_PREFERRED_LANGUAGE] ?: UserProfile.AUTO_LANGUAGE,
                preferencesNote = prefs[KEY_USER_PREFERENCES].orEmpty()
            )
        }

    fun saveGeminiCredentials(
        apiKey: String,
        modelName: String,
        liveModelName: String = _geminiCredentials.value.liveModelName,
        liveVoiceName: String = _geminiCredentials.value.liveVoiceName
    ) {
        val cleanKey = apiKey.trim()
        val cleanModel = modelName.trim().ifBlank { GeminiCredentials.DEFAULT_GEMINI_MODEL }
        val cleanLiveModel = liveModelName.trim().ifBlank { GeminiCredentials.DEFAULT_GEMINI_LIVE_MODEL }
        val cleanLiveVoice = liveVoiceName.trim().ifBlank { GeminiCredentials.DEFAULT_LIVE_VOICE }
        val encryptedKey = if (cleanKey.isNotEmpty()) geminiCrypto.encrypt(cleanKey) else ""
        geminiPrefs.edit()
            .putString(FIELD_GEMINI_API_KEY_ENC, encryptedKey)
            .putString(FIELD_GEMINI_MODEL, cleanModel)
            .putString(FIELD_GEMINI_LIVE_MODEL, cleanLiveModel)
            .putString(FIELD_GEMINI_LIVE_VOICE, cleanLiveVoice)
            .apply()
        _geminiCredentials.value = GeminiCredentials(
            apiKey = cleanKey,
            modelName = cleanModel,
            liveModelName = cleanLiveModel,
            liveVoiceName = cleanLiveVoice
        )
    }

    fun clearGeminiApiKey() {
        val current = _geminiCredentials.value
        geminiPrefs.edit()
            .remove(FIELD_GEMINI_API_KEY_ENC)
            .apply()
        _geminiCredentials.value = current.copy(apiKey = "")
    }

    fun saveElevenLabsCredentials(apiKey: String, voiceId: String) {
        val cleanKey = apiKey.trim()
        val cleanVoiceId = voiceId.trim()
        val encryptedKey = if (cleanKey.isNotEmpty()) elevenLabsCrypto.encrypt(cleanKey) else ""
        elevenLabsPrefs.edit()
            .putString(FIELD_ELEVENLABS_API_KEY_ENC, encryptedKey)
            .putString(FIELD_ELEVENLABS_VOICE_ID, cleanVoiceId)
            .apply()
        _elevenLabsCredentials.value = ElevenLabsCredentials(
            apiKey = cleanKey,
            voiceId = cleanVoiceId
        )
    }

    fun clearElevenLabsCredentials() {
        elevenLabsPrefs.edit()
            .remove(FIELD_ELEVENLABS_API_KEY_ENC)
            .remove(FIELD_ELEVENLABS_VOICE_ID)
            .apply()
        _elevenLabsCredentials.value = ElevenLabsCredentials()
    }

    suspend fun saveUserProfile(name: String, preferredLanguage: String, preferencesNote: String) {
        appContext.saraAssistantPrefs.edit { prefs ->
            prefs[KEY_USER_NAME] = name.trim()
            prefs[KEY_PREFERRED_LANGUAGE] = preferredLanguage.trim().ifBlank { UserProfile.AUTO_LANGUAGE }
            prefs[KEY_USER_PREFERENCES] = preferencesNote.trim()
        }
    }

    suspend fun updateContinuousConversation(enabled: Boolean) {
        appContext.saraAssistantPrefs.edit { it[KEY_CONTINUOUS_CONVERSATION] = enabled }
    }

    suspend fun updateLiveCallMode(enabled: Boolean) {
        appContext.saraAssistantPrefs.edit { it[KEY_LIVE_CALL_MODE] = enabled }
    }

    suspend fun updateGeminiLiveEnabled(enabled: Boolean) {
        appContext.saraAssistantPrefs.edit { it[KEY_GEMINI_LIVE_ENABLED] = enabled }
    }

    suspend fun updateMemoryEnabled(enabled: Boolean) {
        appContext.saraAssistantPrefs.edit { it[KEY_MEMORY_ENABLED] = enabled }
    }

    suspend fun updateConfirmBeforeExternalAction(enabled: Boolean) {
        appContext.saraAssistantPrefs.edit { it[KEY_CONFIRM_BEFORE_ACTION] = enabled }
    }

    suspend fun updateAutoSpeakResponses(enabled: Boolean) {
        appContext.saraAssistantPrefs.edit { it[KEY_AUTO_SPEAK] = enabled }
    }

    suspend fun updateBargeInEnabled(enabled: Boolean) {
        appContext.saraAssistantPrefs.edit { it[KEY_BARGE_IN_ENABLED] = enabled }
    }

    suspend fun updateWakeUpDetectionEnabled(enabled: Boolean) {
        appContext.saraAssistantPrefs.edit { it[KEY_WAKE_UP_ENABLED] = enabled }
    }

    suspend fun updateWakeEnrolledCount(count: Int) {
        appContext.saraAssistantPrefs.edit { it[KEY_WAKE_ENROLLED_COUNT] = count.coerceIn(0, 5) }
    }

    suspend fun updateWakeSensitivity(sensitivity: Float) {
        appContext.saraAssistantPrefs.edit { it[KEY_WAKE_SENSITIVITY] = sensitivity.coerceIn(0.3f, 0.95f) }
    }

    private fun readGeminiCredentials(): GeminiCredentials {
        val encKey = geminiPrefs.getString(FIELD_GEMINI_API_KEY_ENC, "").orEmpty()
        val decryptedKey = if (encKey.isNotBlank()) geminiCrypto.decrypt(encKey) else ""
        val model = geminiPrefs.getString(FIELD_GEMINI_MODEL, GeminiCredentials.DEFAULT_GEMINI_MODEL)
            .orEmpty()
            .ifBlank { GeminiCredentials.DEFAULT_GEMINI_MODEL }
        val liveModel = geminiPrefs.getString(
            FIELD_GEMINI_LIVE_MODEL,
            GeminiCredentials.DEFAULT_GEMINI_LIVE_MODEL
        ).orEmpty().ifBlank { GeminiCredentials.DEFAULT_GEMINI_LIVE_MODEL }
        val liveVoice = geminiPrefs.getString(
            FIELD_GEMINI_LIVE_VOICE,
            GeminiCredentials.DEFAULT_LIVE_VOICE
        ).orEmpty().ifBlank { GeminiCredentials.DEFAULT_LIVE_VOICE }
        return GeminiCredentials(
            apiKey = decryptedKey,
            modelName = model,
            liveModelName = liveModel,
            liveVoiceName = liveVoice
        )
    }

    private fun readElevenLabsCredentials(): ElevenLabsCredentials {
        val encKey = elevenLabsPrefs.getString(FIELD_ELEVENLABS_API_KEY_ENC, "").orEmpty()
        val decryptedKey = if (encKey.isNotBlank()) elevenLabsCrypto.decrypt(encKey) else ""
        val voiceId = elevenLabsPrefs.getString(FIELD_ELEVENLABS_VOICE_ID, "").orEmpty()
        return ElevenLabsCredentials(apiKey = decryptedKey, voiceId = voiceId)
    }

    companion object {
        private const val PREFS_GEMINI_ISOLATED = "sara_gemini_secure_prefs"
        private const val PREFS_ELEVENLABS_ISOLATED = "sara_elevenlabs_secure_prefs"
        private const val KEYSTORE_ALIAS_GEMINI = "sara_gemini_aes_key"
        private const val KEYSTORE_ALIAS_ELEVENLABS = "sara_elevenlabs_aes_key"

        private const val FIELD_GEMINI_API_KEY_ENC = "gemini_api_key_enc"
        private const val FIELD_GEMINI_MODEL = "gemini_model_name"
        private const val FIELD_GEMINI_LIVE_MODEL = "gemini_live_model_name"
        private const val FIELD_GEMINI_LIVE_VOICE = "gemini_live_voice_name"

        private const val FIELD_ELEVENLABS_API_KEY_ENC = "elevenlabs_api_key_enc"
        private const val FIELD_ELEVENLABS_VOICE_ID = "elevenlabs_voice_id"

        private val KEY_CONTINUOUS_CONVERSATION = booleanPreferencesKey("continuous_conversation")
        private val KEY_LIVE_CALL_MODE = booleanPreferencesKey("live_call_mode")
        private val KEY_GEMINI_LIVE_ENABLED = booleanPreferencesKey("gemini_live_enabled")
        private val KEY_MEMORY_ENABLED = booleanPreferencesKey("memory_enabled")
        private val KEY_CONFIRM_BEFORE_ACTION = booleanPreferencesKey("confirm_before_action")
        private val KEY_AUTO_SPEAK = booleanPreferencesKey("auto_speak")
        private val KEY_BARGE_IN_ENABLED = booleanPreferencesKey("barge_in_enabled")
        private val KEY_WAKE_UP_ENABLED = booleanPreferencesKey("wake_up_detection_enabled")
        private val KEY_WAKE_ENROLLED_COUNT = intPreferencesKey("wake_enrolled_count")
        private val KEY_WAKE_SENSITIVITY = floatPreferencesKey("wake_sensitivity")

        private val KEY_USER_NAME = stringPreferencesKey("user_profile_name")
        private val KEY_PREFERRED_LANGUAGE = stringPreferencesKey("user_preferred_language")
        private val KEY_USER_PREFERENCES = stringPreferencesKey("user_preferences_note")
    }
}
