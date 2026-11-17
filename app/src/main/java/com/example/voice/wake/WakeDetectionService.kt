package com.example.voice.wake

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.SaraApplication
import com.example.voice.audio.AudioCaptureOwner
import com.example.voice.audio.VadEvent
import com.example.voice.audio.VoiceActivityDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android 14+ (targetSdk 36) compliant Foreground Service (`microphone` type) powering both:
 * 1) Background Online Mode — keeps Gemini Live / Classic conversation & app/action execution alive
 *    while SARA is minimized or other apps are open.
 * 2) Sleep Mode — stops active Gemini streaming and listens lightly for "Hey Sara"
 *    (using local offline Vosk if imported, or silent VAD + SpeechRecognizer fallback otherwise).
 */
class WakeDetectionService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private var captureJob: Job? = null

    private val voskEngine = VoskWakeWordEngine()
    private val appContainer by lazy { (application as SaraApplication).container }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TURN_OFF -> {
                serviceScope.launch {
                    appContainer.credentialStore.updateWakeUpDetectionEnabled(false)
                    withContext(Dispatchers.Main) {
                        appContainer.voiceCoordinator.endConversationSession()
                    }
                }
                stopServiceCleanly(WakeServiceState.DISABLED, "Background voice & wake service turned off.")
                return START_NOT_STICKY
            }
            ACTION_UI_SLEEP_NOW -> {
                serviceScope.launch(Dispatchers.Main) {
                    appContainer.voiceCoordinator.enterSleepMode(speakConfirmation = true)
                }
                return START_STICKY
            }
            ACTION_UI_WAKE_NOW -> {
                serviceScope.launch(Dispatchers.Main) {
                    appContainer.voiceCoordinator.wakeUpFromSleep("notification button")
                }
                return START_STICKY
            }
            ACTION_MODE_ONLINE -> {
                enterOnlineForegroundMode()
                return START_STICKY
            }
            ACTION_MODE_SLEEP -> {
                startSleepMonitoringPipeline()
                return START_STICKY
            }
            ACTION_START_WAKE,
            ACTION_RESUME_WAKE -> {
                if (appContainer.wakeEventBus.powerMode.value == SaraPowerMode.ONLINE) {
                    enterOnlineForegroundMode()
                } else {
                    startSleepMonitoringPipeline()
                }
                return START_STICKY
            }
            else -> {
                if (appContainer.wakeEventBus.powerMode.value == SaraPowerMode.ONLINE) {
                    enterOnlineForegroundMode()
                } else {
                    startSleepMonitoringPipeline()
                }
                return START_STICKY
            }
        }
    }

    private fun enterForegroundWithNotification(notification: Notification): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceCompat.startForeground(
                    this,
                    FOREGROUND_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(FOREGROUND_NOTIFICATION_ID, notification)
            }
            true
        }.onFailure { err ->
            Log.e(TAG, "Unable to enter microphone foreground service", err)
            appContainer.wakeEventBus.updateServiceState(
                WakeServiceState.ERROR,
                "Android blocked background microphone start. Open SARA first to start background voice."
            )
            stopSelf()
        }.getOrDefault(false)
    }

    private fun enterOnlineForegroundMode() {
        if (!hasRecordAudioPermission()) {
            appContainer.wakeEventBus.updateServiceState(
                WakeServiceState.PERMISSION_MISSING,
                "Microphone permission required for Background Voice Assistant."
            )
            stopSelf()
            return
        }

        cancelSleepLoopsOnly()
        val notification = buildOnlineNotification()
        if (!enterForegroundWithNotification(notification)) return

        appContainer.wakeEventBus.updatePowerMode(SaraPowerMode.ONLINE)
        appContainer.wakeEventBus.updateServiceState(
            WakeServiceState.ONLINE_BACKGROUND,
            "Background Online active — speak naturally or say \"Sara sleep\""
        )
    }

    private fun startSleepMonitoringPipeline() {
        if (!hasRecordAudioPermission()) {
            appContainer.wakeEventBus.updateServiceState(
                WakeServiceState.PERMISSION_MISSING,
                "Microphone permission revoked. Re-grant RECORD_AUDIO to run Wake / Sleep mode."
            )
            stopSelf()
            return
        }

        cancelSleepLoopsOnly()
        val inspection = VoskModelInspector.inspect(applicationContext)
        appContainer.refreshVoskInspection(inspection)
        appContainer.wakeEventBus.updatePowerMode(SaraPowerMode.SLEEP)

        val statusLine = if (inspection.isReady) {
            "Sleep Mode (Offline Vosk) — say \"Hey Sara\" to come online"
        } else {
            "Sleep Mode — say \"Hey Sara\" to come online"
        }
        val notification = buildSleepNotification(statusLine)
        if (!enterForegroundWithNotification(notification)) return

        if (inspection.isReady) {
            appContainer.wakeEventBus.updateServiceState(WakeServiceState.STARTING, "Loading Vosk wake model...")
            monitorJob = serviceScope.launch {
                val initResult = voskEngine.initialize(inspection)
                if (initResult.isFailure) {
                    // Gracefully degrade to VAD+STT sleep monitoring instead of failing
                    runVadSttSleepFallbackLoop()
                    return@launch
                }

                appContainer.audioCaptureCoordinator.currentOwner.collect { owner ->
                    if (!isActive || appContainer.wakeEventBus.powerMode.value != SaraPowerMode.SLEEP) {
                        return@collect
                    }
                    if (!hasRecordAudioPermission()) {
                        stopServiceCleanly(WakeServiceState.PERMISSION_MISSING, "Microphone permission removed.")
                        return@collect
                    }
                    if (owner == AudioCaptureOwner.NONE) {
                        beginVoskSleepLoop()
                    } else if (owner != AudioCaptureOwner.WAKE_LISTENING) {
                        pauseSleepLoop()
                    }
                }
            }
        } else {
            monitorJob = serviceScope.launch {
                runVadSttSleepFallbackLoop()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun beginVoskSleepLoop() {
        if (captureJob?.isActive == true) return
        if (!appContainer.audioCaptureCoordinator.acquire(AudioCaptureOwner.WAKE_LISTENING) {
                pauseSleepLoop()
            }
        ) {
            return
        }

        updateNotification(buildSleepNotification("Sleep Mode (Offline Vosk) — say \"Hey Sara\""))
        appContainer.wakeEventBus.updateServiceState(
            WakeServiceState.SLEEPING,
            "Sleep Mode (Offline Vosk) — say \"Hey Sara\" to wake"
        )

        captureJob = serviceScope.launch {
            val minBuf = AudioRecord.getMinBufferSize(
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                appContainer.audioCaptureCoordinator.release(AudioCaptureOwner.WAKE_LISTENING)
                appContainer.wakeEventBus.updateServiceState(
                    WakeServiceState.ERROR,
                    "16 kHz AudioRecord unavailable on device."
                )
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
                appContainer.audioCaptureCoordinator.release(AudioCaptureOwner.WAKE_LISTENING)
                appContainer.wakeEventBus.updateServiceState(
                    WakeServiceState.ERROR,
                    "Microphone unavailable (in use by another app or call)."
                )
                return@launch
            }

            runCatching { recorder.startRecording() }.onFailure { err ->
                Log.e(TAG, "AudioRecord start failed", err)
                runCatching { recorder.release() }
                appContainer.audioCaptureCoordinator.release(AudioCaptureOwner.WAKE_LISTENING)
                return@launch
            }

            val vad = VoiceActivityDetector(
                speechStartRatio = 2.4,
                minSpeechRms = 340.0,
                startConfirmFrames = 2,
                endSilenceHangoverFrames = 16
            )
            val buf = ByteArray(1280)
            var speechWindowBytes = ByteArray(0)

            try {
                while (isActive && appContainer.wakeEventBus.powerMode.value == SaraPowerMode.SLEEP) {
                    val read = recorder.read(buf, 0, buf.size)
                    if (read > 0) {
                        val (vadEvent, _) = vad.processFrame(buf, read)
                        if (vadEvent != VadEvent.SILENCE) {
                            if (speechWindowBytes.size < 48000) {
                                speechWindowBytes += buf.copyOf(read)
                            }
                            val result = voskEngine.processPcm16(buf, read)
                            if (result.detected) {
                                val sensitivity = appContainer.credentialStore.behaviorSettings.first().wakeSensitivity
                                val profileMatch = appContainer.wakeEnrollmentManager
                                    .matchesAcousticProfile(speechWindowBytes, sensitivity)
                                if (profileMatch) {
                                    onWakePhraseDetected(result.recognizedPhrase)
                                    break
                                } else {
                                    voskEngine.reset()
                                    speechWindowBytes = ByteArray(0)
                                }
                            }
                        } else if (speechWindowBytes.isNotEmpty()) {
                            speechWindowBytes = ByteArray(0)
                        }
                    } else {
                        delay(25)
                    }
                }
            } finally {
                runCatching {
                    if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        recorder.stop()
                    }
                    recorder.release()
                }
                appContainer.audioCaptureCoordinator.release(AudioCaptureOwner.WAKE_LISTENING)
            }
        }
    }

    /**
     * Zero-setup Sleep Mode loop when no offline Vosk folder is imported:
     * Uses quiet VoiceActivityDetector gating so SpeechRecognizer is only invoked on actual
     * speech bursts to check for "Hey Sara", preventing constant STT beeps during silence.
     */
    @SuppressLint("MissingPermission")
    private suspend fun runVadSttSleepFallbackLoop() {
        val sttAvailable = appContainer.sttEngine.isRecognitionAvailable()
        if (!sttAvailable) {
            appContainer.wakeEventBus.updateServiceState(
                WakeServiceState.MODEL_MISSING,
                "Import a Vosk folder (or enable Google Speech Services) for Sleep/Wake spotting."
            )
            return
        }

        updateNotification(buildSleepNotification("Sleep Mode — say \"Hey Sara\" to come online"))
        appContainer.wakeEventBus.updateServiceState(
            WakeServiceState.SLEEPING,
            "Sleep Mode active — say \"Hey Sara\" to bring Sara online (import Vosk anytime for 100% offline spotting)"
        )

        while (serviceScope.isActive && appContainer.wakeEventBus.powerMode.value == SaraPowerMode.SLEEP) {
            // Wait until microphone is free (e.g., sleep confirmation TTS finishes)
            while (serviceScope.isActive &&
                appContainer.wakeEventBus.powerMode.value == SaraPowerMode.SLEEP &&
                (appContainer.elevenLabsTtsEngine.isSpeaking.value ||
                    appContainer.audioCaptureCoordinator.currentOwner.value != AudioCaptureOwner.NONE)
            ) {
                delay(180L)
            }
            if (!serviceScope.isActive || appContainer.wakeEventBus.powerMode.value != SaraPowerMode.SLEEP) {
                break
            }

            if (!appContainer.audioCaptureCoordinator.acquire(AudioCaptureOwner.WAKE_LISTENING) {
                    pauseSleepLoop()
                }
            ) {
                delay(300L)
                continue
            }

            val minBuf = AudioRecord.getMinBufferSize(
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val recorder = if (minBuf > 0) {
                runCatching {
                    AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        16000,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        maxOf(minBuf * 2, 4096)
                    )
                }.getOrNull()
            } else {
                null
            }

            if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { recorder?.release() }
                appContainer.audioCaptureCoordinator.release(AudioCaptureOwner.WAKE_LISTENING)
                delay(700L)
                continue
            }

            var speechDetected = false
            runCatching { recorder.startRecording() }.onFailure {
                runCatching { recorder.release() }
                appContainer.audioCaptureCoordinator.release(AudioCaptureOwner.WAKE_LISTENING)
                delay(700L)
                return
            }

            try {
                val vad = VoiceActivityDetector(
                    speechStartRatio = 2.3,
                    minSpeechRms = 320.0,
                    startConfirmFrames = 2,
                    endSilenceHangoverFrames = 12
                )
                val buf = ByteArray(1280)
                while (serviceScope.isActive &&
                    appContainer.wakeEventBus.powerMode.value == SaraPowerMode.SLEEP &&
                    !appContainer.elevenLabsTtsEngine.isSpeaking.value
                ) {
                    val read = recorder.read(buf, 0, buf.size)
                    if (read > 0) {
                        val (vadEvent, _) = vad.processFrame(buf, read)
                        if (vadEvent == VadEvent.SPEECH_STARTED || vadEvent == VadEvent.SPEECH_ACTIVE) {
                            speechDetected = true
                            break
                        }
                    } else {
                        delay(25L)
                    }
                }
            } finally {
                runCatching {
                    if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        recorder.stop()
                    }
                    recorder.release()
                }
                appContainer.audioCaptureCoordinator.release(AudioCaptureOwner.WAKE_LISTENING)
            }

            if (!speechDetected || appContainer.wakeEventBus.powerMode.value != SaraPowerMode.SLEEP) {
                continue
            }

            // Brief release window before SpeechRecognizer captures wake phrase check
            delay(130L)
            if (appContainer.wakeEventBus.powerMode.value != SaraPowerMode.SLEEP) break

            val localeTag = appContainer.credentialStore.userProfile.first().sttLocaleTag()
            withContext(Dispatchers.Main) {
                if (appContainer.wakeEventBus.powerMode.value == SaraPowerMode.SLEEP &&
                    appContainer.audioCaptureCoordinator.acquire(AudioCaptureOwner.CLASSIC_STT)
                ) {
                    appContainer.sttEngine.startListening(localeTag)
                }
            }

            // Wait while SpeechRecognizer listens (up to 4.5s) — SaraVoiceCoordinator checks transcripts
            val waitStart = System.currentTimeMillis()
            while (serviceScope.isActive &&
                appContainer.wakeEventBus.powerMode.value == SaraPowerMode.SLEEP &&
                System.currentTimeMillis() - waitStart < 4500L &&
                appContainer.audioCaptureCoordinator.currentOwner.value == AudioCaptureOwner.CLASSIC_STT
            ) {
                delay(120L)
            }

            withContext(Dispatchers.Main) {
                if (appContainer.wakeEventBus.powerMode.value == SaraPowerMode.SLEEP &&
                    appContainer.audioCaptureCoordinator.currentOwner.value == AudioCaptureOwner.CLASSIC_STT
                ) {
                    appContainer.sttEngine.cancel()
                    appContainer.audioCaptureCoordinator.release(AudioCaptureOwner.CLASSIC_STT)
                }
            }
            delay(180L)
        }
    }

    private fun pauseSleepLoop() {
        captureJob?.cancel()
        captureJob = null
        appContainer.audioCaptureCoordinator.release(AudioCaptureOwner.WAKE_LISTENING)
        voskEngine.reset()
        updateNotification(buildSleepNotification("Paused while SARA voice session is active"))
        appContainer.wakeEventBus.updateServiceState(
            WakeServiceState.PAUSED_FOR_SESSION,
            "Paused while active voice session owns microphone"
        )
    }

    private fun cancelSleepLoopsOnly() {
        captureJob?.cancel()
        captureJob = null
        monitorJob?.cancel()
        monitorJob = null
        appContainer.audioCaptureCoordinator.release(AudioCaptureOwner.WAKE_LISTENING)
        voskEngine.reset()
    }

    private fun onWakePhraseDetected(phrase: String) {
        captureJob?.cancel()
        captureJob = null
        monitorJob?.cancel()
        monitorJob = null
        appContainer.audioCaptureCoordinator.release(AudioCaptureOwner.WAKE_LISTENING)

        appContainer.wakeEventBus.emitWakeTrigger(phrase)
        postWakeStatusNotification(phrase)
    }

    private fun postWakeStatusNotification(phrase: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_WAKE_TRIGGERED, true)
            putExtra(EXTRA_WAKE_PHRASE, phrase)
        }

        val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val opts = ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
            PendingIntent.getActivity(
                this,
                WAKE_TRIGGER_REQUEST_CODE,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                opts.toBundle()
            )
        } else {
            PendingIntent.getActivity(
                this,
                WAKE_TRIGGER_REQUEST_CODE,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val wakeNotif = NotificationCompat.Builder(this, CHANNEL_WAKE_TRIGGER)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("SARA is Online")
            .setContentText("Heard \"$phrase\" — speak your command or tap to open HUD")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setTimeoutAfter(4500L)
            .setContentIntent(pendingIntent)
            .build()
        nm.notify(WAKE_TRIGGER_NOTIFICATION_ID, wakeNotif)
    }

    private fun stopServiceCleanly(finalState: WakeServiceState, diagnostic: String?) {
        cancelSleepLoopsOnly()
        voskEngine.release()
        appContainer.wakeEventBus.updatePowerMode(SaraPowerMode.OFF)
        appContainer.wakeEventBus.updateServiceState(finalState, diagnostic)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (activeInstance === this) activeInstance = null
        captureJob?.cancel()
        monitorJob?.cancel()
        appContainer.audioCaptureCoordinator.release(AudioCaptureOwner.WAKE_LISTENING)
        voskEngine.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val monitorChannel = NotificationChannel(
                CHANNEL_WAKE_MONITOR,
                "SARA Background Voice & Sleep Mode",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent indicator while SARA listens in Online or Sleep Mode"
            }
            val triggerChannel = NotificationChannel(
                CHANNEL_WAKE_TRIGGER,
                "SARA Online Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies briefly when \"Hey Sara\" wakes SARA from Sleep Mode"
            }
            nm.createNotificationChannel(monitorChannel)
            nm.createNotificationChannel(triggerChannel)
        }
    }

    private fun updateNotification(notification: Notification) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(FOREGROUND_NOTIFICATION_ID, notification)
    }

    private fun buildOnlineNotification(): Notification {
        val openIntent = buildOpenAppPendingIntent()
        val sleepIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, WakeDetectionService::class.java).apply { action = ACTION_UI_SLEEP_NOW },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val turnOffIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, WakeDetectionService::class.java).apply { action = ACTION_TURN_OFF },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_WAKE_MONITOR)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("SARA Online (Background Active)")
            .setContentText("Talk naturally, open apps, or say \"Sara sleep\"")
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openIntent)
            .addAction(0, "Sleep Mode", sleepIntent)
            .addAction(0, "Turn Off", turnOffIntent)
            .build()
    }

    private fun buildSleepNotification(statusText: String): Notification {
        val openIntent = buildOpenAppPendingIntent()
        val wakeIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, WakeDetectionService::class.java).apply { action = ACTION_UI_WAKE_NOW },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val turnOffIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, WakeDetectionService::class.java).apply { action = ACTION_TURN_OFF },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_WAKE_MONITOR)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("SARA Sleep Mode")
            .setContentText(statusText)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openIntent)
            .addAction(0, "Wake Up", wakeIntent)
            .addAction(0, "Turn Off", turnOffIntent)
            .build()
    }

    private fun buildOpenAppPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    companion object {
        private const val TAG = "WakeDetectionService"
        private const val CHANNEL_WAKE_MONITOR = "sara_wake_monitor"
        private const val CHANNEL_WAKE_TRIGGER = "sara_wake_trigger"
        private const val FOREGROUND_NOTIFICATION_ID = 4101
        private const val WAKE_TRIGGER_NOTIFICATION_ID = 4102
        private const val WAKE_TRIGGER_REQUEST_CODE = 4109

        const val ACTION_START_WAKE = "com.example.action.START_WAKE"
        const val ACTION_MODE_ONLINE = "com.example.action.MODE_ONLINE"
        const val ACTION_MODE_SLEEP = "com.example.action.MODE_SLEEP"
        const val ACTION_UI_SLEEP_NOW = "com.example.action.UI_SLEEP_NOW"
        const val ACTION_UI_WAKE_NOW = "com.example.action.UI_WAKE_NOW"
        const val ACTION_TURN_OFF = "com.example.action.TURN_OFF_WAKE"
        const val ACTION_RESUME_WAKE = "com.example.action.RESUME_WAKE"
        const val EXTRA_WAKE_TRIGGERED = "extra_wake_triggered"
        const val EXTRA_WAKE_PHRASE = "extra_wake_phrase"

        @Volatile
        private var activeInstance: WakeDetectionService? = null

        fun stopSleepCaptureHint() {
            activeInstance?.cancelSleepLoopsOnly()
        }

        fun start(context: Context) {
            startSleep(context)
        }

        fun startOnline(context: Context) {
            val intent = Intent(context, WakeDetectionService::class.java).apply {
                action = ACTION_MODE_ONLINE
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun startSleep(context: Context) {
            val intent = Intent(context, WakeDetectionService::class.java).apply {
                action = ACTION_MODE_SLEEP
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeDetectionService::class.java))
        }
    }
}
