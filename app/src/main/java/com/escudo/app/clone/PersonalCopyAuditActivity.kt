package com.escudo.app.clone

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Runs only in the personal profile.
 *
 * The managed-profile Escudo instance asks this no-UI activity whether any package already
 * protected inside the vault is also installed in the personal profile. The provisioning-time
 * pairing secret authenticates the request. No account data, files, or app contents cross here.
 */
class PersonalCopyAuditActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action != ACTION_AUDIT_PERSONAL_COPIES ||
            !BridgeSecretStore(this).matches(intent.getStringExtra(BridgeSecretStore.EXTRA_PAIRING_SECRET))
        ) {
            fail()
            return
        }

        val requested = intent.getStringArrayListExtra(EXTRA_PACKAGES)
            .orEmpty()
            .asSequence()
            .filter { it.isNotBlank() && it != packageName }
            .distinct()
            .take(MAX_PACKAGES)
            .toList()

        val installed = requested.filter(::isInstalled)
        setResult(
            RESULT_OK,
            Intent().putStringArrayListExtra(EXTRA_INSTALLED_PACKAGES, ArrayList(installed))
        )
        finish()
    }

    private fun isInstalled(pkg: String): Boolean = runCatching {
        @Suppress("DEPRECATION")
        packageManager.getApplicationInfo(pkg, 0)
        true
    }.getOrDefault(false)

    private fun fail() {
        setResult(RESULT_CANCELED)
        finish()
    }

    companion object {
        const val ACTION_AUDIT_PERSONAL_COPIES = "com.escudo.app.action.AUDIT_PERSONAL_COPIES"
        const val EXTRA_PACKAGES = "audit_packages"
        const val EXTRA_INSTALLED_PACKAGES = "audit_installed_packages"
        private const val MAX_PACKAGES = 100
    }
}
