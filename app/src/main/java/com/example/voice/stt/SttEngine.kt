package com.example.voice.stt

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface SttEvent {
    data class FinalTranscript(val text: String) : SttEvent
    data class TransientTimeout(val code: Int) : SttEvent
    data class Error(val code: Int, val message: String, val isFatal: Boolean) : SttEvent
}

/**
 * Modular Speech-to-Text engine interface so Android SpeechRecognizer can be
 * replaced or upgraded independently (e.g. Whisper / Deepgram / streaming STT).
 */
interface SttEngine {
    val isListening: StateFlow<Boolean>
    val partialTranscript: StateFlow<String>
    val normalizedRms: StateFlow<Float>
    val events: SharedFlow<SttEvent>

    fun isRecognitionAvailable(): Boolean
    fun startListening(languageTag: String = "en-IN")
    fun stopListening()
    fun cancel()
    fun release()
}
