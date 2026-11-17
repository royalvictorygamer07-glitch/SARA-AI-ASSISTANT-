package com.example.voice.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AudioCaptureOwner {
    NONE,
    MIC_TEST,
    WAKE_ENROLLMENT,
    WAKE_LISTENING,
    CLASSIC_STT,
    GEMINI_LIVE,
    BARGE_IN_MONITOR
}

/**
 * Single-owner microphone arbiter across the entire SARA app so background wake detection,
 * 5-phrase enrollment, mic diagnostics, Android SpeechRecognizer, and Gemini Live PCM capture
 * never hold competing AudioRecord sessions simultaneously.
 */
class AudioCaptureCoordinator {

    private val lock = Any()
    private var preemptCallback: (() -> Unit)? = null

    private val _currentOwner = MutableStateFlow(AudioCaptureOwner.NONE)
    val currentOwner: StateFlow<AudioCaptureOwner> = _currentOwner.asStateFlow()

    fun acquire(owner: AudioCaptureOwner, onPreempted: (() -> Unit)? = null): Boolean {
        if (owner == AudioCaptureOwner.NONE) return false
        var callbackToInvoke: (() -> Unit)? = null
        synchronized(lock) {
            val active = _currentOwner.value
            if (active != AudioCaptureOwner.NONE && active != owner) {
                if (active == AudioCaptureOwner.WAKE_LISTENING || active == AudioCaptureOwner.BARGE_IN_MONITOR) {
                    callbackToInvoke = preemptCallback
                } else {
                    return false
                }
            }
            _currentOwner.value = owner
            preemptCallback = onPreempted
        }
        callbackToInvoke?.invoke()
        return true
    }

    fun release(owner: AudioCaptureOwner) {
        synchronized(lock) {
            if (_currentOwner.value == owner) {
                _currentOwner.value = AudioCaptureOwner.NONE
                preemptCallback = null
            }
        }
    }

    fun forceReleaseAll() {
        var cb: (() -> Unit)?
        synchronized(lock) {
            cb = preemptCallback
            _currentOwner.value = AudioCaptureOwner.NONE
            preemptCallback = null
        }
        cb?.invoke()
    }
}
