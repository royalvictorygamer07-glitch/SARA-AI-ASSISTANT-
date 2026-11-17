package com.example.actions

import android.Manifest
import android.app.ActivityOptions
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.R
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Coordinates Activity launches both when SARA is in the foreground and when SARA is
 * running in the background via WakeDetectionService.
 *
 * On Android 10–16 (targetSdk 36), background Activity starts succeed silently when the user
 * has granted SYSTEM_ALERT_WINDOW ("Display over other apps"). If that permission is not yet
 * granted while SARA is backgrounded, we still attempt the launch and also post a high-priority
 * tap-to-open notification fallback so the action is never silently lost.
 */
object BackgroundActivityStarter {

    private const val TAG = "BgActivityStarter"
    private const val CHANNEL_BG_ACTIONS = "sara_background_actions"
    private val foregroundFlag = AtomicBoolean(true)
    private val notificationSeq = AtomicInteger(5100)

    fun setAppForeground(inForeground: Boolean) {
        foregroundFlag.set(inForeground)
    }

    fun isAppInForeground(): Boolean = foregroundFlag.get()

    fun canLaunchFromBackground(context: Context): Boolean {
        if (foregroundFlag.get()) return true
        return Settings.canDrawOverlays(context)
    }

    sealed interface LaunchOutcome {
        data object LaunchedDirectly : LaunchOutcome
        data object NotificationFallbackPosted : LaunchOutcome
        data class Failed(val reason: String) : LaunchOutcome
    }

    fun launchActivity(
        context: Context,
        intent: Intent,
        actionLabel: String
    ): LaunchOutcome {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val inForeground = foregroundFlag.get()
        val hasOverlay = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

        if (inForeground || hasOverlay) {
            return runCatching {
                context.startActivity(intent)
                LaunchOutcome.LaunchedDirectly
            }.getOrElse { err ->
                Log.w(TAG, "Direct startActivity failed for $actionLabel", err)
                LaunchOutcome.Failed(err.localizedMessage ?: "Unsupported launch")
            }
        }

        // Background without SYSTEM_ALERT_WINDOW: try PendingIntent/startActivity AND post a
        // high-priority fallback notification so Android BAL restrictions never drop the action silently.
        val pendingIntent = buildLaunchPendingIntent(context, intent)
        var immediateStarted = false
        runCatching {
            pendingIntent.send()
            immediateStarted = true
        }
        if (!immediateStarted) {
            runCatching {
                context.startActivity(intent)
            }
        }

        val posted = postActionFallbackNotification(context, actionLabel, pendingIntent)
        return if (posted || immediateStarted) {
            LaunchOutcome.NotificationFallbackPosted
        } else {
            LaunchOutcome.Failed("Grant 'Display over other apps' in Settings to open $actionLabel directly from background.")
        }
    }

    private fun buildLaunchPendingIntent(context: Context, intent: Intent): PendingIntent {
        val code = notificationSeq.incrementAndGet()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val opts = ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
            PendingIntent.getActivity(
                context,
                code,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                opts.toBundle()
            )
        } else {
            PendingIntent.getActivity(
                context,
                code,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    private fun postActionFallbackNotification(
        context: Context,
        actionLabel: String,
        pendingIntent: PendingIntent
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_BG_ACTIONS,
                "SARA Background App Launch",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Tap to open apps launched by SARA while backgrounded"
            }
            nm.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_BG_ACTIONS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("SARA: $actionLabel")
            .setContentText("Tap to open now (enable Display Over Other Apps in Settings for hands-free launch)")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        nm.notify(notificationSeq.get(), notification)
        return true
    }
}
