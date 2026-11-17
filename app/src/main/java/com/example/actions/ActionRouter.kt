package com.example.actions

import com.example.commands.ActionCategory
import com.example.commands.ActionExecutionResult
import com.example.commands.AssistantCommand
import com.example.commands.PendingConfirmation

/**
 * Central modular router connecting parsed Gemini commands to Android action handlers.
 * New ActionCategory handlers can be plugged in without rewriting UI or Gemini layers.
 */
class ActionRouter(
    private val appLauncherHandler: AppLauncherHandler,
    private val youTubeHandler: YouTubeHandler,
    private val callingHandler: CallingHandler,
    private val messagingHandler: MessagingHandler
) {

    fun route(
        command: AssistantCommand,
        confirmBeforeExternalAction: Boolean,
        onNavigateToRoute: (String) -> Unit
    ): ActionExecutionResult {
        return when (command.category) {
            ActionCategory.CHAT -> ActionExecutionResult.NoOp
            ActionCategory.OPEN_APP -> appLauncherHandler.openApp(command)
            ActionCategory.PLAY_YOUTUBE -> youTubeHandler.playOrSearch(command)
            ActionCategory.CALL_CONTACT -> callingHandler.prepareCall(
                command = command,
                requireConfirmation = confirmBeforeExternalAction || command.requiresConfirmation
            )
            ActionCategory.SEND_MESSAGE -> messagingHandler.prepareMessage(
                command = command,
                requireConfirmation = confirmBeforeExternalAction || command.requiresConfirmation
            )
            ActionCategory.CAMERA -> {
                onNavigateToRoute("vision")
                ActionExecutionResult.Executed("Opened SARA Vision Camera")
            }
            ActionCategory.IMAGE_ANALYSIS -> {
                onNavigateToRoute("vision")
                ActionExecutionResult.Executed("Opened SARA Photo Analysis")
            }
            ActionCategory.MEMORY -> {
                onNavigateToRoute("memory")
                ActionExecutionResult.Executed("Opened SARA Memory Vault")
            }
            ActionCategory.SETTINGS -> {
                onNavigateToRoute("settings")
                ActionExecutionResult.Executed("Opened SARA Settings")
            }
            ActionCategory.SYSTEM_ACTION -> {
                onNavigateToRoute("actions")
                ActionExecutionResult.Executed("Opened SARA Action Center")
            }
        }
    }

    fun executeConfirmedAction(pending: PendingConfirmation): ActionExecutionResult {
        return when (pending.command.category) {
            ActionCategory.CALL_CONTACT -> callingHandler.executeCall(
                displayName = pending.command.target,
                phoneNumber = pending.resolvedPhone
            )
            ActionCategory.SEND_MESSAGE -> messagingHandler.executeMessageIntent(
                displayName = pending.command.target,
                phoneNumber = pending.resolvedPhone,
                messageBody = pending.command.messageBody,
                channel = pending.command.channel
            )
            else -> ActionExecutionResult.NoOp
        }
    }
}
