package com.escudo.app.policy

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import com.escudo.app.clone.BridgeSecretStore

/**
 * Android 12+ provisioning compatibility.
 *
 * Escudo only chooses managed-profile mode here. It never opts the user's whole personal phone
 * into fully-managed Device Owner provisioning from this activity.
 */
class ProvisioningModeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        BridgeSecretStore(this).importFromProvisioning(intent)

        when (intent?.action) {
            DevicePolicyManager.ACTION_GET_PROVISIONING_MODE -> {
                val allowed = intent.getIntegerArrayListExtra(
                    DevicePolicyManager.EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES
                )
                if (allowed == null || DevicePolicyManager.PROVISIONING_MODE_MANAGED_PROFILE in allowed) {
                    setResult(
                        RESULT_OK,
                        Intent().putExtra(
                            DevicePolicyManager.EXTRA_PROVISIONING_MODE,
                            DevicePolicyManager.PROVISIONING_MODE_MANAGED_PROFILE
                        )
                    )
                } else {
                    setResult(RESULT_CANCELED)
                }
                finish()
            }

            DevicePolicyManager.ACTION_ADMIN_POLICY_COMPLIANCE -> {
                setResult(RESULT_OK)
                finish()
            }

            else -> {
                setResult(RESULT_CANCELED)
                finish()
            }
        }
    }
}
