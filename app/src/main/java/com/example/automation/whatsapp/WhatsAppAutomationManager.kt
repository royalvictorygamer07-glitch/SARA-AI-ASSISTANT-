package com.example.automation.whatsapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.speech.tts.TextToSpeech
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.SaraApplication
import com.example.core.security.CredentialStore
import com.example.voice.audio.AudioCaptureCoordinator
import com.example.voice.audio.AudioCaptureOwner
import com.example.voice.audio.SaraAudioFocusManager
import com.example.voice.tts.ElevenLabsTtsEngine
import com.example.voice.tts.TtsResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Central orchestrator for Sara's WhatsApp Automation Brain:
 * - Thread-safe per-conversation message batching & serialization
 * - Personalized Rule Engine -> Gemini AI Brain fallback
 * - Conversation Memory & deduplication
 * - RemoteInput reply dispatching via WhatsAppReplySender
 * - Call-safe voice announcements
 * - WorkManager reliability watchdog scheduling
 */
class WhatsAppAutomationManager(
    private val context: Context,
    val settingsStore: AutomationSettingsStore,
    val conversationMemory: ConversationMemory,
    val ruleEngine: ContactRuleEngine,
    val geminiReplyEngine: GeminiReplyEngine,
    val replySender: WhatsAppReplySender,
    private val credentialStore: CredentialStore,
    private val ttsEngine: ElevenLabsTtsEngine,
    private val audioFocusManager: SaraAudioFocusManager,
    private val audioCaptureCoordinator: AudioCaptureCoordinator
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _serviceStatus = MutableStateFlow(AutomationServiceStatus.STOPPED)
    val serviceStatus: StateFlow<AutomationServiceStatus> = _serviceStatus.asStateFlow()

    private val _statusDetail = MutableStateFlow("WhatsApp Automation is OFF.")
    val statusDetail: StateFlow<String> = _statusDetail.asStateFlow()

    private val _listenerConnected = MutableStateFlow(false)
    val listenerConnected: StateFlow<Boolean> = _listenerConnected.asStateFlow()

    private val _notificationAccessGranted = MutableStateFlow(
        AutomationSettingsStore.isNotificationAccessGranted(appContext)
    )
    val notificationAccessGranted: StateFlow<Boolean> = _notificationAccessGranted.asStateFlow()

    val settingsFlow: Flow<AutomationSettings> = settingsStore.settings
    val contactProfilesFlow: Flow<List<WhatsAppContactProfileEntity>> = conversationMemory.observeContactProfiles()
    val contactRulesFlow: Flow<List<WhatsAppContactRuleEntity>> = ruleEngine.observeRules()
    val activityLogsFlow: Flow<List<WhatsAppActivityLogEntity>> = conversationMemory.observeActivityLogs()
    val recentMessagesFlow: Flow<List<WhatsAppMessageEntity>> = conversationMemory.observeRecentMessages()

    private data class PendingBatch(
        val conversationKey: String,
        val conversationTitle: String,
        val isGroupConversation: Boolean,
        val messages: MutableList<WhatsAppIncomingMessage> = mutableListOf(),
        var job: Job? = null
    )

    private val batchMutex = Mutex()
    private val pendingBatches = ConcurrentHashMap<String, PendingBatch>()
    private val contactLocks = ConcurrentHashMap<String, Mutex>()
    private val geminiConcurrencyLimiter = Semaphore(2)

    // Track recently sent replies so WhatsApp self-echo notifications are ignored cleanly
    private val recentSelfSentEchoes = ConcurrentHashMap<String, Long>()
    private val recentAnnouncedFingerprints = ConcurrentHashMap<String, Long>()

    private var androidTts: TextToSpeech? = null
    @Volatile
    private var androidTtsReady: Boolean = false

    init {
        scope.launch {
            settingsStore.settings.collect { settings ->
                refreshNotificationAccessState()
                updateBaselineStatus(settings)
                if (settings.automationEnabled) {
                    scheduleWatchdogWorker()
                }
            }
        }
    }

    fun refreshNotificationAccessState(): Boolean {
        val granted = AutomationSettingsStore.isNotificationAccessGranted(appContext)
        _notificationAccessGranted.value = granted
        return granted
    }

    fun onListenerConnected() {
        _listenerConnected.value = true
        _notificationAccessGranted.value = true
        scope.launch {
            val settings = settingsStore.settings.first()
            updateBaselineStatus(settings)
            conversationMemory.logActivity(
                conversationKey = "system",
                senderName = "System",
                eventType = "SERVICE",
                status = if (settings.automationEnabled) "Waiting for notification" else "Connected (Paused)",
                detail = "SaraNotificationService connected to Android NotificationManager."
            )
        }
    }

    fun onListenerDisconnected() {
        _listenerConnected.value = false
        scope.launch {
            val settings = settingsStore.settings.first()
            updateBaselineStatus(settings)
            conversationMemory.logActivity(
                conversationKey = "system",
                senderName = "System",
                eventType = "SERVICE",
                status = "Disconnected",
                detail = "NotificationListenerService disconnected; rebind requested if enabled."
            )
        }
    }

    fun onIncomingParsedNotification(parsed: ParsedWhatsAppNotification) {
        parsed.replyTarget?.let { replySender.registerTarget(it) }
            ?: parsed.messages.lastOrNull()?.let { firstMsg ->
                replySender.markNoReplyActionAvailable(firstMsg.notificationKey, parsed.conversationKey)
            }

        scope.launch {
            val settings = settingsStore.settings.first()
            if (!settings.automationEnabled) return@launch

            val unseenMessages = mutableListOf<WhatsAppIncomingMessage>()
            val existingContext = if (parsed.messages.isNotEmpty()) {
                runCatching {
                    conversationMemory.loadConversationContext(
                        conversationKey = parsed.conversationKey,
                        displayName = parsed.conversationTitle,
                        isGroup = parsed.isGroupConversation,
                        historyLimit = 6
                    )
                }.getOrNull()
            } else {
                null
            }
            val recentPersistedOutgoing = existingContext?.recentOutgoingReplies.orEmpty()
                .map { it.trim().lowercase() }
                .toSet()

            for (msg in parsed.messages) {
                if (isSelfSentEcho(msg.conversationKey, msg.messageText)) {
                    continue
                }
                if (msg.messageText.trim().lowercase() in recentPersistedOutgoing) {
                    continue
                }
                if (conversationMemory.isAlreadyProcessed(msg.fingerprint)) {
                    continue
                }
                val inserted = conversationMemory.recordIncomingMessage(
                    conversationKey = msg.conversationKey,
                    senderName = msg.senderName,
                    displayName = msg.conversationTitle,
                    isGroup = msg.isGroupConversation,
                    messageText = msg.messageText,
                    fingerprint = msg.fingerprint,
                    timestamp = msg.timestamp
                )
                if (inserted) {
                    unseenMessages.add(msg)
                }
            }

            if (unseenMessages.isEmpty()) return@launch

            unseenMessages.forEach { msg ->
                conversationMemory.logActivity(
                    conversationKey = msg.conversationKey,
                    senderName = msg.senderName,
                    eventType = "RECEIVED",
                    status = "Processing message",
                    detail = msg.messageText.take(120)
                )
            }

            // Optional Voice Announcement for incoming message(s)
            if (settings.voiceAnnouncementEnabled) {
                val latest = unseenMessages.last()
                val text = if (settings.readIncomingMessagesAloud) {
                    "${latest.senderName} ka message aaya hai. ${latest.messageText}"
                } else {
                    "${latest.senderName} ka naya message aaya hai."
                }
                announceSafely(fingerprint = "in_${latest.fingerprint}", spokenText = text)
            }

            if (!settings.autoReplyEnabled) {
                _serviceStatus.value = AutomationServiceStatus.WAITING_FOR_NOTIFICATION
                _statusDetail.value = "Auto-reply OFF: saved ${unseenMessages.last().senderName}'s message to memory."
                return@launch
            }

            if (parsed.isGroupConversation && !settings.replyToGroupChats) {
                _serviceStatus.value = AutomationServiceStatus.WAITING_FOR_NOTIFICATION
                _statusDetail.value = "Group chat reply disabled (${parsed.conversationTitle})."
                conversationMemory.logActivity(
                    conversationKey = parsed.conversationKey,
                    senderName = parsed.conversationTitle,
                    eventType = "SKIPPED",
                    status = "Group chat disabled",
                    detail = "Group auto-reply is turned OFF in settings."
                )
                return@launch
            }

            enqueueBatchForContact(parsed.conversationKey, parsed.conversationTitle, parsed.isGroupConversation, unseenMessages, settings.aiReplyDelaySeconds)
        }
    }

    fun onNotificationRemoved(notificationKey: String) {
        replySender.removeByNotificationKey(notificationKey)
    }

    /**
     * Thread-safe debounce batching so rapid consecutive messages from the same contact
     * are processed together cleanly, while two different contacts run independently.
     */
    private suspend fun enqueueBatchForContact(
        conversationKey: String,
        conversationTitle: String,
        isGroup: Boolean,
        newMessages: List<WhatsAppIncomingMessage>,
        delaySeconds: Int
    ) {
        batchMutex.withLock {
            _serviceStatus.value = AutomationServiceStatus.PROCESSING_MESSAGE
            _statusDetail.value = "Processing message from $conversationTitle..."

            val batch = pendingBatches.getOrPut(conversationKey) {
                PendingBatch(
                    conversationKey = conversationKey,
                    conversationTitle = conversationTitle,
                    isGroupConversation = isGroup
                )
            }
            batch.messages.addAll(newMessages)
            batch.job?.cancel()
            batch.job = scope.launch {
                if (delaySeconds > 0) {
                    delay(delaySeconds * 1000L)
                }
                val drained: List<WhatsAppIncomingMessage>
                batchMutex.withLock {
                    drained = batch.messages.toList()
                    batch.messages.clear()
                    pendingBatches.remove(conversationKey)
                }
                if (drained.isNotEmpty()) {
                    processContactBatch(conversationKey, conversationTitle, isGroup, drained)
                }
            }
        }
    }

    /**
     * Publicly callable from tests or direct pipeline invocation without waiting on NotificationManager.
     */
    suspend fun processContactBatch(
        conversationKey: String,
        conversationTitle: String,
        isGroup: Boolean,
        messages: List<WhatsAppIncomingMessage>
    ) {
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = runCatching {
            powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SARA:WhatsAppAutoReply")?.apply {
                setReferenceCounted(false)
                acquire(25_000L)
            }
        }.getOrNull()
        try {
            processContactBatchInternal(conversationKey, conversationTitle, isGroup, messages)
        } finally {
            runCatching {
                if (wakeLock?.isHeld == true) wakeLock.release()
            }
        }
    }

    private suspend fun processContactBatchInternal(
        conversationKey: String,
        conversationTitle: String,
        isGroup: Boolean,
        messages: List<WhatsAppIncomingMessage>
    ) {
        val contactMutex = contactLocks.getOrPut(conversationKey) { Mutex() }
        contactMutex.withLock {
            val settings = settingsStore.settings.first()
            if (!settings.automationEnabled || !settings.autoReplyEnabled) {
                updateBaselineStatus(settings)
                return@withLock
            }

            val latestMsg = messages.last()
            val combinedIncomingText = if (messages.size == 1) {
                latestMsg.messageText
            } else {
                messages.joinToString("\n") { it.messageText }
            }

            // 1. Pre-AI Safety & Spam screening
            when (val safetyCheck = WhatsAppAutoReply.checkIncomingSafety(combinedIncomingText)) {
                is SafetyValidationResult.Blocked -> {
                    conversationMemory.logActivity(
                        conversationKey = conversationKey,
                        senderName = latestMsg.senderName,
                        eventType = "SKIPPED",
                        status = "Safety Filter",
                        detail = safetyCheck.reason
                    )
                    _serviceStatus.value = AutomationServiceStatus.WAITING_FOR_NOTIFICATION
                    _statusDetail.value = safetyCheck.reason
                    return@withLock
                }
                is SafetyValidationResult.Safe -> Unit
            }

            // 2. Load strictly contact-isolated conversation context
            val contextBundle = conversationMemory.loadConversationContext(
                conversationKey = conversationKey,
                displayName = conversationTitle,
                isGroup = isGroup,
                historyLimit = 12
            )

            if (contextBundle.profile.isRestricted) {
                val msg = "Replies paused for restricted contact ${contextBundle.profile.displayName}."
                conversationMemory.logActivity(conversationKey, latestMsg.senderName, "SKIPPED", "Restricted", msg)
                _serviceStatus.value = AutomationServiceStatus.WAITING_FOR_NOTIFICATION
                _statusDetail.value = msg
                return@withLock
            }

            if (contextBundle.profile.autoReplyCount >= settings.maxMessagesPerConversation) {
                val msg = "Max replies (${settings.maxMessagesPerConversation}) reached for ${contextBundle.profile.displayName}."
                conversationMemory.logActivity(conversationKey, latestMsg.senderName, "SKIPPED", "Cap Reached", msg)
                _serviceStatus.value = AutomationServiceStatus.WAITING_FOR_NOTIFICATION
                _statusDetail.value = msg
                return@withLock
            }

            _serviceStatus.value = AutomationServiceStatus.GENERATING_REPLY
            _statusDetail.value = "Generating reply for ${latestMsg.senderName}..."

            // 3. Personalized Contact Rule Engine ALWAYS has priority over generic AI
            val ruleResult = ruleEngine.evaluate(
                senderName = latestMsg.senderName,
                conversationTitle = conversationTitle,
                incomingText = combinedIncomingText
            )

            var replySource = "GEMINI"
            var detectedLanguage = settings.replyLanguageMode
            var generatedReply: String? = null
            var ruleInstruction: String? = null

            when (ruleResult) {
                is RuleMatchResult.BlockAutoReply -> {
                    conversationMemory.logActivity(
                        conversationKey,
                        latestMsg.senderName,
                        "SKIPPED",
                        "Rule Blocked",
                        ruleResult.reason
                    )
                    _serviceStatus.value = AutomationServiceStatus.WAITING_FOR_NOTIFICATION
                    _statusDetail.value = ruleResult.reason
                    return@withLock
                }
                is RuleMatchResult.ExactReply -> {
                    replySource = "RULE"
                    generatedReply = ruleResult.replyText
                    detectedLanguage = "Rule"
                    conversationMemory.logActivity(
                        conversationKey,
                        latestMsg.senderName,
                        "RULE_MATCHED",
                        "Priority Rule",
                        "Matched rule for ${ruleResult.rule.contactNamePattern}"
                    )
                }
                is RuleMatchResult.AiInstruction -> {
                    ruleInstruction = ruleResult.instruction
                    conversationMemory.logActivity(
                        conversationKey,
                        latestMsg.senderName,
                        "RULE_MATCHED",
                        "Rule + AI",
                        "Applying custom rule instruction for ${ruleResult.rule.contactNamePattern}"
                    )
                }
                RuleMatchResult.NoMatch -> Unit
            }

            // 4. Gemini AI Brain fallback (or rule-guided AI generation)
            if (generatedReply == null) {
                if (!settings.geminiBrainEnabled) {
                    val reason = "No matching contact rule and Gemini Brain is OFF."
                    conversationMemory.logActivity(conversationKey, latestMsg.senderName, "SKIPPED", "Gemini OFF", reason)
                    _serviceStatus.value = AutomationServiceStatus.WAITING_FOR_NOTIFICATION
                    _statusDetail.value = reason
                    return@withLock
                }

                val geminiOutcome = geminiConcurrencyLimiter.withPermit {
                    geminiReplyEngine.generateWhatsAppReply(
                        senderName = latestMsg.senderName,
                        conversationTitle = conversationTitle,
                        isGroupConversation = isGroup,
                        incomingMessage = combinedIncomingText,
                        contextBundle = contextBundle,
                        ruleInstruction = ruleInstruction,
                        settings = settings
                    )
                }

                when (geminiOutcome) {
                    is GeminiReplyOutcome.Generated -> {
                        generatedReply = geminiOutcome.replyText
                        detectedLanguage = geminiOutcome.detectedLanguage
                        conversationMemory.logActivity(
                            conversationKey,
                            latestMsg.senderName,
                            "GEMINI_GENERATED",
                            geminiOutcome.detectedLanguage,
                            geminiOutcome.replyText.take(120)
                        )
                    }
                    is GeminiReplyOutcome.SkippedBySafety -> {
                        conversationMemory.logActivity(
                            conversationKey,
                            latestMsg.senderName,
                            "SKIPPED",
                            "Gemini Safety",
                            geminiOutcome.reason
                        )
                        _serviceStatus.value = AutomationServiceStatus.WAITING_FOR_NOTIFICATION
                        _statusDetail.value = geminiOutcome.reason
                        return@withLock
                    }
                    is GeminiReplyOutcome.Failure -> {
                        _serviceStatus.value = AutomationServiceStatus.REPLY_FAILED
                        _statusDetail.value = geminiOutcome.reason
                        conversationMemory.logActivity(
                            conversationKey,
                            latestMsg.senderName,
                            "FAILED",
                            "Gemini Error",
                            geminiOutcome.reason
                        )
                        return@withLock
                    }
                }
            }

            // 5. Post-generation reply safety & anti-duplication validation
            var candidateReply = generatedReply
            val geminiCreds = credentialStore.geminiCredentials.value
            val elevenCreds = credentialStore.elevenLabsCredentials.value

            var safeCheck = WhatsAppAutoReply.validateGeneratedReply(
                replyText = candidateReply,
                recentOutgoingReplies = if (replySource == "RULE") emptyList() else contextBundle.recentOutgoingReplies,
                geminiCreds = geminiCreds,
                elevenCreds = elevenCreds
            )

            // If Gemini happened to repeat a previous phrasing verbatim, ask once for a fresh variation
            if (safeCheck is SafetyValidationResult.Blocked &&
                replySource == "GEMINI" &&
                safeCheck.reason.contains("repetitive", ignoreCase = true)
            ) {
                val variedOutcome = geminiConcurrencyLimiter.withPermit {
                    geminiReplyEngine.generateWhatsAppReply(
                        senderName = latestMsg.senderName,
                        conversationTitle = conversationTitle,
                        isGroupConversation = isGroup,
                        incomingMessage = combinedIncomingText,
                        contextBundle = contextBundle,
                        ruleInstruction = buildString {
                            if (!ruleInstruction.isNullOrBlank()) append("$ruleInstruction. ")
                            append("IMPORTANT: Write a completely fresh phrasing different from: \"$candidateReply\"")
                        },
                        settings = settings
                    )
                }
                if (variedOutcome is GeminiReplyOutcome.Generated) {
                    candidateReply = variedOutcome.replyText
                    detectedLanguage = variedOutcome.detectedLanguage
                    safeCheck = WhatsAppAutoReply.validateGeneratedReply(
                        replyText = candidateReply,
                        recentOutgoingReplies = contextBundle.recentOutgoingReplies,
                        geminiCreds = geminiCreds,
                        elevenCreds = elevenCreds
                    )
                }
            }

            val validatedReply = when (val checked = safeCheck) {
                is SafetyValidationResult.Safe -> checked.sanitizedReply
                is SafetyValidationResult.Blocked -> {
                    _serviceStatus.value = AutomationServiceStatus.REPLY_FAILED
                    _statusDetail.value = checked.reason
                    conversationMemory.logActivity(
                        conversationKey,
                        latestMsg.senderName,
                        "SKIPPED",
                        "Safety Blocked",
                        checked.reason
                    )
                    return@withLock
                }
            }

            // 6. Dispatch via RemoteInput on the exact matching notification
            val sendOutcome = replySender.sendReply(
                conversationKey = conversationKey,
                expectedNotificationKey = latestMsg.notificationKey,
                replyText = validatedReply
            )

            val outFingerprint = "out_${latestMsg.fingerprint}_${System.currentTimeMillis()}"
            when (sendOutcome) {
                ReplySendOutcome.Sent -> {
                    markSelfSentEcho(conversationKey, validatedReply)
                    conversationMemory.recordOutgoingReply(
                        conversationKey = conversationKey,
                        senderName = "Sara AI",
                        replyText = validatedReply,
                        detectedLanguage = detectedLanguage,
                        replySource = replySource,
                        status = "SENT",
                        fingerprint = outFingerprint
                    )
                    _serviceStatus.value = AutomationServiceStatus.REPLY_SENT
                    _statusDetail.value = "Reply sent to ${latestMsg.senderName} ($detectedLanguage)."
                    conversationMemory.logActivity(
                        conversationKey,
                        latestMsg.senderName,
                        "SENT",
                        "Reply sent ($replySource)",
                        validatedReply.take(120)
                    )
                    if (settings.voiceAnnouncementEnabled) {
                        announceSafely(
                            fingerprint = "out_${latestMsg.fingerprint}",
                            spokenText = "${latestMsg.senderName} ko reply bhej diya hai."
                        )
                    }
                }
                is ReplySendOutcome.NoReplyAction -> {
                    recordFailedSend(conversationKey, validatedReply, detectedLanguage, replySource, outFingerprint, latestMsg.senderName, sendOutcome.reason)
                }
                is ReplySendOutcome.ExpiredNotification -> {
                    recordFailedSend(conversationKey, validatedReply, detectedLanguage, replySource, outFingerprint, latestMsg.senderName, sendOutcome.reason)
                }
                is ReplySendOutcome.Failed -> {
                    recordFailedSend(conversationKey, validatedReply, detectedLanguage, replySource, outFingerprint, latestMsg.senderName, sendOutcome.reason)
                }
            }
        }
    }

    private suspend fun recordFailedSend(
        conversationKey: String,
        replyText: String,
        detectedLanguage: String,
        replySource: String,
        outFingerprint: String,
        senderName: String,
        reason: String
    ) {
        conversationMemory.recordOutgoingReply(
            conversationKey = conversationKey,
            senderName = "Sara AI",
            replyText = replyText,
            detectedLanguage = detectedLanguage,
            replySource = replySource,
            status = "FAILED",
            fingerprint = outFingerprint
        )
        _serviceStatus.value = AutomationServiceStatus.REPLY_FAILED
        _statusDetail.value = reason
        conversationMemory.logActivity(
            conversationKey = conversationKey,
            senderName = senderName,
            eventType = "FAILED",
            status = "Reply failed",
            detail = reason
        )
    }

    private fun markSelfSentEcho(conversationKey: String, sentText: String) {
        val key = "${conversationKey.lowercase()}|${sentText.trim()}"
        recentSelfSentEchoes[key] = System.currentTimeMillis()
        // Prune stale echo markers > 30s
        val cutoff = System.currentTimeMillis() - 30_000L
        recentSelfSentEchoes.entries.removeIf { it.value < cutoff }
    }

    private fun isSelfSentEcho(conversationKey: String, incomingText: String): Boolean {
        val key = "${conversationKey.lowercase()}|${incomingText.trim()}"
        val ts = recentSelfSentEchoes[key] ?: return false
        return (System.currentTimeMillis() - ts) < 30_000L
    }

    private fun updateBaselineStatus(settings: AutomationSettings) {
        if (!settings.automationEnabled) {
            _serviceStatus.value = AutomationServiceStatus.STOPPED
            _statusDetail.value = "WhatsApp Automation is OFF."
            return
        }
        val granted = refreshNotificationAccessState()
        if (!granted) {
            _serviceStatus.value = AutomationServiceStatus.STOPPED
            _statusDetail.value = "Grant Notification Access in Android Settings to enable monitoring."
            return
        }
        _serviceStatus.value = if (_listenerConnected.value) {
            AutomationServiceStatus.WAITING_FOR_NOTIFICATION
        } else {
            AutomationServiceStatus.RUNNING
        }
        _statusDetail.value = if (_listenerConnected.value) {
            "Listening for WhatsApp messages in background."
        } else {
            "Automation enabled; waiting for Android NotificationListener bind."
        }
    }

    private suspend fun announceSafely(fingerprint: String, spokenText: String) {
        val now = System.currentTimeMillis()
        val prev = recentAnnouncedFingerprints.putIfAbsent(fingerprint, now)
        if (prev != null && (now - prev) < 30_000L) return
        recentAnnouncedFingerprints.entries.removeIf { (now - it.value) > 60_000L }

        if (isPhoneCallActive()) {
            Log.d(TAG, "Skipping voice announcement during active phone call.")
            return
        }
        if (audioCaptureCoordinator.currentOwner.value != AudioCaptureOwner.NONE) {
            Log.d(TAG, "Skipping voice announcement while active voice session owns microphone.")
            return
        }

        val elevenCreds = credentialStore.elevenLabsCredentials.value
        if (elevenCreds.isConfigured) {
            val result = ttsEngine.speak(spokenText, liveCallMode = true)
            if (result is TtsResult.Completed || result is TtsResult.Stopped) {
                return
            }
        }
        speakWithLocalAndroidTts(spokenText)
    }

    @SuppressLint("MissingPermission")
    fun isPhoneCallActive(): Boolean {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val mode = am?.mode ?: AudioManager.MODE_NORMAL
        if (mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE
        ) {
            return true
        }
        val hasPhonePerm = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPhonePerm) {
            val tm = appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            @Suppress("DEPRECATION")
            val state = tm?.callState ?: TelephonyManager.CALL_STATE_IDLE
            if (state != TelephonyManager.CALL_STATE_IDLE) {
                return true
            }
        }
        return false
    }

    private suspend fun speakWithLocalAndroidTts(text: String) = withContext(Dispatchers.Main) {
        if (androidTts == null) {
            androidTts = TextToSpeech(appContext) { status ->
                androidTtsReady = status == TextToSpeech.SUCCESS
                if (androidTtsReady) {
                    androidTts?.language = Locale.forLanguageTag("hi-IN")
                    if (!isPhoneCallActive()) {
                        audioFocusManager.requestAssistantFocus(useVoiceCommunicationUsage = false)
                        androidTts?.speak(text, TextToSpeech.QUEUE_ADD, null, "sara_wa_${System.currentTimeMillis()}")
                    }
                }
            }
            return@withContext
        }
        if (androidTtsReady && !isPhoneCallActive()) {
            audioFocusManager.requestAssistantFocus(useVoiceCommunicationUsage = false)
            androidTts?.speak(text, TextToSpeech.QUEUE_ADD, null, "sara_wa_${System.currentTimeMillis()}")
        }
    }

    fun requestListenerRebindIfNeeded() {
        refreshNotificationAccessState()
        if (AutomationSettingsStore.isNotificationAccessGranted(appContext) &&
            !_listenerConnected.value
        ) {
            runCatching {
                NotificationListenerService.requestRebind(
                    ComponentName(appContext, SaraNotificationService::class.java)
                )
            }.onFailure { err ->
                Log.w(TAG, "NotificationListenerService.requestRebind failed", err)
            }
        }
    }

    fun scheduleWatchdogWorker() {
        runCatching {
            val work = PeriodicWorkRequestBuilder<WhatsAppAutomationWatchdogWorker>(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
                WATCHDOG_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                work
            )
        }
    }

    suspend fun setAutomationEnabled(enabled: Boolean) {
        settingsStore.setAutomationEnabled(enabled)
        if (enabled) {
            requestListenerRebindIfNeeded()
            scheduleWatchdogWorker()
        }
        conversationMemory.logActivity(
            conversationKey = "system",
            senderName = "User",
            eventType = "SERVICE",
            status = if (enabled) "Running" else "Stopped",
            detail = if (enabled) "WhatsApp Automation enabled." else "WhatsApp Automation disabled."
        )
    }

    companion object {
        private const val TAG = "WhatsAppAutoMgr"
        private const val WATCHDOG_WORK_NAME = "sara_whatsapp_automation_watchdog"
    }
}

class WhatsAppAutomationWatchdogWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? SaraApplication ?: return Result.success()
        val manager = app.container.whatsAppAutomationManager
        val settings = manager.settingsStore.settings.first()
        if (settings.automationEnabled) {
            manager.requestListenerRebindIfNeeded()
        }
        manager.conversationMemory.pruneOldLogs(keepCount = 200)
        return Result.success()
    }
}
