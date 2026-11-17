package com.example.actions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import com.example.commands.ActionExecutionResult
import com.example.commands.AssistantCommand
import com.example.commands.PendingConfirmation
import java.util.UUID

class CallingHandler(
    private val context: Context,
    private val contactsResolver: ContactsResolver
) {

    fun prepareCall(
        command: AssistantCommand,
        requireConfirmation: Boolean
    ): ActionExecutionResult {
        val targetName = command.target.ifBlank { command.query }.trim()
        if (targetName.isEmpty()) {
            return ActionExecutionResult.Unavailable("Contact name not provided for calling.")
        }

        // If user directly provided digits, use them without requiring Contacts permission
        val digitsOnly = targetName.filter { it.isDigit() || it == '+' }
        val resolvedMatch = if (digitsOnly.length >= 6 && digitsOnly.length == targetName.replace(" ", "").length) {
            ContactMatch(displayName = targetName, phoneNumber = digitsOnly)
        } else {
            if (!contactsResolver.hasContactsPermission()) {
                return ActionExecutionResult.NeedsPermission(
                    permission = Manifest.permission.READ_CONTACTS,
                    rationale = "Contacts permission is needed to find $targetName's phone number.",
                    retryCommand = command
                )
            }
            contactsResolver.findContactByName(targetName)
        }

        if (resolvedMatch == null) {
            return ActionExecutionResult.Unavailable("Could not find \"$targetName\" in Contacts.")
        }

        if (requireConfirmation) {
            return ActionExecutionResult.NeedsConfirmation(
                PendingConfirmation(
                    id = UUID.randomUUID().toString(),
                    command = command.copy(target = resolvedMatch.displayName),
                    summary = "Calling ${resolvedMatch.displayName}. Confirm?",
                    resolvedDetail = resolvedMatch.phoneNumber,
                    resolvedPhone = resolvedMatch.phoneNumber
                )
            )
        }

        return executeCall(resolvedMatch.displayName, resolvedMatch.phoneNumber)
    }

    fun executeCall(displayName: String, phoneNumber: String): ActionExecutionResult {
        val telUri = Uri.parse("tel:${Uri.encode(phoneNumber)}")
        val hasCallPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        val intent = if (hasCallPermission) {
            Intent(Intent.ACTION_CALL, telUri)
        } else {
            Intent(Intent.ACTION_DIAL, telUri)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return runCatching {
            context.startActivity(intent)
            val mode = if (hasCallPermission) "Calling" else "Opening dialer for"
            ActionExecutionResult.Executed("$mode $displayName ($phoneNumber)")
        }.getOrElse { err ->
            ActionExecutionResult.Unavailable(
                "Unable to start call: ${err.localizedMessage ?: "no phone app"}"
            )
        }
    }
}
