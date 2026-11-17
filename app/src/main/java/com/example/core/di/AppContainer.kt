package com.example.core.di

import android.content.Context
import com.example.actions.ActionRouter
import com.example.actions.AppLauncherHandler
import com.example.actions.CallingHandler
import com.example.actions.ContactsResolver
import com.example.actions.IncomingCallAssistantManager
import com.example.actions.MessagingHandler
import com.example.actions.YouTubeHandler
import com.example.ai.gemini.GeminiClient
import com.example.ai.gemini.RestGeminiClient
import com.example.ai.gemini.live.GeminiLiveClient
import com.example.ai.gemini.live.LiveAudioStreamManager
import com.example.automation.whatsapp.AutomationSettingsStore
import com.example.automation.whatsapp.ContactRuleEngine
import com.example.automation.whatsapp.ConversationMemory
import com.example.automation.whatsapp.GeminiReplyEngine
import com.example.automation.whatsapp.WhatsAppAutomationManager
import com.example.automation.whatsapp.WhatsAppReplySender
import com.example.core.network.NetworkMonitor
import com.example.core.security.CredentialStore
import com.example.memory.ConversationHistoryRepository
import com.example.memory.MemoryStorageProvider
import com.example.memory.RoomMemoryRepository
import com.example.memory.SaraDatabase
import com.example.voice.audio.AudioCaptureCoordinator
import com.example.voice.audio.MicrophoneTestEngine
import com.example.voice.audio.SaraAudioFocusManager
import com.example.voice.stt.AndroidSpeechRecognizerSttEngine
import com.example.voice.stt.SttEngine
import com.example.voice.tts.ElevenLabsTtsEngine
import com.example.voice.wake.VoskModelInspectionResult
import com.example.voice.wake.VoskModelInspector
import com.example.voice.wake.WakeEventBus
import com.example.voice.wake.WakePhraseEnrollmentManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext

    val networkMonitor: NetworkMonitor by lazy { NetworkMonitor(appContext) }
    val credentialStore: CredentialStore by lazy { CredentialStore(appContext) }

    val audioCaptureCoordinator: AudioCaptureCoordinator by lazy { AudioCaptureCoordinator() }
    val audioFocusManager: SaraAudioFocusManager by lazy { SaraAudioFocusManager(appContext) }
    val microphoneTestEngine: MicrophoneTestEngine by lazy {
        MicrophoneTestEngine(audioCaptureCoordinator)
    }

    val wakeEventBus: WakeEventBus by lazy { WakeEventBus() }
    val wakeEnrollmentManager: WakePhraseEnrollmentManager by lazy {
        WakePhraseEnrollmentManager(
            context = appContext,
            credentialStore = credentialStore,
            captureCoordinator = audioCaptureCoordinator
        )
    }

    private val _voskInspection = MutableStateFlow(VoskModelInspector.inspect(appContext))
    val voskInspection: StateFlow<VoskModelInspectionResult> = _voskInspection.asStateFlow()

    fun refreshVoskInspection(result: VoskModelInspectionResult = VoskModelInspector.inspect(appContext)) {
        _voskInspection.value = result
    }

    private val database: SaraDatabase by lazy { SaraDatabase.getInstance(appContext) }
    val memoryRepository: MemoryStorageProvider by lazy {
        RoomMemoryRepository(database.memoryDao())
    }
    val conversationHistoryRepository: ConversationHistoryRepository by lazy {
        ConversationHistoryRepository(database.conversationHistoryDao())
    }

    val geminiClient: GeminiClient by lazy {
        RestGeminiClient(
            credentialStore = credentialStore,
            networkMonitor = networkMonitor
        )
    }

    val liveAudioStreamManager: LiveAudioStreamManager by lazy {
        LiveAudioStreamManager(
            captureCoordinator = audioCaptureCoordinator,
            audioFocusManager = audioFocusManager
        )
    }

    val geminiLiveClient: GeminiLiveClient by lazy {
        GeminiLiveClient(
            credentialStore = credentialStore,
            networkMonitor = networkMonitor,
            audioStreamManager = liveAudioStreamManager
        )
    }

    val elevenLabsTtsEngine: ElevenLabsTtsEngine by lazy {
        ElevenLabsTtsEngine(
            context = appContext,
            credentialStore = credentialStore,
            networkMonitor = networkMonitor,
            audioFocusManager = audioFocusManager
        )
    }

    val sttEngine: SttEngine by lazy {
        AndroidSpeechRecognizerSttEngine(appContext)
    }

    val contactsResolver: ContactsResolver by lazy { ContactsResolver(appContext) }
    val incomingCallAssistantManager: IncomingCallAssistantManager by lazy {
        IncomingCallAssistantManager(appContext)
    }

    val actionRouter: ActionRouter by lazy {
        ActionRouter(
            appLauncherHandler = AppLauncherHandler(appContext),
            youTubeHandler = YouTubeHandler(appContext),
            callingHandler = CallingHandler(appContext, contactsResolver),
            messagingHandler = MessagingHandler(appContext, contactsResolver)
        )
    }

    val automationSettingsStore: AutomationSettingsStore by lazy {
        AutomationSettingsStore(appContext)
    }
    val whatsAppConversationMemory: ConversationMemory by lazy {
        ConversationMemory(database.whatsAppAutomationDao())
    }
    val contactRuleEngine: ContactRuleEngine by lazy {
        ContactRuleEngine(database.whatsAppAutomationDao())
    }
    val geminiReplyEngine: GeminiReplyEngine by lazy {
        GeminiReplyEngine(
            credentialStore = credentialStore,
            networkMonitor = networkMonitor
        )
    }
    val whatsAppReplySender: WhatsAppReplySender by lazy {
        WhatsAppReplySender(appContext)
    }
    val whatsAppAutomationManager: WhatsAppAutomationManager by lazy {
        WhatsAppAutomationManager(
            context = appContext,
            settingsStore = automationSettingsStore,
            conversationMemory = whatsAppConversationMemory,
            ruleEngine = contactRuleEngine,
            geminiReplyEngine = geminiReplyEngine,
            replySender = whatsAppReplySender,
            credentialStore = credentialStore,
            ttsEngine = elevenLabsTtsEngine,
            audioFocusManager = audioFocusManager,
            audioCaptureCoordinator = audioCaptureCoordinator
        )
    }
}
