package com.example.automation.whatsapp

import kotlinx.coroutines.flow.Flow

sealed interface RuleMatchResult {
    data class ExactReply(
        val rule: WhatsAppContactRuleEntity,
        val replyText: String
    ) : RuleMatchResult

    data class AiInstruction(
        val rule: WhatsAppContactRuleEntity,
        val instruction: String
    ) : RuleMatchResult

    data class BlockAutoReply(
        val rule: WhatsAppContactRuleEntity,
        val reason: String
    ) : RuleMatchResult

    data object NoMatch : RuleMatchResult
}

/**
 * Evaluates personalized contact-specific rules in strict priority order before
 * falling back to general Gemini AI reply generation.
 */
class ContactRuleEngine(private val dao: WhatsAppAutomationDao) {

    fun observeRules(): Flow<List<WhatsAppContactRuleEntity>> = dao.observeRules()

    suspend fun evaluate(senderName: String, conversationTitle: String, incomingText: String): RuleMatchResult {
        val enabledRules = dao.getEnabledRules()
        if (enabledRules.isEmpty()) return RuleMatchResult.NoMatch

        val matched = matchFromRules(
            rules = enabledRules,
            senderName = senderName,
            conversationTitle = conversationTitle,
            incomingText = incomingText
        )
        return matched
    }

    suspend fun upsertRule(rule: WhatsAppContactRuleEntity): Long = dao.upsertRule(rule)

    suspend fun setRuleEnabled(id: Long, enabled: Boolean) = dao.setRuleEnabled(id, enabled)

    suspend fun deleteRule(id: Long) = dao.deleteRule(id)

    suspend fun addSampleRahulRules() {
        val existing = dao.getEnabledRules()
        val alreadySeeded = existing.any {
            it.contactNamePattern.equals("Rahul", ignoreCase = true) &&
                it.triggerKeywords.contains("Boss kahan", ignoreCase = true)
        }
        if (alreadySeeded) return

        dao.upsertRule(
            WhatsAppContactRuleEntity(
                contactNamePattern = "Rahul",
                triggerKeywords = "Boss kahan, boss kahan hain, where is boss",
                customReply = "Boss abhi busy hain. Main unhe aapka message bata dungi.",
                behaviorMode = WhatsAppContactRuleEntity.MODE_EXACT_REPLY,
                priority = 95,
                enabled = true
            )
        )
        dao.upsertRule(
            WhatsAppContactRuleEntity(
                contactNamePattern = "Rahul",
                triggerKeywords = "call karne, call back, boss ko call",
                customReply = "Boss ne specifically bola hai call karne ke liye. Main unhe yaad dila dungi.",
                behaviorMode = WhatsAppContactRuleEntity.MODE_EXACT_REPLY,
                priority = 90,
                enabled = true
            )
        )
    }

    companion object {
        fun matchFromRules(
            rules: List<WhatsAppContactRuleEntity>,
            senderName: String,
            conversationTitle: String,
            incomingText: String
        ): RuleMatchResult {
            val sorted = rules.filter { it.enabled }
                .sortedWith(compareByDescending<WhatsAppContactRuleEntity> { it.priority }.thenByDescending { it.id })

            val normalizedSender = senderName.trim().lowercase()
            val normalizedTitle = conversationTitle.trim().lowercase()
            val normalizedText = incomingText.trim().lowercase()

            for (rule in sorted) {
                val pattern = rule.contactNamePattern.trim().lowercase()
                if (pattern.isEmpty()) continue

                val contactMatches = pattern == "*" ||
                    normalizedSender == pattern ||
                    normalizedTitle == pattern ||
                    normalizedSender.contains(pattern) ||
                    normalizedTitle.contains(pattern)

                if (!contactMatches) continue

                val keywords = rule.triggerKeywords
                    .split(',', '|', '\n')
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() }

                val triggerMatches = if (keywords.isEmpty()) {
                    true
                } else {
                    keywords.any { kw -> normalizedText.contains(kw) }
                }

                if (!triggerMatches) continue

                return when (rule.behaviorMode) {
                    WhatsAppContactRuleEntity.MODE_BLOCK_AUTO_REPLY -> {
                        RuleMatchResult.BlockAutoReply(
                            rule = rule,
                            reason = "Blocked by personalized rule for ${rule.contactNamePattern}"
                        )
                    }
                    WhatsAppContactRuleEntity.MODE_AI_INSTRUCTION -> {
                        val instr = rule.aiInstruction.ifBlank { rule.customReply }.trim()
                        if (instr.isNotEmpty()) {
                            RuleMatchResult.AiInstruction(rule, instr)
                        } else {
                            continue
                        }
                    }
                    else -> {
                        val reply = rule.customReply.trim()
                        if (reply.isNotEmpty()) {
                            RuleMatchResult.ExactReply(rule, reply)
                        } else {
                            continue
                        }
                    }
                }
            }
            return RuleMatchResult.NoMatch
        }
    }
}
