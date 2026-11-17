package com.example.voice.stt

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidSpeechRecognizerSttEngine(
    context: Context
) : SttEngine {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null

    private val _isListening = MutableStateFlow(false)
    override val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _partialTranscript = MutableStateFlow("")
    override val partialTranscript: StateFlow<String> = _partialTranscript.asStateFlow()

    private val _normalizedRms = MutableStateFlow(0f)
    override val normalizedRms: StateFlow<Float> = _normalizedRms.asStateFlow()

    private val _events = MutableSharedFlow<SttEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<SttEvent> = _events.asSharedFlow()

    override fun isRecognitionAvailable(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(appContext)

    override fun startListening(languageTag: String) {
        mainHandler.post {
            if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
                _isListening.value = false
                _events.tryEmit(
                    SttEvent.Error(
                        code = -1,
                        message = "Speech recognition service is unavailable on this device.",
                        isFatal = true
                    )
                )
                return@post
            }

            ensureRecognizer()
            _partialTranscript.value = ""
            _normalizedRms.value = 0.08f

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            }

            runCatching {
                speechRecognizer?.startListening(intent)
                _isListening.value = true
            }.onFailure { err ->
                Log.e(TAG, "startListening failed", err)
                _isListening.value = false
                _events.tryEmit(
                    SttEvent.Error(
                        code = SpeechRecognizer.ERROR_CLIENT,
                        message = err.localizedMessage ?: "Failed to start microphone recognition",
                        isFatal = false
                    )
                )
            }
        }
    }

    override fun stopListening() {
        mainHandler.post {
            _isListening.value = false
            _normalizedRms.value = 0f
            runCatching { speechRecognizer?.stopListening() }
        }
    }

    override fun cancel() {
        mainHandler.post {
            _isListening.value = false
            _partialTranscript.value = ""
            _normalizedRms.value = 0f
            runCatching { speechRecognizer?.cancel() }
        }
    }

    override fun release() {
        mainHandler.post {
            _isListening.value = false
            _normalizedRms.value = 0f
            runCatching { speechRecognizer?.destroy() }
            speechRecognizer = null
        }
    }

    private fun ensureRecognizer() {
        if (speechRecognizer != null) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    _isListening.value = true
                }

                override fun onBeginningOfSpeech() {
                    _isListening.value = true
                }

                override fun onRmsChanged(rmsdB: Float) {
                    // Android SpeechRecognizer typically emits RMS in [-2f, 10f]
                    val normalized = ((rmsdB + 2f) / 12f).coerceIn(0.05f, 1f)
                    _normalizedRms.value = normalized
                }

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    _normalizedRms.value = 0.05f
                }

                override fun onError(error: Int) {
                    _isListening.value = false
                    _normalizedRms.value = 0f
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                            _events.tryEmit(SttEvent.TransientTimeout(error))
                        }
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                            _events.tryEmit(
                                SttEvent.Error(
                                    code = error,
                                    message = "Microphone permission required hai.",
                                    isFatal = true
                                )
                            )
                        }
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                            runCatching { speechRecognizer?.cancel() }
                            _events.tryEmit(SttEvent.TransientTimeout(error))
                        }
                        else -> {
                            _events.tryEmit(
                                SttEvent.Error(
                                    code = error,
                                    message = mapErrorMessage(error),
                                    isFatal = error == SpeechRecognizer.ERROR_AUDIO
                                )
                            )
                        }
                    }
                }

                override fun onResults(results: Bundle?) {
                    _isListening.value = false
                    _normalizedRms.value = 0f
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val transcript = matches?.firstOrNull().orEmpty().trim()
                    _partialTranscript.value = ""
                    if (transcript.isNotEmpty()) {
                        _events.tryEmit(SttEvent.FinalTranscript(transcript))
                    } else {
                        _events.tryEmit(SttEvent.TransientTimeout(SpeechRecognizer.ERROR_NO_MATCH))
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    if (partial.isNotBlank()) {
                        _partialTranscript.value = partial
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }
    }

    private fun mapErrorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Internet connection check kijiye."
        SpeechRecognizer.ERROR_AUDIO -> "Microphone audio capture error."
        SpeechRecognizer.ERROR_SERVER -> "Speech recognition server error."
        else -> "Speech recognition error ($error)"
    }

    companion object {
        private const val TAG = "AndroidSttEngine"
    }
}
