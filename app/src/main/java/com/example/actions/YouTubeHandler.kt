package com.example.actions

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.commands.ActionExecutionResult
import com.example.commands.AssistantCommand

class YouTubeHandler(private val context: Context) {

    fun playOrSearch(command: AssistantCommand): ActionExecutionResult {
        val searchQuery = command.query.ifBlank { command.target }.trim()
        if (searchQuery.isEmpty()) {
            val launch = context.packageManager.getLaunchIntentForPackage(YOUTUBE_PACKAGE)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return if (launch != null) {
                launchWithFallback(launch, "Opened YouTube")
            } else {
                openWebFallback("https://www.youtube.com", "Opened YouTube in browser")
            }
        }

        val ytSearchIntent = Intent(Intent.ACTION_SEARCH).apply {
            setPackage(YOUTUBE_PACKAGE)
            putExtra(SearchManager.QUERY, searchQuery)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val canLaunchApp = ytSearchIntent.resolveActivity(context.packageManager) != null
        if (canLaunchApp) {
            val direct = launchWithFallback(ytSearchIntent, "Searching YouTube for \"$searchQuery\"")
            if (direct is ActionExecutionResult.Executed) return direct
        }

        return openWebFallback(
            "https://www.youtube.com/results?search_query=${Uri.encode(searchQuery)}",
            "Searching YouTube for \"$searchQuery\""
        )
    }

    private fun openWebFallback(url: String, successMsg: String): ActionExecutionResult {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launchWithFallback(intent, successMsg)
    }

    private fun launchWithFallback(intent: Intent, successMsg: String): ActionExecutionResult {
        return when (val outcome = BackgroundActivityStarter.launchActivity(context, intent, successMsg)) {
            BackgroundActivityStarter.LaunchOutcome.LaunchedDirectly ->
                ActionExecutionResult.Executed(successMsg)
            BackgroundActivityStarter.LaunchOutcome.NotificationFallbackPosted ->
                ActionExecutionResult.Executed(
                    "$successMsg (Tap notification or grant Display Over Other Apps in Settings)"
                )
            is BackgroundActivityStarter.LaunchOutcome.Failed ->
                ActionExecutionResult.Unavailable(outcome.reason)
        }
    }

    companion object {
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    }
}
