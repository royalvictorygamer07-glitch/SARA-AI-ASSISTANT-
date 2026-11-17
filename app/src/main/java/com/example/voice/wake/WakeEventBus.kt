package com.example.voice.wake

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

enum class WakeServiceState {
    DISABLED,
    MODEL_MISSING,
    PERMISSION_MISSING,
    STARTING,
    LISTENING,
    PAUSED_FOR_SESSION,
    WAKE_TRIGGERED,
    ERROR
}

data class WakePopupUiState(
    val visible: Boolean = false,
    val title: String = "SARA",
    val subtitle: String = "Hey Sara detected — listening...",
    val liveCommandPreview: String = "",
    val waveAmplitude: Float = 0.25f
)

data class WakeTriggerEvent(
    val recognizedPhrase: String,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Shared reactive state bus synchronizing WakeDetectionService, MainActivity, and SaraViewModel.
 */
class WakeEventBus {

    private val _serviceState = MutableStateFlow(WakeServiceState.DISABLED)
    val serviceState: StateFlow<WakeServiceState> = _serviceState.asStateFlow()

    private val _serviceDiagnostic = MutableStateFlow<String?>(null)
    val serviceDiagnostic: StateFlow<String?> = _serviceDiagnostic.asStateFlow()

    private val _wakeTriggers = MutableSharedFlow<WakeTriggerEvent>(extraBufferCapacity = 8)
    val wakeTriggers: SharedFlow<WakeTriggerEvent> = _wakeTriggers.asSharedFlow()

    private val _popupState = MutableStateFlow(WakePopupUiState())
    val popupState: StateFlow<WakePopupUiState> = _popupState.asStateFlow()

    fun updateServiceState(state: WakeServiceState, diagnostic: String? = null) {
        _serviceState.value = state
        _serviceDiagnostic.value = diagnostic
    }

    fun emitWakeTrigger(phrase: String) {
        _serviceState.value = WakeServiceState.WAKE_TRIGGERED
        _wakeTriggers.tryEmit(WakeTriggerEvent(recognizedPhrase = phrase))
    }

    fun showWakePopup(subtitle: String = "Hey Sara detected — listening...") {
        _popupState.value = WakePopupUiState(
            visible = true,
            title = "SARA",
            subtitle = subtitle,
            liveCommandPreview = "",
            waveAmplitude = 0.45f
        )
    }

    fun updateWakePopup(subtitle: String? = null, preview: String? = null, amplitude: Float? = null) {
        val curr = _popupState.value
        if (!curr.visible) return
        _popupState.value = curr.copy(
            subtitle = subtitle ?: curr.subtitle,
            liveCommandPreview = preview ?: curr.liveCommandPreview,
            waveAmplitude = amplitude ?: curr.waveAmplitude
        )
    }

    fun dismissWakePopup() {
        _popupState.value = WakePopupUiState(visible = false)
    }
}
