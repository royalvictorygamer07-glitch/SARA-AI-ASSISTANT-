package com.example.voice.audio

import kotlin.math.log10
import kotlin.math.sqrt

enum class VadEvent {
    SILENCE,
    SPEECH_STARTED,
    SPEECH_ACTIVE,
    SPEECH_ENDED
}

data class AudioFrameMetrics(
    val rms: Double,
    val dbfs: Double,
    val normalizedAmplitude: Float,
    val zeroCrossingRate: Float
)

/**
 * Lightweight 16 kHz mono 16-bit PCM Voice Activity Detector (VAD) with adaptive ambient
 * noise floor estimation and speech hangover smoothing.
 */
class VoiceActivityDetector(
    private val speechStartRatio: Double = 2.6,
    private val minSpeechRms: Double = 420.0,
    private val startConfirmFrames: Int = 3,
    private val endSilenceHangoverFrames: Int = 18
) {
    private var noiseFloorRms = 180.0
    private var inSpeech = false
    private var consecutiveSpeechFrames = 0
    private var consecutiveSilenceFrames = 0

    fun reset() {
        noiseFloorRms = 180.0
        inSpeech = false
        consecutiveSpeechFrames = 0
        consecutiveSilenceFrames = 0
    }

    fun processFrame(pcm16Le: ByteArray, length: Int = pcm16Le.size): Pair<VadEvent, AudioFrameMetrics> {
        val metrics = analyzePcm16(pcm16Le, length)
        val threshold = maxOf(minSpeechRms, noiseFloorRms * speechStartRatio)
        val isFrameSpeech = metrics.rms >= threshold

        val event = if (!inSpeech) {
            if (isFrameSpeech) {
                consecutiveSpeechFrames++
                if (consecutiveSpeechFrames >= startConfirmFrames) {
                    inSpeech = true
                    consecutiveSilenceFrames = 0
                    VadEvent.SPEECH_STARTED
                } else {
                    VadEvent.SILENCE
                }
            } else {
                consecutiveSpeechFrames = 0
                // Adapt ambient noise floor slowly during silence
                noiseFloorRms = (noiseFloorRms * 0.92 + metrics.rms * 0.08).coerceIn(80.0, 1200.0)
                VadEvent.SILENCE
            }
        } else {
            if (isFrameSpeech) {
                consecutiveSilenceFrames = 0
                VadEvent.SPEECH_ACTIVE
            } else {
                consecutiveSilenceFrames++
                if (consecutiveSilenceFrames >= endSilenceHangoverFrames) {
                    inSpeech = false
                    consecutiveSpeechFrames = 0
                    consecutiveSilenceFrames = 0
                    VadEvent.SPEECH_ENDED
                } else {
                    VadEvent.SPEECH_ACTIVE
                }
            }
        }

        return event to metrics
    }

    companion object {
        fun analyzePcm16(pcm16Le: ByteArray, length: Int = pcm16Le.size): AudioFrameMetrics {
            val safeLen = length.coerceAtMost(pcm16Le.size)
            val samples = safeLen / 2
            if (samples <= 0) {
                return AudioFrameMetrics(rms = 0.0, dbfs = -96.0, normalizedAmplitude = 0f, zeroCrossingRate = 0f)
            }

            var sumSq = 0.0
            var zeroCrossings = 0
            var prevSample = 0
            var i = 0
            var idx = 0
            while (i + 1 < safeLen) {
                val lo = pcm16Le[i].toInt() and 0xFF
                val hi = pcm16Le[i + 1].toInt()
                val sample = ((hi shl 8) or lo).toShort().toInt()
                sumSq += sample.toDouble() * sample.toDouble()
                if (idx > 0 && ((sample >= 0 && prevSample < 0) || (sample < 0 && prevSample >= 0))) {
                    zeroCrossings++
                }
                prevSample = sample
                i += 2
                idx++
            }

            val rms = sqrt(sumSq / samples)
            val dbfs = if (rms > 1.0) {
                (20.0 * log10(rms / 32767.0)).coerceIn(-96.0, 0.0)
            } else {
                -96.0
            }
            val normalized = (rms / 6500.0).toFloat().coerceIn(0.04f, 1f)
            val zcr = zeroCrossings.toFloat() / samples.toFloat()

            return AudioFrameMetrics(
                rms = rms,
                dbfs = dbfs,
                normalizedAmplitude = normalized,
                zeroCrossingRate = zcr
            )
        }
    }
}
