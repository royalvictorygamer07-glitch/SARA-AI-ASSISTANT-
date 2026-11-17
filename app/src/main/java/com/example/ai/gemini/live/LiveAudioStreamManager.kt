package com.example.ai.gemini.live

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.example.voice.audio.AudioCaptureCoordinator
import com.example.voice.audio.AudioCaptureOwner
import com.example.voice.audio.SaraAudioFocusManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Full-duplex PCM audio engine for Gemini Live API:
 * - Captures 16 kHz, 16-bit mono PCM from microphone with hardware AEC/NS when available
 * - Plays 24 kHz, 16-bit mono PCM streamed back from Gemini Live
 * - Supports instant buffer flush on barge-in / interruption and safe resource release
 */
class LiveAudioStreamManager(
    private val captureCoordinator: AudioCaptureCoordinator? = null,
    private val audioFocusManager: SaraAudioFocusManager? = null
) {

    private val audioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    private val trackLock = Any()

    private val _micAmplitude = MutableStateFlow(0f)
    val micAmplitude: StateFlow<Float> = _micAmplitude.asStateFlow()

    private val _playbackAmplitude = MutableStateFlow(0f)
    val playbackAmplitude: StateFlow<Float> = _playbackAmplitude.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    @SuppressLint("MissingPermission")
    fun startCapture(
        onPcmChunk: (ByteArray) -> Unit,
        onError: (String) -> Unit
    ) {
        stopCapture()
        if (captureCoordinator != null && !captureCoordinator.acquire(AudioCaptureOwner.GEMINI_LIVE)) {
            onError("Microphone is busy with another session.")
            return
        }
        audioFocusManager?.requestAssistantFocus(useVoiceCommunicationUsage = true) {
            flushPlayback()
        }

        val minBuf = AudioRecord.getMinBufferSize(
            INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            captureCoordinator?.release(AudioCaptureOwner.GEMINI_LIVE)
            audioFocusManager?.abandonFocus()
            onError("Device microphone does not support 16kHz PCM audio capture.")
            return
        }

        val bufferSize = maxOf(minBuf * 2, INPUT_CHUNK_BYTES * 4)
        val recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                INPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        }.getOrNull()

        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder?.release() }
            captureCoordinator?.release(AudioCaptureOwner.GEMINI_LIVE)
            onError("Unable to initialize microphone audio capture.")
            return
        }

        audioRecord = recorder

        // Attach hardware echo cancellation & noise suppression when supported by device
        val sessionId = recorder.audioSessionId
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = runCatching {
                AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
            }.getOrNull()
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = runCatching {
                NoiseSuppressor.create(sessionId)?.apply { enabled = true }
            }.getOrNull()
        }

        runCatching { recorder.startRecording() }.onFailure { err ->
            Log.e(TAG, "AudioRecord startRecording failed", err)
            stopCapture()
            onError("Microphone recording failed to start.")
            return
        }

        _isCapturing.value = true
        captureJob = audioScope.launch {
            val readBuf = ByteArray(INPUT_CHUNK_BYTES)
            while (isActive && _isCapturing.value) {
                val readBytes = recorder.read(readBuf, 0, readBuf.size)
                if (readBytes > 0) {
                    val chunk = readBuf.copyOf(readBytes)
                    _micAmplitude.value = computeNormalizedRms(chunk, readBytes)
                    onPcmChunk(chunk)
                } else if (readBytes < 0) {
                    Log.w(TAG, "AudioRecord read error code=$readBytes")
                    break
                }
            }
            _micAmplitude.value = 0f
        }
    }

    fun playPcm24k(pcmBytes: ByteArray) {
        if (pcmBytes.isEmpty()) return
        synchronized(trackLock) {
            val track = ensureAudioTrack() ?: return
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                runCatching { track.play() }
            }
            _playbackAmplitude.value = computeNormalizedRms(pcmBytes, pcmBytes.size)
            runCatching {
                track.write(pcmBytes, 0, pcmBytes.size, AudioTrack.WRITE_BLOCKING)
            }.onFailure { err ->
                Log.w(TAG, "AudioTrack write warning", err)
            }
        }
    }

    fun markPlaybackIdle() {
        _playbackAmplitude.value = 0f
    }

    fun flushPlayback() {
        synchronized(trackLock) {
            _playbackAmplitude.value = 0f
            runCatching {
                audioTrack?.apply {
                    if (playState == AudioTrack.PLAYSTATE_PLAYING) {
                        pause()
                        flush()
                        play()
                    } else {
                        flush()
                    }
                }
            }
        }
    }

    fun stopCapture() {
        _isCapturing.value = false
        _micAmplitude.value = 0f
        captureJob?.cancel()
        captureJob = null
        runCatching { echoCanceler?.release() }
        echoCanceler = null
        runCatching { noiseSuppressor?.release() }
        noiseSuppressor = null
        runCatching {
            audioRecord?.apply {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    stop()
                }
                release()
            }
        }
        audioRecord = null
        captureCoordinator?.release(AudioCaptureOwner.GEMINI_LIVE)
    }

    fun stopAndRelease() {
        stopCapture()
        audioFocusManager?.abandonFocus()
        synchronized(trackLock) {
            _playbackAmplitude.value = 0f
            runCatching {
                audioTrack?.apply {
                    if (playState == AudioTrack.PLAYSTATE_PLAYING) {
                        pause()
                        flush()
                        stop()
                    }
                    release()
                }
            }
            audioTrack = null
        }
        audioScope.coroutineContext.cancelChildren()
    }

    private fun ensureAudioTrack(): AudioTrack? {
        audioTrack?.let { if (it.state == AudioTrack.STATE_INITIALIZED) return it }

        val minBuf = AudioTrack.getMinBufferSize(
            OUTPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(OUTPUT_SAMPLE_RATE / 5 * 2)

        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(OUTPUT_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull()

        if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { track?.release() }
            return null
        }
        audioTrack = track
        return track
    }

    private fun computeNormalizedRms(pcm16Le: ByteArray, length: Int): Float {
        val samples = length / 2
        if (samples <= 0) return 0f
        var sumSq = 0.0
        var i = 0
        while (i + 1 < length) {
            val lo = pcm16Le[i].toInt() and 0xFF
            val hi = pcm16Le[i + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort().toDouble()
            sumSq += sample * sample
            i += 2
        }
        val rms = sqrt(sumSq / samples)
        // Scale 16-bit speech RMS (~300..9000) into smooth [0.05..1.0] range
        return ((rms / 6500.0).toFloat()).coerceIn(0.05f, 1f)
    }

    companion object {
        private const val TAG = "LiveAudioStreamManager"
        const val INPUT_SAMPLE_RATE = 16000
        const val OUTPUT_SAMPLE_RATE = 24000
        // 40 ms chunks at 16 kHz, 16-bit mono = 1280 bytes
        private const val INPUT_CHUNK_BYTES = 1280
    }
}
