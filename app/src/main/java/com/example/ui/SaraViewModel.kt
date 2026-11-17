package com.example.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.actions.IncomingCallAssistantManager
import com.example.actions.IncomingCallCapabilityReport
import com.example.ai.gemini.GeminiClient
import com.example.ai.gemini.GeminiResult
import com.example.ai.gemini.live.GeminiLiveClient
import com.example.ai.gemini.live.LiveConnectionState
import com.example.automation.whatsapp.AutomationServiceStatus
import com.example.automation.whatsapp.AutomationSettings
import com.example.automation.whatsapp.WhatsAppActivityLogEntity
import com.example.automation.whatsapp.WhatsAppAutomationManager
import com.example.automation.whatsapp.WhatsAppContactProfileEntity
import com.example.automation.whatsapp.WhatsAppContactRuleEntity
import com.example.automation.whatsapp.WhatsAppMessageEntity
import com.example.commands.ActionCategory
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
import com.example.voice.SaraVoiceCoordinator
import com.example.voice.audio.MicTestUiState
import com.example.voice.audio.MicrophoneTestEngine
import com.example.voice.audio.SaraAudioFocusManager
import com.example.voice.stt.SttEngine
import com.example.voice.tts.ElevenLabsTtsEngine
import com.example.voice.tts.ElevenLabsVoiceOption
import com.example.voice.wake.SaraPowerMode
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SaraUiState(
    val status: AssistantStatus = AssistantStatus.IDLE,
    val powerMode: SaraPowerMode = SaraPowerMode.OFF,
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
    private val incomingCallManager: IncomingCallAssistantManager,
    private val audioFocusManager: SaraAudioFocusManager,
    private val microphoneTestEngine: MicrophoneTestEngine,
    private val wakeEnrollmentManager: WakePhraseEnrollmentManager,
    private val wakeEventBus: WakeEventBus,
    private val voiceCoordinator: SaraVoiceCoordinator,
    private val voskInspectionFlow: StateFlow<VoskModelInspectionResult>,
    private val onRefreshVoskInspection: () -> Unit,
    private val whatsAppAutomationManager: WhatsAppAutomationManager
) : ViewModel() {

    private val _selectedSessionId = MutableStateFlow<String?>(null)
    private val _availableVoices = MutableStateFlow<List<ElevenLabsVoiceOption>>(emptyList())
    private val _isFetchingVoices = MutableStateFlow(false)
    private val _voicesFetchMessage = MutableStateFlow<String?>(null)
    private val _isTestingGemini = MutableStateFlow(false)
    private val _geminiTestResult = MutableStateFlow<String?>(null)
    private val _visionState = MutableStateFlow<VisionAnalysisUiState>(VisionAnalysisUiState.Idle)
    private val _incomingCallReport = MutableStateFlow(incomingCallManager.inspectCapabilities())
    private val _incomingCallSimResult = MutableStateFlow<String?>(null)

    val navigationEvents: SharedFlow<String> = voiceCoordinator.navigationEvents
    val requestMicPermissionEvent: SharedFlow<Unit> = voiceCoordinator.requestMicPermissionEvent

    private val _batteryOptimizationIgnored = MutableStateFlow(checkBatteryOptimizationIgnored())
    private val _commandPermissions = MutableStateFlow(checkCommandPermissions())
    private var pendingEnableWakeAfterMicGrant = false

    private val liveStatusFlow = combine(
        voiceCoordinator.classicStatus,
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

        val effectiveStatus = when {
            classic == AssistantStatus.SLEEPING -> AssistantStatus.SLEEPING
            behavior.geminiLiveEnabled && liveConn != LiveConnectionState.DISCONNECTED -> {
                when (liveConn) {
                    LiveConnectionState.CONNECTING -> AssistantStatus.CONNECTING
                    LiveConnectionState.CONNECTED -> AssistantStatus.CONNECTED
                    LiveConnectionState.LISTENING -> AssistantStatus.LISTENING
                    LiveConnectionState.SPEAKING -> AssistantStatus.SPEAKING
                    LiveConnectionState.RECONNECTING -> AssistantStatus.RECONNECTING
                    LiveConnectionState.ERROR -> AssistantStatus.ERROR
                    LiveConnectionState.DISCONNECTED -> classic
                }
            }
            else -> classic
        }

        val wave = if (behavior.geminiLiveEnabled &&
            liveConn != LiveConnectionState.DISCONNECTED &&
            effectiveStatus != AssistantStatus.SLEEPING
        ) {
            when (liveConn) {
                LiveConnectionState.SPEAKING -> livePlayAmp.coerceAtLeast(0.18f)
                LiveConnectionState.LISTENING,
                LiveConnectionState.CONNECTED -> liveMicAmp.coerceAtLeast(0.08f)
                LiveConnectionState.CONNECTING,
                LiveConnectionState.RECONNECTING -> 0.42f
                else -> 0.08f
            }
        } else {
            when (effectiveStatus) {
                AssistantStatus.LISTENING -> sttRms
                AssistantStatus.SPEAKING -> ttsAmp
                AssistantStatus.CONNECTED -> 0.16f
                AssistantStatus.THINKING,
                AssistantStatus.CONNECTING,
                AssistantStatus.RECONNECTING -> 0.45f
                AssistantStatus.SLEEPING -> 0.05f
                AssistantStatus.IDLE,
                AssistantStatus.ERROR -> 0.08f
            }
        }

        Triple(effectiveStatus, liveConn, wave)
    }

    private val transcriptAndMessagesFlow = combine(
        sttEngine.partialTranscript,
        voiceCoordinator.liveUserDraft,
        voiceCoordinator.liveSaraDraft,
        voiceCoordinator.messages,
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
        voiceCoordinator.pendingConfirmation,
        voiceCoordinator.pendingPermissionRequest,
        voiceCoordinator.statusBanner,
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
        wakeEventBus.powerMode,
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
            powerMode = values[2] as SaraPowerMode,
            voskInspection = values[3] as VoskModelInspectionResult?,
            enrollmentState = values[4] as WakeEnrollmentUiState,
            micTestState = values[5] as MicTestUiState,
            popupState = values[6] as WakePopupUiState,
            isIgnoringBatteryOptimizations = values[7] as Boolean,
            audioFocusConflict = values[8] as String?
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
            powerMode = wake.powerMode,
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
        observeWakePopupStatus()
        syncWakeServiceOnLaunch()
    }

    private fun observeWakePopupStatus() {
        viewModelScope.launch {
            liveStatusFlow.collect { (status, _, amp) ->
                if (wakeEventBus.popupState.value.visible) {
                    val subtitle = when (status) {
                        AssistantStatus.CONNECTING -> "Connecting Gemini Live..."
                        AssistantStatus.CONNECTED,
                        AssistantStatus.LISTENING -> "Listening to your command..."
                        AssistantStatus.THINKING -> "Thinking..."
                        AssistantStatus.SPEAKING -> "Sara is speaking..."
                        AssistantStatus.SLEEPING -> "Sleep Mode — say \"Hey Sara\""
                        AssistantStatus.RECONNECTING -> "Reconnecting..."
                        AssistantStatus.ERROR -> "Voice error — check Settings"
                        AssistantStatus.IDLE -> "Ready"
                    }
                    val preview = voiceCoordinator.liveUserDraft.value
                        .ifBlank { sttEngine.partialTranscript.value }
                        .ifBlank { voiceCoordinator.liveSaraDraft.value }
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
                    !inspection.isReady && !sttEngine.isRecognitionAvailable() -> {
                        wakeEventBus.updateServiceState(
                            WakeServiceState.MODEL_MISSING,
                            inspection.diagnosticMessage
                        )
                    }
                    wakeEventBus.powerMode.value == SaraPowerMode.ONLINE -> {
                        WakeDetectionService.startOnline(appContext)
                    }
                    else -> {
                        WakeDetectionService.startSleep(appContext)
                    }
                }
            }
        }
    }

    fun onMicButtonPressed(hasMicPermission: Boolean) {
        voiceCoordinator.onMicButtonPressed(hasMicPermission)
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
                voiceCoordinator.showBanner("Microphone permission is required for Wake Up Detection.")
            }
            return
        }

        if (granted) {
            voiceCoordinator.clearStatusBanner()
            voiceCoordinator.startOnlineConversation()
        } else {
            val msg = "Microphone permission required hai."
            voiceCoordinator.showBanner(msg)
            voiceCoordinator.appendErrorMessage(msg)
        }
    }

    fun handleWakePhraseTriggered(phrase: String = "hey sara", hasMicPermission: Boolean = hasMicPermission()) {
        if (!hasMicPermission) {
            voiceCoordinator.showBanner("Grant Microphone permission to listen after wake phrase.")
            return
        }
        voiceCoordinator.wakeUpFromSleep(phrase)
    }

    fun dismissWakePopup() {
        wakeEventBus.dismissWakePopup()
        voiceCoordinator.endConversationSession()
    }

    fun enterSleepMode() {
        voiceCoordinator.enterSleepMode(speakConfirmation = true)
    }

    fun wakeUpFromSleep() {
        voiceCoordinator.wakeUpFromSleep("manual tap")
    }

    fun startVoiceListening() {
        voiceCoordinator.startVoiceListening()
    }

    fun interruptSpeechAndListen() {
        voiceCoordinator.interruptSpeechAndListen()
    }

    fun endConversationSession() {
        voiceCoordinator.endConversationSession()
    }

    fun stopSpeakingAndListening() {
        voiceCoordinator.endConversationSession()
    }

    fun onAppBackgrounded() {
        voiceCoordinator.onAppBackgrounded()
    }

    fun onAppForegrounded() {
        voiceCoordinator.onAppForegrounded()
    }

    fun startNewConversationSession() {
        voiceCoordinator.startNewConversationSession()
    }

    fun sendTextCommand(text: String) {
        voiceCoordinator.sendTextCommand(text)
    }

    fun confirmPendingAction() {
        voiceCoordinator.confirmPendingAction()
    }

    fun cancelPendingAction() {
        voiceCoordinator.cancelPendingAction()
    }

    fun onDynamicPermissionResult(granted: Boolean) {
        voiceCoordinator.onDynamicPermissionResult(granted)
    }

    fun dismissPermissionPrompt() {
        voiceCoordinator.dismissPermissionPrompt()
    }

    fun dismissBanner() {
        voiceCoordinator.dismissBanner()
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
                    voiceCoordinator.appendMessage(
                        ConversationMessage(
                            isUser = false,
                            text = reply,
                            actionTag = ActionCategory.IMAGE_ANALYSIS.name,
                            actionStatus = "Vision analysis complete"
                        )
                    )
                    if (speakResult && uiState.value.behaviorSettings.autoSpeakResponses) {
                        voiceCoordinator.speakAndMaybeResume(reply, resumeListening = false)
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
            voiceCoordinator.clearStatusBanner()
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

    fun setBackgroundAssistantEnabled(enabled: Boolean) {
        viewModelScope.launch {
            credentialStore.updateBackgroundAssistantEnabled(enabled)
            if (!enabled && wakeEventBus.powerMode.value == SaraPowerMode.OFF) {
                WakeDetectionService.stop(appContext)
            }
        }
    }

    fun setWakeUpDetectionEnabled(enabled: Boolean, hasMicPermission: Boolean = hasMicPermission()) {
        if (!enabled) {
            pendingEnableWakeAfterMicGrant = false
            viewModelScope.launch { credentialStore.updateWakeUpDetectionEnabled(false) }
            if (wakeEventBus.powerMode.value == SaraPowerMode.SLEEP ||
                wakeEventBus.powerMode.value == SaraPowerMode.OFF
            ) {
                WakeDetectionService.stop(appContext)
                wakeEventBus.updatePowerMode(SaraPowerMode.OFF)
                wakeEventBus.updateServiceState(WakeServiceState.DISABLED, null)
            }
            return
        }
        if (!hasMicPermission) {
            pendingEnableWakeAfterMicGrant = true
            wakeEventBus.updateServiceState(
                WakeServiceState.PERMISSION_MISSING,
                "Grant Microphone permission to enable Wake Up Detection."
            )
            voiceCoordinator.onMicButtonPressed(false)
            return
        }

        onRefreshVoskInspection()
        val inspection = voskInspectionFlow.value
        viewModelScope.launch { credentialStore.updateWakeUpDetectionEnabled(true) }
        if (!inspection.isReady && !sttEngine.isRecognitionAvailable()) {
            wakeEventBus.updateServiceState(WakeServiceState.MODEL_MISSING, inspection.diagnosticMessage)
            voiceCoordinator.showBanner(inspection.diagnosticMessage)
            return
        }
        if (wakeEventBus.powerMode.value == SaraPowerMode.ONLINE) {
            WakeDetectionService.startOnline(appContext)
        } else {
            WakeDetectionService.startSleep(appContext)
        }
    }

    fun importVoskModelTreeUri(treeUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = VoskModelInspector.importModelFromTreeUri(appContext, treeUri)
            withContext(Dispatchers.Main) {
                onRefreshVoskInspection()
                result.fold(
                    onSuccess = { info ->
                        voiceCoordinator.showBanner(info.diagnosticMessage)
                        if (uiState.value.behaviorSettings.wakeUpDetectionEnabled &&
                            hasMicPermission() &&
                            info.isReady
                        ) {
                            WakeDetectionService.startSleep(appContext)
                        }
                    },
                    onFailure = { err ->
                        voiceCoordinator.showBanner(
                            err.localizedMessage ?: "Failed to import Vosk model folder."
                        )
                    }
                )
            }
        }
    }

    fun clearImportedVoskModel() {
        VoskModelInspector.clearImportedModel(appContext)
        onRefreshVoskInspection()
        if (uiState.value.behaviorSettings.wakeUpDetectionEnabled && hasMicPermission()) {
            WakeDetectionService.startSleep(appContext)
        }
    }

    fun recordNextWakeEnrollmentSample() {
        if (!hasMicPermission()) {
            voiceCoordinator.onMicButtonPressed(false)
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
            voiceCoordinator.onMicButtonPressed(false)
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
        val canDrawOverlays = runCatching { Settings.canDrawOverlays(appContext) }.getOrDefault(false)
        return CommandPermissionStatus(
            recordAudio = granted(Manifest.permission.RECORD_AUDIO),
            readContacts = granted(Manifest.permission.READ_CONTACTS),
            callPhone = granted(Manifest.permission.CALL_PHONE),
            readPhoneState = granted(Manifest.permission.READ_PHONE_STATE),
            postNotifications = postNotif,
            camera = granted(Manifest.permission.CAMERA),
            bluetoothConnect = btConnect,
            canDrawOverlays = canDrawOverlays
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
            if (uiState.value.behaviorSettings.autoSpeakResponses) {
                ttsEngine.speak(prompt, liveCallMode = true)
            }
        }
    }

    fun simulateIncomingCallDecision(affirmative: Boolean) {
        _incomingCallSimResult.value = incomingCallManager.evaluateVoiceAnswerDecision(affirmative)
    }

    override fun onCleared() {
        super.onCleared()
        try {
            microphoneTestEngine.stopTest()
            wakeEnrollmentManager.stopRecording()
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
        val powerMode: SaraPowerMode,
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
                incomingCallManager = container.incomingCallAssistantManager,
                audioFocusManager = container.audioFocusManager,
                microphoneTestEngine = container.microphoneTestEngine,
                wakeEnrollmentManager = container.wakeEnrollmentManager,
                wakeEventBus = container.wakeEventBus,
                voiceCoordinator = container.voiceCoordinator,
                voskInspectionFlow = container.voskInspection,
                onRefreshVoskInspection = { container.refreshVoskInspection() },
                whatsAppAutomationManager = container.whatsAppAutomationManager
            ) as T
        }
    }
}
