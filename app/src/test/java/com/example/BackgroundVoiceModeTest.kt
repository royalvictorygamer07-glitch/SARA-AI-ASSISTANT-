package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.example.actions.ActionRouter
import com.example.actions.AppLauncherHandler
import com.example.actions.BackgroundActivityStarter
import com.example.actions.CallingHandler
import com.example.actions.ContactsResolver
import com.example.actions.MessagingHandler
import com.example.actions.YouTubeHandler
import com.example.ai.gemini.ChatTurn
import com.example.ai.gemini.GeminiClient
import com.example.ai.gemini.GeminiResult
import com.example.ai.gemini.live.GeminiLiveClient
import com.example.ai.gemini.live.LiveAudioStreamManager
import com.example.commands.ActionCategory
import com.example.commands.ActionExecutionResult
import com.example.commands.AssistantCommand
import com.example.commands.CommandParser
import com.example.commands.VoiceControlIntentDetector
import com.example.core.network.NetworkMonitor
import com.example.core.security.CredentialStore
import com.example.core.security.UserProfile
import com.example.memory.ConversationHistoryRepository
import com.example.memory.MemoryRecord
import com.example.memory.RoomMemoryRepository
import com.example.memory.SaraDatabase
import com.example.voice.SaraVoiceCoordinator
import com.example.voice.audio.AudioCaptureCoordinator
import com.example.voice.audio.SaraAudioFocusManager
import com.example.voice.stt.SttEngine
import com.example.voice.stt.SttEvent
import com.example.voice.tts.ElevenLabsTtsEngine
import com.example.voice.wake.SaraPowerMode
import com.example.voice.wake.WakeEventBus
import androidx.room.Room
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BackgroundVoiceModeTest {

    private class FakeSttEngine : SttEngine {
        private val _isListening = MutableStateFlow(false)
        override val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

        private val _partialTranscript = MutableStateFlow("")
        override val partialTranscript: StateFlow<String> = _partialTranscript.asStateFlow()

        private val _normalizedRms = MutableStateFlow(0f)
        override val normalizedRms: StateFlow<Float> = _normalizedRms.asStateFlow()

        private val _events = MutableSharedFlow<SttEvent>(extraBufferCapacity = 16)
        override val events: SharedFlow<SttEvent> = _events.asSharedFlow()

        var startedCount = 0
        var cancelledCount = 0

        override fun isRecognitionAvailable(): Boolean = true

        override fun startListening(languageTag: String) {
            startedCount++
            _isListening.value = true
        }

        override fun stopListening() {
            _isListening.value = false
        }

        override fun cancel() {
            cancelledCount++
            _isListening.value = false
        }

        override fun release() {
            _isListening.value = false
        }

        fun emitFinal(text: String) {
            _isListening.value = false
            _events.tryEmit(SttEvent.FinalTranscript(text))
        }

        fun emitPartial(text: String) {
            _partialTranscript.value = text
        }
    }

    private class StubGeminiClient : GeminiClient {
        override suspend fun generateAssistantResponse(
            userMessage: String,
            history: List<ChatTurn>,
            memories: List<MemoryRecord>,
            userProfile: UserProfile,
            liveCallMode: Boolean
        ): GeminiResult = GeminiResult.Success(
            command = AssistantCommand(category = ActionCategory.CHAT, spokenReply = "Haan Sir, sun rahi hoon."),
            rawText = """{"action":"CHAT","reply":"Haan Sir, sun rahi hoon."}"""
        )

        override suspend fun analyzeImage(
            bitmap: android.graphics.Bitmap,
            prompt: String,
            memories: List<MemoryRecord>
        ): GeminiResult = GeminiResult.Success(
            command = AssistantCommand(category = ActionCategory.CHAT, spokenReply = "Photo analyzed."),
            rawText = "Photo analyzed."
        )

        override suspend fun verifyApiKey(apiKey: String, modelName: String): Result<String> =
            Result.success("OK")
    }

    @Test
    fun `voice control detector recognizes sleep wake and device commands across Hindi and English`() {
        // Sleep triggers
        assertTrue(VoiceControlIntentDetector.isSleepCommand("Sara sleep"))
        assertTrue(VoiceControlIntentDetector.isSleepCommand("Sara, sleep mode mein chali jao"))
        assertTrue(VoiceControlIntentDetector.isSleepCommand("so jao sara"))
        assertTrue(VoiceControlIntentDetector.isSleepCommand("go to sleep"))
        assertFalse(VoiceControlIntentDetector.isSleepCommand("YouTube open karo"))

        // Wake triggers
        assertTrue(VoiceControlIntentDetector.isWakeCommand("Hey Sara"))
        assertTrue(VoiceControlIntentDetector.isWakeCommand("hey sarah online aa jao"))
        assertTrue(VoiceControlIntentDetector.isWakeCommand("OK Sara"))
        assertTrue(VoiceControlIntentDetector.isWakeCommand("wake up sara"))
        assertFalse(VoiceControlIntentDetector.isWakeCommand("hello friend how are you"))

        // Clipped wake check during Sleep VAD->STT handoff
        assertTrue(VoiceControlIntentDetector.isWakeCommand("Sara", allowClippedWakeWord = true))
        assertFalse(VoiceControlIntentDetector.isWakeCommand("what is weather today", allowClippedWakeWord = true))

        // Device command heuristics for Gemini Live mode
        assertTrue(VoiceControlIntentDetector.looksLikeDeviceActionCommand("YouTube open karo"))
        assertTrue(VoiceControlIntentDetector.looksLikeDeviceActionCommand("WhatsApp kholo"))
        assertTrue(VoiceControlIntentDetector.looksLikeDeviceActionCommand("Mummy ko call karo"))
        assertFalse(VoiceControlIntentDetector.looksLikeDeviceActionCommand("aaj mausam kaisa hai"))
        assertFalse(VoiceControlIntentDetector.looksLikeDeviceActionCommand("Sara sleep"))
    }

    @Test
    fun `command parser handles SLEEP_MODE action and ActionRouter routes it cleanly`() {
        val cmd = CommandParser.parseModelOutput(
            """{"action":"SLEEP_MODE","reply":"Theek hai Sir, sleep mode mein ja rahi hoon.","target":"","requires_confirmation":false}"""
        )
        assertEquals(ActionCategory.SLEEP_MODE, cmd.category)

        val context = ApplicationProvider.getApplicationContext<Context>()
        val contactsResolver = ContactsResolver(context)
        val router = ActionRouter(
            appLauncherHandler = AppLauncherHandler(context),
            youTubeHandler = YouTubeHandler(context),
            callingHandler = CallingHandler(context, contactsResolver),
            messagingHandler = MessagingHandler(context, contactsResolver)
        )
        val res = router.route(cmd, confirmBeforeExternalAction = true, onNavigateToRoute = {})
        assertTrue(res is ActionExecutionResult.Executed)
    }

    @Test
    fun `manifest declares SYSTEM_ALERT_WINDOW and FOREGROUND_SERVICE_MICROPHONE`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pkgInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS
        )
        val requested = pkgInfo.requestedPermissions?.toSet().orEmpty()
        assertTrue(requested.contains(Manifest.permission.SYSTEM_ALERT_WINDOW))
        assertTrue(requested.contains(Manifest.permission.FOREGROUND_SERVICE))
        assertTrue(requested.contains(Manifest.permission.FOREGROUND_SERVICE_MICROPHONE))
        assertTrue(requested.contains(Manifest.permission.RECORD_AUDIO))
    }

    @Test
    fun `background activity starter detects foreground vs background fallback`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        BackgroundActivityStarter.setAppForeground(true)
        assertTrue(BackgroundActivityStarter.canLaunchFromBackground(context))

        val settingsIntent = Intent(android.provider.Settings.ACTION_SETTINGS)
        val outcome = BackgroundActivityStarter.launchActivity(context, settingsIntent, "Android Settings")
        assertTrue(outcome is BackgroundActivityStarter.LaunchOutcome.LaunchedDirectly)
    }

    @Test
    fun `coordinator switches from ONLINE to SLEEP on Sara sleep and wakes back to ONLINE on Hey Sara`() = runTest {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        org.robolectric.Shadows.shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val db = Room.inMemoryDatabaseBuilder(app, SaraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val credentialStore = CredentialStore(app)
        val networkMonitor = NetworkMonitor(app)
        val audioCaptureCoordinator = AudioCaptureCoordinator()
        val audioFocusManager = SaraAudioFocusManager(app)
        val wakeEventBus = WakeEventBus()
        val fakeStt = FakeSttEngine()
        val liveClient = GeminiLiveClient(
            credentialStore = credentialStore,
            networkMonitor = networkMonitor,
            audioStreamManager = LiveAudioStreamManager(audioCaptureCoordinator, audioFocusManager)
        )
        val contactsResolver = ContactsResolver(app)
        val coordinator = SaraVoiceCoordinator(
            context = app,
            credentialStore = credentialStore,
            networkMonitor = networkMonitor,
            memoryRepository = RoomMemoryRepository(db.memoryDao()),
            historyRepository = ConversationHistoryRepository(db.conversationHistoryDao()),
            geminiClient = StubGeminiClient(),
            geminiLiveClient = liveClient,
            ttsEngine = ElevenLabsTtsEngine(app, credentialStore, networkMonitor, audioFocusManager),
            sttEngine = fakeStt,
            actionRouter = ActionRouter(
                appLauncherHandler = AppLauncherHandler(app),
                youTubeHandler = YouTubeHandler(app),
                callingHandler = CallingHandler(app, contactsResolver),
                messagingHandler = MessagingHandler(app, contactsResolver)
            ),
            audioCaptureCoordinator = audioCaptureCoordinator,
            wakeEventBus = wakeEventBus
        )
        try {
            // Transition to Sleep mode cleanly via text/voice path
            coordinator.enterSleepMode(speakConfirmation = false)
            assertEquals(SaraPowerMode.SLEEP, wakeEventBus.powerMode.value)

            // Random unrelated chatter during Sleep Mode should NOT wake Sara up
            fakeStt.emitPartial("random tv noise")
            assertEquals(SaraPowerMode.SLEEP, wakeEventBus.powerMode.value)

            // Saying "Hey Sara" while in Sleep Mode wakes Sara immediately
            coordinator.wakeUpFromSleep("hey sara")
            assertEquals(SaraPowerMode.ONLINE, wakeEventBus.powerMode.value)

            // Sending "Sara sleep" returns cleanly to SLEEP mode
            coordinator.sendTextCommand("Sara sleep")
            assertEquals(SaraPowerMode.SLEEP, wakeEventBus.powerMode.value)
        } finally {
            coordinator.shutdown()
            runCatching { org.robolectric.shadows.ShadowLooper.idleMainLooper() }
            db.close()
        }
    }
}
