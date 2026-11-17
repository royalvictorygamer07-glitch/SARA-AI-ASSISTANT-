package com.example.actions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.commands.ActionExecutionResult
import com.example.commands.AssistantCommand
import com.example.commands.PendingConfirmation
import java.util.UUID

class MessagingHandler(
    private val context: Context,
    private val contactsResolver: ContactsResolver
) {

    fun prepareMessage(
        command: AssistantCommand,
        requireConfirmation: Boolean
    ): ActionExecutionResult {
        val contactName = command.target.trim()
        val messageBody = command.messageBody.ifBlank { command.query }.trim()
        val channel = command.channel.lowercase().ifBlank { "whatsapp" }

        if (contactName.isEmpty()) {
            return ActionExecutionResult.Unavailable("Recipient contact name is missing.")
        }
        if (messageBody.isEmpty()) {
            return ActionExecutionResult.Unavailable("Message text is empty.")
        }

        if (!contactsResolver.hasContactsPermission()) {
            return ActionExecutionResult.NeedsPermission(
                permission = Manifest.permission.READ_CONTACTS,
                rationale = "Contacts permission is needed to look up $contactName before preparing the message.",
                retryCommand = command
            )
        }

        val match = contactsResolver.findContactByName(contactName)
            ?: return ActionExecutionResult.Unavailable("Could not find \"$contactName\" in Contacts.")

        if (requireConfirmation) {
            val channelLabel = when {
                channel.contains("sms") -> "SMS"
                channel.contains("business") || channel.contains("w4b") -> "WhatsApp Business"
                else -> "WhatsApp"
            }
            return ActionExecutionResult.NeedsConfirmation(
                PendingConfirmation(
                    id = UUID.randomUUID().toString(),
                    command = command.copy(target = match.displayName, messageBody = messageBody, channel = channelLabel),
                    summary = "Send $channelLabel to ${match.displayName}?",
                    resolvedDetail = "\"$messageBody\" (${match.phoneNumber})",
                    resolvedPhone = match.phoneNumber
                )
            )
        }

        return executeMessageIntent(
            displayName = match.displayName,
            phoneNumber = match.phoneNumber,
            messageBody = messageBody,
            channel = channel
        )
    }

    fun executeMessageIntent(
        displayName: String,
        phoneNumber: String,
        messageBody: String,
        channel: String
    ): ActionExecutionResult {
        val cleanPhone = phoneNumber.replace("[^+\\d]".toRegex(), "")
        val normalizedChannel = channel.lowercase()
        if (normalizedChannel.contains("sms")) {
            val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$cleanPhone")).apply {
                putExtra("sms_body", messageBody)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return launchMessageIntent(smsIntent, "Opened SMS composer for $displayName")
                ?: ActionExecutionResult.Unavailable("No SMS app available on this device.")
        }

        // Order candidate packages cleanly based on whether user requested WhatsApp Business
        val candidatePackages = if (normalizedChannel.contains("business") || normalizedChannel.contains("w4b")) {
            listOf(PKG_WHATSAPP_BUSINESS, PKG_WHATSAPP)
        } else {
            listOf(PKG_WHATSAPP, PKG_WHATSAPP_BUSINESS)
        }

        val waDigits = cleanPhone.removePrefix("+")
        val waUri = Uri.parse("https://api.whatsapp.com/send?phone=$waDigits&text=${Uri.encode(messageBody)}")

        for (pkg in candidatePackages) {
            val appLabel = if (pkg == PKG_WHATSAPP_BUSINESS) "WhatsApp Business" else "WhatsApp"
            val waIntent = Intent(Intent.ACTION_VIEW, waUri).apply {
                setPackage(pkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (waIntent.resolveActivity(context.packageManager) != null) {
                launchMessageIntent(waIntent, "Prepared $appLabel message to $displayName")?.let { return it }
            }
        }

        for (pkg in candidatePackages) {
            val appLabel = if (pkg == PKG_WHATSAPP_BUSINESS) "WhatsApp Business" else "WhatsApp"
            val waShare = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, messageBody)
                setPackage(pkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (waShare.resolveActivity(context.packageManager) != null) {
                launchMessageIntent(waShare, "Opened $appLabel share sheet for $displayName")?.let { return it }
            }
        }

        val smsFallback = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$cleanPhone")).apply {
            putExtra("sms_body", messageBody)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launchMessageIntent(smsFallback, "WhatsApp/Business not installed; opened SMS for $displayName")
            ?: ActionExecutionResult.Unavailable("WhatsApp and WhatsApp Business are not installed on this device.")
    }

    private fun launchMessageIntent(intent: Intent, successMessage: String): ActionExecutionResult? {
        return when (BackgroundActivityStarter.launchActivity(context, intent, successMessage)) {
            BackgroundActivityStarter.LaunchOutcome.LaunchedDirectly ->
                ActionExecutionResult.Executed(successMessage)
            BackgroundActivityStarter.LaunchOutcome.NotificationFallbackPosted ->
                ActionExecutionResult.Executed("$successMessage (Tap notification to open)")
            is BackgroundActivityStarter.LaunchOutcome.Failed -> null
        }
    }

    companion object {
        const val PKG_WHATSAPP = "com.whatsapp"
        const val PKG_WHATSAPP_BUSINESS = "com.whatsapp.w4b"
    }
}
