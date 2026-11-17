package com.example.automation.whatsapp

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "wa_contact_profiles")
data class WhatsAppContactProfileEntity(
    @PrimaryKey val conversationKey: String,
    val displayName: String,
    val isGroupConversation: Boolean = false,
    val preferredLanguage: String = "Automatic",
    val senderPreferences: String = "",
    val customInstructions: String = "",
    val isRestricted: Boolean = false,
    val autoReplyCount: Int = 0,
    val lastIncomingAt: Long = 0L,
    val lastReplyAt: Long = 0L
)

@Entity(
    tableName = "wa_messages",
    indices = [
        Index(value = ["conversationKey"]),
        Index(value = ["fingerprint"], unique = true)
    ]
)
data class WhatsAppMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationKey: String,
    val senderName: String,
    val isOutgoing: Boolean,
    val messageText: String,
    val detectedLanguage: String = "",
    val fingerprint: String,
    val replySource: String,
    val status: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "wa_contact_rules")
data class WhatsAppContactRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contactNamePattern: String,
    val triggerKeywords: String,
    val customReply: String,
    val aiInstruction: String = "",
    val behaviorMode: String = MODE_EXACT_REPLY,
    val priority: Int = 50,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val MODE_EXACT_REPLY = "EXACT_REPLY"
        const val MODE_AI_INSTRUCTION = "AI_INSTRUCTION"
        const val MODE_BLOCK_AUTO_REPLY = "BLOCK_AUTO_REPLY"
    }
}

@Entity(
    tableName = "wa_activity_logs",
    indices = [Index(value = ["timestamp"])]
)
data class WhatsAppActivityLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationKey: String,
    val senderName: String,
    val eventType: String,
    val status: String,
    val detail: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface WhatsAppAutomationDao {

    @Query("SELECT * FROM wa_contact_profiles ORDER BY lastIncomingAt DESC")
    fun observeContactProfiles(): Flow<List<WhatsAppContactProfileEntity>>

    @Query("SELECT * FROM wa_contact_profiles WHERE conversationKey = :conversationKey LIMIT 1")
    suspend fun getContactProfile(conversationKey: String): WhatsAppContactProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertContactProfile(profile: WhatsAppContactProfileEntity)

    @Query("UPDATE wa_contact_profiles SET autoReplyCount = 0 WHERE conversationKey = :conversationKey")
    suspend fun resetContactReplyCount(conversationKey: String)

    @Query("DELETE FROM wa_contact_profiles WHERE conversationKey = :conversationKey")
    suspend fun deleteContactProfile(conversationKey: String)

    @Query("DELETE FROM wa_contact_profiles")
    suspend fun clearAllContactProfiles()

    @Query("SELECT * FROM wa_messages WHERE conversationKey = :conversationKey ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessagesDesc(conversationKey: String, limit: Int): List<WhatsAppMessageEntity>

    @Query("SELECT * FROM wa_messages ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecentMessages(limit: Int = 100): Flow<List<WhatsAppMessageEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM wa_messages WHERE fingerprint = :fingerprint)")
    suspend fun hasFingerprint(fingerprint: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: WhatsAppMessageEntity): Long

    @Query("DELETE FROM wa_messages WHERE conversationKey = :conversationKey")
    suspend fun deleteMessagesForContact(conversationKey: String)

    @Query("DELETE FROM wa_messages")
    suspend fun clearAllMessages()

    @Query("SELECT * FROM wa_contact_rules ORDER BY priority DESC, id DESC")
    fun observeRules(): Flow<List<WhatsAppContactRuleEntity>>

    @Query("SELECT * FROM wa_contact_rules WHERE enabled = 1 ORDER BY priority DESC, id DESC")
    suspend fun getEnabledRules(): List<WhatsAppContactRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRule(rule: WhatsAppContactRuleEntity): Long

    @Query("UPDATE wa_contact_rules SET enabled = :enabled WHERE id = :id")
    suspend fun setRuleEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM wa_contact_rules WHERE id = :id")
    suspend fun deleteRule(id: Long)

    @Query("SELECT * FROM wa_activity_logs ORDER BY timestamp DESC LIMIT :limit")
    fun observeActivityLogs(limit: Int = 120): Flow<List<WhatsAppActivityLogEntity>>

    @Insert
    suspend fun insertActivityLog(log: WhatsAppActivityLogEntity): Long

    @Query("DELETE FROM wa_activity_logs WHERE id NOT IN (SELECT id FROM wa_activity_logs ORDER BY timestamp DESC LIMIT :keepCount)")
    suspend fun trimActivityLogs(keepCount: Int = 200)

    @Query("DELETE FROM wa_activity_logs")
    suspend fun clearActivityLogs()
}

data class ConversationContextBundle(
    val profile: WhatsAppContactProfileEntity,
    val recentMessagesAsc: List<WhatsAppMessageEntity>,
    val recentOutgoingReplies: List<String>
)

/**
 * Persistent Room-backed conversation memory for WhatsApp automation.
 * Strictly isolates each contact's history so one contact's private messages
 * are never leaked into another contact's reply context.
 */
class ConversationMemory(private val dao: WhatsAppAutomationDao) {

    fun observeContactProfiles(): Flow<List<WhatsAppContactProfileEntity>> =
        dao.observeContactProfiles()

    fun observeRecentMessages(limit: Int = 100): Flow<List<WhatsAppMessageEntity>> =
        dao.observeRecentMessages(limit)

    fun observeActivityLogs(limit: Int = 120): Flow<List<WhatsAppActivityLogEntity>> =
        dao.observeActivityLogs(limit)

    suspend fun isAlreadyProcessed(fingerprint: String): Boolean =
        dao.hasFingerprint(fingerprint)

    suspend fun getOrCreateProfile(
        conversationKey: String,
        displayName: String,
        isGroup: Boolean
    ): WhatsAppContactProfileEntity {
        val existing = dao.getContactProfile(conversationKey)
        if (existing != null) {
            if (existing.displayName != displayName && displayName.isNotBlank()) {
                val updated = existing.copy(displayName = displayName)
                dao.upsertContactProfile(updated)
                return updated
            }
            return existing
        }
        val created = WhatsAppContactProfileEntity(
            conversationKey = conversationKey,
            displayName = displayName.ifBlank { conversationKey },
            isGroupConversation = isGroup
        )
        dao.upsertContactProfile(created)
        return created
    }

    suspend fun recordIncomingMessage(
        conversationKey: String,
        senderName: String,
        displayName: String,
        isGroup: Boolean,
        messageText: String,
        fingerprint: String,
        timestamp: Long
    ): Boolean {
        val rowId = dao.insertMessage(
            WhatsAppMessageEntity(
                conversationKey = conversationKey,
                senderName = senderName,
                isOutgoing = false,
                messageText = messageText,
                fingerprint = fingerprint,
                replySource = "INCOMING",
                status = "RECEIVED",
                timestamp = timestamp
            )
        )
        if (rowId == -1L) return false

        val current = getOrCreateProfile(conversationKey, displayName, isGroup)
        dao.upsertContactProfile(
            current.copy(
                displayName = displayName.ifBlank { current.displayName },
                lastIncomingAt = maxOf(current.lastIncomingAt, timestamp)
            )
        )
        return true
    }

    suspend fun recordOutgoingReply(
        conversationKey: String,
        senderName: String,
        replyText: String,
        detectedLanguage: String,
        replySource: String,
        status: String,
        fingerprint: String,
        timestamp: Long = System.currentTimeMillis()
    ) {
        dao.insertMessage(
            WhatsAppMessageEntity(
                conversationKey = conversationKey,
                senderName = senderName,
                isOutgoing = true,
                messageText = replyText,
                detectedLanguage = detectedLanguage,
                fingerprint = fingerprint,
                replySource = replySource,
                status = status,
                timestamp = timestamp
            )
        )
        val existing = dao.getContactProfile(conversationKey) ?: return
        val increment = if (status == "SENT") 1 else 0
        dao.upsertContactProfile(
            existing.copy(
                preferredLanguage = detectedLanguage.ifBlank { existing.preferredLanguage },
                autoReplyCount = existing.autoReplyCount + increment,
                lastReplyAt = if (status == "SENT") timestamp else existing.lastReplyAt
            )
        )
    }

    suspend fun loadConversationContext(
        conversationKey: String,
        displayName: String,
        isGroup: Boolean,
        historyLimit: Int = 12
    ): ConversationContextBundle {
        val profile = getOrCreateProfile(conversationKey, displayName, isGroup)
        val desc = dao.getRecentMessagesDesc(conversationKey, historyLimit)
        val asc = desc.reversed()
        val recentOutgoing = asc.filter { it.isOutgoing && it.status == "SENT" }
            .map { it.messageText }
            .takeLast(5)
        return ConversationContextBundle(
            profile = profile,
            recentMessagesAsc = asc,
            recentOutgoingReplies = recentOutgoing
        )
    }

    suspend fun updateContactInstructions(
        conversationKey: String,
        senderPreferences: String,
        customInstructions: String,
        isRestricted: Boolean
    ) {
        val existing = dao.getContactProfile(conversationKey) ?: return
        dao.upsertContactProfile(
            existing.copy(
                senderPreferences = senderPreferences.trim(),
                customInstructions = customInstructions.trim(),
                isRestricted = isRestricted
            )
        )
    }

    suspend fun resetAutoReplyCount(conversationKey: String) {
        dao.resetContactReplyCount(conversationKey)
    }

    suspend fun clearContactMemory(conversationKey: String) {
        dao.deleteMessagesForContact(conversationKey)
        dao.deleteContactProfile(conversationKey)
    }

    suspend fun clearAllMemory() {
        dao.clearAllMessages()
        dao.clearAllContactProfiles()
    }

    suspend fun logActivity(
        conversationKey: String,
        senderName: String,
        eventType: String,
        status: String,
        detail: String
    ) {
        dao.insertActivityLog(
            WhatsAppActivityLogEntity(
                conversationKey = conversationKey,
                senderName = senderName,
                eventType = eventType,
                status = status,
                detail = detail,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    suspend fun pruneOldLogs(keepCount: Int = 200) {
        dao.trimActivityLogs(keepCount)
    }

    suspend fun clearLogs() {
        dao.clearActivityLogs()
    }
}
