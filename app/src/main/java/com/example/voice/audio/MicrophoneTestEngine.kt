package com.example.voice.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class MicTestUiState(
    val isTesting: Boolean = false,
    val liveAmplitude: Float = 0f,
    val liveDbfs: Double = -96.0,
    val peakDbfs: Double = -96.0,
    val statusMessage: String = "Tap Test Microphone to verify 16 kHz capture.",
    val aecSupported: Boolean = AcousticEchoCanceler.isAvailable(),
    val nsSupported: Boolean = NoiseSuppressor.isAvailable()
)

/**
 * Interactive 16 kHz PCM diagnostic tool allowing users to verify microphone capture,
 * noise floor, and speech detection cleanly without interfering with other capture sessions.
 */
class MicrophoneTestEngine(
    private val captureCoordinator: AudioCaptureCoordinator
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var testJob: Job? = null

    private val _state = MutableStateFlow(MicTestUiState())
    val state: StateFlow<MicTestUiState> = _state.asStateFlow()

    @SuppressLint("MissingPermission")
    fun startTest(durationMillis: Long = 5000L) {
        stopTest()
        if (!captureCoordinator.acquire(AudioCaptureOwner.MIC_TEST) { stopTest() }) {
            _state.value = _state.value.copy(
                isTesting = false,
                statusMessage = "Microphone is busy with another active session."
            )
            return
        }

        val minBuf = AudioRecord.getMinBufferSize(
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            captureCoordinator.release(AudioCaptureOwner.MIC_TEST)
            _state.value = _state.value.copy(
                isTesting = false,
                statusMessage = "16 kHz PCM microphone capture unsupported on this device."
            )
            return
        }

        val recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 2, 2560)
            )
        }.getOrNull()

        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder?.release() }
            captureCoordinator.release(AudioCaptureOwner.MIC_TEST)
            _state.value = _state.value.copy(
                isTesting = false,
                statusMessage = "Unable to initialize AudioRecord. Check microphone permission."
            )
            return
        }

        runCatching { recorder.startRecording() }.onFailure {
            runCatching { recorder.release() }
            captureCoordinator.release(AudioCaptureOwner.MIC_TEST)
            _state.value = _state.value.copy(
                isTesting = false,
                statusMessage = "AudioRecord failed to start."
            )
            return
        }

        _state.value = _state.value.copy(
            isTesting = true,
            peakDbfs = -96.0,
            statusMessage = "Speak \"Hey Sara\" or a test phrase..."
        )

        testJob = scope.launch {
            val vad = VoiceActivityDetector()
            val buf = ByteArray(1280)
            var peak = -96.0
            var speechDetected = false
            val startedAt = System.currentTimeMillis()
            try {
                while (isActive && System.currentTimeMillis() - startedAt < durationMillis) {
                    val read = recorder.read(buf, 0, buf.size)
                    if (read > 0) {
                        val (vadEvent, metrics) = vad.processFrame(buf, read)
                        if (metrics.dbfs > peak) peak = metrics.dbfs
                        if (vadEvent == VadEvent.SPEECH_STARTED || vadEvent == VadEvent.SPEECH_ACTIVE) {
                            speechDetected = true
                        }
                        _state.value = _state.value.copy(
                            liveAmplitude = metrics.normalizedAmplitude,
                            liveDbfs = metrics.dbfs,
                            peakDbfs = peak
                        )
                    } else {
                        delay(20)
                    }
                }
            } finally {
                runCatching {
                    if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
                    recorder.release()
                }
                captureCoordinator.release(AudioCaptureOwner.MIC_TEST)
                val summary = if (speechDetected && peak > -45.0) {
                    "Microphone passed (Peak ${"%.1f".format(peak)} dBFS). Speech energy detected."
                } else {
                    "Mic active, low speech signal (Peak ${"%.1f".format(peak)} dBFS). Try speaking closer."
                }
                _state.value = _state.value.copy(
                    isTesting = false,
                    liveAmplitude = 0f,
                    statusMessage = summary
                )
            }
        }
    }

    fun stopTest() {
        testJob?.cancel()
        testJob = null
        captureCoordinator.release(AudioCaptureOwner.MIC_TEST)
        if (_state.value.isTesting) {
            _state.value = _state.value.copy(
                isTesting = false,
                liveAmplitude = 0f,
                statusMessage = "Microphone test stopped."
            )
        }
    }
}
