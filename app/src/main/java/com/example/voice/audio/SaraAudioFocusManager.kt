package com.example.voice.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Coordinates Android AudioFocus for Sara's TTS and Gemini Live voice output
 * so phone calls, media players, and navigation prompts are respected cleanly.
 */
class SaraAudioFocusManager(context: Context) {

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val _hasFocus = MutableStateFlow(false)
    val hasFocus: StateFlow<Boolean> = _hasFocus.asStateFlow()

    private val _focusConflictReason = MutableStateFlow<String?>(null)
    val focusConflictReason: StateFlow<String?> = _focusConflictReason.asStateFlow()

    private var onFocusLostCallback: (() -> Unit)? = null
    private var activeFocusRequest: AudioFocusRequest? = null

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                _hasFocus.value = true
                _focusConflictReason.value = null
            }
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                _hasFocus.value = false
                _focusConflictReason.value = if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                    "Audio focus taken by another app or call."
                } else {
                    "Audio focus temporarily paused."
                }
                onFocusLostCallback?.invoke()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Keep focus while another notification briefly ducks
                _hasFocus.value = true
            }
        }
    }

    fun requestAssistantFocus(
        useVoiceCommunicationUsage: Boolean = false,
        onFocusLost: (() -> Unit)? = null
    ): Boolean {
        val am = audioManager ?: return false
        onFocusLostCallback = onFocusLost

        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(
                    if (useVoiceCommunicationUsage) {
                        AudioAttributes.USAGE_VOICE_COMMUNICATION
                    } else {
                        AudioAttributes.USAGE_ASSISTANT
                    }
                )
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
            activeFocusRequest = request
            am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }

        _hasFocus.value = granted
        _focusConflictReason.value = if (granted) null else "Audio focus denied (call or active audio conflict)."
        return granted
    }

    fun abandonFocus() {
        val am = audioManager ?: return
        onFocusLostCallback = null
        _hasFocus.value = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activeFocusRequest?.let { runCatching { am.abandonAudioFocusRequest(it) } }
            activeFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            runCatching { am.abandonAudioFocus(focusChangeListener) }
        }
    }
}
