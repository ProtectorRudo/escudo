package com.escudo.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Secondary fail-safe for protected-app sessions.
 *
 * The foreground service is the primary timer. This alarm exists so an OS process kill does not
 * silently turn a temporary release into an indefinite release. It intentionally uses an inexact
 * alarm: no exact-alarm special access is required, keeping onboarding friction at zero.
 */
object SessionExpiryScheduler {
    private const val REQUEST_CODE = 701

    fun schedule(context: Context, packageName: String, triggerAtEpochMs: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAtEpochMs,
            pendingIntent(context, packageName)
        )
    }

    fun scheduleRetry(context: Context, packageName: String, delayMs: Long = 15_000L) {
        schedule(context, packageName, System.currentTimeMillis() + delayMs)
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context, null))
    }

    private fun pendingIntent(context: Context, packageName: String?): PendingIntent {
        val intent = Intent(context, SessionExpiryReceiver::class.java).apply {
            packageName?.let { putExtra(SessionExpiryReceiver.EXTRA_PACKAGE, it) }
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
