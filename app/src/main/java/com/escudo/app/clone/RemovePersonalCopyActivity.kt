package com.escudo.app.clone

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * Runs in the personal profile and delegates removal to Android's official uninstall UI.
 * The request must carry the secret established during managed-profile provisioning.
 */
class RemovePersonalCopyActivity : Activity() {
    private var packageNameToRemove: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action != ACTION_REMOVE_PERSONAL_COPY ||
            !BridgeSecretStore(this).matches(intent.getStringExtra(BridgeSecretStore.EXTRA_PAIRING_SECRET))
        ) {
            fail()
            return
        }

        val pkg = intent.getStringExtra(EXTRA_PACKAGE)?.takeIf { it.isNotBlank() }
        if (pkg == null || pkg == packageName) {
            fail()
            return
        }
        packageNameToRemove = pkg

        val uninstall = Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:$pkg")).apply {
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
        startActivityForResult(uninstall, REQUEST_UNINSTALL)
    }

    @Deprecated("Activity result kept for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_UNINSTALL) return
        val pkg = packageNameToRemove
        val stillInstalled = pkg?.let(::isInstalled) ?: true
        val result = Intent().putExtra(EXTRA_PACKAGE, pkg)
        setResult(if (!stillInstalled) RESULT_OK else RESULT_CANCELED, result)
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
        const val ACTION_REMOVE_PERSONAL_COPY = "com.escudo.app.action.REMOVE_PERSONAL_COPY"
        const val EXTRA_PACKAGE = "remove_personal_package"
        private const val REQUEST_UNINSTALL = 901
    }
}
