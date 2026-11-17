package com.example

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.ai.gemini.ChatTurn
import com.example.ai.gemini.SaraPersonaPromptBuilder
import com.example.core.security.UserProfile
import com.example.memory.ConversationHistoryRepository
import com.example.memory.MemoryCategory
import com.example.memory.MemoryRecord
import com.example.memory.RoomMemoryRepository
import com.example.memory.SaraDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `app name resolves to SARA`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("SARA", appName)
    }

    @Test
    fun `room memory repository saves deletes and clears records`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, SaraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = RoomMemoryRepository(db.memoryDao())
            repo.saveMemory(MemoryCategory.PREFERENCE, "Prefers concise Hinglish replies")
            repo.saveMemory(MemoryCategory.RELATIONSHIP, "Mummy is saved as Mom")

            val saved = repo.observeMemories().first()
            assertEquals(2, saved.size)

            repo.deleteMemory(saved.first().id)
            assertEquals(1, repo.observeMemories().first().size)

            repo.clearAllMemories()
            assertTrue(repo.observeMemories().first().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun `conversation history repository manages sessions turns and cascading delete`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, SaraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val historyRepo = ConversationHistoryRepository(db.conversationHistoryDao())
            historyRepo.ensureSession("sess-1", "Morning check-in", "LIVE")
            historyRepo.appendTurn("sess-1", isUser = true, text = "Hey Sara, good morning!", sourceMode = "LIVE", sessionTitleHint = "Morning check-in")
            historyRepo.appendTurn("sess-1", isUser = false, text = "Good morning! How is your day starting?", sourceMode = "LIVE")

            val sessions = historyRepo.observeSessions().first()
            assertEquals(1, sessions.size)
            assertEquals("LIVE", sessions.first().mode)

            val turns = historyRepo.observeTurnsForSession("sess-1").first()
            assertEquals(2, turns.size)
            assertTrue(turns[0].isUser)
            assertFalse(turns[1].isUser)

            val recent = historyRepo.getRecentContextTurns(limit = 5)
            assertEquals(listOf("Hey Sara, good morning!", "Good morning! How is your day starting?"), recent.map { it.text })

            historyRepo.deleteSession("sess-1")
            assertTrue(historyRepo.observeSessions().first().isEmpty())
            assertTrue(historyRepo.observeTurnsForSession("sess-1").first().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun `sara persona prompt builder injects profile language memories and context`() {
        val profile = UserProfile(
            name = "Aarav",
            preferredLanguage = "Hinglish",
            preferencesNote = "Keep responses energetic and brief"
        )
        val memories = listOf(
            MemoryRecord(
                id = 1L,
                category = MemoryCategory.PREFERENCE,
                content = "Loves filter coffee in the morning",
                source = "live_voice",
                createdAt = 1000L
            )
        )
        val recentTurns = listOf(
            ChatTurn(isUser = true, text = "Kal ka schedule remind karna"),
            ChatTurn(isUser = false, text = "Bilkul Aarav, kal subah 9 baje team sync hai.")
        )

        val liveInstruction = SaraPersonaPromptBuilder.buildLiveVoiceSystemInstruction(
            userProfile = profile,
            memories = memories,
            recentTurnsSummary = recentTurns
        )

        assertTrue(liveInstruction.contains("Sara"))
        assertTrue(liveInstruction.contains("Aarav"))
        assertTrue(liveInstruction.contains("Hinglish"))
        assertTrue(liveInstruction.contains("Keep responses energetic and brief"))
        assertTrue(liveInstruction.contains("Loves filter coffee in the morning"))
        assertTrue(liveInstruction.contains("team sync"))

        val autoInstruction = SaraPersonaPromptBuilder.buildLiveVoiceSystemInstruction(
            userProfile = UserProfile(preferredLanguage = UserProfile.AUTO_LANGUAGE),
            memories = emptyList(),
            recentTurnsSummary = emptyList()
        )
        assertTrue(autoInstruction.contains("Automatically detect and reply in the exact language"))
    }

    @Test
    fun `database migration 1 to 2 preserves memories and adds conversation tables`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration_test_sara.db"
        context.deleteDatabase(dbName)

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `memories` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `category` TEXT NOT NULL,
                                `content` TEXT NOT NULL,
                                `source` TEXT NOT NULL,
                                `createdAt` INTEGER NOT NULL
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            "INSERT INTO `memories` (`category`, `content`, `source`, `createdAt`) VALUES ('USER_INFO', 'Name is Kabir', 'manual', 12345)"
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        helper.writableDatabase.close()

        val migratedDb = Room.databaseBuilder(context, SaraDatabase::class.java, dbName)
            .addMigrations(SaraDatabase.MIGRATION_1_2, SaraDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        try {
            val memories = RoomMemoryRepository(migratedDb.memoryDao()).observeMemories().first()
            assertEquals(1, memories.size)
            assertEquals("Name is Kabir", memories.first().content)

            val historyRepo = ConversationHistoryRepository(migratedDb.conversationHistoryDao())
            historyRepo.ensureSession("migrated-s1", "Post-migration chat", "LIVE")
            historyRepo.appendTurn("migrated-s1", true, "Hello Sara", "LIVE")
            assertEquals(1, historyRepo.observeSessions().first().size)
            assertEquals(1, historyRepo.observeTurnsForSession("migrated-s1").first().size)
        } finally {
            migratedDb.close()
            context.deleteDatabase(dbName)
        }
    }
}
