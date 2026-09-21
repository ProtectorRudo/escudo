package com.escudo.app.policy

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.escudo.app.clone.BridgeSecretStore

class EscudoDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(context, EscudoDeviceAdminReceiver::class.java)

        // Pair the two Escudo instances using Android's provisioning extras before opening any bridge.
        BridgeSecretStore(context).importFromProvisioning(intent)

        // This callback runs inside the newly-created managed profile.
        runCatching { dpm.setProfileName(admin, "Escudo") }
        runCatching { dpm.setProfileEnabled(admin) }
        runCatching { ProvisioningController(context).configureCrossProfileCloneBridge() }

        runCatching { PolicyController(context).hardenEscudo() }
    }
}
