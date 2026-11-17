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
                runCatching {
                    context.startActivity(launch)
                    ActionExecutionResult.Executed("Opened YouTube")
                }.getOrElse {
                    ActionExecutionResult.Unavailable("Could not open YouTube")
                }
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
            return runCatching {
                context.startActivity(ytSearchIntent)
                ActionExecutionResult.Executed("Searching YouTube for \"$searchQuery\"")
            }.getOrElse {
                openWebFallback(
                    "https://www.youtube.com/results?search_query=${Uri.encode(searchQuery)}",
                    "Searching YouTube for \"$searchQuery\""
                )
            }
        }

        return openWebFallback(
            "https://www.youtube.com/results?search_query=${Uri.encode(searchQuery)}",
            "Searching YouTube for \"$searchQuery\""
        )
    }

    private fun openWebFallback(url: String, successMsg: String): ActionExecutionResult {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            ActionExecutionResult.Executed(successMsg)
        }.getOrElse {
            ActionExecutionResult.Unavailable("No app available to open YouTube.")
        }
    }

    companion object {
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    }
}
