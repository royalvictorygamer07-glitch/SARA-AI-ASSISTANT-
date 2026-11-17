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

/**
 * Android 14+ (targetSdk 36) compliant Foreground Service (`microphone` type) that runs
 * local offline Vosk wake-phrase detection ("Hey Sara") only when explicitly enabled by
 * the user, a valid Vosk model is installed, and RECORD_AUDIO permission is granted.
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
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TURN_OFF -> {
                serviceScope.launch {
                    appContainer.credentialStore.updateWakeUpDetectionEnabled(false)
                }
                stopServiceCleanly(WakeServiceState.DISABLED, "Wake Up Detection turned off.")
                return START_NOT_STICKY
            }
            ACTION_RESUME_WAKE -> {
                startMonitoringPipeline()
                return START_STICKY
            }
            else -> {
                startMonitoringPipeline()
                return START_STICKY
            }
        }
    }

    private fun startMonitoringPipeline() {
        if (!hasRecordAudioPermission()) {
            appContainer.wakeEventBus.updateServiceState(
                WakeServiceState.PERMISSION_MISSING,
                "Microphone permission revoked. Re-grant RECORD_AUDIO to run Wake Up Detection."
            )
            stopSelf()
            return
        }

        val inspection = VoskModelInspector.inspect(applicationContext)
        appContainer.refreshVoskInspection(inspection)
        if (!inspection.isReady) {
            appContainer.wakeEventBus.updateServiceState(
                WakeServiceState.MODEL_MISSING,
                inspection.diagnosticMessage
            )
            stopSelf()
            return
        }

        val notification = buildPersistentNotification("Listening offline for \"Hey Sara\"")
        runCatching {
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
        }.onFailure { err ->
            Log.e(TAG, "Unable to enter microphone foreground service", err)
            appContainer.wakeEventBus.updateServiceState(
                WakeServiceState.ERROR,
                "Android blocked background microphone service start. Open SARA to enable Wake Up Detection."
            )
            stopSelf()
            return
        }

        appContainer.wakeEventBus.updateServiceState(WakeServiceState.STARTING, "Loading Vosk wake model...")

        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            val initResult = voskEngine.initialize(inspection)
            if (initResult.isFailure) {
                val msg = initResult.exceptionOrNull()?.localizedMessage ?: "Failed to load Vosk model."
                appContainer.wakeEventBus.updateServiceState(WakeServiceState.ERROR, msg)
                stopSelf()
                return@launch
            }

            // Watch AudioCaptureCoordinator so wake capture pauses during active Gemini Live / STT
            // and resumes cleanly once the foreground voice session ends.
            appContainer.audioCaptureCoordinator.currentOwner.collect { owner ->
                if (!isActive) return@collect
                val enabled = appContainer.credentialStore.behaviorSettings.first().wakeUpDetectionEnabled
                if (!enabled) {
                    stopServiceCleanly(WakeServiceState.DISABLED, null)
                    return@collect
                }
                if (!hasRecordAudioPermission()) {
                    stopServiceCleanly(
                        WakeServiceState.PERMISSION_MISSING,
                        "Microphone permission removed."
                    )
                    return@collect
                }
                if (owner == AudioCaptureOwner.NONE) {
                    beginPassiveWakeLoop()
                } else if (owner != AudioCaptureOwner.WAKE_LISTENING) {
                    pausePassiveWakeLoop()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun beginPassiveWakeLoop() {
        if (captureJob?.isActive == true) return
        if (!appContainer.audioCaptureCoordinator.acquire(AudioCaptureOwner.WAKE_LISTENING) {
                pausePassiveWakeLoop()
            }
        ) {
            return
        }

        updatePersistentNotification("Listening offline for \"Hey Sara\"")
        appContainer.wakeEventBus.updateServiceState(
            WakeServiceState.LISTENING,
            "Active in foreground service — say \"Hey Sara\""
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
            val buf = ByteArray(1280) // 40 ms chunks
            var speechWindowBytes = ByteArray(0)

            try {
                while (isActive) {
                    val read = recorder.read(buf, 0, buf.size)
                    if (read > 0) {
                        val (vadEvent, _) = vad.processFrame(buf, read)
                        if (vadEvent != VadEvent.SILENCE) {
                            // Feed Vosk only during active speech + hangover to minimize battery drain
                            if (speechWindowBytes.size < 48000) {
                                speechWindowBytes += buf.copyOf(read)
                            }
                            val result = voskEngine.processPcm16(buf, read)
                            if (result.detected) {
                                val sensitivity = appContainer.credentialStore.behaviorSettings.first().wakeSensitivity
                                val profileMatch = appContainer.wakeEnrollmentManager
                                    .matchesAcousticProfile(speechWindowBytes, sensitivity)
                                if (profileMatch) {
                                    onWakeDetected(result.recognizedPhrase)
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

    private fun pausePassiveWakeLoop() {
        captureJob?.cancel()
        captureJob = null
        appContainer.audioCaptureCoordinator.release(AudioCaptureOwner.WAKE_LISTENING)
        voskEngine.reset()
        updatePersistentNotification("Paused while SARA voice session is active")
        appContainer.wakeEventBus.updateServiceState(
            WakeServiceState.PAUSED_FOR_SESSION,
            "Paused while foreground voice session owns microphone"
        )
    }

    private fun onWakeDetected(phrase: String) {
        captureJob?.cancel()
        captureJob = null
        appContainer.audioCaptureCoordinator.release(AudioCaptureOwner.WAKE_LISTENING)

        appContainer.wakeEventBus.emitWakeTrigger(phrase)
        postWakeLaunchNotification(phrase)
    }

    private fun postWakeLaunchNotification(phrase: String) {
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

        // Trigger immediate handoff if the system permits BAL for the foregrounded/exempt service
        runCatching { pendingIntent.send() }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val wakeNotif = NotificationCompat.Builder(this, CHANNEL_WAKE_TRIGGER)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("SARA is listening")
                .setContentText("Wake phrase detected — tap to open Sara voice popup")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()
            nm.notify(WAKE_TRIGGER_NOTIFICATION_ID, wakeNotif)
        }
    }

    private fun stopServiceCleanly(finalState: WakeServiceState, diagnostic: String?) {
        captureJob?.cancel()
        captureJob = null
        monitorJob?.cancel()
        monitorJob = null
        appContainer.audioCaptureCoordinator.release(AudioCaptureOwner.WAKE_LISTENING)
        voskEngine.release()
        appContainer.wakeEventBus.updateServiceState(finalState, diagnostic)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
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
                "SARA Wake Up Detection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent indicator while SARA listens offline for \"Hey Sara\""
            }
            val triggerChannel = NotificationChannel(
                CHANNEL_WAKE_TRIGGER,
                "SARA Wake Popup Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies when \"Hey Sara\" triggers while app is backgrounded"
            }
            nm.createNotificationChannel(monitorChannel)
            nm.createNotificationChannel(triggerChannel)
        }
    }

    private fun updatePersistentNotification(statusText: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(FOREGROUND_NOTIFICATION_ID, buildPersistentNotification(statusText))
    }

    private fun buildPersistentNotification(statusText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
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
            .setContentTitle("SARA Wake Up Detection")
            .setContentText(statusText)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openIntent)
            .addAction(0, "Turn Off", turnOffIntent)
            .build()
    }

    companion object {
        private const val TAG = "WakeDetectionService"
        private const val CHANNEL_WAKE_MONITOR = "sara_wake_monitor"
        private const val CHANNEL_WAKE_TRIGGER = "sara_wake_trigger"
        private const val FOREGROUND_NOTIFICATION_ID = 4101
        private const val WAKE_TRIGGER_NOTIFICATION_ID = 4102
        private const val WAKE_TRIGGER_REQUEST_CODE = 4109

        const val ACTION_START_WAKE = "com.example.action.START_WAKE"
        const val ACTION_TURN_OFF = "com.example.action.TURN_OFF_WAKE"
        const val ACTION_RESUME_WAKE = "com.example.action.RESUME_WAKE"
        const val EXTRA_WAKE_TRIGGERED = "extra_wake_triggered"
        const val EXTRA_WAKE_PHRASE = "extra_wake_phrase"

        fun start(context: Context) {
            val intent = Intent(context, WakeDetectionService::class.java).apply {
                action = ACTION_START_WAKE
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeDetectionService::class.java))
        }
    }
}
