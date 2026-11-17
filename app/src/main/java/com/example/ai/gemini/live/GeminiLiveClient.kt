package com.example.ai.gemini.live

import android.util.Base64
import android.util.Log
import com.example.ai.gemini.ChatTurn
import com.example.ai.gemini.SaraPersonaPromptBuilder
import com.example.core.network.NetworkMonitor
import com.example.core.security.CredentialStore
import com.example.core.security.UserProfile
import com.example.memory.MemoryRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class LiveConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    LISTENING,
    SPEAKING,
    RECONNECTING,
    ERROR
}

sealed interface GeminiLiveEvent {
    data object Connected : GeminiLiveEvent
    data class InputTranscript(val text: String) : GeminiLiveEvent
    data class OutputTranscript(val text: String) : GeminiLiveEvent
    data object TurnComplete : GeminiLiveEvent
    data object Interrupted : GeminiLiveEvent
    data class Error(val userMessage: String, val diagnostic: String, val recoverable: Boolean) : GeminiLiveEvent
    data class Disconnected(val reason: String) : GeminiLiveEvent
}

/**
 * Real-time bidirectional WebSocket brain powered by Gemini Live API (BidiGenerateContent).
 * Streams raw 16kHz PCM microphone input and receives native 24kHz PCM audio + transcripts.
 */
class GeminiLiveClient(
    private val credentialStore: CredentialStore,
    private val networkMonitor: NetworkMonitor,
    private val audioStreamManager: LiveAudioStreamManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val wsClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Persistent streaming WebSocket
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val _connectionState = MutableStateFlow(LiveConnectionState.DISCONNECTED)
    val connectionState: StateFlow<LiveConnectionState> = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<GeminiLiveEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<GeminiLiveEvent> = _events.asSharedFlow()

    val micAmplitude: StateFlow<Float> = audioStreamManager.micAmplitude
    val playbackAmplitude: StateFlow<Float> = audioStreamManager.playbackAmplitude

    private var webSocket: WebSocket? = null
    private var setupCompleted = false
    private var manualDisconnect = false
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null

    private var cachedUserProfile: UserProfile = UserProfile()
    private var cachedMemories: List<MemoryRecord> = emptyList()
    private var cachedHistory: List<ChatTurn> = emptyList()

    fun startSession(
        userProfile: UserProfile,
        memories: List<MemoryRecord>,
        recentHistory: List<ChatTurn>
    ) {
        cachedUserProfile = userProfile
        cachedMemories = memories
        cachedHistory = recentHistory
        manualDisconnect = false
        reconnectAttempt = 0
        reconnectJob?.cancel()

        if (!networkMonitor.isOnline()) {
            _connectionState.value = LiveConnectionState.ERROR
            _events.tryEmit(
                GeminiLiveEvent.Error(
                    userMessage = "Internet connection check kijiye.",
                    diagnostic = "No active internet transport",
                    recoverable = false
                )
            )
            return
        }

        val creds = credentialStore.geminiCredentials.value
        if (!creds.isConfigured) {
            _connectionState.value = LiveConnectionState.ERROR
            _events.tryEmit(
                GeminiLiveEvent.Error(
                    userMessage = "Settings mein Gemini API key configure kijiye.",
                    diagnostic = "Gemini API key missing",
                    recoverable = false
                )
            )
            return
        }

        openWebSocket(isReconnect = false)
    }

    fun sendTextTurn(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val ws = webSocket ?: return
        if (!setupCompleted) return

        val msg = JSONObject().apply {
            put(
                "clientContent",
                JSONObject().apply {
                    put(
                        "turns",
                        JSONArray().put(
                            JSONObject().apply {
                                put("role", "user")
                                put("parts", JSONArray().put(JSONObject().put("text", clean)))
                            }
                        )
                    )
                    put("turnComplete", true)
                }
            )
        }
        ws.send(msg.toString())
    }

    fun interruptCurrentPlayback() {
        audioStreamManager.flushPlayback()
        audioStreamManager.markPlaybackIdle()
        if (setupCompleted && audioStreamManager.isCapturing.value) {
            _connectionState.value = LiveConnectionState.LISTENING
        }
    }

    fun stopSession(reason: String = "Session ended by user") {
        manualDisconnect = true
        reconnectJob?.cancel()
        reconnectJob = null
        setupCompleted = false
        audioStreamManager.stopAndRelease()
        runCatching { webSocket?.close(1000, reason) }
        webSocket = null
        _connectionState.value = LiveConnectionState.DISCONNECTED
        _events.tryEmit(GeminiLiveEvent.Disconnected(reason))
    }

    private fun openWebSocket(isReconnect: Boolean) {
        runCatching { webSocket?.cancel() }
        webSocket = null
        setupCompleted = false
        _connectionState.value = if (isReconnect) {
            LiveConnectionState.RECONNECTING
        } else {
            LiveConnectionState.CONNECTING
        }

        val creds = credentialStore.geminiCredentials.value
        val request = Request.Builder()
            .url(GEMINI_LIVE_WS_ENDPOINT)
            .addHeader("x-goog-api-key", creds.apiKey)
            .build()

        webSocket = wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val setupJson = buildSetupPayload(
                    liveModelName = creds.liveModelName,
                    voiceName = creds.liveVoiceName
                )
                val sent = webSocket.send(setupJson.toString())
                if (!sent) {
                    handleSocketFailure("Failed to send Gemini Live setup payload", recoverable = true)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleServerMessage(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                audioStreamManager.stopCapture()
                audioStreamManager.markPlaybackIdle()
                if (!manualDisconnect) {
                    val recoverable = code != 1008 && code != 1003 && code != 4001 && code != 4003
                    if (recoverable) {
                        scheduleReconnect("Connection closed ($code): $reason")
                    } else {
                        _connectionState.value = LiveConnectionState.ERROR
                        _events.tryEmit(
                            GeminiLiveEvent.Error(
                                userMessage = "Sir, Gemini Live session reject hua. API key ya model ID check kijiye.",
                                diagnostic = "WebSocket closed code=$code reason=$reason",
                                recoverable = false
                            )
                        )
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val httpCode = response?.code
                Log.e(TAG, "Gemini Live WebSocket failure (http=$httpCode)", t)
                val authFailure = httpCode == 401 || httpCode == 403
                val detail = buildString {
                    if (httpCode != null) append("HTTP $httpCode: ")
                    append(t.localizedMessage ?: "WebSocket failure")
                }
                handleSocketFailure(detail, recoverable = !authFailure)
            }
        })
    }

    private fun handleServerMessage(rawJson: String) {
        runCatching {
            val root = JSONObject(rawJson)

            if (root.has("setupComplete")) {
                setupCompleted = true
                reconnectAttempt = 0
                _connectionState.value = LiveConnectionState.CONNECTED
                _events.tryEmit(GeminiLiveEvent.Connected)
                beginMicStreaming()
                return
            }

            if (root.has("goAway")) {
                // Server warns connection is nearing session timeout; proactively reconnect
                if (!manualDisconnect) {
                    scheduleReconnect("Session timeout approaching, refreshing connection")
                }
                return
            }

            val serverContent = root.optJSONObject("serverContent") ?: return

            if (serverContent.optBoolean("interrupted", false)) {
                audioStreamManager.flushPlayback()
                _connectionState.value = LiveConnectionState.LISTENING
                _events.tryEmit(GeminiLiveEvent.Interrupted)
            }

            val inputTranscription = serverContent.optJSONObject("inputTranscription")
            val userText = inputTranscription?.optString("text", "").orEmpty()
            if (userText.isNotEmpty()) {
                _events.tryEmit(GeminiLiveEvent.InputTranscript(userText))
            }

            val outputTranscription = serverContent.optJSONObject("outputTranscription")
            val saraText = outputTranscription?.optString("text", "").orEmpty()
            if (saraText.isNotEmpty()) {
                _events.tryEmit(GeminiLiveEvent.OutputTranscript(saraText))
            }

            val modelTurn = serverContent.optJSONObject("modelTurn")
            val parts = modelTurn?.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    val inlineData = part.optJSONObject("inlineData")
                    if (inlineData != null) {
                        val mime = inlineData.optString("mimeType", "")
                        val dataB64 = inlineData.optString("data", "")
                        if (mime.startsWith("audio/pcm") && dataB64.isNotEmpty()) {
                            val pcmBytes = Base64.decode(dataB64, Base64.DEFAULT)
                            _connectionState.value = LiveConnectionState.SPEAKING
                            audioStreamManager.playPcm24k(pcmBytes)
                        }
                    } else if (part.has("text")) {
                        val textPart = part.optString("text", "")
                        if (textPart.isNotBlank()) {
                            _events.tryEmit(GeminiLiveEvent.OutputTranscript(textPart))
                        }
                    }
                }
            }

            if (serverContent.optBoolean("turnComplete", false)) {
                audioStreamManager.markPlaybackIdle()
                _connectionState.value = if (audioStreamManager.isCapturing.value) {
                    LiveConnectionState.LISTENING
                } else {
                    LiveConnectionState.CONNECTED
                }
                _events.tryEmit(GeminiLiveEvent.TurnComplete)
            }
        }.onFailure { err ->
            Log.w(TAG, "Malformed server frame ignored", err)
        }
    }

    private fun beginMicStreaming() {
        audioStreamManager.startCapture(
            onPcmChunk = { pcmChunk ->
                val ws = webSocket ?: return@startCapture
                if (!setupCompleted) return@startCapture
                val encoded = Base64.encodeToString(pcmChunk, Base64.NO_WRAP)
                val frame = JSONObject().apply {
                    put(
                        "realtimeInput",
                        JSONObject().apply {
                            put(
                                "mediaChunks",
                                JSONArray().put(
                                    JSONObject().apply {
                                        put("mimeType", "audio/pcm;rate=16000")
                                        put("data", encoded)
                                    }
                                )
                            )
                        }
                    )
                }
                ws.send(frame.toString())
            },
            onError = { micErr ->
                _connectionState.value = LiveConnectionState.ERROR
                _events.tryEmit(
                    GeminiLiveEvent.Error(
                        userMessage = micErr,
                        diagnostic = micErr,
                        recoverable = false
                    )
                )
                stopSession(micErr)
            }
        )
        if (audioStreamManager.isCapturing.value) {
            _connectionState.value = LiveConnectionState.LISTENING
        }
    }

    private fun handleSocketFailure(detail: String, recoverable: Boolean) {
        audioStreamManager.stopCapture()
        audioStreamManager.markPlaybackIdle()
        if (!manualDisconnect && recoverable && networkMonitor.isOnline()) {
            scheduleReconnect(detail)
        } else {
            _connectionState.value = LiveConnectionState.ERROR
            val friendly = if (!networkMonitor.isOnline()) {
                "Internet connection check kijiye."
            } else {
                "Sir, Gemini Live connect nahi ho pa raha."
            }
            _events.tryEmit(
                GeminiLiveEvent.Error(
                    userMessage = friendly,
                    diagnostic = detail,
                    recoverable = false
                )
            )
        }
    }

    private fun scheduleReconnect(reason: String) {
        if (manualDisconnect) return
        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            _connectionState.value = LiveConnectionState.ERROR
            _events.tryEmit(
                GeminiLiveEvent.Error(
                    userMessage = "Sir, Gemini Live session disconnect ho gaya. Dobara Start dabaiye.",
                    diagnostic = "Max reconnect attempts exhausted: $reason",
                    recoverable = false
                )
            )
            return
        }

        reconnectAttempt++
        _connectionState.value = LiveConnectionState.RECONNECTING
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(reconnectAttempt * 1200L)
            if (!manualDisconnect && networkMonitor.isOnline()) {
                openWebSocket(isReconnect = true)
            } else if (!networkMonitor.isOnline()) {
                _connectionState.value = LiveConnectionState.ERROR
                _events.tryEmit(
                    GeminiLiveEvent.Error(
                        userMessage = "Internet connection check kijiye.",
                        diagnostic = "Offline during reconnect",
                        recoverable = false
                    )
                )
            }
        }
    }

    private fun buildSetupPayload(liveModelName: String, voiceName: String): JSONObject {
        val modelPath = if (liveModelName.startsWith("models/")) {
            liveModelName
        } else {
            "models/$liveModelName"
        }
        val systemInstructionText = SaraPersonaPromptBuilder.buildLiveVoiceSystemInstruction(
            userProfile = cachedUserProfile,
            memories = cachedMemories,
            recentTurnsSummary = cachedHistory
        )

        return JSONObject().apply {
            put(
                "setup",
                JSONObject().apply {
                    put("model", modelPath)
                    put(
                        "generationConfig",
                        JSONObject().apply {
                            put("responseModalities", JSONArray().put("AUDIO"))
                            put(
                                "speechConfig",
                                JSONObject().apply {
                                    put(
                                        "voiceConfig",
                                        JSONObject().apply {
                                            put(
                                                "prebuiltVoiceConfig",
                                                JSONObject().put("voiceName", voiceName)
                                            )
                                        }
                                    )
                                }
                            )
                        }
                    )
                    put("inputAudioTranscription", JSONObject())
                    put("outputAudioTranscription", JSONObject())
                    put(
                        "systemInstruction",
                        JSONObject().put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", systemInstructionText))
                        )
                    )
                }
            )
        }
    }

    companion object {
        private const val TAG = "GeminiLiveClient"
        private const val MAX_RECONNECT_ATTEMPTS = 2
        private const val GEMINI_LIVE_WS_ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    }
}
