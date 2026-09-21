package com.escudo.app.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.escudo.app.R
import com.escudo.app.data.AppPreferences
import com.escudo.app.policy.PolicyController

@SuppressLint("ForegroundServiceType")
class VaultGuardService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: AppPreferences
    private var packageNameToRelock: String? = null
    private var intentionalRelock = false

    private val securityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF,
                Intent.ACTION_SHUTDOWN -> relockNow()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(this)
        ContextCompat.registerReceiver(
            this,
            securityReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SHUTDOWN)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RELOCK_NOW) {
            relockNow()
            return START_NOT_STICKY
        }

        val incomingPackage = intent?.getStringExtra(EXTRA_PACKAGE)
        if (incomingPackage != null) {
            intentionalRelock = false
            packageNameToRelock = incomingPackage
            // armAndStart() persists the session before the target Activity is launched. Keep that
            // deadline if present instead of creating a race-dependent second deadline here.
            if (prefs.activeSessionPackage != incomingPackage ||
                prefs.activeSessionUntilEpochMs <= System.currentTimeMillis()
            ) {
                prefs.activeSessionPackage = incomingPackage
                prefs.activeSessionUntilEpochMs = System.currentTimeMillis() + DEFAULT_TIMEOUT_MS
                SessionExpiryScheduler.schedule(this, incomingPackage, prefs.activeSessionUntilEpochMs)
            }
        } else {
            packageNameToRelock = prefs.activeSessionPackage
        }

        val pkg = packageNameToRelock
        if (pkg == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        scheduleInProcessRelock()
        return START_STICKY
    }

    private fun scheduleInProcessRelock() {
        val remaining = prefs.activeSessionUntilEpochMs - System.currentTimeMillis()
        handler.removeCallbacksAndMessages(null)
        if (remaining <= 0L) {
            relockNow()
        } else {
            handler.postDelayed(::relockNow, remaining.coerceAtMost(DEFAULT_TIMEOUT_MS))
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_escudo)
        .setContentTitle("Escudo")
        .setContentText("Sesión protegida activa · cierre automático")
        .setSilent(true)
        .setShowWhen(false)
        .setVisibility(NotificationCompat.VISIBILITY_SECRET)
        .setOngoing(true)
        .addAction(
            0,
            "Cerrar ahora",
            PendingIntent.getService(
                this,
                402,
                Intent(this, VaultGuardService::class.java).setAction(ACTION_RELOCK_NOW),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    private fun relockNow() {
        val pkg = packageNameToRelock ?: prefs.activeSessionPackage
        if (pkg == null) {
            intentionalRelock = true
            prefs.clearActiveSession()
            SessionExpiryScheduler.cancel(this)
            stopSelf()
            return
        }

        val result = PolicyController(this).protect(pkg)
        if (result.isSuccess) {
            // Only forget the open session AFTER Android confirms hidden+suspended again.
            intentionalRelock = true
            prefs.clearActiveSession()
            SessionExpiryScheduler.cancel(this)
            packageNameToRelock = null
            stopSelf()
        } else {
            // Fail closed in bookkeeping: preserve the session marker and keep retrying.
            intentionalRelock = false
            prefs.activeSessionPackage = pkg
            SessionExpiryScheduler.scheduleRetry(this, pkg, RETRY_DELAY_MS)
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed(::relockNow, RETRY_DELAY_MS)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        relockNow()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(securityReceiver) }

        if (!intentionalRelock) {
            val pkg = packageNameToRelock ?: prefs.activeSessionPackage
            if (pkg != null) {
                val result = PolicyController(this).protect(pkg)
                if (result.isSuccess) {
                    prefs.clearActiveSession()
                    SessionExpiryScheduler.cancel(this)
                } else {
                    // Never erase the only record that tells future receivers/boots what to close.
                    prefs.activeSessionPackage = pkg
                    SessionExpiryScheduler.scheduleRetry(this, pkg, RETRY_DELAY_MS)
                }
            }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Sesiones protegidas",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "escudo_active_session"
        private const val NOTIFICATION_ID = 401
        private const val EXTRA_PACKAGE = "package"
        private const val ACTION_RELOCK_NOW = "com.escudo.app.action.RELOCK_NOW"
        private const val DEFAULT_TIMEOUT_MS = 120_000L
        private const val RETRY_DELAY_MS = 5_000L

        /**
         * Arms fail-safe state synchronously BEFORE Android is allowed to leave Escudo.
         * This closes the release->service-start race: even if service startup crashes, the OS alarm
         * and persisted package marker already know what must be closed.
         */
        fun armAndStart(context: Context, packageName: String): Result<Unit> = runCatching {
            val prefs = AppPreferences(context)
            val deadline = System.currentTimeMillis() + DEFAULT_TIMEOUT_MS
            prefs.activeSessionPackage = packageName
            prefs.activeSessionUntilEpochMs = deadline
            SessionExpiryScheduler.schedule(context, packageName, deadline)

            val intent = Intent(context, VaultGuardService::class.java)
                .putExtra(EXTRA_PACKAGE, packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
