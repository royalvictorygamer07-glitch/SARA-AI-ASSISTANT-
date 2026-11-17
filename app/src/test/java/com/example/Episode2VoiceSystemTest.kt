package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.security.CredentialStore
import com.example.core.security.UserProfile
import com.example.voice.audio.AudioCaptureCoordinator
import com.example.voice.audio.AudioCaptureOwner
import com.example.voice.audio.VadEvent
import com.example.voice.audio.VoiceActivityDetector
import com.example.voice.wake.VoskModelInspector
import com.example.voice.wake.VoskWakeWordEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Episode2VoiceSystemTest {

    @Test
    fun `audio capture coordinator preempts wake listening for gemini live and blocks duplicate sessions`() {
        val coordinator = AudioCaptureCoordinator()
        var wakePreempted = false

        assertTrue(coordinator.acquire(AudioCaptureOwner.WAKE_LISTENING) { wakePreempted = true })
        assertEquals(AudioCaptureOwner.WAKE_LISTENING, coordinator.currentOwner.value)

        // Foreground Gemini Live session should cleanly preempt passive wake listening
        assertTrue(coordinator.acquire(AudioCaptureOwner.GEMINI_LIVE))
        assertTrue(wakePreempted)
        assertEquals(AudioCaptureOwner.GEMINI_LIVE, coordinator.currentOwner.value)

        // Mic test cannot steal active Gemini Live mic capture
        assertFalse(coordinator.acquire(AudioCaptureOwner.MIC_TEST))

        coordinator.release(AudioCaptureOwner.GEMINI_LIVE)
        assertEquals(AudioCaptureOwner.NONE, coordinator.currentOwner.value)
    }

    @Test
    fun `voice activity detector detects speech start and trailing silence end`() {
        val vad = VoiceActivityDetector(
            speechStartRatio = 2.0,
            minSpeechRms = 400.0,
            startConfirmFrames = 2,
            endSilenceHangoverFrames = 4
        )
        val quietFrame = ByteArray(1280) // all zeros
        val speechFrame = generateSinePcm16(frequencyHz = 440.0, amplitude = 4000, sampleCount = 640)

        assertEquals(VadEvent.SILENCE, vad.processFrame(quietFrame).first)
        assertEquals(VadEvent.SILENCE, vad.processFrame(speechFrame).first) // frame 1 of 2
        assertEquals(VadEvent.SPEECH_STARTED, vad.processFrame(speechFrame).first) // frame 2 -> started
        assertEquals(VadEvent.SPEECH_ACTIVE, vad.processFrame(speechFrame).first)

        // 3 silence frames stay within hangover, 4th triggers SPEECH_ENDED
        repeat(3) {
            assertEquals(VadEvent.SPEECH_ACTIVE, vad.processFrame(quietFrame).first)
        }
        assertEquals(VadEvent.SPEECH_ENDED, vad.processFrame(quietFrame).first)
    }

    @Test
    fun `vosk model inspector accurately validates present, grammar, full-graph, and incomplete models`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val emptyInspection = VoskModelInspector.inspect(context)
        assertFalse(emptyInspection.isReady)
        assertTrue(emptyInspection.missingFiles.isNotEmpty())

        val tempRoot = File(context.cacheDir, "vosk-model-small-en-in-test").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            File(tempRoot, "am").mkdirs()
            File(tempRoot, "conf").mkdirs()
            File(tempRoot, "graph").mkdirs()
            File(tempRoot, "am/final.mdl").writeBytes(ByteArray(1024))
            File(tempRoot, "conf/mfcc.conf").writeText("--sample-frequency=16000")
            File(tempRoot, "graph/Gr.fst").writeBytes(ByteArray(512))

            val grammarInspection = VoskModelInspector.validateDiskModelDir(tempRoot, "Test")
            assertTrue(grammarInspection.isReady)
            assertTrue(grammarInspection.supportsGrammarMode)
            assertEquals("English (India)", grammarInspection.detectedLanguageHint)

            // Replace Gr.fst with HCLG.fst -> still valid, but no grammar spotting
            File(tempRoot, "graph/Gr.fst").delete()
            File(tempRoot, "graph/HCLG.fst").writeBytes(ByteArray(512))
            val fullGraphInspection = VoskModelInspector.validateDiskModelDir(tempRoot, "Test")
            assertTrue(fullGraphInspection.isReady)
            assertFalse(fullGraphInspection.supportsGrammarMode)

            // Remove am/final.mdl -> incomplete
            File(tempRoot, "am/final.mdl").delete()
            val brokenInspection = VoskModelInspector.validateDiskModelDir(tempRoot, "Test")
            assertFalse(brokenInspection.isReady)
            assertTrue(brokenInspection.missingFiles.contains("am/final.mdl"))
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `vosk json parser extracts partial and final wake transcripts`() {
        assertEquals(
            "hey sara",
            VoskWakeWordEngine.extractRecognizedText("""{ "text" : "Hey Sara" }""")
        )
        assertEquals(
            "hey sarah open",
            VoskWakeWordEngine.extractRecognizedText("""{"partial": "Hey Sarah Open"}""")
        )
        assertEquals("", VoskWakeWordEngine.extractRecognizedText(null))
    }

    @Test
    fun `credential store persists wake settings and maps language locales`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = CredentialStore(context)

        assertEquals("en-US", UserProfile(preferredLanguage = "English").sttLocaleTag())
        assertEquals("hi-IN", UserProfile(preferredLanguage = "Hindi").sttLocaleTag())
        assertEquals("en-IN", UserProfile(preferredLanguage = "Hinglish").sttLocaleTag())

        store.updateWakeUpDetectionEnabled(true)
        store.updateWakeEnrolledCount(5)
        store.updateBargeInEnabled(false)

        val settings = store.behaviorSettings.first()
        assertTrue(settings.wakeUpDetectionEnabled)
        assertEquals(5, settings.wakeEnrolledCount)
        assertFalse(settings.bargeInEnabled)
    }

    private fun generateSinePcm16(frequencyHz: Double, amplitude: Int, sampleCount: Int): ByteArray {
        val out = ByteBuffer.allocate(sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            val angle = 2.0 * PI * frequencyHz * (i / 16000.0)
            val sample = (sin(angle) * amplitude).toInt().toShort()
            out.putShort(sample)
        }
        return out.array()
    }
}
