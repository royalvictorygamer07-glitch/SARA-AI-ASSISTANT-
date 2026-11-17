package com.example.voice.tts

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.voice.audio.SaraAudioFocusManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.sin

/**
 * Built-in Android TextToSpeech speaker used automatically as fallback when ElevenLabs
 * is unconfigured, offline, or rate-limited — guaranteeing Sara always speaks aloud in
 * foreground and background modes (including Sleep/Wake & app-launch confirmations).
 */
class SystemTtsSpeaker(
    context: Context,
    private val audioFocusManager: SaraAudioFocusManager? = null,
    private val onSpeakingStateChanged: (speaking: Boolean, amplitude: Float) -> Unit = { _, _ -> }
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var tts: TextToSpeech? = null
    private var initDeferred: CompletableDeferred<Boolean>? = null
    private var amplitudeJob: Job? = null
    @Volatile
    private var stopRequested = false

    suspend fun speak(text: String, liveCallMode: Boolean = false): TtsResult {
        val clean = text.trim()
        if (clean.isEmpty()) return TtsResult.Completed

        stopRequested = false
        val ready = ensureInitialized()
        if (!ready) {
            return TtsResult.Error(
                userMessage = "Sir, voice service temporarily unavailable hai.",
                diagnostic = "Android TextToSpeech initialization failed"
            )
        }

        if (stopRequested) return TtsResult.Stopped

        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val engine = tts
                if (engine == null) {
                    if (cont.isActive) {
                        cont.resume(
                            TtsResult.Error(
                                userMessage = "Sir, voice service temporarily unavailable hai.",
                                diagnostic = "TextToSpeech engine unavailable"
                            )
                        )
                    }
                    return@suspendCancellableCoroutine
                }

                val utteranceId = "sara_tts_${UUID.randomUUID()}"
                selectPreferredLocale(engine, clean)
                engine.setSpeechRate(if (liveCallMode) 1.08f else 1.0f)
                engine.setPitch(1.06f)

                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {
                        if (id != utteranceId) return
                        audioFocusManager?.requestAssistantFocus(useVoiceCommunicationUsage = false) {
                            stop()
                        }
                        startAmplitudeEnvelope()
                    }

                    override fun onDone(id: String?) {
                        if (id != utteranceId) return
                        finishPlayback()
                        if (cont.isActive) {
                            cont.resume(if (stopRequested) TtsResult.Stopped else TtsResult.Completed)
                        }
                    }

                    @Deprecated("Deprecated in Android API")
                    override fun onError(id: String?) {
                        if (id != utteranceId) return
                        finishPlayback()
                        if (cont.isActive) {
                            cont.resume(
                                TtsResult.Error(
                                    userMessage = "Sir, voice service temporarily unavailable hai.",
                                    diagnostic = "Android TextToSpeech utterance error"
                                )
                            )
                        }
                    }

                    override fun onError(id: String?, errorCode: Int) {
                        if (id != utteranceId) return
                        finishPlayback()
                        if (cont.isActive) {
                            cont.resume(
                                TtsResult.Error(
                                    userMessage = "Sir, voice service temporarily unavailable hai.",
                                    diagnostic = "Android TextToSpeech error ($errorCode)"
                                )
                            )
                        }
                    }

                    override fun onStop(id: String?, interrupted: Boolean) {
                        if (id != utteranceId) return
                        finishPlayback()
                        if (cont.isActive) {
                            cont.resume(TtsResult.Stopped)
                        }
                    }
                })

                cont.invokeOnCancellation {
                    stop()
                }

                val params = Bundle().apply {
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                }
                val result = engine.speak(clean, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
                if (result != TextToSpeech.SUCCESS) {
                    finishPlayback()
                    if (cont.isActive) {
                        cont.resume(
                            TtsResult.Error(
                                userMessage = "Sir, voice service temporarily unavailable hai.",
                                diagnostic = "TextToSpeech.speak returned $result"
                            )
                        )
                    }
                }
            }
        }
    }

    private suspend fun ensureInitialized(): Boolean = withContext(Dispatchers.Main) {
        initDeferred?.let { existing ->
            if (existing.isCompleted) return@withContext existing.getCompleted()
            return@withContext withTimeoutOrNull(3500L) { existing.await() } ?: false
        }

        val deferred = CompletableDeferred<Boolean>()
        initDeferred = deferred
        try {
            val instance = TextToSpeech(appContext) { status ->
                val ok = status == TextToSpeech.SUCCESS
                if (ok) {
                    runCatching {
                        val usage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            AudioAttributes.USAGE_ASSISTANT
                        } else {
                            AudioAttributes.USAGE_MEDIA
                        }
                        tts?.setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(usage)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                    }
                } else {
                    Log.w(TAG, "TextToSpeech init failed with status=$status")
                }
                if (!deferred.isCompleted) deferred.complete(ok)
            }
            tts = instance
        } catch (t: Throwable) {
            Log.e(TAG, "Failed constructing Android TextToSpeech", t)
            if (!deferred.isCompleted) deferred.complete(false)
        }
        withTimeoutOrNull(3500L) { deferred.await() } ?: false
    }

    private fun selectPreferredLocale(engine: TextToSpeech, text: String) {
        val containsDevanagari = text.any { it in '\u0900'..'\u097F' }
        val candidates = if (containsDevanagari) {
            listOf(Locale("hi", "IN"), Locale("en", "IN"), Locale.US)
        } else {
            listOf(Locale("en", "IN"), Locale("hi", "IN"), Locale.US)
        }
        for (locale in candidates) {
            val avail = runCatching { engine.isLanguageAvailable(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (avail >= TextToSpeech.LANG_AVAILABLE) {
                runCatching { engine.language = locale }
                break
            }
        }
    }

    private fun startAmplitudeEnvelope() {
        amplitudeJob?.cancel()
        amplitudeJob = scope.launch {
            var phase = 0f
            while (isActive && !stopRequested) {
                phase += 0.35f
                val env = (0.4f + 0.35f * abs(sin(phase)) + 0.2f * abs(sin(phase * 2.1f)))
                    .coerceIn(0.15f, 1f)
                onSpeakingStateChanged(true, env)
                delay(45L)
            }
        }
    }

    private fun finishPlayback() {
        amplitudeJob?.cancel()
        amplitudeJob = null
        onSpeakingStateChanged(false, 0f)
        audioFocusManager?.abandonFocus()
    }

    fun stop() {
        stopRequested = true
        amplitudeJob?.cancel()
        amplitudeJob = null
        runCatching { tts?.stop() }
        onSpeakingStateChanged(false, 0f)
        audioFocusManager?.abandonFocus()
    }

    fun release() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
        initDeferred = null
    }

    companion object {
        private const val TAG = "SystemTtsSpeaker"
    }
}
