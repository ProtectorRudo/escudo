package com.escudo.app.clone

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import com.escudo.app.apps.InstalledApp
import com.escudo.app.data.AppPreferences
import com.escudo.app.policy.PolicyController

/** Receives PackageInstaller's explicit result. Never exported. */
class CloneInstallResultActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    @Suppress("DEPRECATION")
    private fun handle(intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )
        val expectedPackage = intent.getStringExtra(EXTRA_EXPECTED_PACKAGE)

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            if (confirmation != null) {
                startActivity(confirmation)
                finish()
                return
            }
        }

        if (status == PackageInstaller.STATUS_SUCCESS && !expectedPackage.isNullOrBlank()) {
            // Register first so boot/recovery code also knows this package belongs to the vault.
            val prefs = AppPreferences(this)
            val label = runCatching {
                @Suppress("DEPRECATION")
                val info = packageManager.getApplicationInfo(expectedPackage, 0)
                packageManager.getApplicationLabel(info).toString().ifBlank { expectedPackage }
            }.getOrDefault(expectedPackage)
            prefs.rememberApp(InstalledApp(expectedPackage, label))
            prefs.setSelectedPackages(prefs.selectedPackages() + expectedPackage)
            prefs.markPersonalCopyPending(expectedPackage)

            // A newly cloned sensitive app enters Escudo closed immediately.
            PolicyController(this).protect(expectedPackage)
        }
        finish()
    }

    companion object {
        const val EXTRA_EXPECTED_PACKAGE = "expected_package"
    }
}
