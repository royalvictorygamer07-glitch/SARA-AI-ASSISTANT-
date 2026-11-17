package com.example.voice.wake

import android.util.Log
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer

data class WakeDetectionResult(
    val detected: Boolean,
    val recognizedPhrase: String = ""
)

/**
 * Local offline Vosk wake-phrase engine ("Hey Sara").
 * Loads exclusively from a validated VoskModelInspectionResult path; never fakes recognition
 * when no compatible Vosk model is present.
 */
class VoskWakeWordEngine {

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var loadedPath: String? = null

    fun initialize(inspection: VoskModelInspectionResult): Result<Unit> {
        val modelPath = inspection.resolvedPath
        if (!inspection.isReady || modelPath.isNullOrBlank()) {
            return Result.failure(IllegalStateException(inspection.diagnosticMessage))
        }
        if (loadedPath == modelPath && model != null && recognizer != null) {
            return Result.success(Unit)
        }

        release()
        return runCatching {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            val loadedModel = Model(modelPath)
            val rec = if (inspection.supportsGrammarMode) {
                Recognizer(loadedModel, 16000f, WAKE_GRAMMAR_JSON)
            } else {
                Recognizer(loadedModel, 16000f)
            }
            model = loadedModel
            recognizer = rec
            loadedPath = modelPath
        }.onFailure { err ->
            Log.e(TAG, "Failed to load Vosk model from $modelPath", err)
            release()
        }
    }

    fun processPcm16(pcmBuffer: ByteArray, length: Int): WakeDetectionResult {
        val rec = recognizer ?: return WakeDetectionResult(false)
        val isFinal = runCatching { rec.acceptWaveForm(pcmBuffer, length) }.getOrDefault(false)
        val rawJson = if (isFinal) rec.result else rec.partialResult
        val text = extractRecognizedText(rawJson)
        if (text.isBlank()) return WakeDetectionResult(false)

        val matched = WAKE_PHRASE_VARIANTS.any { variant -> text.contains(variant) }
        return if (matched) {
            runCatching { rec.reset() }
            WakeDetectionResult(detected = true, recognizedPhrase = text)
        } else {
            WakeDetectionResult(false, text)
        }
    }

    fun reset() {
        runCatching { recognizer?.reset() }
    }

    fun release() {
        runCatching { recognizer?.close() }
        runCatching { model?.close() }
        recognizer = null
        model = null
        loadedPath = null
    }

    companion object {
        private const val TAG = "VoskWakeWordEngine"

        private const val WAKE_GRAMMAR_JSON =
            """["hey sara", "hey sarah", "hi sara", "ok sara", "sara", "[unk]"]"""

        private val WAKE_PHRASE_VARIANTS = listOf(
            "hey sara",
            "hey sarah",
            "hi sara",
            "ok sara",
            "okay sara"
        )

        private val TEXT_REGEX = Regex(""""(?:text|partial)"\s*:\s*"([^"]*)"""")

        internal fun extractRecognizedText(voskJson: String?): String {
            if (voskJson.isNullOrBlank()) return ""
            return TEXT_REGEX.find(voskJson)?.groupValues?.getOrNull(1)
                ?.trim()
                ?.lowercase()
                .orEmpty()
        }
    }
}
