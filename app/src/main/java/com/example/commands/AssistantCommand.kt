package com.example.commands

import com.example.memory.MemoryCategory

data class AssistantCommand(
    val category: ActionCategory = ActionCategory.CHAT,
    val spokenReply: String,
    val target: String = "",
    val query: String = "",
    val messageBody: String = "",
    val channel: String = "",
    val requiresConfirmation: Boolean = false,
    val memoryNote: String = "",
    val memoryCategory: MemoryCategory = MemoryCategory.CONVERSATION
)

data class PendingConfirmation(
    val id: String,
    val command: AssistantCommand,
    val summary: String,
    val resolvedDetail: String,
    val resolvedPhone: String = ""
)

sealed interface ActionExecutionResult {
    data class Executed(val statusMessage: String) : ActionExecutionResult
    data class NeedsConfirmation(val pending: PendingConfirmation) : ActionExecutionResult
    data class NeedsPermission(
        val permission: String,
        val rationale: String,
        val retryCommand: AssistantCommand
    ) : ActionExecutionResult
    data class Unavailable(val reason: String) : ActionExecutionResult
    data object NoOp : ActionExecutionResult
}
