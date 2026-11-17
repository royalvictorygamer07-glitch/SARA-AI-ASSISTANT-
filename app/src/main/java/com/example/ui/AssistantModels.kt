package com.example.ui

import android.Manifest
import android.os.Build
import com.example.commands.AssistantCommand
import java.util.UUID

enum class AssistantStatus {
    IDLE,
    CONNECTING,
    CONNECTED,
    LISTENING,
    THINKING,
    SPEAKING,
    SLEEPING,
    RECONNECTING,
    ERROR
}

data class ConversationMessage(
    val id: String = UUID.randomUUID().toString(),
    val isUser: Boolean,
    val text: String,
    val actionTag: String? = null,
    val actionStatus: String? = null,
    val isError: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

sealed interface VisionAnalysisUiState {
    data object Idle : VisionAnalysisUiState
    data object Analyzing : VisionAnalysisUiState
    data class Success(val prompt: String, val analysis: String) : VisionAnalysisUiState
    data class Error(val message: String) : VisionAnalysisUiState
}

data class PermissionRequestPrompt(
    val permission: String,
    val rationale: String,
    val retryCommand: AssistantCommand? = null
)

data class CommandPermissionStatus(
    val recordAudio: Boolean = false,
    val readContacts: Boolean = false,
    val callPhone: Boolean = false,
    val readPhoneState: Boolean = false,
    val postNotifications: Boolean = true,
    val camera: Boolean = false,
    val bluetoothConnect: Boolean = true,
    val canDrawOverlays: Boolean = false
) {
    val allGranted: Boolean
        get() = recordAudio &&
            readContacts &&
            callPhone &&
            readPhoneState &&
            postNotifications &&
            camera &&
            bluetoothConnect

    fun missingPermissions(): Array<String> = buildList {
        if (!recordAudio) add(Manifest.permission.RECORD_AUDIO)
        if (!readContacts) add(Manifest.permission.READ_CONTACTS)
        if (!callPhone) add(Manifest.permission.CALL_PHONE)
        if (!readPhoneState) add(Manifest.permission.READ_PHONE_STATE)
        if (!camera) add(Manifest.permission.CAMERA)
        if (!postNotifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (!bluetoothConnect && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }.toTypedArray()
}
