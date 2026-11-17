package com.example.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import androidx.core.content.ContextCompat
import com.example.actions.ActionRouter
import com.example.actions.BackgroundActivityStarter
import com.example.ai.gemini.ChatTurn
import com.example.ai.gemini.GeminiClient
import com.example.ai.gemini.GeminiResult
import com.example.ai.gemini.live.GeminiLiveClient
import com.example.ai.gemini.live.GeminiLiveEvent
import com.example.ai.gemini.live.LiveConnectionState
import com.example.commands.ActionCategory
import com.example.commands.ActionExecutionResult
import com.example.commands.AssistantCommand
import com.example.commands.CommandParser
import com.example.commands.PendingConfirmation
import com.example.commands.VoiceControlIntentDetector
import com.example.core.network.NetworkMonitor
import com.example.core.security.CredentialStore
import com.example.memory.ConversationHistoryRepository
import com.example.memory.MemoryCategory
import com.example.memory.MemoryStorageProvider
import com.example.ui.AssistantStatus
import com.example.ui.ConversationMessage
import com.example.ui.PermissionRequestPrompt
import com.example.voice.audio.AudioCaptureCoordinator
import com.example.voice.audio.AudioCaptureOwner
import com.example.voice.audio.VadEvent
import com.example.voice.audio.VoiceActivityDetector
import com.example.voice.stt.SttEngine
import com.example.voice.stt.SttEvent
import com.example.voice.tts.ElevenLabsTtsEngine
import com.example.voice.tts.TtsResult
import com.example.voice.wake.SaraPowerMode
import com.example.voice.wake.VoskModelInspector
import com.example.voice.wake.WakeDetectionService
import com.example.voice.wake.WakeEventBus
import com.example.voice.wake.WakeServiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Application-scoped voice & conversation orchestrator shared by MainActivity/SaraViewModel
 * and WakeDetectionService. Enables seamless foreground & background voice conversations,
 * background app/action execution, and instant voice transitions between ONLINE and SLEEP modes.
 */
class SaraVoiceCoordinator(
    context: Context,
    private val credentialStore: CredentialStore,
    private val networkMonitor: NetworkMonitor,
    private val memoryRepository: MemoryStorageProvider,
    private val historyRepository: ConversationHistoryRepository,
    private val geminiClient: GeminiClient,
    private val geminiLiveClient: GeminiLiveClient,
    private val ttsEngine: ElevenLabsTtsEngine,
    private val sttEngine: SttEngine,
    private val actionRouter: ActionRouter,
    private val audioCaptureCoordinator: AudioCaptureCoordinator,
    private val wakeEventBus: WakeEventBus
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _classicStatus = MutableStateFlow(AssistantStatus.IDLE)
    val classicStatus: StateFlow<AssistantStatus> = _classicStatus.asStateFlow()

    private val _messages = MutableStateFlow(
        listOf(
            ConversationMessage(
                isUser = false,
                text = "Hi, I'm Sara. Tap Start Conversation for real-time voice chat, or say \"Sara sleep\" / \"Hey Sara\" anytime — even in the background."
            )
        )
    )
    val messages: StateFlow<List<ConversationMessage>> = _messages.asStateFlow()

    private val _liveUserDraft = MutableStateFlow("")
    val liveUserDraft: StateFlow<String> = _liveUserDraft.asStateFlow()

    private val _liveSaraDraft = MutableStateFlow("")
    val liveSaraDraft: StateFlow<String> = _liveSaraDraft.asStateFlow()

    private val _pendingConfirmation = MutableStateFlow<PendingConfirmation?>(null)
    val pendingConfirmation: StateFlow<PendingConfirmation?> = _pendingConfirmation.asStateFlow()

    private val _pendingPermissionRequest = MutableStateFlow<PermissionRequestPrompt?>(null)
    val pendingPermissionRequest: StateFlow<PermissionRequestPrompt?> = _pendingPermissionRequest.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private val _navigationEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val navigationEvents: SharedFlow<String> = _navigationEvents.asSharedFlow()

    private val _requestMicPermissionEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val requestMicPermissionEvent: SharedFlow<Unit> = _requestMicPermissionEvent.asSharedFlow()

    private var activeSessionId: String = UUID.randomUUID().toString()
    private var sessionInitializedInDb = false

    private var activeTurnJob: Job? = null
    private var bargeInMonitorJob: Job? = null
    private var backgroundVadResumeJob: Job? = null
    private var autoRestartAllowed = false
    private var consecutiveSilentTimeouts = 0
    private var lastAutoResumeFromVoice = false
    private var lastDispatchedLiveActionText: String = ""
    private var lastDispatchedLiveActionTimeMs: Long = 0L

    init {
        observeSttEvents()
        observeGeminiLiveEvents()
        observeWakeEvents()
    }

    private fun observeWakeEvents() {
        scope.launch {
            wakeEventBus.wakeTriggers.collect { event ->
                wakeUpFromSleep(event.recognizedPhrase)
            }
        }
        scope.launch {
            sttEngine.partialTranscript.collect { partial ->
                if (wakeEventBus.powerMode.value == SaraPowerMode.SLEEP &&
                    partial.isNotBlank() &&
                    VoiceControlIntentDetector.isWakeCommand(partial, allowClippedWakeWord = true)
                ) {
                    wakeUpFromSleep(partial)
                }
            }
        }
    }

    private fun observeGeminiLiveEvents() {
        scope.launch {
            geminiLiveClient.events.collect { event ->
                when (event) {
                    GeminiLiveEvent.Connected -> {
                        _statusBanner.value = null
                    }
                    is GeminiLiveEvent.InputTranscript -> {
                        _liveUserDraft.update { prev -> appendTranscriptFragment(prev, event.text) }
                        val currentDraft = _liveUserDraft.value.trim()
                        if (VoiceControlIntentDetector.isSleepCommand(currentDraft) &&
                            wakeEventBus.powerMode.value == SaraPowerMode.ONLINE
                        ) {
                            _liveUserDraft.value = ""
                            enterSleepMode(speakConfirmation = true)
                        }
                    }
                    is GeminiLiveEvent.OutputTranscript -> {
                        commitLiveUserDraftAndRouteActionIfNeeded()
                        _liveSaraDraft.update { prev -> appendTranscriptFragment(prev, event.text) }
                    }
                    GeminiLiveEvent.Interrupted -> {
                        commitLiveUserDraftAndRouteActionIfNeeded()
                        commitLiveSaraDraftIfPresent(interrupted = true)
                    }
                    GeminiLiveEvent.TurnComplete -> {
                        commitLiveUserDraftAndRouteActionIfNeeded()
                        commitLiveSaraDraftIfPresent(interrupted = false)
                    }
                    is GeminiLiveEvent.Error -> {
                        _statusBanner.value = "${event.userMessage} (${event.diagnostic})"
                        appendErrorMessage(event.userMessage)
                    }
                    is GeminiLiveEvent.Disconnected -> {
                        commitLiveUserDraftAndRouteActionIfNeeded()
                        commitLiveSaraDraftIfPresent(interrupted = false)
                    }
                }
            }
        }
    }

    private suspend fun commitLiveUserDraftAndRouteActionIfNeeded() {
        val text = _liveUserDraft.value.trim()
        if (text.isEmpty()) return
        _liveUserDraft.value = ""
        appendMessage(ConversationMessage(isUser = true, text = text, actionTag = "LIVE"))
        persistTurnToHistory(isUser = true, text = text, mode = "LIVE")

        if (VoiceControlIntentDetector.isSleepCommand(text)) {
            enterSleepMode(speakConfirmation = true)
            return
        }

        val pending = _pendingConfirmation.value
        if (pending != null) {
            if (CommandParser.isAffirmativeConfirmation(text)) {
                _pendingConfirmation.value = null
                executePendingAction(pending, resumeListening = false)
                return
            } else if (CommandParser.isNegativeConfirmation(text)) {
                _pendingConfirmation.value = null
                appendMessage(ConversationMessage(isUser = false, text = "Action cancelled.", actionTag = "CANCELLED"))
                return
            }
        }

        if (VoiceControlIntentDetector.looksLikeDeviceActionCommand(text)) {
            val now = System.currentTimeMillis()
            if (text.equals(lastDispatchedLiveActionText, ignoreCase = true) &&
                now - lastDispatchedLiveActionTimeMs < 3500L
            ) {
                return
            }
            lastDispatchedLiveActionText = text
            lastDispatchedLiveActionTimeMs = now
            routeCommandInLiveSession(text)
        }
    }

    private fun routeCommandInLiveSession(utterance: String) {
        scope.launch {
            if (!networkMonitor.isOnline()) return@launch
            val behavior = credentialStore.behaviorSettings.first()
            val profile = credentialStore.userProfile.first()
            val memories = if (behavior.memoryEnabled) {
                memoryRepository.getContextMemories(limit = 10)
            } else {
                emptyList()
            }
            val result = geminiClient.generateAssistantResponse(
                userMessage = utterance,
                history = emptyList(),
                memories = memories,
                userProfile = profile,
                liveCallMode = true
            )
            if (result is GeminiResult.Success) {
                val cmd = result.command
                if (cmd.category == ActionCategory.SLEEP_MODE) {
                    enterSleepMode(speakConfirmation = true)
                    return@launch
                }
                if (cmd.category == ActionCategory.CHAT) return@launch

                val execution = actionRouter.route(
                    command = cmd,
                    confirmBeforeExternalAction = behavior.confirmBeforeExternalAction,
                    onNavigateToRoute = { route -> _navigationEvents.tryEmit(route) }
                )
                when (execution) {
                    is ActionExecutionResult.Executed -> {
                        appendMessage(
                            ConversationMessage(
                                isUser = false,
                                text = execution.statusMessage,
                                actionTag = cmd.category.name,
                                actionStatus = execution.statusMessage
                            )
                        )
                    }
                    is ActionExecutionResult.NeedsConfirmation -> {
                        _pendingConfirmation.value = execution.pending
                        appendMessage(
                            ConversationMessage(
                                isUser = false,
                                text = execution.pending.summary,
                                actionTag = cmd.category.name,
                                actionStatus = "Say Yes to confirm"
                            )
                        )
                    }
                    is ActionExecutionResult.NeedsPermission -> {
                        _pendingPermissionRequest.value = PermissionRequestPrompt(
                            permission = execution.permission,
                            rationale = execution.rationale,
                            retryCommand = execution.retryCommand
                        )
                    }
                    is ActionExecutionResult.Unavailable -> {
                        appendMessage(
                            ConversationMessage(
                                isUser = false,
                                text = execution.reason,
                                actionTag = cmd.category.name,
                                actionStatus = "Unavailable"
                            )
                        )
                    }
                    ActionExecutionResult.NoOp -> Unit
                }
            }
        }
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
        val behavior = credentialStore.behaviorSettings.first()
        if (!behavior.memoryEnabled) return
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
        scope.launch {
            sttEngine.events.collect { event ->
                audioCaptureCoordinator.release(AudioCaptureOwner.CLASSIC_STT)
                val powerMode = wakeEventBus.powerMode.value

                // When in SLEEP MODE and fallback STT wake check finishes:
                if (powerMode == SaraPowerMode.SLEEP) {
                    if (event is SttEvent.FinalTranscript &&
                        VoiceControlIntentDetector.isWakeCommand(event.text, allowClippedWakeWord = true)
                    ) {
                        wakeUpFromSleep(event.text)
                    }
                    return@collect
                }

                when (event) {
                    is SttEvent.FinalTranscript -> {
                        consecutiveSilentTimeouts = 0
                        lastAutoResumeFromVoice = true
                        handleUserUtterance(event.text, fromVoice = true)
                    }
                    is SttEvent.TransientTimeout -> {
                        val behavior = credentialStore.behaviorSettings.first()
                        val inBackground = !BackgroundActivityStarter.isAppInForeground()
                        val shouldKeepOnline = powerMode == SaraPowerMode.ONLINE &&
                            (behavior.continuousConversationEnabled ||
                                behavior.liveCallModeEnabled ||
                                (inBackground && behavior.backgroundAssistantEnabled))

                        if (autoRestartAllowed && shouldKeepOnline) {
                            consecutiveSilentTimeouts++
                            if (behavior.liveCallModeEnabled || consecutiveSilentTimeouts <= 2) {
                                delay(350L)
                                resumeClassicSttNow()
                            } else if (behavior.backgroundAssistantEnabled) {
                                // Switch to low-power VAD monitor so background mode stays silent until user speaks again
                                waitForSpeechAndResumeClassicStt()
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
                        if (wakeEventBus.powerMode.value == SaraPowerMode.ONLINE &&
                            !event.isFatal &&
                            autoRestartAllowed
                        ) {
                            delay(700L)
                            resumeClassicSttNow()
                        } else {
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
    }

    private suspend fun resumeClassicSttNow() {
        if (wakeEventBus.powerMode.value != SaraPowerMode.ONLINE) return
        if (!autoRestartAllowed ||
            _classicStatus.value == AssistantStatus.SPEAKING ||
            _classicStatus.value == AssistantStatus.THINKING
        ) {
            return
        }
        val profile = credentialStore.userProfile.first()
        if (audioCaptureCoordinator.acquire(AudioCaptureOwner.CLASSIC_STT)) {
            _classicStatus.value = AssistantStatus.LISTENING
            sttEngine.startListening(profile.sttLocaleTag())
        }
    }

    @SuppressLint("MissingPermission")
    private fun waitForSpeechAndResumeClassicStt() {
        backgroundVadResumeJob?.cancel()
        if (!hasMicPermission()) return
        if (!audioCaptureCoordinator.acquire(AudioCaptureOwner.WAKE_LISTENING) {
                backgroundVadResumeJob?.cancel()
            }
        ) {
            return
        }

        _classicStatus.value = AssistantStatus.CONNECTED
        backgroundVadResumeJob = scope.launch(Dispatchers.IO) {
            val minBuf = AudioRecord.getMinBufferSize(
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                audioCaptureCoordinator.release(AudioCaptureOwner.WAKE_LISTENING)
                return@launch
            }
            val recorder = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf * 2, 4096)
                )
            }.getOrNull()
            if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { recorder?.release() }
                audioCaptureCoordinator.release(AudioCaptureOwner.WAKE_LISTENING)
                return@launch
            }

            runCatching { recorder.startRecording() }.onFailure {
                runCatching { recorder.release() }
                audioCaptureCoordinator.release(AudioCaptureOwner.WAKE_LISTENING)
                return@launch
            }

            val vad = VoiceActivityDetector(
                speechStartRatio = 2.3,
                minSpeechRms = 320.0,
                startConfirmFrames = 2,
                endSilenceHangoverFrames = 14
            )
            val buf = ByteArray(1280)
            var heardSpeech = false
            try {
                while (isActive && wakeEventBus.powerMode.value == SaraPowerMode.ONLINE && autoRestartAllowed) {
                    val read = recorder.read(buf, 0, buf.size)
                    if (read > 0) {
                        val (ev, _) = vad.processFrame(buf, read)
                        if (ev == VadEvent.SPEECH_STARTED || ev == VadEvent.SPEECH_ACTIVE) {
                            heardSpeech = true
                            break
                        }
                    } else {
                        delay(25L)
                    }
                }
            } finally {
                runCatching {
                    if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
                    recorder.release()
                }
                audioCaptureCoordinator.release(AudioCaptureOwner.WAKE_LISTENING)
            }

            if (heardSpeech && wakeEventBus.powerMode.value == SaraPowerMode.ONLINE && autoRestartAllowed) {
                delay(140L)
                withContext(Dispatchers.Main) {
                    consecutiveSilentTimeouts = 0
                    resumeClassicSttNow()
                }
            }
        }
    }

    fun onMicButtonPressed(hasMicPermission: Boolean) {
        if (!hasMicPermission) {
            _requestMicPermissionEvent.tryEmit(Unit)
            return
        }

        if (wakeEventBus.powerMode.value == SaraPowerMode.SLEEP) {
            wakeUpFromSleep("manual")
            return
        }

        scope.launch {
            val behavior = credentialStore.behaviorSettings.first()
            if (behavior.geminiLiveEnabled) {
                if (geminiLiveClient.connectionState.value == LiveConnectionState.DISCONNECTED ||
                    geminiLiveClient.connectionState.value == LiveConnectionState.ERROR
                ) {
                    startOnlineConversation()
                } else {
                    endConversationSession()
                }
                return@launch
            }

            when (_classicStatus.value) {
                AssistantStatus.LISTENING -> {
                    autoRestartAllowed = false
                    backgroundVadResumeJob?.cancel()
                    sttEngine.stopListening()
                    _classicStatus.value = AssistantStatus.IDLE
                    wakeEventBus.updatePowerMode(SaraPowerMode.OFF)
                }
                AssistantStatus.SPEAKING,
                AssistantStatus.THINKING,
                AssistantStatus.CONNECTING,
                AssistantStatus.RECONNECTING,
                AssistantStatus.CONNECTED -> {
                    endConversationSession()
                }
                AssistantStatus.SLEEPING -> {
                    wakeUpFromSleep("manual")
                }
                AssistantStatus.ERROR,
                AssistantStatus.IDLE -> {
                    startOnlineConversation()
                }
            }
        }
    }

    fun startOnlineConversation() {
        if (!hasMicPermission()) {
            _requestMicPermissionEvent.tryEmit(Unit)
            return
        }

        backgroundVadResumeJob?.cancel()
        WakeDetectionService.stopSleepCaptureHint()
        wakeEventBus.updatePowerMode(SaraPowerMode.ONLINE)

        scope.launch {
            val behavior = credentialStore.behaviorSettings.first()
            if (behavior.backgroundAssistantEnabled || behavior.wakeUpDetectionEnabled) {
                ensureForegroundServiceForMode(online = true)
            }

            if (behavior.geminiLiveEnabled) {
                startGeminiLiveSession()
            } else {
                startVoiceListening()
            }
        }
    }

    fun enterSleepMode(speakConfirmation: Boolean = true) {
        if (wakeEventBus.powerMode.value == SaraPowerMode.SLEEP) return

        autoRestartAllowed = false
        lastAutoResumeFromVoice = false
        activeTurnJob?.cancel()
        activeTurnJob = null
        bargeInMonitorJob?.cancel()
        bargeInMonitorJob = null
        backgroundVadResumeJob?.cancel()
        backgroundVadResumeJob = null

        audioCaptureCoordinator.release(AudioCaptureOwner.BARGE_IN_MONITOR)
        audioCaptureCoordinator.release(AudioCaptureOwner.CLASSIC_STT)
        geminiLiveClient.stopSession("Entered Sleep Mode")
        sttEngine.cancel()
        wakeEventBus.dismissWakePopup()

        wakeEventBus.updatePowerMode(SaraPowerMode.SLEEP)
        _classicStatus.value = AssistantStatus.SLEEPING
        _liveUserDraft.value = ""
        _liveSaraDraft.value = ""
        _statusBanner.value = "Sleep Mode active — say \"Hey Sara\" to come back online."

        val sleepNotice = "Theek hai Sir, main sleep mode mein ja rahi hoon. Bulane ke liye Hey Sara boliye."
        appendMessage(
            ConversationMessage(
                isUser = false,
                text = sleepNotice,
                actionTag = "SLEEP MODE",
                actionStatus = "Say \"Hey Sara\" to wake"
            )
        )

        scope.launch {
            if (hasMicPermission() &&
                (BackgroundActivityStarter.isAppInForeground() ||
                    wakeEventBus.serviceState.value != WakeServiceState.DISABLED)
            ) {
                ensureForegroundServiceForMode(online = false)
            }

            val behavior = credentialStore.behaviorSettings.first()
            if (speakConfirmation && behavior.autoSpeakResponses) {
                ttsEngine.speak(sleepNotice, liveCallMode = true)
            }
            if (wakeEventBus.powerMode.value == SaraPowerMode.SLEEP && hasMicPermission()) {
                ensureForegroundServiceForMode(online = false)
            }
        }
    }

    fun wakeUpFromSleep(phrase: String = "hey sara") {
        if (!hasMicPermission()) {
            _statusBanner.value = "Grant Microphone permission to wake Sara."
            _requestMicPermissionEvent.tryEmit(Unit)
            return
        }

        backgroundVadResumeJob?.cancel()
        wakeEventBus.updatePowerMode(SaraPowerMode.ONLINE)
        _statusBanner.value = null

        if (BackgroundActivityStarter.isAppInForeground()) {
            wakeEventBus.showWakePopup("Hey Sara detected — online!")
        }

        val wakeAck = "Ji Sir, main online hoon."
        appendMessage(
            ConversationMessage(
                isUser = false,
                text = wakeAck,
                actionTag = "ONLINE",
                actionStatus = "Triggered by \"$phrase\""
            )
        )

        scope.launch {
            ensureForegroundServiceForMode(online = true)
            val behavior = credentialStore.behaviorSettings.first()
            if (behavior.autoSpeakResponses && !BackgroundActivityStarter.isAppInForeground()) {
                // Speak crisp audio acknowledgment when user wakes Sara while on another screen
                ttsEngine.speak(wakeAck, liveCallMode = true)
                delay(150L)
            }
            if (wakeEventBus.powerMode.value != SaraPowerMode.ONLINE) return@launch

            if (behavior.geminiLiveEnabled) {
                startGeminiLiveSession()
            } else {
                startVoiceListening()
            }
        }
    }

    private fun ensureForegroundServiceForMode(online: Boolean) {
        if (!hasMicPermission()) return
        val canStartFgs = BackgroundActivityStarter.isAppInForeground() ||
            wakeEventBus.serviceState.value != WakeServiceState.DISABLED
        if (!canStartFgs) return

        runCatching {
            if (online) {
                WakeDetectionService.startOnline(appContext)
            } else {
                WakeDetectionService.startSleep(appContext)
            }
        }
    }

    private fun startGeminiLiveSession() {
        _statusBanner.value = null
        _liveUserDraft.value = ""
        _liveSaraDraft.value = ""
        bargeInMonitorJob?.cancel()
        ttsEngine.stop()
        sttEngine.cancel()
        audioCaptureCoordinator.release(AudioCaptureOwner.CLASSIC_STT)
        wakeEventBus.updatePowerMode(SaraPowerMode.ONLINE)

        scope.launch {
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
        wakeEventBus.updatePowerMode(SaraPowerMode.ONLINE)
        _statusBanner.value = null
        consecutiveSilentTimeouts = 0
        autoRestartAllowed = true
        lastAutoResumeFromVoice = true
        _classicStatus.value = AssistantStatus.LISTENING
        scope.launch {
            val profile = credentialStore.userProfile.first()
            sttEngine.startListening(profile.sttLocaleTag())
        }
    }

    fun interruptSpeechAndListen() {
        bargeInMonitorJob?.cancel()
        bargeInMonitorJob = null
        audioCaptureCoordinator.release(AudioCaptureOwner.BARGE_IN_MONITOR)

        scope.launch {
            val behavior = credentialStore.behaviorSettings.first()
            if (behavior.geminiLiveEnabled &&
                geminiLiveClient.connectionState.value != LiveConnectionState.DISCONNECTED
            ) {
                geminiLiveClient.interruptCurrentPlayback()
                return@launch
            }

            ttsEngine.stop()
            if (hasMicPermission()) {
                startVoiceListening()
            } else {
                _classicStatus.value = AssistantStatus.IDLE
            }
        }
    }

    fun endConversationSession() {
        autoRestartAllowed = false
        lastAutoResumeFromVoice = false
        activeTurnJob?.cancel()
        activeTurnJob = null
        bargeInMonitorJob?.cancel()
        bargeInMonitorJob = null
        backgroundVadResumeJob?.cancel()
        backgroundVadResumeJob = null
        audioCaptureCoordinator.release(AudioCaptureOwner.BARGE_IN_MONITOR)
        audioCaptureCoordinator.release(AudioCaptureOwner.CLASSIC_STT)
        geminiLiveClient.stopSession()
        ttsEngine.stop()
        sttEngine.cancel()
        wakeEventBus.dismissWakePopup()
        _classicStatus.value = AssistantStatus.IDLE

        scope.launch {
            val behavior = credentialStore.behaviorSettings.first()
            if (behavior.wakeUpDetectionEnabled && hasMicPermission()) {
                wakeEventBus.updatePowerMode(SaraPowerMode.SLEEP)
                _classicStatus.value = AssistantStatus.SLEEPING
                WakeDetectionService.startSleep(appContext)
            } else {
                wakeEventBus.updatePowerMode(SaraPowerMode.OFF)
                WakeDetectionService.stop(appContext)
                wakeEventBus.updateServiceState(WakeServiceState.DISABLED, null)
            }
        }
    }

    fun onAppBackgrounded() {
        BackgroundActivityStarter.setAppForeground(false)
        scope.launch {
            val behavior = credentialStore.behaviorSettings.first()
            val currentMode = wakeEventBus.powerMode.value
            val hasActiveVoice = currentMode == SaraPowerMode.ONLINE ||
                currentMode == SaraPowerMode.SLEEP ||
                geminiLiveClient.connectionState.value != LiveConnectionState.DISCONNECTED

            if (behavior.backgroundAssistantEnabled && hasActiveVoice && hasMicPermission()) {
                if (currentMode == SaraPowerMode.SLEEP) {
                    WakeDetectionService.startSleep(appContext)
                } else {
                    wakeEventBus.updatePowerMode(SaraPowerMode.ONLINE)
                    WakeDetectionService.startOnline(appContext)
                }
                return@launch
            }

            if (behavior.wakeUpDetectionEnabled && hasMicPermission()) {
                wakeEventBus.updatePowerMode(SaraPowerMode.SLEEP)
                WakeDetectionService.startSleep(appContext)
                return@launch
            }

            endConversationSession()
        }
    }

    fun onAppForegrounded() {
        BackgroundActivityStarter.setAppForeground(true)
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

        if (VoiceControlIntentDetector.isSleepCommand(clean)) {
            appendMessage(ConversationMessage(isUser = true, text = clean))
            enterSleepMode(speakConfirmation = true)
            return
        }
        if (wakeEventBus.powerMode.value == SaraPowerMode.SLEEP &&
            VoiceControlIntentDetector.isWakeCommand(clean)
        ) {
            appendMessage(ConversationMessage(isUser = true, text = clean))
            wakeUpFromSleep(clean)
            return
        }

        scope.launch {
            val behavior = credentialStore.behaviorSettings.first()
            val liveState = geminiLiveClient.connectionState.value
            if (behavior.geminiLiveEnabled &&
                (liveState == LiveConnectionState.CONNECTED ||
                    liveState == LiveConnectionState.LISTENING ||
                    liveState == LiveConnectionState.SPEAKING)
            ) {
                appendMessage(ConversationMessage(isUser = true, text = clean, actionTag = "LIVE TEXT"))
                persistTurnToHistory(isUser = true, text = clean, mode = "LIVE")
                if (VoiceControlIntentDetector.looksLikeDeviceActionCommand(clean)) {
                    routeCommandInLiveSession(clean)
                } else {
                    geminiLiveClient.sendTextTurn(clean)
                }
                return@launch
            }

            lastAutoResumeFromVoice = false
            autoRestartAllowed = false
            handleUserUtterance(clean, fromVoice = false)
        }
    }

    private fun handleUserUtterance(utterance: String, fromVoice: Boolean) {
        activeTurnJob?.cancel()
        activeTurnJob = scope.launch {
            _statusBanner.value = null
            appendMessage(ConversationMessage(isUser = true, text = utterance))
            persistTurnToHistory(isUser = true, text = utterance, mode = "CLASSIC")

            if (VoiceControlIntentDetector.isSleepCommand(utterance)) {
                enterSleepMode(speakConfirmation = true)
                return@launch
            }

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

            val behavior = credentialStore.behaviorSettings.first()
            val profile = credentialStore.userProfile.first()
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
                    if (behavior.autoSpeakResponses) {
                        speakAndMaybeResume(result.userFacingMessage, resumeListening = false)
                    }
                }
                is GeminiResult.Success -> {
                    val cmd = result.command
                    if (cmd.category == ActionCategory.SLEEP_MODE) {
                        enterSleepMode(speakConfirmation = true)
                        return@launch
                    }
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

                    val stayOnlineAfterAppLaunch = launchedExternalActivity &&
                        behavior.backgroundAssistantEnabled &&
                        wakeEventBus.powerMode.value == SaraPowerMode.ONLINE

                    val shouldResume = (fromVoice || lastAutoResumeFromVoice) &&
                        (!launchedExternalActivity || stayOnlineAfterAppLaunch) &&
                        (behavior.continuousConversationEnabled ||
                            behavior.liveCallModeEnabled ||
                            behavior.backgroundAssistantEnabled ||
                            execution is ActionExecutionResult.NeedsConfirmation)

                    if (launchedExternalActivity && stayOnlineAfterAppLaunch) {
                        delay(850L)
                    }

                    speakAndMaybeResume(replyToSpeak, resumeListening = shouldResume)
                }
            }
        }
    }

    fun confirmPendingAction() {
        val pending = _pendingConfirmation.value ?: return
        _pendingConfirmation.value = null
        scope.launch {
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
                speakAndMaybeResume(msg, resumeListening = resumeListening)
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
            scope.launch {
                val behavior = credentialStore.behaviorSettings.first()
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

    fun dismissBanner() {
        _statusBanner.value = null
    }

    fun clearStatusBanner() {
        _statusBanner.value = null
    }

    fun showBanner(message: String) {
        _statusBanner.value = message
    }

    fun appendMessage(msg: ConversationMessage) {
        _messages.update { it + msg }
    }

    fun appendErrorMessage(message: String) {
        _messages.update {
            it + ConversationMessage(isUser = false, text = message, isError = true)
        }
    }

    suspend fun speakAndMaybeResume(text: String, resumeListening: Boolean) {
        val behavior = credentialStore.behaviorSettings.first()
        val profile = credentialStore.userProfile.first()
        if (!behavior.autoSpeakResponses) {
            _classicStatus.value = if (wakeEventBus.powerMode.value == SaraPowerMode.SLEEP) {
                AssistantStatus.SLEEPING
            } else {
                AssistantStatus.IDLE
            }
            if (resumeListening && autoRestartAllowed &&
                wakeEventBus.powerMode.value == SaraPowerMode.ONLINE &&
                audioCaptureCoordinator.acquire(AudioCaptureOwner.CLASSIC_STT)
            ) {
                delay(300L)
                _classicStatus.value = AssistantStatus.LISTENING
                sttEngine.startListening(profile.sttLocaleTag())
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

        if (wakeEventBus.powerMode.value == SaraPowerMode.SLEEP) {
            _classicStatus.value = AssistantStatus.SLEEPING
            return
        }

        when (ttsResult) {
            is TtsResult.Completed, is TtsResult.Error -> {
                if (ttsResult is TtsResult.Error) {
                    _statusBanner.value = "${ttsResult.userMessage} (${ttsResult.diagnostic})"
                }
                if (resumeListening && autoRestartAllowed &&
                    wakeEventBus.powerMode.value == SaraPowerMode.ONLINE &&
                    (behavior.continuousConversationEnabled ||
                        behavior.liveCallModeEnabled ||
                        behavior.backgroundAssistantEnabled ||
                        _pendingConfirmation.value != null)
                ) {
                    delay(350L)
                    if (autoRestartAllowed &&
                        wakeEventBus.powerMode.value == SaraPowerMode.ONLINE &&
                        audioCaptureCoordinator.acquire(AudioCaptureOwner.CLASSIC_STT)
                    ) {
                        _classicStatus.value = AssistantStatus.LISTENING
                        sttEngine.startListening(profile.sttLocaleTag())
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
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAecBargeInMonitor() {
        bargeInMonitorJob?.cancel()
        if (!audioCaptureCoordinator.acquire(AudioCaptureOwner.BARGE_IN_MONITOR)) return
        bargeInMonitorJob = scope.launch(Dispatchers.IO) {
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
                delay(350L)
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

    private fun appendTranscriptFragment(existing: String, fragment: String): String {
        if (fragment.isBlank()) return existing
        if (existing.isEmpty()) return fragment.trimStart()
        return if (existing.endsWith(" ") || fragment.startsWith(" ")) {
            existing + fragment
        } else {
            "$existing$fragment"
        }
    }

    fun shutdown() {
        autoRestartAllowed = false
        activeTurnJob?.cancel()
        activeTurnJob = null
        bargeInMonitorJob?.cancel()
        bargeInMonitorJob = null
        backgroundVadResumeJob?.cancel()
        backgroundVadResumeJob = null
        audioCaptureCoordinator.release(AudioCaptureOwner.BARGE_IN_MONITOR)
        audioCaptureCoordinator.release(AudioCaptureOwner.CLASSIC_STT)
        geminiLiveClient.stopSession()
        ttsEngine.stop()
        sttEngine.cancel()
        scope.coroutineContext[Job]?.cancel()
    }

    private fun hasMicPermission(): Boolean =
        runCatching {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
}
