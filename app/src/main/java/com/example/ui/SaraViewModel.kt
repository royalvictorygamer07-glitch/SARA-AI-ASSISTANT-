package com.example.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.actions.ActionRouter
import com.example.actions.IncomingCallAssistantManager
import com.example.actions.IncomingCallCapabilityReport
import com.example.ai.gemini.ChatTurn
import com.example.ai.gemini.GeminiClient
import com.example.ai.gemini.GeminiResult
import com.example.ai.gemini.live.GeminiLiveClient
import com.example.ai.gemini.live.GeminiLiveEvent
import com.example.ai.gemini.live.LiveConnectionState
import com.example.automation.whatsapp.AutomationServiceStatus
import com.example.automation.whatsapp.AutomationSettings
import com.example.automation.whatsapp.WhatsAppActivityLogEntity
import com.example.automation.whatsapp.WhatsAppAutomationManager
import com.example.automation.whatsapp.WhatsAppContactProfileEntity
import com.example.automation.whatsapp.WhatsAppContactRuleEntity
import com.example.automation.whatsapp.WhatsAppMessageEntity
import com.example.commands.ActionCategory
import com.example.commands.ActionExecutionResult
import com.example.commands.AssistantCommand
import com.example.commands.CommandParser
import com.example.commands.PendingConfirmation
import com.example.core.di.AppContainer
import com.example.core.network.NetworkMonitor
import com.example.core.security.AssistantBehaviorSettings
import com.example.core.security.CredentialStore
import com.example.core.security.ElevenLabsCredentials
import com.example.core.security.GeminiCredentials
import com.example.core.security.UserProfile
import com.example.memory.ConversationHistoryRepository
import com.example.memory.ConversationSessionEntity
import com.example.memory.ConversationTurnEntity
import com.example.memory.MemoryCategory
import com.example.memory.MemoryRecord
import com.example.memory.MemoryStorageProvider
import com.example.voice.audio.AudioCaptureCoordinator
import com.example.voice.audio.AudioCaptureOwner
import com.example.voice.audio.MicTestUiState
import com.example.voice.audio.MicrophoneTestEngine
import com.example.voice.audio.SaraAudioFocusManager
import com.example.voice.audio.VadEvent
import com.example.voice.audio.VoiceActivityDetector
import com.example.voice.stt.SttEngine
import com.example.voice.stt.SttEvent
import com.example.voice.tts.ElevenLabsTtsEngine
import com.example.voice.tts.ElevenLabsVoiceOption
import com.example.voice.tts.TtsResult
import com.example.voice.wake.VoskModelInspectionResult
import com.example.voice.wake.VoskModelInspector
import com.example.voice.wake.WakeDetectionService
import com.example.voice.wake.WakeEnrollmentUiState
import com.example.voice.wake.WakeEventBus
import com.example.voice.wake.WakePhraseEnrollmentManager
import com.example.voice.wake.WakePopupUiState
import com.example.voice.wake.WakeServiceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class AssistantStatus {
    IDLE,
    CONNECTING,
    CONNECTED,
    LISTENING,
    THINKING,
    SPEAKING,
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
    val bluetoothConnect: Boolean = true
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

data class SaraUiState(
    val status: AssistantStatus = AssistantStatus.IDLE,
    val liveConnectionState: LiveConnectionState = LiveConnectionState.DISCONNECTED,
    val partialTranscript: String = "",
    val liveSaraTranscript: String = "",
    val waveAmplitude: Float = 0f,
    val messages: List<ConversationMessage> = emptyList(),
    val pastSessions: List<ConversationSessionEntity> = emptyList(),
    val selectedSessionId: String? = null,
    val selectedSessionTurns: List<ConversationTurnEntity> = emptyList(),
    val pendingConfirmation: PendingConfirmation? = null,
    val pendingPermissionRequest: PermissionRequestPrompt? = null,
    val statusBannerMessage: String? = null,
    val geminiCredentials: GeminiCredentials = GeminiCredentials(),
    val elevenLabsCredentials: ElevenLabsCredentials = ElevenLabsCredentials(),
    val userProfile: UserProfile = UserProfile(),
    val behaviorSettings: AssistantBehaviorSettings = AssistantBehaviorSettings(),
    val memories: List<MemoryRecord> = emptyList(),
    val memoryBackendName: String = "Room SQLite (Local)",
    val availableVoices: List<ElevenLabsVoiceOption> = emptyList(),
    val isFetchingVoices: Boolean = false,
    val voicesFetchMessage: String? = null,
    val isTestingGemini: Boolean = false,
    val geminiTestResult: String? = null,
    val visionState: VisionAnalysisUiState = VisionAnalysisUiState.Idle,
    val incomingCallReport: IncomingCallCapabilityReport? = null,
    val incomingCallSimResult: String? = null,
    val isSpeechRecognizerAvailable: Boolean = true,
    val wakeServiceState: WakeServiceState = WakeServiceState.DISABLED,
    val wakeServiceDiagnostic: String? = null,
    val voskInspection: VoskModelInspectionResult? = null,
    val wakeEnrollmentState: WakeEnrollmentUiState = WakeEnrollmentUiState(),
    val micTestState: MicTestUiState = MicTestUiState(),
    val wakePopupState: WakePopupUiState = WakePopupUiState(),
    val isIgnoringBatteryOptimizations: Boolean = false,
    val audioFocusConflict: String? = null,
    val waAutomationSettings: AutomationSettings = AutomationSettings(),
    val waServiceStatus: AutomationServiceStatus = AutomationServiceStatus.STOPPED,
    val waStatusDetail: String = "WhatsApp Automation is OFF.",
    val waListenerConnected: Boolean = false,
    val waNotificationAccessGranted: Boolean = false,
    val waContactRules: List<WhatsAppContactRuleEntity> = emptyList(),
    val waContactProfiles: List<WhatsAppContactProfileEntity> = emptyList(),
    val waRecentMessages: List<WhatsAppMessageEntity> = emptyList(),
    val waActivityLogs: List<WhatsAppActivityLogEntity> = emptyList(),
    val commandPermissions: CommandPermissionStatus = CommandPermissionStatus()
)

class SaraViewModel(
    private val appContext: Context,
    private val credentialStore: CredentialStore,
    private val networkMonitor: NetworkMonitor,
    private val memoryRepository: MemoryStorageProvider,
    private val historyRepository: ConversationHistoryRepository,
    private val geminiClient: GeminiClient,
    private val geminiLiveClient: GeminiLiveClient,
    private val ttsEngine: ElevenLabsTtsEngine,
    private val sttEngine: SttEngine,
    private val actionRouter: ActionRouter,
    private val incomingCallManager: IncomingCallAssistantManager,
    private val audioCaptureCoordinator: AudioCaptureCoordinator,
    private val audioFocusManager: SaraAudioFocusManager,
    private val microphoneTestEngine: MicrophoneTestEngine,
    private val wakeEnrollmentManager: WakePhraseEnrollmentManager,
    private val wakeEventBus: WakeEventBus,
    private val voskInspectionFlow: StateFlow<VoskModelInspectionResult>,
    private val onRefreshVoskInspection: () -> Unit,
    private val whatsAppAutomationManager: WhatsAppAutomationManager
) : ViewModel() {

    private val _classicStatus = MutableStateFlow(AssistantStatus.IDLE)
    private val _messages = MutableStateFlow(
        listOf(
            ConversationMessage(
                isUser = false,
                text = "Hi, I'm Sara. Tap Start Conversation for real-time Gemini Live voice chat, or ask anything below."
            )
        )
    )
    private val _liveUserDraft = MutableStateFlow("")
    private val _liveSaraDraft = MutableStateFlow("")
    private val _selectedSessionId = MutableStateFlow<String?>(null)
    private val _pendingConfirmation = MutableStateFlow<PendingConfirmation?>(null)
    private val _pendingPermissionRequest = MutableStateFlow<PermissionRequestPrompt?>(null)
    private val _statusBanner = MutableStateFlow<String?>(null)
    private val _availableVoices = MutableStateFlow<List<ElevenLabsVoiceOption>>(emptyList())
    private val _isFetchingVoices = MutableStateFlow(false)
    private val _voicesFetchMessage = MutableStateFlow<String?>(null)
    private val _isTestingGemini = MutableStateFlow(false)
    private val _geminiTestResult = MutableStateFlow<String?>(null)
    private val _visionState = MutableStateFlow<VisionAnalysisUiState>(VisionAnalysisUiState.Idle)
    private val _incomingCallReport = MutableStateFlow(incomingCallManager.inspectCapabilities())
    private val _incomingCallSimResult = MutableStateFlow<String?>(null)

    private val _navigationEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val navigationEvents: SharedFlow<String> = _navigationEvents.asSharedFlow()

    private val _requestMicPermissionEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val requestMicPermissionEvent: SharedFlow<Unit> = _requestMicPermissionEvent.asSharedFlow()

    private val _batteryOptimizationIgnored = MutableStateFlow(checkBatteryOptimizationIgnored())
    private val _commandPermissions = MutableStateFlow(checkCommandPermissions())
    private var pendingEnableWakeAfterMicGrant = false

    private var activeSessionId: String = UUID.randomUUID().toString()
    private var sessionInitializedInDb = false

    private var activeTurnJob: Job? = null
    private var bargeInMonitorJob: Job? = null
    private var autoRestartAllowed = false
    private var consecutiveSilentTimeouts = 0
    private var lastAutoResumeFromVoice = false

    private val liveStatusFlow = combine(
        _classicStatus,
        geminiLiveClient.connectionState,
        credentialStore.behaviorSettings,
        sttEngine.normalizedRms,
        ttsEngine.playbackAmplitude,
        geminiLiveClient.micAmplitude,
        geminiLiveClient.playbackAmplitude
    ) { values ->
        val classic = values[0] as AssistantStatus
        val liveConn = values[1] as LiveConnectionState
        val behavior = values[2] as AssistantBehaviorSettings
        val sttRms = values[3] as Float
        val ttsAmp = values[4] as Float
        val liveMicAmp = values[5] as Float
        val livePlayAmp = values[6] as Float

        val effectiveStatus = if (behavior.geminiLiveEnabled && liveConn != LiveConnectionState.DISCONNECTED) {
            when (liveConn) {
                LiveConnectionState.CONNECTING -> AssistantStatus.CONNECTING
                LiveConnectionState.CONNECTED -> AssistantStatus.CONNECTED
                LiveConnectionState.LISTENING -> AssistantStatus.LISTENING
                LiveConnectionState.SPEAKING -> AssistantStatus.SPEAKING
                LiveConnectionState.RECONNECTING -> AssistantStatus.RECONNECTING
                LiveConnectionState.ERROR -> AssistantStatus.ERROR
                LiveConnectionState.DISCONNECTED -> classic
            }
        } else {
            classic
        }

        val wave = if (behavior.geminiLiveEnabled && liveConn != LiveConnectionState.DISCONNECTED) {
            when (liveConn) {
                LiveConnectionState.SPEAKING -> livePlayAmp.coerceAtLeast(0.18f)
                LiveConnectionState.LISTENING,
                LiveConnectionState.CONNECTED -> liveMicAmp.coerceAtLeast(0.08f)
                LiveConnectionState.CONNECTING,
                LiveConnectionState.RECONNECTING -> 0.42f
                else -> 0.08f
            }
        } else {
            when (classic) {
                AssistantStatus.LISTENING -> sttRms
                AssistantStatus.SPEAKING -> ttsAmp
                AssistantStatus.CONNECTED -> 0.16f
                AssistantStatus.THINKING,
                AssistantStatus.CONNECTING,
                AssistantStatus.RECONNECTING -> 0.45f
                AssistantStatus.IDLE,
                AssistantStatus.ERROR -> 0.08f
            }
        }

        Triple(effectiveStatus, liveConn, wave)
    }

    private val transcriptAndMessagesFlow = combine(
        sttEngine.partialTranscript,
        _liveUserDraft,
        _liveSaraDraft,
        _messages,
        historyRepository.observeSessions()
    ) { sttPartial, liveUser, liveSara, messages, sessions ->
        TranscriptBundle(
            partialTranscript = liveUser.ifBlank { sttPartial },
            liveSaraTranscript = liveSara,
            messages = messages,
            sessions = sessions
        )
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val selectedTurnsFlow = _selectedSessionId.flatMapLatest { sid ->
        if (sid == null) flowOf(emptyList()) else historyRepository.observeTurnsForSession(sid)
    }

    private val configFlow = combine(
        credentialStore.geminiCredentials,
        credentialStore.elevenLabsCredentials,
        credentialStore.userProfile,
        credentialStore.behaviorSettings,
        memoryRepository.observeMemories()
    ) { gemini, eleven, profile, behavior, memories ->
        ConfigBundle(gemini, eleven, profile, behavior, memories)
    }

    private val auxFlow = combine(
        _pendingConfirmation,
        _pendingPermissionRequest,
        _statusBanner,
        _availableVoices,
        _isFetchingVoices,
        _voicesFetchMessage,
        _isTestingGemini,
        _geminiTestResult,
        _visionState,
        _incomingCallReport,
        _incomingCallSimResult,
        _selectedSessionId,
        selectedTurnsFlow
    ) { values ->
        AuxBundle(
            pendingConfirmation = values[0] as PendingConfirmation?,
            pendingPermissionRequest = values[1] as PermissionRequestPrompt?,
            statusBanner = values[2] as String?,
            availableVoices = @Suppress("UNCHECKED_CAST") (values[3] as List<ElevenLabsVoiceOption>),
            isFetchingVoices = values[4] as Boolean,
            voicesFetchMessage = values[5] as String?,
            isTestingGemini = values[6] as Boolean,
            geminiTestResult = values[7] as String?,
            visionState = values[8] as VisionAnalysisUiState,
            incomingCallReport = values[9] as IncomingCallCapabilityReport?,
            incomingCallSimResult = values[10] as String?,
            selectedSessionId = values[11] as String?,
            selectedSessionTurns = @Suppress("UNCHECKED_CAST") (values[12] as List<ConversationTurnEntity>)
        )
    }

    private val wakeFlow = combine(
        wakeEventBus.serviceState,
        wakeEventBus.serviceDiagnostic,
        voskInspectionFlow,
        wakeEnrollmentManager.uiState,
        microphoneTestEngine.state,
        wakeEventBus.popupState,
        _batteryOptimizationIgnored,
        audioFocusManager.focusConflictReason
    ) { values ->
        WakeBundle(
            serviceState = values[0] as WakeServiceState,
            serviceDiagnostic = values[1] as String?,
            voskInspection = values[2] as VoskModelInspectionResult?,
            enrollmentState = values[3] as WakeEnrollmentUiState,
            micTestState = values[4] as MicTestUiState,
            popupState = values[5] as WakePopupUiState,
            isIgnoringBatteryOptimizations = values[6] as Boolean,
            audioFocusConflict = values[7] as String?
        )
    }

    private val waConfigFlow = combine(
        whatsAppAutomationManager.settingsFlow,
        whatsAppAutomationManager.serviceStatus,
        whatsAppAutomationManager.statusDetail,
        whatsAppAutomationManager.listenerConnected,
        whatsAppAutomationManager.notificationAccessGranted
    ) { settings, status, detail, connected, granted ->
        WaConfigBundle(settings, status, detail, connected, granted)
    }

    private val waDataFlow = combine(
        whatsAppAutomationManager.contactRulesFlow,
        whatsAppAutomationManager.contactProfilesFlow,
        whatsAppAutomationManager.recentMessagesFlow,
        whatsAppAutomationManager.activityLogsFlow
    ) { rules, profiles, msgs, logs ->
        WaDataBundle(rules, profiles, msgs, logs)
    }

    private val whatsAppFlow = combine(
        waConfigFlow,
        waDataFlow,
        _commandPermissions
    ) { cfg, data, cmdPerms ->
        Triple(cfg, data, cmdPerms)
    }

    val uiState: StateFlow<SaraUiState> = combine(
        liveStatusFlow,
        transcriptAndMessagesFlow,
        configFlow,
        auxFlow,
        wakeFlow,
        whatsAppFlow
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val statusTriple = values[0] as Triple<AssistantStatus, LiveConnectionState, Float>
        val transcriptBundle = values[1] as TranscriptBundle
        val cfg = values[2] as ConfigBundle
        val aux = values[3] as AuxBundle
        val wake = values[4] as WakeBundle
        @Suppress("UNCHECKED_CAST")
        val waTriple = values[5] as Triple<WaConfigBundle, WaDataBundle, CommandPermissionStatus>
        val waCfg = waTriple.first
        val waData = waTriple.second
        val cmdPerms = waTriple.third

        SaraUiState(
            status = statusTriple.first,
            liveConnectionState = statusTriple.second,
            partialTranscript = transcriptBundle.partialTranscript,
            liveSaraTranscript = transcriptBundle.liveSaraTranscript,
            waveAmplitude = statusTriple.third,
            messages = transcriptBundle.messages,
            pastSessions = transcriptBundle.sessions,
            selectedSessionId = aux.selectedSessionId,
            selectedSessionTurns = aux.selectedSessionTurns,
            pendingConfirmation = aux.pendingConfirmation,
            pendingPermissionRequest = aux.pendingPermissionRequest,
            statusBannerMessage = aux.statusBanner,
            geminiCredentials = cfg.gemini,
            elevenLabsCredentials = cfg.eleven,
            userProfile = cfg.userProfile,
            behaviorSettings = cfg.behavior,
            memories = cfg.memories,
            memoryBackendName = memoryRepository.backendName,
            availableVoices = aux.availableVoices,
            isFetchingVoices = aux.isFetchingVoices,
            voicesFetchMessage = aux.voicesFetchMessage,
            isTestingGemini = aux.isTestingGemini,
            geminiTestResult = aux.geminiTestResult,
            visionState = aux.visionState,
            incomingCallReport = aux.incomingCallReport,
            incomingCallSimResult = aux.incomingCallSimResult,
            isSpeechRecognizerAvailable = sttEngine.isRecognitionAvailable(),
            wakeServiceState = wake.serviceState,
            wakeServiceDiagnostic = wake.serviceDiagnostic,
            voskInspection = wake.voskInspection,
            wakeEnrollmentState = wake.enrollmentState,
            micTestState = wake.micTestState,
            wakePopupState = wake.popupState,
            isIgnoringBatteryOptimizations = wake.isIgnoringBatteryOptimizations,
            audioFocusConflict = wake.audioFocusConflict,
            waAutomationSettings = waCfg.settings,
            waServiceStatus = waCfg.status,
            waStatusDetail = waCfg.detail,
            waListenerConnected = waCfg.listenerConnected,
            waNotificationAccessGranted = waCfg.notificationAccessGranted,
            waContactRules = waData.rules,
            waContactProfiles = waData.profiles,
            waRecentMessages = waData.messages,
            waActivityLogs = waData.logs,
            commandPermissions = cmdPerms
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SaraUiState(
            incomingCallReport = incomingCallManager.inspectCapabilities(),
            isSpeechRecognizerAvailable = sttEngine.isRecognitionAvailable(),
            voskInspection = voskInspectionFlow.value,
            isIgnoringBatteryOptimizations = checkBatteryOptimizationIgnored(),
            waNotificationAccessGranted = whatsAppAutomationManager.notificationAccessGranted.value,
            commandPermissions = checkCommandPermissions()
        )
    )

    init {
        observeSttEvents()
        observeGeminiLiveEvents()
        observeWakeEvents()
        syncWakeServiceOnLaunch()
    }

    private fun observeWakeEvents() {
        viewModelScope.launch {
            wakeEventBus.wakeTriggers.collect { event ->
                handleWakePhraseTriggered(event.recognizedPhrase, hasMicPermission())
            }
        }
        viewModelScope.launch {
            liveStatusFlow.collect { (status, _, amp) ->
                if (wakeEventBus.popupState.value.visible) {
                    val subtitle = when (status) {
                        AssistantStatus.CONNECTING -> "Connecting Gemini Live..."
                        AssistantStatus.CONNECTED,
                        AssistantStatus.LISTENING -> "Listening to your command..."
                        AssistantStatus.THINKING -> "Thinking..."
                        AssistantStatus.SPEAKING -> "Sara is speaking..."
                        AssistantStatus.RECONNECTING -> "Reconnecting..."
                        AssistantStatus.ERROR -> "Voice error — check Settings"
                        AssistantStatus.IDLE -> "Ready"
                    }
                    val preview = _liveUserDraft.value
                        .ifBlank { sttEngine.partialTranscript.value }
                        .ifBlank { _liveSaraDraft.value }
                    wakeEventBus.updateWakePopup(subtitle = subtitle, preview = preview, amplitude = amp)
                }
            }
        }
    }

    private fun syncWakeServiceOnLaunch() {
        viewModelScope.launch {
            val behavior = credentialStore.behaviorSettings.first()
            onRefreshVoskInspection()
            val inspection = voskInspectionFlow.value
            if (behavior.wakeUpDetectionEnabled) {
                when {
                    !hasMicPermission() -> {
                        wakeEventBus.updateServiceState(
                            WakeServiceState.PERMISSION_MISSING,
                            "Microphone permission required for Wake Up Detection."
                        )
                    }
                    !inspection.isReady -> {
                        wakeEventBus.updateServiceState(
                            WakeServiceState.MODEL_MISSING,
                            inspection.diagnosticMessage
                        )
                    }
                    else -> {
                        WakeDetectionService.start(appContext)
                    }
                }
            }
        }
    }

    private fun observeGeminiLiveEvents() {
        viewModelScope.launch {
            geminiLiveClient.events.collect { event ->
                when (event) {
                    GeminiLiveEvent.Connected -> {
                        _statusBanner.value = null
                    }
                    is GeminiLiveEvent.InputTranscript -> {
                        _liveUserDraft.update { prev -> appendTranscriptFragment(prev, event.text) }
                    }
                    is GeminiLiveEvent.OutputTranscript -> {
                        // Commit pending user transcript as soon as Sara starts replying
                        commitLiveUserDraftIfPresent()
                        _liveSaraDraft.update { prev -> appendTranscriptFragment(prev, event.text) }
                    }
                    GeminiLiveEvent.Interrupted -> {
                        commitLiveUserDraftIfPresent()
                        commitLiveSaraDraftIfPresent(interrupted = true)
                    }
                    GeminiLiveEvent.TurnComplete -> {
                        commitLiveUserDraftIfPresent()
                        commitLiveSaraDraftIfPresent(interrupted = false)
                    }
                    is GeminiLiveEvent.Error -> {
                        _statusBanner.value = "${event.userMessage} (${event.diagnostic})"
                        appendErrorMessage(event.userMessage)
                    }
                    is GeminiLiveEvent.Disconnected -> {
                        commitLiveUserDraftIfPresent()
                        commitLiveSaraDraftIfPresent(interrupted = false)
                    }
                }
            }
        }
    }

    private suspend fun commitLiveUserDraftIfPresent() {
        val text = _liveUserDraft.value.trim()
        if (text.isEmpty()) return
        _liveUserDraft.value = ""
        appendMessage(ConversationMessage(isUser = true, text = text, actionTag = "LIVE"))
        persistTurnToHistory(isUser = true, text = text, mode = "LIVE")
    }

    private suspend fun commitLiveSaraDraftIfPresent(interrupted: Boolean) {
        val text = _liveSaraDraft.value.trim()
        if (text.isEmpty()) return
        _liveSaraDraft.value = ""
        val suffix = if (interrupted) " [interrupted]" else ""
        val display = "$text$suffix"
        appendMessage(ConversationMessage(isUser = false, text = display, actionTag = "SARA LIVE"))
        persistTurnToHistory(isUser = false, text = display, mode = "LIVE")
        maybeExtractLiveMemory(text)
    }

    private suspend fun maybeExtractLiveMemory(saraResponse: String) {
        if (!uiState.value.behaviorSettings.memoryEnabled) return
        // Keep long-term memory focused on durable facts rather than noisy chatter;
        // explicit user statements are stored cleanly when the user mentions preferences or details.
        val lastUser = _messages.value.lastOrNull { it.isUser }?.text.orEmpty().trim()
        val lower = lastUser.lowercase()
        val containsDurableCue = listOf(
            "my name is", "mera naam", "i like", "i prefer", "remember", "yaad rakhna",
            "call me", "birthday", "lives in", "i work", "favorite", "favourite"
        ).any { lower.contains(it) }
        if (containsDurableCue && lastUser.length in 6..180) {
            val cat = when {
                lower.contains("prefer") || lower.contains("like") || lower.contains("fav") ->
                    MemoryCategory.PREFERENCE
                lower.contains("name") || lower.contains("naam") || lower.contains("call me") ->
                    MemoryCategory.USER_INFO
                else -> MemoryCategory.CONVERSATION
            }
            memoryRepository.saveMemory(category = cat, content = lastUser, source = "live_voice")
        }
    }

    private fun observeSttEvents() {
        viewModelScope.launch {
            sttEngine.events.collect { event ->
                audioCaptureCoordinator.release(AudioCaptureOwner.CLASSIC_STT)
                when (event) {
                    is SttEvent.FinalTranscript -> {
                        consecutiveSilentTimeouts = 0
                        lastAutoResumeFromVoice = true
                        handleUserUtterance(event.text, fromVoice = true)
                    }
                    is SttEvent.TransientTimeout -> {
                        val behavior = uiState.value.behaviorSettings
                        if (autoRestartAllowed &&
                            (behavior.continuousConversationEnabled || behavior.liveCallModeEnabled)
                        ) {
                            consecutiveSilentTimeouts++
                            if (behavior.liveCallModeEnabled || consecutiveSilentTimeouts <= 3) {
                                delay(300L)
                                if (autoRestartAllowed && _classicStatus.value != AssistantStatus.SPEAKING &&
                                    _classicStatus.value != AssistantStatus.THINKING &&
                                    audioCaptureCoordinator.acquire(AudioCaptureOwner.CLASSIC_STT)
                                ) {
                                    _classicStatus.value = AssistantStatus.LISTENING
                                    sttEngine.startListening(uiState.value.userProfile.sttLocaleTag())
                                }
                            } else {
                                autoRestartAllowed = false
                                _classicStatus.value = AssistantStatus.IDLE
                                _statusBanner.value = "Listening paused after silence. Tap Start to resume."
                            }
                        } else {
                            _classicStatus.value = AssistantStatus.IDLE
                        }
                    }
                    is SttEvent.Error -> {
                        _classicStatus.value = AssistantStatus.IDLE
                        _statusBanner.value = event.message
                        if (event.isFatal) {
                            autoRestartAllowed = false
                            appendErrorMessage(event.message)
                        }
                    }
                }
            }
        }
    }

    fun onMicButtonPressed(hasMicPermission: Boolean) {
        if (!hasMicPermission) {
            _requestMicPermissionEvent.tryEmit(Unit)
            return
        }

        val behavior = uiState.value.behaviorSettings
        if (behavior.geminiLiveEnabled) {
            if (geminiLiveClient.connectionState.value == LiveConnectionState.DISCONNECTED ||
                geminiLiveClient.connectionState.value == LiveConnectionState.ERROR
            ) {
                startGeminiLiveSession()
            } else {
                endConversationSession()
            }
            return
        }

        when (_classicStatus.value) {
            AssistantStatus.LISTENING -> {
                autoRestartAllowed = false
                sttEngine.stopListening()
                _classicStatus.value = AssistantStatus.IDLE
            }
            AssistantStatus.SPEAKING,
            AssistantStatus.THINKING,
            AssistantStatus.CONNECTING,
            AssistantStatus.RECONNECTING -> {
                endConversationSession()
            }
            AssistantStatus.CONNECTED,
            AssistantStatus.ERROR,
            AssistantStatus.IDLE -> {
                startVoiceListening()
            }
        }
    }

    fun onMicPermissionResult(granted: Boolean) {
        if (pendingEnableWakeAfterMicGrant) {
            pendingEnableWakeAfterMicGrant = false
            if (granted) {
                setWakeUpDetectionEnabled(true, hasMicPermission = true)
            } else {
                viewModelScope.launch { credentialStore.updateWakeUpDetectionEnabled(false) }
                wakeEventBus.updateServiceState(
                    WakeServiceState.PERMISSION_MISSING,
                    "Microphone permission denied for Wake Up Detection."
                )
                _statusBanner.value = "Microphone permission is required for Wake Up Detection."
            }
            return
        }

        if (granted) {
            _statusBanner.value = null
            if (uiState.value.behaviorSettings.geminiLiveEnabled) {
                startGeminiLiveSession()
            } else {
                startVoiceListening()
            }
        } else {
            autoRestartAllowed = false
            _classicStatus.value = AssistantStatus.IDLE
            val msg = "Microphone permission required hai."
            _statusBanner.value = msg
            appendErrorMessage(msg)
        }
    }

    fun handleWakePhraseTriggered(phrase: String = "hey sara", hasMicPermission: Boolean = hasMicPermission()) {
        if (!hasMicPermission) {
            _statusBanner.value = "Grant Microphone permission to listen after wake phrase."
            _requestMicPermissionEvent.tryEmit(Unit)
            return
        }
        wakeEventBus.showWakePopup("Hey Sara detected — listening...")
        if (uiState.value.behaviorSettings.geminiLiveEnabled) {
            if (geminiLiveClient.connectionState.value == LiveConnectionState.DISCONNECTED ||
                geminiLiveClient.connectionState.value == LiveConnectionState.ERROR
            ) {
                startGeminiLiveSession()
            }
        } else {
            startVoiceListening()
        }
    }

    fun dismissWakePopup() {
        wakeEventBus.dismissWakePopup()
        endConversationSession()
    }

    private fun startGeminiLiveSession() {
        _statusBanner.value = null
        _liveUserDraft.value = ""
        _liveSaraDraft.value = ""
        bargeInMonitorJob?.cancel()
        ttsEngine.stop()
        sttEngine.cancel()
        audioCaptureCoordinator.release(AudioCaptureOwner.CLASSIC_STT)

        viewModelScope.launch {
            val behavior = credentialStore.behaviorSettings.first()
            val profile = credentialStore.userProfile.first()
            val memories = if (behavior.memoryEnabled) {
                memoryRepository.getContextMemories(limit = 15)
            } else {
                emptyList()
            }
            val recentDbTurns = historyRepository.getRecentContextTurns(limit = 8)
                .map { ChatTurn(isUser = it.isUser, text = it.text) }

            geminiLiveClient.startSession(
                userProfile = profile,
                memories = memories,
                recentHistory = recentDbTurns
            )
        }
    }

    fun startVoiceListening() {
        if (!sttEngine.isRecognitionAvailable()) {
            val msg = "Speech recognition service is unavailable on this device. You can use text input below."
            _statusBanner.value = msg
            appendErrorMessage(msg)
            return
        }
        if (!audioCaptureCoordinator.acquire(AudioCaptureOwner.CLASSIC_STT)) {
            _statusBanner.value = "Microphone is busy with another session."
            return
        }
        _statusBanner.value = null
        consecutiveSilentTimeouts = 0
        autoRestartAllowed = true
        lastAutoResumeFromVoice = true
        _classicStatus.value = AssistantStatus.LISTENING
        sttEngine.startListening(uiState.value.userProfile.sttLocaleTag())
    }

    fun interruptSpeechAndListen() {
        bargeInMonitorJob?.cancel()
        bargeInMonitorJob = null
        audioCaptureCoordinator.release(AudioCaptureOwner.BARGE_IN_MONITOR)

        if (uiState.value.behaviorSettings.geminiLiveEnabled &&
            geminiLiveClient.connectionState.value != LiveConnectionState.DISCONNECTED
        ) {
            geminiLiveClient.interruptCurrentPlayback()
            return
        }

        ttsEngine.stop()
        if (hasMicPermission()) {
            startVoiceListening()
        } else {
            _classicStatus.value = AssistantStatus.IDLE
        }
    }

    fun endConversationSession() {
        autoRestartAllowed = false
        lastAutoResumeFromVoice = false
        activeTurnJob?.cancel()
        activeTurnJob = null
        bargeInMonitorJob?.cancel()
        bargeInMonitorJob = null
        audioCaptureCoordinator.release(AudioCaptureOwner.BARGE_IN_MONITOR)
        audioCaptureCoordinator.release(AudioCaptureOwner.CLASSIC_STT)
        geminiLiveClient.stopSession()
        ttsEngine.stop()
        sttEngine.cancel()
        wakeEventBus.dismissWakePopup()
        _classicStatus.value = AssistantStatus.IDLE
    }

    fun stopSpeakingAndListening() {
        endConversationSession()
    }

    fun startNewConversationSession() {
        endConversationSession()
        activeSessionId = UUID.randomUUID().toString()
        sessionInitializedInDb = false
        _messages.value = listOf(
            ConversationMessage(
                isUser = false,
                text = "New conversation started. I'm listening whenever you're ready!"
            )
        )
    }

    fun sendTextCommand(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return

        // If Gemini Live WebSocket is currently open & active, send directly through the Live session
        val liveState = geminiLiveClient.connectionState.value
        if (uiState.value.behaviorSettings.geminiLiveEnabled &&
            (liveState == LiveConnectionState.CONNECTED ||
                liveState == LiveConnectionState.LISTENING ||
                liveState == LiveConnectionState.SPEAKING)
        ) {
            appendMessage(ConversationMessage(isUser = true, text = clean, actionTag = "LIVE TEXT"))
            viewModelScope.launch {
                persistTurnToHistory(isUser = true, text = clean, mode = "LIVE")
            }
            geminiLiveClient.sendTextTurn(clean)
            return
        }

        lastAutoResumeFromVoice = false
        autoRestartAllowed = false
        handleUserUtterance(clean, fromVoice = false)
    }

    private fun handleUserUtterance(utterance: String, fromVoice: Boolean) {
        activeTurnJob?.cancel()
        activeTurnJob = viewModelScope.launch {
            _statusBanner.value = null
            appendMessage(ConversationMessage(isUser = true, text = utterance))
            persistTurnToHistory(isUser = true, text = utterance, mode = "CLASSIC")

            val pending = _pendingConfirmation.value
            if (pending != null) {
                if (CommandParser.isAffirmativeConfirmation(utterance)) {
                    _pendingConfirmation.value = null
                    executePendingAction(pending, resumeListening = fromVoice)
                    return@launch
                } else if (CommandParser.isNegativeConfirmation(utterance)) {
                    _pendingConfirmation.value = null
                    val cancelReply = "Theek hai, action cancel kar diya."
                    appendMessage(ConversationMessage(isUser = false, text = cancelReply, actionTag = "CANCELLED"))
                    persistTurnToHistory(isUser = false, text = cancelReply, mode = "CLASSIC")
                    speakAndMaybeResume(cancelReply, resumeListening = fromVoice)
                    return@launch
                } else {
                    _pendingConfirmation.value = null
                }
            }

            if (!networkMonitor.isOnline()) {
                val netErr = "Internet connection check kijiye."
                _classicStatus.value = AssistantStatus.ERROR
                _statusBanner.value = netErr
                appendErrorMessage(netErr)
                return@launch
            }

            _classicStatus.value = AssistantStatus.THINKING

            val behavior = uiState.value.behaviorSettings
            val profile = uiState.value.userProfile
            val memories = if (behavior.memoryEnabled) {
                memoryRepository.getContextMemories(limit = 15)
            } else {
                emptyList()
            }

            val history = _messages.value
                .filterNot { it.isError }
                .takeLast(12)
                .map { ChatTurn(isUser = it.isUser, text = it.text) }

            val result = geminiClient.generateAssistantResponse(
                userMessage = utterance,
                history = history,
                memories = memories,
                userProfile = profile,
                liveCallMode = behavior.liveCallModeEnabled
            )

            when (result) {
                is GeminiResult.Failure -> {
                    _classicStatus.value = AssistantStatus.ERROR
                    _statusBanner.value = "${result.userFacingMessage} (${result.diagnostic})"
                    appendErrorMessage(result.userFacingMessage)
                    if (behavior.autoSpeakResponses && uiState.value.elevenLabsCredentials.isConfigured) {
                        speakAndMaybeResume(result.userFacingMessage, resumeListening = false)
                    }
                }
                is GeminiResult.Success -> {
                    val cmd = result.command
                    if (behavior.memoryEnabled) {
                        saveMemoriesFromTurn(utterance, cmd)
                    }

                    val execution = actionRouter.route(
                        command = cmd,
                        confirmBeforeExternalAction = behavior.confirmBeforeExternalAction,
                        onNavigateToRoute = { route -> _navigationEvents.tryEmit(route) }
                    )

                    var launchedExternalActivity = false
                    var replyToSpeak = cmd.spokenReply
                    var actionStatus: String? = null

                    when (execution) {
                        is ActionExecutionResult.Executed -> {
                            actionStatus = execution.statusMessage
                            launchedExternalActivity = cmd.category in setOf(
                                ActionCategory.OPEN_APP,
                                ActionCategory.PLAY_YOUTUBE,
                                ActionCategory.CALL_CONTACT,
                                ActionCategory.SEND_MESSAGE
                            )
                        }
                        is ActionExecutionResult.NeedsConfirmation -> {
                            _pendingConfirmation.value = execution.pending
                            replyToSpeak = execution.pending.summary
                            actionStatus = "Awaiting confirmation"
                        }
                        is ActionExecutionResult.NeedsPermission -> {
                            _pendingPermissionRequest.value = PermissionRequestPrompt(
                                permission = execution.permission,
                                rationale = execution.rationale,
                                retryCommand = execution.retryCommand
                            )
                            actionStatus = "Permission required"
                        }
                        is ActionExecutionResult.Unavailable -> {
                            actionStatus = execution.reason
                            if (cmd.category != ActionCategory.CHAT) {
                                replyToSpeak = "${cmd.spokenReply} (${execution.reason})"
                            }
                        }
                        ActionExecutionResult.NoOp -> Unit
                    }

                    appendMessage(
                        ConversationMessage(
                            isUser = false,
                            text = replyToSpeak,
                            actionTag = if (cmd.category != ActionCategory.CHAT) cmd.category.name else null,
                            actionStatus = actionStatus
                        )
                    )
                    persistTurnToHistory(isUser = false, text = replyToSpeak, mode = "CLASSIC")

                    val shouldResume = (fromVoice || lastAutoResumeFromVoice) &&
                        !launchedExternalActivity &&
                        (behavior.continuousConversationEnabled || behavior.liveCallModeEnabled || execution is ActionExecutionResult.NeedsConfirmation)

                    speakAndMaybeResume(replyToSpeak, resumeListening = shouldResume)
                }
            }
        }
    }

    private suspend fun persistTurnToHistory(isUser: Boolean, text: String, mode: String) {
        if (!sessionInitializedInDb) {
            sessionInitializedInDb = true
            historyRepository.ensureSession(
                sessionId = activeSessionId,
                initialTitle = if (isUser) text else "Conversation with Sara",
                mode = mode
            )
        }
        historyRepository.appendTurn(
            sessionId = activeSessionId,
            isUser = isUser,
            text = text,
            sourceMode = mode,
            sessionTitleHint = if (isUser) text else null
        )
    }

    fun confirmPendingAction() {
        val pending = _pendingConfirmation.value ?: return
        _pendingConfirmation.value = null
        viewModelScope.launch {
            executePendingAction(pending, resumeListening = false)
        }
    }

    fun cancelPendingAction() {
        if (_pendingConfirmation.value == null) return
        _pendingConfirmation.value = null
        appendMessage(
            ConversationMessage(
                isUser = false,
                text = "Action cancelled.",
                actionTag = "CANCELLED"
            )
        )
    }

    private suspend fun executePendingAction(pending: PendingConfirmation, resumeListening: Boolean) {
        val result = actionRouter.executeConfirmedAction(pending)
        when (result) {
            is ActionExecutionResult.Executed -> {
                val msg = result.statusMessage
                appendMessage(
                    ConversationMessage(
                        isUser = false,
                        text = msg,
                        actionTag = pending.command.category.name,
                        actionStatus = "Executed"
                    )
                )
                speakAndMaybeResume(msg, resumeListening = false)
            }
            is ActionExecutionResult.Unavailable -> {
                appendErrorMessage(result.reason)
                speakAndMaybeResume(result.reason, resumeListening = resumeListening)
            }
            else -> Unit
        }
    }

    fun onDynamicPermissionResult(granted: Boolean) {
        val req = _pendingPermissionRequest.value ?: return
        _pendingPermissionRequest.value = null
        if (granted && req.retryCommand != null) {
            viewModelScope.launch {
                val behavior = uiState.value.behaviorSettings
                val execution = actionRouter.route(
                    command = req.retryCommand,
                    confirmBeforeExternalAction = behavior.confirmBeforeExternalAction,
                    onNavigateToRoute = { route -> _navigationEvents.tryEmit(route) }
                )
                if (execution is ActionExecutionResult.NeedsConfirmation) {
                    _pendingConfirmation.value = execution.pending
                    appendMessage(
                        ConversationMessage(
                            isUser = false,
                            text = execution.pending.summary,
                            actionTag = req.retryCommand.category.name,
                            actionStatus = "Awaiting confirmation"
                        )
                    )
                    speakAndMaybeResume(execution.pending.summary, resumeListening = true)
                } else if (execution is ActionExecutionResult.Executed) {
                    appendMessage(
                        ConversationMessage(
                            isUser = false,
                            text = execution.statusMessage,
                            actionTag = req.retryCommand.category.name
                        )
                    )
                }
            }
        } else if (!granted) {
            val deniedMsg = "Permission denied: ${req.rationale}"
            _statusBanner.value = deniedMsg
            appendErrorMessage(deniedMsg)
        }
    }

    fun dismissPermissionPrompt() {
        _pendingPermissionRequest.value = null
    }

    private suspend fun speakAndMaybeResume(text: String, resumeListening: Boolean) {
        val behavior = uiState.value.behaviorSettings
        if (!behavior.autoSpeakResponses) {
            _classicStatus.value = AssistantStatus.IDLE
            if (resumeListening && autoRestartAllowed &&
                audioCaptureCoordinator.acquire(AudioCaptureOwner.CLASSIC_STT)
            ) {
                delay(300L)
                _classicStatus.value = AssistantStatus.LISTENING
                sttEngine.startListening(uiState.value.userProfile.sttLocaleTag())
            }
            return
        }

        _classicStatus.value = AssistantStatus.SPEAKING
        if (behavior.bargeInEnabled && AcousticEchoCanceler.isAvailable() && hasMicPermission()) {
            startAecBargeInMonitor()
        }

        val ttsResult = ttsEngine.speak(text, liveCallMode = behavior.liveCallModeEnabled)
        bargeInMonitorJob?.cancel()
        bargeInMonitorJob = null
        audioCaptureCoordinator.release(AudioCaptureOwner.BARGE_IN_MONITOR)

        when (ttsResult) {
            is TtsResult.Completed -> {
                if (resumeListening && autoRestartAllowed &&
                    (behavior.continuousConversationEnabled || behavior.liveCallModeEnabled || _pendingConfirmation.value != null)
                ) {
                    delay(350L)
                    if (autoRestartAllowed && audioCaptureCoordinator.acquire(AudioCaptureOwner.CLASSIC_STT)) {
                        _classicStatus.value = AssistantStatus.LISTENING
                        sttEngine.startListening(uiState.value.userProfile.sttLocaleTag())
                    } else {
                        _classicStatus.value = AssistantStatus.IDLE
                    }
                } else {
                    _classicStatus.value = AssistantStatus.IDLE
                }
            }
            is TtsResult.Stopped -> {
                _classicStatus.value = AssistantStatus.IDLE
            }
            is TtsResult.Error -> {
                _statusBanner.value = "${ttsResult.userMessage} (${ttsResult.diagnostic})"
                if (resumeListening && autoRestartAllowed &&
                    (behavior.continuousConversationEnabled || behavior.liveCallModeEnabled)
                ) {
                    delay(350L)
                    if (autoRestartAllowed && audioCaptureCoordinator.acquire(AudioCaptureOwner.CLASSIC_STT)) {
                        _classicStatus.value = AssistantStatus.LISTENING
                        sttEngine.startListening(uiState.value.userProfile.sttLocaleTag())
                    } else {
                        _classicStatus.value = AssistantStatus.IDLE
                    }
                } else {
                    _classicStatus.value = AssistantStatus.IDLE
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAecBargeInMonitor() {
        bargeInMonitorJob?.cancel()
        if (!audioCaptureCoordinator.acquire(AudioCaptureOwner.BARGE_IN_MONITOR)) return
        bargeInMonitorJob = viewModelScope.launch(Dispatchers.IO) {
            val minBuf = AudioRecord.getMinBufferSize(
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                audioCaptureCoordinator.release(AudioCaptureOwner.BARGE_IN_MONITOR)
                return@launch
            }
            val recorder = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf * 2, 2560)
                )
            }.getOrNull()
            if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { recorder?.release() }
                audioCaptureCoordinator.release(AudioCaptureOwner.BARGE_IN_MONITOR)
                return@launch
            }
            val aec = runCatching { AcousticEchoCanceler.create(recorder.audioSessionId)?.apply { enabled = true } }.getOrNull()
            val vad = VoiceActivityDetector(
                speechStartRatio = 3.2,
                minSpeechRms = 1150.0,
                startConfirmFrames = 4,
                endSilenceHangoverFrames = 12
            )
            val buf = ByteArray(1280)
            try {
                recorder.startRecording()
                delay(350L) // Ignore speaker startup transient
                while (isActive && ttsEngine.isSpeaking.value) {
                    val read = recorder.read(buf, 0, buf.size)
                    if (read > 0) {
                        val (event, _) = vad.processFrame(buf, read)
                        if (event == VadEvent.SPEECH_STARTED) {
                            withContext(Dispatchers.Main) {
                                interruptSpeechAndListen()
                            }
                            break
                        }
                    }
                }
            } finally {
                runCatching { aec?.release() }
                runCatching {
                    if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
                    recorder.release()
                }
                audioCaptureCoordinator.release(AudioCaptureOwner.BARGE_IN_MONITOR)
            }
        }
    }

    private suspend fun saveMemoriesFromTurn(userText: String, cmd: AssistantCommand) {
        if (cmd.memoryNote.isNotBlank()) {
            memoryRepository.saveMemory(
                category = cmd.memoryCategory,
                content = cmd.memoryNote,
                source = "gemini_extract"
            )
        }
        val compactUser = userText.trim()
        if (compactUser.length > 6 && cmd.category != ActionCategory.CHAT) {
            memoryRepository.saveMemory(
                category = MemoryCategory.TASK,
                content = "Requested ${cmd.category.name}: $compactUser",
                source = "command"
            )
        }
    }

    fun analyzeImageWithGemini(bitmap: Bitmap, prompt: String, speakResult: Boolean = true) {
        viewModelScope.launch {
            if (!networkMonitor.isOnline()) {
                _visionState.value = VisionAnalysisUiState.Error("Internet connection check kijiye.")
                return@launch
            }
            _visionState.value = VisionAnalysisUiState.Analyzing
            val memories = if (uiState.value.behaviorSettings.memoryEnabled) {
                memoryRepository.getContextMemories(limit = 5)
            } else {
                emptyList()
            }

            when (val res = geminiClient.analyzeImage(bitmap, prompt, memories)) {
                is GeminiResult.Success -> {
                    val reply = res.command.spokenReply
                    _visionState.value = VisionAnalysisUiState.Success(prompt, reply)
                    appendMessage(
                        ConversationMessage(
                            isUser = false,
                            text = reply,
                            actionTag = ActionCategory.IMAGE_ANALYSIS.name,
                            actionStatus = "Vision analysis complete"
                        )
                    )
                    if (speakResult && uiState.value.behaviorSettings.autoSpeakResponses) {
                        speakAndMaybeResume(reply, resumeListening = false)
                    }
                }
                is GeminiResult.Failure -> {
                    _visionState.value = VisionAnalysisUiState.Error(
                        "${res.userFacingMessage} (${res.diagnostic})"
                    )
                }
            }
        }
    }

    fun resetVisionState() {
        _visionState.value = VisionAnalysisUiState.Idle
    }

    fun saveGeminiSettings(
        apiKey: String,
        modelName: String,
        liveModelName: String,
        liveVoiceName: String
    ) {
        credentialStore.saveGeminiCredentials(apiKey, modelName, liveModelName, liveVoiceName)
        _geminiTestResult.value = "Gemini settings saved securely."
    }

    fun clearGeminiKey() {
        credentialStore.clearGeminiApiKey()
        _geminiTestResult.value = "Gemini API key cleared."
    }

    fun testGeminiConnection(apiKey: String, modelName: String) {
        viewModelScope.launch {
            _isTestingGemini.value = true
            _geminiTestResult.value = null
            val res = geminiClient.verifyApiKey(apiKey, modelName)
            _isTestingGemini.value = false
            _geminiTestResult.value = res.fold(
                onSuccess = { "Verified: $it" },
                onFailure = { "Error: ${it.localizedMessage ?: "Verification failed"}" }
            )
        }
    }

    fun saveElevenLabsSettings(apiKey: String, voiceId: String) {
        credentialStore.saveElevenLabsCredentials(apiKey, voiceId)
        _voicesFetchMessage.value = "ElevenLabs credentials saved securely."
    }

    fun clearElevenLabsSettings() {
        credentialStore.clearElevenLabsCredentials()
        _availableVoices.value = emptyList()
        _voicesFetchMessage.value = "ElevenLabs credentials cleared."
    }

    fun fetchElevenLabsVoices(apiKey: String) {
        viewModelScope.launch {
            _isFetchingVoices.value = true
            _voicesFetchMessage.value = null
            val res = ttsEngine.fetchAvailableVoices(apiKey)
            _isFetchingVoices.value = false
            res.fold(
                onSuccess = { list ->
                    _availableVoices.value = list
                    _voicesFetchMessage.value = "Loaded ${list.size} voices from ElevenLabs."
                },
                onFailure = { err ->
                    _voicesFetchMessage.value = err.localizedMessage ?: "Failed to load voices."
                }
            )
        }
    }

    fun saveUserProfile(name: String, preferredLanguage: String, preferencesNote: String) {
        viewModelScope.launch {
            credentialStore.saveUserProfile(name, preferredLanguage, preferencesNote)
            _statusBanner.value = null
        }
    }

    fun setGeminiLiveEnabled(enabled: Boolean) {
        if (!enabled) {
            geminiLiveClient.stopSession()
        }
        viewModelScope.launch { credentialStore.updateGeminiLiveEnabled(enabled) }
    }

    fun setContinuousConversation(enabled: Boolean) {
        viewModelScope.launch { credentialStore.updateContinuousConversation(enabled) }
    }

    fun setLiveCallMode(enabled: Boolean) {
        viewModelScope.launch {
            credentialStore.updateLiveCallMode(enabled)
            if (enabled) {
                credentialStore.updateContinuousConversation(true)
            }
        }
    }

    fun setMemoryEnabled(enabled: Boolean) {
        viewModelScope.launch { credentialStore.updateMemoryEnabled(enabled) }
    }

    fun setConfirmBeforeAction(enabled: Boolean) {
        viewModelScope.launch { credentialStore.updateConfirmBeforeExternalAction(enabled) }
    }

    fun setAutoSpeakResponses(enabled: Boolean) {
        viewModelScope.launch { credentialStore.updateAutoSpeakResponses(enabled) }
    }

    fun setBargeInEnabled(enabled: Boolean) {
        viewModelScope.launch { credentialStore.updateBargeInEnabled(enabled) }
    }

    fun setWakeSensitivity(sensitivity: Float) {
        viewModelScope.launch { credentialStore.updateWakeSensitivity(sensitivity) }
    }

    fun setWakeUpDetectionEnabled(enabled: Boolean, hasMicPermission: Boolean = hasMicPermission()) {
        if (!enabled) {
            pendingEnableWakeAfterMicGrant = false
            viewModelScope.launch { credentialStore.updateWakeUpDetectionEnabled(false) }
            WakeDetectionService.stop(appContext)
            wakeEventBus.updateServiceState(WakeServiceState.DISABLED, null)
            return
        }
        if (!hasMicPermission) {
            pendingEnableWakeAfterMicGrant = true
            wakeEventBus.updateServiceState(
                WakeServiceState.PERMISSION_MISSING,
                "Grant Microphone permission to enable Wake Up Detection."
            )
            _requestMicPermissionEvent.tryEmit(Unit)
            return
        }

        onRefreshVoskInspection()
        val inspection = voskInspectionFlow.value
        viewModelScope.launch { credentialStore.updateWakeUpDetectionEnabled(true) }
        if (!inspection.isReady) {
            wakeEventBus.updateServiceState(WakeServiceState.MODEL_MISSING, inspection.diagnosticMessage)
            _statusBanner.value = inspection.diagnosticMessage
            return
        }
        WakeDetectionService.start(appContext)
    }

    fun importVoskModelTreeUri(treeUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = VoskModelInspector.importModelFromTreeUri(appContext, treeUri)
            withContext(Dispatchers.Main) {
                onRefreshVoskInspection()
                result.fold(
                    onSuccess = { info ->
                        _statusBanner.value = info.diagnosticMessage
                        if (uiState.value.behaviorSettings.wakeUpDetectionEnabled && hasMicPermission() && info.isReady) {
                            WakeDetectionService.start(appContext)
                        }
                    },
                    onFailure = { err ->
                        _statusBanner.value = err.localizedMessage ?: "Failed to import Vosk model folder."
                    }
                )
            }
        }
    }

    fun clearImportedVoskModel() {
        WakeDetectionService.stop(appContext)
        VoskModelInspector.clearImportedModel(appContext)
        onRefreshVoskInspection()
        if (uiState.value.behaviorSettings.wakeUpDetectionEnabled) {
            wakeEventBus.updateServiceState(
                WakeServiceState.MODEL_MISSING,
                voskInspectionFlow.value.diagnosticMessage
            )
        }
    }

    fun recordNextWakeEnrollmentSample() {
        if (!hasMicPermission()) {
            _requestMicPermissionEvent.tryEmit(Unit)
            return
        }
        wakeEnrollmentManager.recordNextSample()
    }

    fun stopWakeEnrollmentRecording() {
        wakeEnrollmentManager.stopRecording()
    }

    fun clearWakeEnrollment() {
        viewModelScope.launch { wakeEnrollmentManager.clearEnrollment() }
    }

    fun startMicrophoneTest() {
        if (!hasMicPermission()) {
            _requestMicPermissionEvent.tryEmit(Unit)
            return
        }
        microphoneTestEngine.startTest()
    }

    fun stopMicrophoneTest() {
        microphoneTestEngine.stopTest()
    }

    fun refreshBatteryOptimizationStatus() {
        _batteryOptimizationIgnored.value = checkBatteryOptimizationIgnored()
        refreshCommandPermissions()
        whatsAppAutomationManager.refreshNotificationAccessState()
        whatsAppAutomationManager.requestListenerRebindIfNeeded()
    }

    fun refreshCommandPermissions() {
        _commandPermissions.value = checkCommandPermissions()
    }

    private fun checkCommandPermissions(): CommandPermissionStatus {
        fun granted(perm: String): Boolean =
            ContextCompat.checkSelfPermission(appContext, perm) == PackageManager.PERMISSION_GRANTED

        val postNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            granted(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }
        val btConnect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            true
        }
        return CommandPermissionStatus(
            recordAudio = granted(Manifest.permission.RECORD_AUDIO),
            readContacts = granted(Manifest.permission.READ_CONTACTS),
            callPhone = granted(Manifest.permission.CALL_PHONE),
            readPhoneState = granted(Manifest.permission.READ_PHONE_STATE),
            postNotifications = postNotif,
            camera = granted(Manifest.permission.CAMERA),
            bluetoothConnect = btConnect
        )
    }

    fun setWhatsAppAutomationEnabled(enabled: Boolean) {
        viewModelScope.launch { whatsAppAutomationManager.setAutomationEnabled(enabled) }
    }

    fun setWhatsAppGeminiBrainEnabled(enabled: Boolean) {
        viewModelScope.launch { whatsAppAutomationManager.settingsStore.setGeminiBrainEnabled(enabled) }
    }

    fun setWhatsAppAutoReplyEnabled(enabled: Boolean) {
        viewModelScope.launch { whatsAppAutomationManager.settingsStore.setAutoReplyEnabled(enabled) }
    }

    fun setWhatsAppVoiceAnnouncementEnabled(enabled: Boolean) {
        viewModelScope.launch { whatsAppAutomationManager.settingsStore.setVoiceAnnouncementEnabled(enabled) }
    }

    fun setWhatsAppReadIncomingEnabled(enabled: Boolean) {
        viewModelScope.launch { whatsAppAutomationManager.settingsStore.setReadIncomingMessagesAloud(enabled) }
    }

    fun setWhatsAppReplyToGroups(enabled: Boolean) {
        viewModelScope.launch { whatsAppAutomationManager.settingsStore.setReplyToGroupChats(enabled) }
    }

    fun setWhatsAppReplyLanguageMode(mode: String) {
        viewModelScope.launch { whatsAppAutomationManager.settingsStore.setReplyLanguageMode(mode) }
    }

    fun setWhatsAppReplyDelaySeconds(seconds: Int) {
        viewModelScope.launch { whatsAppAutomationManager.settingsStore.setAiReplyDelaySeconds(seconds) }
    }

    fun setWhatsAppMaxMessagesPerConversation(maxCount: Int) {
        viewModelScope.launch { whatsAppAutomationManager.settingsStore.setMaxMessagesPerConversation(maxCount) }
    }

    fun saveWhatsAppContactRule(
        contactNamePattern: String,
        triggerKeywords: String,
        customReply: String,
        aiInstruction: String,
        behaviorMode: String,
        priority: Int
    ) {
        viewModelScope.launch {
            whatsAppAutomationManager.ruleEngine.upsertRule(
                WhatsAppContactRuleEntity(
                    contactNamePattern = contactNamePattern,
                    triggerKeywords = triggerKeywords,
                    customReply = customReply,
                    aiInstruction = aiInstruction,
                    behaviorMode = behaviorMode,
                    priority = priority,
                    enabled = true
                )
            )
        }
    }

    fun toggleWhatsAppRuleEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch { whatsAppAutomationManager.ruleEngine.setRuleEnabled(id, enabled) }
    }

    fun deleteWhatsAppRule(id: Long) {
        viewModelScope.launch { whatsAppAutomationManager.ruleEngine.deleteRule(id) }
    }

    fun seedSampleRahulRules() {
        viewModelScope.launch { whatsAppAutomationManager.ruleEngine.addSampleRahulRules() }
    }

    fun updateWhatsAppContactInstructions(
        conversationKey: String,
        senderPreferences: String,
        customInstructions: String,
        isRestricted: Boolean
    ) {
        viewModelScope.launch {
            whatsAppAutomationManager.conversationMemory.updateContactInstructions(
                conversationKey = conversationKey,
                senderPreferences = senderPreferences,
                customInstructions = customInstructions,
                isRestricted = isRestricted
            )
        }
    }

    fun resetWhatsAppContactCounter(conversationKey: String) {
        viewModelScope.launch {
            whatsAppAutomationManager.conversationMemory.resetAutoReplyCount(conversationKey)
        }
    }

    fun clearWhatsAppContactMemory(conversationKey: String) {
        viewModelScope.launch {
            whatsAppAutomationManager.conversationMemory.clearContactMemory(conversationKey)
        }
    }

    fun clearAllWhatsAppMemory() {
        viewModelScope.launch { whatsAppAutomationManager.conversationMemory.clearAllMemory() }
    }

    fun clearWhatsAppActivityLogs() {
        viewModelScope.launch { whatsAppAutomationManager.conversationMemory.clearLogs() }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun checkBatteryOptimizationIgnored(): Boolean {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(appContext.packageName)
    }

    fun addManualMemory(category: MemoryCategory, content: String) {
        viewModelScope.launch {
            memoryRepository.saveMemory(category = category, content = content, source = "manual")
        }
    }

    fun deleteMemory(id: Long) {
        viewModelScope.launch { memoryRepository.deleteMemory(id) }
    }

    fun clearAllMemories() {
        viewModelScope.launch { memoryRepository.clearAllMemories() }
    }

    fun selectHistorySession(sessionId: String?) {
        _selectedSessionId.value = sessionId
    }

    fun deleteHistorySession(sessionId: String) {
        viewModelScope.launch {
            if (_selectedSessionId.value == sessionId) {
                _selectedSessionId.value = null
            }
            historyRepository.deleteSession(sessionId)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            _selectedSessionId.value = null
            historyRepository.clearAllSessions()
        }
    }

    fun refreshIncomingCallReport() {
        _incomingCallReport.value = incomingCallManager.inspectCapabilities()
    }

    fun simulateIncomingCallPrompt(callerName: String) {
        viewModelScope.launch {
            val prompt = "Sir, $callerName ka call hai. Answer karna hai?"
            _incomingCallSimResult.value = prompt
            if (uiState.value.behaviorSettings.autoSpeakResponses &&
                uiState.value.elevenLabsCredentials.isConfigured
            ) {
                ttsEngine.speak(prompt, liveCallMode = true)
            }
        }
    }

    fun simulateIncomingCallDecision(affirmative: Boolean) {
        _incomingCallSimResult.value = incomingCallManager.evaluateVoiceAnswerDecision(affirmative)
    }

    fun dismissBanner() {
        _statusBanner.value = null
    }

    private fun appendMessage(msg: ConversationMessage) {
        _messages.update { it + msg }
    }

    private fun appendErrorMessage(message: String) {
        _messages.update {
            it + ConversationMessage(isUser = false, text = message, isError = true)
        }
    }

    private fun appendTranscriptFragment(existing: String, fragment: String): String {
        if (fragment.isBlank()) return existing
        if (existing.isEmpty()) return fragment.trimStart()
        return if (existing.endsWith(" ") || fragment.startsWith(" ")) {
            existing + fragment
        } else {
            "$existing$fragment"
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            bargeInMonitorJob?.cancel()
            microphoneTestEngine.stopTest()
            wakeEnrollmentManager.stopRecording()
            geminiLiveClient.stopSession("ViewModel cleared")
            ttsEngine.release()
            sttEngine.release()
            audioCaptureCoordinator.release(AudioCaptureOwner.CLASSIC_STT)
            audioCaptureCoordinator.release(AudioCaptureOwner.BARGE_IN_MONITOR)
            audioFocusManager.abandonFocus()
        } catch (_: CancellationException) {
        }
    }

    private data class TranscriptBundle(
        val partialTranscript: String,
        val liveSaraTranscript: String,
        val messages: List<ConversationMessage>,
        val sessions: List<ConversationSessionEntity>
    )

    private data class ConfigBundle(
        val gemini: GeminiCredentials,
        val eleven: ElevenLabsCredentials,
        val userProfile: UserProfile,
        val behavior: AssistantBehaviorSettings,
        val memories: List<MemoryRecord>
    )

    private data class AuxBundle(
        val pendingConfirmation: PendingConfirmation?,
        val pendingPermissionRequest: PermissionRequestPrompt?,
        val statusBanner: String?,
        val availableVoices: List<ElevenLabsVoiceOption>,
        val isFetchingVoices: Boolean,
        val voicesFetchMessage: String?,
        val isTestingGemini: Boolean,
        val geminiTestResult: String?,
        val visionState: VisionAnalysisUiState,
        val incomingCallReport: IncomingCallCapabilityReport?,
        val incomingCallSimResult: String?,
        val selectedSessionId: String?,
        val selectedSessionTurns: List<ConversationTurnEntity>
    )

    private data class WakeBundle(
        val serviceState: WakeServiceState,
        val serviceDiagnostic: String?,
        val voskInspection: VoskModelInspectionResult?,
        val enrollmentState: WakeEnrollmentUiState,
        val micTestState: MicTestUiState,
        val popupState: WakePopupUiState,
        val isIgnoringBatteryOptimizations: Boolean,
        val audioFocusConflict: String?
    )

    private data class WaConfigBundle(
        val settings: AutomationSettings,
        val status: AutomationServiceStatus,
        val detail: String,
        val listenerConnected: Boolean,
        val notificationAccessGranted: Boolean
    )

    private data class WaDataBundle(
        val rules: List<WhatsAppContactRuleEntity>,
        val profiles: List<WhatsAppContactProfileEntity>,
        val messages: List<WhatsAppMessageEntity>,
        val logs: List<WhatsAppActivityLogEntity>
    )

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SaraViewModel(
                appContext = container.appContext,
                credentialStore = container.credentialStore,
                networkMonitor = container.networkMonitor,
                memoryRepository = container.memoryRepository,
                historyRepository = container.conversationHistoryRepository,
                geminiClient = container.geminiClient,
                geminiLiveClient = container.geminiLiveClient,
                ttsEngine = container.elevenLabsTtsEngine,
                sttEngine = container.sttEngine,
                actionRouter = container.actionRouter,
                incomingCallManager = container.incomingCallAssistantManager,
                audioCaptureCoordinator = container.audioCaptureCoordinator,
                audioFocusManager = container.audioFocusManager,
                microphoneTestEngine = container.microphoneTestEngine,
                wakeEnrollmentManager = container.wakeEnrollmentManager,
                wakeEventBus = container.wakeEventBus,
                voskInspectionFlow = container.voskInspection,
                onRefreshVoskInspection = { container.refreshVoskInspection() },
                whatsAppAutomationManager = container.whatsAppAutomationManager
            ) as T
        }
    }
}
