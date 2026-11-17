package com.example.voice.wake

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.example.core.security.CredentialStore
import com.example.voice.audio.AudioCaptureCoordinator
import com.example.voice.audio.AudioCaptureOwner
import com.example.voice.audio.VadEvent
import com.example.voice.audio.VoiceActivityDetector
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Properties
import kotlin.math.abs

data class WakeEnrollmentProfile(
    val enrolledCount: Int = 0,
    val meanRms: Double = 0.0,
    val meanZcr: Float = 0f,
    val meanDurationMs: Long = 0L
) {
    val isComplete: Boolean get() = enrolledCount >= REQUIRED_SAMPLES

    companion object {
        const val REQUIRED_SAMPLES = 5
    }
}

data class WakeEnrollmentUiState(
    val isRecordingSample: Boolean = false,
    val activeSampleNumber: Int = 0,
    val liveAmplitude: Float = 0f,
    val profile: WakeEnrollmentProfile = WakeEnrollmentProfile(),
    val statusMessage: String = "Record \"Hey Sara\" 5 times to build your personal wake profile."
)

/**
 * Records and persists 5 personal "Hey Sara" voice samples (16 kHz mono WAV + acoustic
 * consistency profile) into app-private storage for local wake-phrase verification.
 */
class WakePhraseEnrollmentManager(
    context: Context,
    private val credentialStore: CredentialStore,
    private val captureCoordinator: AudioCaptureCoordinator
) {
    private val appContext = context.applicationContext
    private val enrollmentDir = File(appContext.filesDir, "wake_enrollment").apply { mkdirs() }
    private val profileFile = File(enrollmentDir, "enrollment_profile.properties")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recordingJob: Job? = null

    private val _uiState = MutableStateFlow(
        WakeEnrollmentUiState(profile = loadSavedProfile())
    )
    val uiState: StateFlow<WakeEnrollmentUiState> = _uiState.asStateFlow()

    @SuppressLint("MissingPermission")
    fun recordNextSample() {
        val current = _uiState.value.profile
        val nextIndex = (current.enrolledCount + 1).coerceAtMost(WakeEnrollmentProfile.REQUIRED_SAMPLES)
        recordSampleAtIndex(nextIndex)
    }

    @SuppressLint("MissingPermission")
    fun recordSampleAtIndex(sampleIndex: Int) {
        if (sampleIndex !in 1..WakeEnrollmentProfile.REQUIRED_SAMPLES) return
        stopRecording()

        if (!captureCoordinator.acquire(AudioCaptureOwner.WAKE_ENROLLMENT) { stopRecording() }) {
            _uiState.value = _uiState.value.copy(
                isRecordingSample = false,
                statusMessage = "Microphone is currently busy. End active voice session first."
            )
            return
        }

        val minBuf = AudioRecord.getMinBufferSize(
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            captureCoordinator.release(AudioCaptureOwner.WAKE_ENROLLMENT)
            _uiState.value = _uiState.value.copy(
                isRecordingSample = false,
                statusMessage = "16 kHz audio capture is not supported on this device."
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
            captureCoordinator.release(AudioCaptureOwner.WAKE_ENROLLMENT)
            _uiState.value = _uiState.value.copy(
                isRecordingSample = false,
                statusMessage = "Could not open microphone. Grant Microphone permission."
            )
            return
        }

        runCatching { recorder.startRecording() }.onFailure {
            runCatching { recorder.release() }
            captureCoordinator.release(AudioCaptureOwner.WAKE_ENROLLMENT)
            _uiState.value = _uiState.value.copy(
                isRecordingSample = false,
                statusMessage = "Microphone recording failed to start."
            )
            return
        }

        _uiState.value = _uiState.value.copy(
            isRecordingSample = true,
            activeSampleNumber = sampleIndex,
            liveAmplitude = 0.08f,
            statusMessage = "Listening for sample $sampleIndex/5 — say \"Hey Sara\" clearly..."
        )

        recordingJob = scope.launch {
            val vad = VoiceActivityDetector(
                speechStartRatio = 2.2,
                minSpeechRms = 360.0,
                startConfirmFrames = 2,
                endSilenceHangoverFrames = 14
            )
            val buffer = ByteArray(1280) // 40 ms at 16 kHz 16-bit mono
            val pcmOut = ByteArrayOutputStream()
            var speechStarted = false
            var voicedFrames = 0
            var rmsSum = 0.0
            var zcrSum = 0f
            val startedAt = System.currentTimeMillis()
            val maxRecordMs = 3200L

            try {
                while (isActive && System.currentTimeMillis() - startedAt < maxRecordMs) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val (vadEvent, metrics) = vad.processFrame(buffer, read)
                        _uiState.value = _uiState.value.copy(liveAmplitude = metrics.normalizedAmplitude)
                        pcmOut.write(buffer, 0, read)

                        when (vadEvent) {
                            VadEvent.SPEECH_STARTED, VadEvent.SPEECH_ACTIVE -> {
                                speechStarted = true
                                voicedFrames++
                                rmsSum += metrics.rms
                                zcrSum += metrics.zeroCrossingRate
                            }
                            VadEvent.SPEECH_ENDED -> {
                                if (speechStarted && voicedFrames >= 7) {
                                    break
                                }
                            }
                            VadEvent.SILENCE -> Unit
                        }
                    } else {
                        delay(15)
                    }
                }
            } finally {
                runCatching {
                    if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
                    recorder.release()
                }
                captureCoordinator.release(AudioCaptureOwner.WAKE_ENROLLMENT)
            }

            val durationMs = voicedFrames * 40L
            if (voicedFrames < 7 || rmsSum / voicedFrames.coerceAtLeast(1) < 350.0) {
                _uiState.value = _uiState.value.copy(
                    isRecordingSample = false,
                    liveAmplitude = 0f,
                    statusMessage = "Sample $sampleIndex too quiet or short. Say \"Hey Sara\" clearly and retry."
                )
                return@launch
            }

            val pcmBytes = pcmOut.toByteArray()
            val wavFile = File(enrollmentDir, "sample_$sampleIndex.wav")
            writeWavFile(wavFile, pcmBytes, sampleRate = 16000)

            val avgRms = rmsSum / voicedFrames
            val avgZcr = zcrSum / voicedFrames
            saveSampleStats(sampleIndex, avgRms, avgZcr, durationMs)

            val updatedProfile = loadSavedProfile()
            credentialStore.updateWakeEnrolledCount(updatedProfile.enrolledCount)

            val nextMsg = if (updatedProfile.isComplete) {
                "All 5 \"Hey Sara\" samples enrolled! Personal wake profile ready."
            } else {
                "Sample $sampleIndex/5 saved (${durationMs}ms). Record ${5 - updatedProfile.enrolledCount} more."
            }
            _uiState.value = _uiState.value.copy(
                isRecordingSample = false,
                liveAmplitude = 0f,
                profile = updatedProfile,
                statusMessage = nextMsg
            )
        }
    }

    fun stopRecording() {
        recordingJob?.cancel()
        recordingJob = null
        captureCoordinator.release(AudioCaptureOwner.WAKE_ENROLLMENT)
        if (_uiState.value.isRecordingSample) {
            _uiState.value = _uiState.value.copy(
                isRecordingSample = false,
                liveAmplitude = 0f,
                statusMessage = "Enrollment recording cancelled."
            )
        }
    }

    suspend fun clearEnrollment() {
        stopRecording()
        enrollmentDir.listFiles()?.forEach { runCatching { it.delete() } }
        val empty = WakeEnrollmentProfile()
        credentialStore.updateWakeEnrolledCount(0)
        _uiState.value = _uiState.value.copy(
            profile = empty,
            statusMessage = "Wake phrase enrollment cleared. Record \"Hey Sara\" 5 times."
        )
    }

    /**
     * Checks whether a recent audio burst aligns reasonably with the user's enrolled
     * "Hey Sara" RMS/zero-crossing profile (soft gate when profile is complete).
     */
    fun matchesAcousticProfile(pcm16Le: ByteArray, sensitivity: Float): Boolean {
        val profile = _uiState.value.profile
        if (!profile.isComplete || pcm16Le.size < 3200) return true
        val metrics = VoiceActivityDetector.analyzePcm16(pcm16Le, pcm16Le.size)
        val zcrDiff = abs(metrics.zeroCrossingRate - profile.meanZcr)
        val maxAllowedZcrDiff = 0.25f + (1f - sensitivity) * 0.2f
        return metrics.rms >= 260.0 && zcrDiff <= maxAllowedZcrDiff
    }

    fun getCurrentProfile(): WakeEnrollmentProfile = _uiState.value.profile

    private fun saveSampleStats(index: Int, rms: Double, zcr: Float, durationMs: Long) {
        val props = Properties()
        if (profileFile.exists()) {
            runCatching { FileInputStream(profileFile).use { props.load(it) } }
        }
        props.setProperty("sample_${index}_rms", rms.toString())
        props.setProperty("sample_${index}_zcr", zcr.toString())
        props.setProperty("sample_${index}_dur", durationMs.toString())
        runCatching {
            FileOutputStream(profileFile).use { props.store(it, "SARA Hey Sara Enrollment Profile") }
        }
    }

    fun loadSavedProfile(): WakeEnrollmentProfile {
        val props = Properties()
        if (profileFile.exists()) {
            runCatching { FileInputStream(profileFile).use { props.load(it) } }
        }
        var count = 0
        var rmsSum = 0.0
        var zcrSum = 0f
        var durSum = 0L
        for (i in 1..WakeEnrollmentProfile.REQUIRED_SAMPLES) {
            val wav = File(enrollmentDir, "sample_$i.wav")
            if (wav.exists() && wav.length() > 44L) {
                count++
                rmsSum += props.getProperty("sample_${i}_rms")?.toDoubleOrNull() ?: 900.0
                zcrSum += props.getProperty("sample_${i}_zcr")?.toFloatOrNull() ?: 0.08f
                durSum += props.getProperty("sample_${i}_dur")?.toLongOrNull() ?: 750L
            }
        }
        return if (count == 0) {
            WakeEnrollmentProfile()
        } else {
            WakeEnrollmentProfile(
                enrolledCount = count,
                meanRms = rmsSum / count,
                meanZcr = zcrSum / count,
                meanDurationMs = durSum / count
            )
        }
    }

    private fun writeWavFile(file: File, pcmData: ByteArray, sampleRate: Int) {
        val totalAudioLen = pcmData.size
        val totalDataLen = totalAudioLen + 36
        val byteRate = sampleRate * 2 // 16-bit mono
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(totalDataLen)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16) // PCM chunk size
            putShort(1) // PCM format
            putShort(1) // Mono
            putInt(sampleRate)
            putInt(byteRate)
            putShort(2) // Block align
            putShort(16) // Bits per sample
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(totalAudioLen)
        }.array()

        FileOutputStream(file).use { out ->
            out.write(header)
            out.write(pcmData)
        }
    }
}
