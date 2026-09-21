package com.escudo.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.escudo.app.data.AppPreferences
import com.escudo.app.policy.PolicyController

/**
 * Process-death fail-safe. Android can recreate this receiver even if Escudo's process was killed.
 * We only erase the active-session marker after Android confirms the package is protected again.
 */
class SessionExpiryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val prefs = AppPreferences(context)
        val packageName = prefs.activeSessionPackage
            ?: intent?.getStringExtra(EXTRA_PACKAGE)
            ?: return

        val result = PolicyController(context).protect(packageName)
        if (result.isSuccess) {
            prefs.clearActiveSession()
            SessionExpiryScheduler.cancel(context)
        } else {
            // Preserve evidence of the open session and keep trying instead of failing open.
            prefs.activeSessionPackage = packageName
            SessionExpiryScheduler.scheduleRetry(context, packageName)
        }
    }

    companion object {
        const val EXTRA_PACKAGE = "package"
    }
}
