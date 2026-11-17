package com.example.memory

import kotlinx.coroutines.flow.Flow

data class MemoryRecord(
    val id: Long = 0,
    val category: MemoryCategory,
    val content: String,
    val source: String = "assistant",
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Modular memory storage abstraction.
 * Allows local Room storage to be paired with or swapped for cloud sync adapters
 * without modifying ViewModels, Gemini orchestration, or UI layers.
 */
interface MemoryStorageProvider {
    val backendName: String

    fun observeMemories(): Flow<List<MemoryRecord>>

    suspend fun getContextMemories(limit: Int = 15): List<MemoryRecord>

    suspend fun saveMemory(
        category: MemoryCategory,
        content: String,
        source: String = "assistant"
    )

    suspend fun deleteMemory(id: Long)

    suspend fun clearAllMemories()
}
