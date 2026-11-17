package com.example.memory

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomMemoryRepository(
    private val memoryDao: MemoryDao
) : MemoryStorageProvider {

    override val backendName: String = "Room SQLite (Local)"

    override fun observeMemories(): Flow<List<MemoryRecord>> =
        memoryDao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getContextMemories(limit: Int): List<MemoryRecord> =
        memoryDao.getRecent(limit).map { it.toDomain() }

    override suspend fun saveMemory(
        category: MemoryCategory,
        content: String,
        source: String
    ) {
        val clean = content.trim()
        if (clean.isEmpty()) return
        memoryDao.insert(
            MemoryEntity(
                category = category.name,
                content = clean,
                source = source
            )
        )
    }

    override suspend fun deleteMemory(id: Long) {
        memoryDao.deleteById(id)
    }

    override suspend fun clearAllMemories() {
        memoryDao.clearAll()
    }

    private fun MemoryEntity.toDomain(): MemoryRecord {
        val parsedCategory = runCatching { MemoryCategory.valueOf(category) }
            .getOrDefault(MemoryCategory.CONVERSATION)
        return MemoryRecord(
            id = id,
            category = parsedCategory,
            content = content,
            source = source,
            createdAt = createdAt
        )
    }
}
