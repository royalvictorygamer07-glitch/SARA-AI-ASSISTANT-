package com.example.voice.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import com.example.core.network.NetworkMonitor
import com.example.core.security.CredentialStore
import com.example.voice.audio.SaraAudioFocusManager
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.sin

class ElevenLabsTtsEngine(
    context: Context,
    private val credentialStore: CredentialStore,
    private val networkMonitor: NetworkMonitor,
    private val audioFocusManager: SaraAudioFocusManager? = null
) : TtsEngine {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val _isSpeaking = MutableStateFlow(false)
    override val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _playbackAmplitude = MutableStateFlow(0f)
    override val playbackAmplitude: StateFlow<Float> = _playbackAmplitude.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var amplitudeJob: Job? = null
    @Volatile
    private var stopRequested = false

    private val systemTtsSpeaker = SystemTtsSpeaker(
        context = appContext,
        audioFocusManager = audioFocusManager,
        onSpeakingStateChanged = { speaking, amp ->
            _isSpeaking.value = speaking
            _playbackAmplitude.value = amp
        }
    )

    override suspend fun speak(text: String, liveCallMode: Boolean): TtsResult {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return TtsResult.Completed

        stop()
        stopRequested = false

        val creds = credentialStore.elevenLabsCredentials.value
        if (!networkMonitor.isOnline() || !creds.isConfigured) {
            return systemTtsSpeaker.speak(cleanText, liveCallMode)
        }

        val audioFile = withContext(Dispatchers.IO) {
            fetchTtsAudio(
                text = cleanText,
                apiKey = creds.apiKey,
                voiceId = creds.voiceId,
                liveCallMode = liveCallMode
            )
        }

        if (stopRequested) {
            audioFile.getOrNull()?.delete()
            return TtsResult.Stopped
        }

        val file = audioFile.getOrElse {
            return systemTtsSpeaker.speak(cleanText, liveCallMode)
        }

        return try {
            playAudioFile(file)
        } finally {
            file.delete()
        }
    }

    suspend fun fetchAvailableVoices(apiKey: String): Result<List<ElevenLabsVoiceOption>> =
        withContext(Dispatchers.IO) {
            val cleanKey = apiKey.trim()
            if (cleanKey.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("ElevenLabs API key is empty."))
            }
            if (!networkMonitor.isOnline()) {
                return@withContext Result.failure(IOException("Internet connection check kijiye."))
            }

            val request = Request.Builder()
                .url("https://api.elevenlabs.io/v1/voices")
                .addHeader("xi-api-key", cleanKey)
                .get()
                .build()

            try {
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val msg = parseElevenLabsError(body, response.code)
                        return@withContext Result.failure(IllegalStateException(msg))
                    }
                    val root = JSONObject(body)
                    val arr = root.optJSONArray("voices")
                    val voices = mutableListOf<ElevenLabsVoiceOption>()
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            voices.add(
                                ElevenLabsVoiceOption(
                                    voiceId = obj.optString("voice_id"),
                                    name = obj.optString("name"),
                                    category = obj.optString("category", "premade")
                                )
                            )
                        }
                    }
                    Result.success(voices.filter { it.voiceId.isNotBlank() })
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed fetching ElevenLabs voices", e)
                Result.failure(e)
            }
        }

    private fun fetchTtsAudio(
        text: String,
        apiKey: String,
        voiceId: String,
        liveCallMode: Boolean
    ): Result<File> {
        val latencyParam = if (liveCallMode) "?optimize_streaming_latency=3" else ""
        val url = "https://api.elevenlabs.io/v1/text-to-speech/$voiceId$latencyParam"

        val payload = JSONObject().apply {
            put("text", text)
            put("model_id", "eleven_multilingual_v2")
            put(
                "voice_settings",
                JSONObject().apply {
                    put("stability", 0.5)
                    put("similarity_boost", 0.75)
                }
            )
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("xi-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "audio/mpeg")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string().orEmpty()
                    val msg = parseElevenLabsError(errorBody, response.code)
                    Log.e(TAG, "ElevenLabs HTTP ${response.code}: $msg")
                    return Result.failure(IllegalStateException(msg))
                }

                val bytes = response.body?.bytes()
                if (bytes == null || bytes.isEmpty()) {
                    return Result.failure(IllegalStateException("Empty audio response from ElevenLabs"))
                }

                val outFile = File(appContext.cacheDir, "sara_tts_${System.currentTimeMillis()}.mp3")
                outFile.writeBytes(bytes)
                Result.success(outFile)
            }
        } catch (e: IOException) {
            Log.e(TAG, "ElevenLabs network error", e)
            Result.failure(IOException("ElevenLabs network failure"))
        } catch (e: Exception) {
            Log.e(TAG, "ElevenLabs unexpected error", e)
            Result.failure(e)
        }
    }

    private suspend fun playAudioFile(file: File): TtsResult =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val player = MediaPlayer()
                mediaPlayer = player
                try {
                    val usage = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        AudioAttributes.USAGE_ASSISTANT
                    } else {
                        AudioAttributes.USAGE_MEDIA
                    }
                    player.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(usage)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    player.setDataSource(file.absolutePath)
                    player.setOnPreparedListener { mp ->
                        if (stopRequested) {
                            cleanupPlayer()
                            if (cont.isActive) cont.resume(TtsResult.Stopped)
                        } else {
                            audioFocusManager?.requestAssistantFocus(useVoiceCommunicationUsage = false) {
                                stop()
                            }
                            _isSpeaking.value = true
                            startSpeechWaveformEnvelope()
                            mp.start()
                        }
                    }
                    player.setOnCompletionListener {
                        cleanupPlayer()
                        if (cont.isActive) {
                            cont.resume(if (stopRequested) TtsResult.Stopped else TtsResult.Completed)
                        }
                    }
                    player.setOnErrorListener { _, what, extra ->
                        Log.e(TAG, "MediaPlayer error what=$what extra=$extra")
                        cleanupPlayer()
                        if (cont.isActive) {
                            cont.resume(
                                TtsResult.Error(
                                    userMessage = "Sir, voice service temporarily unavailable hai.",
                                    diagnostic = "Audio playback error ($what/$extra)"
                                )
                            )
                        }
                        true
                    }
                    cont.invokeOnCancellation {
                        stopRequested = true
                        cleanupPlayer()
                    }
                    player.prepareAsync()
                } catch (e: CancellationException) {
                    cleanupPlayer()
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "MediaPlayer setup failed", e)
                    cleanupPlayer()
                    if (cont.isActive) {
                        cont.resume(
                            TtsResult.Error(
                                userMessage = "Sir, voice service temporarily unavailable hai.",
                                diagnostic = e.localizedMessage ?: "Audio setup error"
                            )
                        )
                    }
                }
            }
        }

    private fun startSpeechWaveformEnvelope() {
        amplitudeJob?.cancel()
        amplitudeJob = scope.launch {
            var phase = 0f
            while (isActive && _isSpeaking.value) {
                phase += 0.35f
                val envelope = (0.45f + 0.35f * abs(sin(phase)) + 0.2f * abs(sin(phase * 2.3f)))
                    .coerceIn(0.15f, 1f)
                _playbackAmplitude.value = envelope
                delay(45L)
            }
            _playbackAmplitude.value = 0f
        }
    }

    override fun stop() {
        stopRequested = true
        systemTtsSpeaker.stop()
        cleanupPlayer()
    }

    override fun release() {
        stop()
        systemTtsSpeaker.release()
        amplitudeJob?.cancel()
    }

    private fun cleanupPlayer() {
        amplitudeJob?.cancel()
        amplitudeJob = null
        _isSpeaking.value = false
        _playbackAmplitude.value = 0f
        runCatching {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                reset()
                release()
            }
        }
        mediaPlayer = null
        audioFocusManager?.abandonFocus()
    }

    private fun parseElevenLabsError(body: String, code: Int): String {
        val detail = runCatching {
            val json = JSONObject(body)
            val detailObj = json.optJSONObject("detail")
            detailObj?.optString("message") ?: json.optString("detail")
        }.getOrNull().orEmpty()
        return when (code) {
            401 -> "Invalid ElevenLabs API key (HTTP 401)"
            402, 429 -> "ElevenLabs quota or rate limit exceeded (HTTP $code)"
            404 -> "Selected ElevenLabs Voice ID not found (HTTP 404)"
            else -> detail.ifBlank { "ElevenLabs HTTP $code" }
        }
    }

    companion object {
        private const val TAG = "ElevenLabsTtsEngine"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
