package com.escudo.app.policy

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import com.escudo.app.clone.BridgeSecretStore
import com.escudo.app.clone.CloneSourceActivity
import com.escudo.app.clone.RemovePersonalCopyActivity
import com.escudo.app.clone.PersonalCopyAuditActivity

/**
 * Consumer-friendly path for Escudo.
 *
 * Device Owner is too invasive for a normal personal phone. A managed profile can be provisioned
 * on a personally-owned device with explicit user consent and gives Escudo Profile Owner control
 * inside that isolated profile. Protected apps must live inside that profile for the policy APIs to
 * actually control them.
 */
class ProvisioningController(private val context: Context) {
    private val dpm = context.getSystemService(DevicePolicyManager::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val admin = ComponentName(context, EscudoDeviceAdminReceiver::class.java)

    val isManagedProfile: Boolean
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        userManager.isManagedProfile
    } else {
        isProfileOwner && !isDeviceOwner
    }

    val isProfileOwner: Boolean
        get() = dpm.isProfileOwnerApp(context.packageName)

    val isDeviceOwner: Boolean
        get() = dpm.isDeviceOwnerApp(context.packageName)

    val canProvisionManagedProfile: Boolean
        get() = !isProfileOwner && !isDeviceOwner && runCatching {
            dpm.isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)
        }.getOrDefault(false)

    fun managedProfileProvisioningIntent(): Intent {
        check(canProvisionManagedProfile) {
            "Android no permite crear un perfil seguro en este dispositivo o ya existe uno administrado"
        }
        val bridgeExtras = BridgeSecretStore(context).provisioningExtras()
        return Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE).apply {
            putExtra(DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME, admin)
            putExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE, bridgeExtras)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                putExtra(DevicePolicyManager.EXTRA_PROVISIONING_SKIP_ENCRYPTION, true)
            }
        }
    }
    fun configureCrossProfileCloneBridge(): Result<Unit> = runCatching {
        check(isProfileOwner && isManagedProfile) {
            "El puente de clonación sólo puede configurarse dentro del perfil Escudo"
        }
        listOf(
            CloneSourceActivity.ACTION_PICK_CLONE_SOURCE,
            RemovePersonalCopyActivity.ACTION_REMOVE_PERSONAL_COPY,
            PersonalCopyAuditActivity.ACTION_AUDIT_PERSONAL_COPIES
        ).forEach { action ->
            val filter = IntentFilter(action).apply { addCategory(Intent.CATEGORY_DEFAULT) }
            dpm.addCrossProfileIntentFilter(
                admin,
                filter,
                DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT
            )
        }
        listOf(
            CloneSourceActivity::class.java,
            RemovePersonalCopyActivity::class.java,
            PersonalCopyAuditActivity::class.java
        ).forEach { componentClass ->
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, componentClass),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }

        // El usuario debe ver un solo icono Escudo. El launcher queda en el perfil personal;
        // ese icono reenvía luego a MainActivity dentro del perfil administrado.
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context.packageName, "${context.packageName}.EscudoLauncher"),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )

        val secureEntry = IntentFilter(ACTION_OPEN_SECURE_ESCUDO).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        dpm.addCrossProfileIntentFilter(
            admin,
            secureEntry,
            DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED
        )
    }

    fun crossProfileCloneSourceIntent(): Intent {
        val token = BridgeSecretStore(context).current()
            ?: error("Escudo perdió la vinculación con el perfil personal")
        return resolveParentForwarder(
            Intent(CloneSourceActivity.ACTION_PICK_CLONE_SOURCE).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                putExtra(BridgeSecretStore.EXTRA_PAIRING_SECRET, token)
            }
        )
    }

    fun crossProfileRemovePersonalCopyIntent(packageName: String): Intent {
        require(packageName.isNotBlank() && packageName != context.packageName) { "Paquete inválido" }
        val token = BridgeSecretStore(context).current()
            ?: error("Escudo perdió la vinculación con el perfil personal")
        return resolveParentForwarder(
            Intent(RemovePersonalCopyActivity.ACTION_REMOVE_PERSONAL_COPY).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                putExtra(RemovePersonalCopyActivity.EXTRA_PACKAGE, packageName)
                putExtra(BridgeSecretStore.EXTRA_PAIRING_SECRET, token)
            }
        )
    }


    fun crossProfilePersonalCopyAuditIntent(packages: Set<String>): Intent {
        val token = BridgeSecretStore(context).current()
            ?: error("Escudo perdió la vinculación con el perfil personal")
        val safePackages = packages
            .asSequence()
            .filter { it.isNotBlank() && it != context.packageName }
            .distinct()
            .take(100)
            .toList()
        return resolveParentForwarder(
            Intent(PersonalCopyAuditActivity.ACTION_AUDIT_PERSONAL_COPIES).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                putStringArrayListExtra(PersonalCopyAuditActivity.EXTRA_PACKAGES, ArrayList(safePackages))
                putExtra(BridgeSecretStore.EXTRA_PAIRING_SECRET, token)
            }
        )
    }

    fun secureProfileEntryIntentOrNull(): Intent? {
        if (isManagedProfile) return null
        val intent = Intent(ACTION_OPEN_SECURE_ESCUDO).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        @Suppress("DEPRECATION")
        val candidates = context.packageManager.queryIntentActivities(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY
        )
        val forwarder = candidates.firstOrNull { candidate ->
            val appInfo = candidate.activityInfo.applicationInfo
            candidate.activityInfo.packageName != context.packageName &&
                (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        } ?: return null
        return intent.setComponent(
            ComponentName(forwarder.activityInfo.packageName, forwarder.activityInfo.name)
        )
    }

    private fun resolveParentForwarder(intent: Intent): Intent {
        check(isProfileOwner && isManagedProfile) {
            "Esta acción sólo puede iniciarse desde el perfil Escudo"
        }
        @Suppress("DEPRECATION")
        val candidates = context.packageManager.queryIntentActivities(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY
        )
        val forwarder = candidates.firstOrNull { candidate ->
            val appInfo = candidate.activityInfo.applicationInfo
            candidate.activityInfo.packageName != context.packageName &&
                (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        } ?: error("Android no encontró un puente de sistema seguro hacia el perfil personal")
        return intent.setComponent(
            ComponentName(forwarder.activityInfo.packageName, forwarder.activityInfo.name)
        )
    }


    companion object {
        const val ACTION_OPEN_SECURE_ESCUDO = "com.escudo.app.action.OPEN_SECURE_ESCUDO"
    }

}
