package com.example.commands

/**
 * Pure Kotlin detector for voice power-state transitions ("Sara sleep" / "Hey Sara")
 * and device-action command heuristics (used both in Classic and Gemini Live modes).
 */
object VoiceControlIntentDetector {

    private val punctuationRegex = Regex("[^a-z0-9\\s]")
    private val multiSpaceRegex = Regex("\\s+")

    private val sleepPhrases = listOf(
        "sara sleep",
        "sarah sleep",
        "sleep sara",
        "sleep sarah",
        "sleep mode",
        "sleep mod",
        "go to sleep",
        "sara go to sleep",
        "sara so jao",
        "so jao sara",
        "so jao",
        "sara soja",
        "soja sara",
        "sara offline",
        "offline sara",
        "offline ho jao",
        "sara rest",
        "rest mode",
        "band ho jao",
        "sara band ho jao",
        "chup ho jao",
        "sara chup"
    )

    private val wakePhrases = listOf(
        "hey sara",
        "hey sarah",
        "he sara",
        "hai sara",
        "hi sara",
        "hi sarah",
        "hello sara",
        "hello sarah",
        "ok sara",
        "okay sara",
        "wake up sara",
        "sara wake up",
        "sara online",
        "online sara",
        "online aa jao",
        "sara online aa jao",
        "sara utho",
        "utho sara",
        "sara listen",
        "sun rahi ho sara",
        "sara suno",
        "oye sara",
        "a sara",
        "eh sara"
    )

    private val deviceActionCues = listOf(
        "open ",
        " open",
        "kholo",
        "khol ",
        "launch ",
        "start ",
        "chalu karo",
        "chala do",
        "chalao",
        "youtube",
        "whatsapp",
        "instagram",
        "chrome",
        "spotify",
        "gmail",
        "google maps",
        "maps kholo",
        "settings",
        "camera",
        "call ",
        "phone karo",
        "ring ",
        "dial ",
        "message ",
        "msg ",
        "bhejo",
        "send ",
        "sms "
    )

    fun normalize(raw: String): String =
        raw.lowercase()
            .replace(punctuationRegex, " ")
            .replace(multiSpaceRegex, " ")
            .trim()

    fun isSleepCommand(rawText: String): Boolean {
        val norm = normalize(rawText)
        if (norm.isEmpty()) return false
        if (sleepPhrases.any { norm == it || norm.contains(it) }) {
            // Avoid matching inside long unrelated explanations unless clearly commanding sleep
            val words = norm.split(' ')
            if (words.size <= 7) return true
            if (norm.startsWith("sara ") || norm.startsWith("sarah ") || norm.contains("sleep mode")) {
                return true
            }
        }
        return false
    }

    /**
     * Checks whether [rawText] contains a wake-up phrase ("Hey Sara", "Sara online", etc.).
     * When [allowClippedWakeWord] is true (used after VAD->STT handoff in Sleep fallback mode),
     * short utterances ending with or equal to "sara"/"sarah" are also accepted in case the
     * first 100ms ("Hey") happened during microphone handoff.
     */
    fun isWakeCommand(rawText: String, allowClippedWakeWord: Boolean = false): Boolean {
        val norm = normalize(rawText)
        if (norm.isEmpty()) return false
        if (wakePhrases.any { norm == it || norm.contains(it) }) {
            return true
        }
        if (allowClippedWakeWord) {
            val words = norm.split(' ')
            if (words.size <= 3 && (words.contains("sara") || words.contains("sarah"))) {
                return true
            }
        }
        return false
    }

    /**
     * Heuristic check used in Gemini Live voice sessions to detect whether a spoken user turn
     * intends to execute an Android app/call/message/navigation action rather than pure chat.
     */
    fun looksLikeDeviceActionCommand(rawText: String): Boolean {
        val norm = normalize(rawText)
        if (norm.isEmpty() || isSleepCommand(norm)) return false
        return deviceActionCues.any { cue ->
            norm.contains(cue.trim()) && (norm.contains(cue) || norm.startsWith(cue.trim()) || norm.endsWith(cue.trim()))
        }
    }
}
