package com.example.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.automation.whatsapp.WhatsAppActivityLogEntity
import com.example.automation.whatsapp.WhatsAppAutomationDao
import com.example.automation.whatsapp.WhatsAppContactProfileEntity
import com.example.automation.whatsapp.WhatsAppContactRuleEntity
import com.example.automation.whatsapp.WhatsAppMessageEntity

@Database(
    entities = [
        MemoryEntity::class,
        ConversationSessionEntity::class,
        ConversationTurnEntity::class,
        WhatsAppContactProfileEntity::class,
        WhatsAppMessageEntity::class,
        WhatsAppContactRuleEntity::class,
        WhatsAppActivityLogEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class SaraDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao
    abstract fun conversationHistoryDao(): ConversationHistoryDao
    abstract fun whatsAppAutomationDao(): WhatsAppAutomationDao

    companion object {
        @Volatile
        private var instance: SaraDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `conversation_sessions` (
                        `sessionId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `mode` TEXT NOT NULL,
                        `startedAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`sessionId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `conversation_turns` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `isUser` INTEGER NOT NULL,
                        `text` TEXT NOT NULL,
                        `sourceMode` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        FOREIGN KEY(`sessionId`) REFERENCES `conversation_sessions`(`sessionId`) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_conversation_turns_sessionId` ON `conversation_turns` (`sessionId`)"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `wa_contact_profiles` (
                        `conversationKey` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `isGroupConversation` INTEGER NOT NULL,
                        `preferredLanguage` TEXT NOT NULL,
                        `senderPreferences` TEXT NOT NULL,
                        `customInstructions` TEXT NOT NULL,
                        `isRestricted` INTEGER NOT NULL,
                        `autoReplyCount` INTEGER NOT NULL,
                        `lastIncomingAt` INTEGER NOT NULL,
                        `lastReplyAt` INTEGER NOT NULL,
                        PRIMARY KEY(`conversationKey`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `wa_messages` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `conversationKey` TEXT NOT NULL,
                        `senderName` TEXT NOT NULL,
                        `isOutgoing` INTEGER NOT NULL,
                        `messageText` TEXT NOT NULL,
                        `detectedLanguage` TEXT NOT NULL,
                        `fingerprint` TEXT NOT NULL,
                        `replySource` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_wa_messages_conversationKey` ON `wa_messages` (`conversationKey`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_wa_messages_fingerprint` ON `wa_messages` (`fingerprint`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `wa_contact_rules` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `contactNamePattern` TEXT NOT NULL,
                        `triggerKeywords` TEXT NOT NULL,
                        `customReply` TEXT NOT NULL,
                        `aiInstruction` TEXT NOT NULL,
                        `behaviorMode` TEXT NOT NULL,
                        `priority` INTEGER NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `wa_activity_logs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `conversationKey` TEXT NOT NULL,
                        `senderName` TEXT NOT NULL,
                        `eventType` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `detail` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_wa_activity_logs_timestamp` ON `wa_activity_logs` (`timestamp`)"
                )
            }
        }

        fun getInstance(context: Context): SaraDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SaraDatabase::class.java,
                    "sara_memory.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
