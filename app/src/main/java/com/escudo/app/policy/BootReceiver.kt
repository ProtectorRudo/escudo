package com.escudo.app.policy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.escudo.app.data.AppPreferences

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_USER_UNLOCKED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // Nunca restauramos una sesión temporal después de reinicio/actualización.
                val prefs = AppPreferences(context)
                // Preserve the remembered package until Android confirms that every selected app
                // is protected again; lockAllSelected clears it only on complete success.
                val policy = PolicyController(context)
                policy.hardenEscudo()
                val failures = policy.lockAllSelected()
                if (failures.isEmpty()) prefs.clearActiveSession()
            }
        }
    }
}
