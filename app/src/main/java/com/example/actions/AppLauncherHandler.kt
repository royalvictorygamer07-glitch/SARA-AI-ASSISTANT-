package com.example.actions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import com.example.commands.ActionExecutionResult
import com.example.commands.AssistantCommand

class AppLauncherHandler(private val context: Context) {

    fun openApp(command: AssistantCommand): ActionExecutionResult {
        val requested = command.target.ifBlank { command.query }.trim()
        if (requested.isEmpty()) {
            return ActionExecutionResult.Unavailable("App name not specified.")
        }

        val normalized = requested.lowercase()
        if (normalized == "settings" || normalized == "android settings") {
            val intent = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return launchSafe(intent, "Opened Android Settings")
        }

        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = pm.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)

        val knownPackages = mapOf(
            "youtube" to "com.google.android.youtube",
            "whatsapp" to "com.whatsapp",
            "wa" to "com.whatsapp",
            "whatsapp business" to "com.whatsapp.w4b",
            "wa business" to "com.whatsapp.w4b",
            "business whatsapp" to "com.whatsapp.w4b",
            "w4b" to "com.whatsapp.w4b",
            "chrome" to "com.android.chrome",
            "maps" to "com.google.android.apps.maps",
            "google maps" to "com.google.android.apps.maps",
            "spotify" to "com.spotify.music",
            "gmail" to "com.google.android.gm",
            "instagram" to "com.instagram.android"
        )

        val mappedPackage = knownPackages[normalized]
        if (mappedPackage != null) {
            val launch = pm.getLaunchIntentForPackage(mappedPackage)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return launchSafe(launch, "Opened $requested")
            }
            if (mappedPackage == "com.whatsapp") {
                val businessLaunch = pm.getLaunchIntentForPackage("com.whatsapp.w4b")
                if (businessLaunch != null) {
                    businessLaunch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    return launchSafe(businessLaunch, "Opened WhatsApp Business")
                }
            }
        }

        val matchedResolve = activities.firstOrNull { resolveInfo ->
            val label = resolveInfo.loadLabel(pm).toString().lowercase()
            label == normalized || label.startsWith(normalized) || label.contains(normalized)
        }

        if (matchedResolve != null) {
            val pkg = matchedResolve.activityInfo.packageName
            val launch = pm.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val appLabel = matchedResolve.loadLabel(pm).toString()
                return launchSafe(launch, "Opened $appLabel")
            }
        }

        return ActionExecutionResult.Unavailable(
            "$requested is not installed or visible to SARA."
        )
    }

    private fun launchSafe(intent: Intent, successMessage: String): ActionExecutionResult {
        return when (val outcome = BackgroundActivityStarter.launchActivity(context, intent, successMessage)) {
            BackgroundActivityStarter.LaunchOutcome.LaunchedDirectly ->
                ActionExecutionResult.Executed(successMessage)
            BackgroundActivityStarter.LaunchOutcome.NotificationFallbackPosted ->
                ActionExecutionResult.Executed(
                    "$successMessage (Tap notification or grant Display Over Other Apps in Settings for direct background open)"
                )
            is BackgroundActivityStarter.LaunchOutcome.Failed ->
                ActionExecutionResult.Unavailable("Could not launch app: ${outcome.reason}")
        }
    }
}
