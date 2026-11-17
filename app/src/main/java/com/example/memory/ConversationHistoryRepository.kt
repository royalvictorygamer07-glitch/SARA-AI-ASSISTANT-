package com.example.memory

import kotlinx.coroutines.flow.Flow

class ConversationHistoryRepository(
    private val dao: ConversationHistoryDao
) {
    fun observeSessions(): Flow<List<ConversationSessionEntity>> = dao.observeSessions()

    fun observeTurnsForSession(sessionId: String): Flow<List<ConversationTurnEntity>> =
        dao.observeTurnsForSession(sessionId)

    suspend fun getRecentContextTurns(limit: Int = 10): List<ConversationTurnEntity> =
        dao.getRecentTurns(limit).reversed()

    suspend fun ensureSession(sessionId: String, initialTitle: String, mode: String) {
        val now = System.currentTimeMillis()
        dao.upsertSession(
            ConversationSessionEntity(
                sessionId = sessionId,
                title = initialTitle.take(60).ifBlank { "Conversation with Sara" },
                mode = mode,
                startedAt = now,
                updatedAt = now
            )
        )
    }

    suspend fun appendTurn(
        sessionId: String,
        isUser: Boolean,
        text: String,
        sourceMode: String,
        sessionTitleHint: String? = null
    ) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val now = System.currentTimeMillis()
        dao.insertTurn(
            ConversationTurnEntity(
                sessionId = sessionId,
                isUser = isUser,
                text = clean,
                sourceMode = sourceMode,
                timestamp = now
            )
        )
        if (!sessionTitleHint.isNullOrBlank()) {
            dao.updateSessionMetadata(
                sessionId = sessionId,
                title = sessionTitleHint.take(60),
                updatedAt = now
            )
        }
    }

    suspend fun deleteSession(sessionId: String) {
        dao.deleteSession(sessionId)
    }

    suspend fun clearAllSessions() {
        dao.clearAllSessions()
    }
}
