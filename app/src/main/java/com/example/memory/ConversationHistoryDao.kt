package com.example.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationHistoryDao {

    @Query("SELECT * FROM conversation_sessions ORDER BY updatedAt DESC")
    fun observeSessions(): Flow<List<ConversationSessionEntity>>

    @Query("SELECT * FROM conversation_turns WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun observeTurnsForSession(sessionId: String): Flow<List<ConversationTurnEntity>>

    @Query("SELECT * FROM conversation_turns ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentTurns(limit: Int): List<ConversationTurnEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: ConversationSessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTurn(turn: ConversationTurnEntity): Long

    @Query("UPDATE conversation_sessions SET title = :title, updatedAt = :updatedAt WHERE sessionId = :sessionId")
    suspend fun updateSessionMetadata(sessionId: String, title: String, updatedAt: Long)

    @Query("DELETE FROM conversation_sessions WHERE sessionId = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Query("DELETE FROM conversation_sessions")
    suspend fun clearAllSessions()
}
