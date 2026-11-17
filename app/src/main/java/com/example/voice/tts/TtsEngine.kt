package com.example.voice.tts

import kotlinx.coroutines.flow.StateFlow

sealed interface TtsResult {
    data object Completed : TtsResult
    data object Stopped : TtsResult
    data class Error(val userMessage: String, val diagnostic: String) : TtsResult
}

data class ElevenLabsVoiceOption(
    val voiceId: String,
    val name: String,
    val category: String
)

/**
 * Modular Text-to-Speech interface so ElevenLabs can be replaced or extended independently.
 */
interface TtsEngine {
    val isSpeaking: StateFlow<Boolean>
    val playbackAmplitude: StateFlow<Float>

    suspend fun speak(text: String, liveCallMode: Boolean = false): TtsResult

    fun stop()

    fun release()
}
