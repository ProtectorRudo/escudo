package com.escudo.app.clone

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import com.escudo.app.apps.AppRepository

/**
 * Runs only in the personal profile.
 *
 * The managed-profile Escudo instance reaches this Activity through Android's cross-profile
 * forwarding. The activity validates the provisioning-time pairing secret, lets the user choose a
 * source app, then returns a Binder to CloneBridgeService. APK file descriptors travel over AIDL,
 * never inside the Activity result Intent.
 */
class CloneSourceActivity : Activity() {
    private var bound = false
    private var selectedPackage: String? = null
    private var selectedLabel: String? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service == null) {
                fail()
                return
            }
            val pkg = selectedPackage ?: run { fail(); return }
            val label = selectedLabel ?: pkg
            val extra = Bundle().apply { putBinder(EXTRA_BRIDGE_BINDER, service) }
            setResult(
                RESULT_OK,
                Intent()
                    .putExtra(EXTRA_PACKAGE, pkg)
                    .putExtra(EXTRA_LABEL, label)
                    .putExtra(EXTRA_BRIDGE_BUNDLE, extra)
            )
            // Service is also started, so unbinding here does not invalidate the returned Binder.
            safeUnbind()
            finish()
        }

        override fun onServiceDisconnected(name: ComponentName?) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action != ACTION_PICK_CLONE_SOURCE ||
            !BridgeSecretStore(this).matches(intent.getStringExtra(BridgeSecretStore.EXTRA_PAIRING_SECRET))
        ) {
            fail()
            return
        }

        val apps = AppRepository(this).launchableApps()
        if (apps.isEmpty()) {
            fail()
            return
        }

        val labels = apps.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Elegí la app para llevar a Escudo")
            .setItems(labels) { _, which ->
                val chosen = apps[which]
                selectedPackage = chosen.packageName
                selectedLabel = chosen.label
                connectBridge()
            }
            .setNegativeButton("Cancelar") { _, _ -> fail() }
            .setOnCancelListener { fail() }
            .show()
    }

    private fun connectBridge() {
        runCatching {
            val serviceIntent = Intent(this, CloneBridgeService::class.java)
            startService(serviceIntent)
            bound = bindService(serviceIntent, connection, BIND_AUTO_CREATE)
            check(bound) { "No se pudo abrir el puente local" }
        }.onFailure { fail() }
    }

    private fun safeUnbind() {
        if (!bound) return
        runCatching { unbindService(connection) }
        bound = false
    }

    private fun fail() {
        safeUnbind()
        setResult(RESULT_CANCELED)
        finish()
    }

    override fun onDestroy() {
        safeUnbind()
        super.onDestroy()
    }

    companion object {
        const val ACTION_PICK_CLONE_SOURCE = "com.escudo.app.action.PICK_CLONE_SOURCE"
        const val EXTRA_PACKAGE = "clone_package"
        const val EXTRA_LABEL = "clone_label"
        const val EXTRA_BRIDGE_BUNDLE = "clone_bridge_bundle"
        const val EXTRA_BRIDGE_BINDER = "clone_bridge_binder"
    }
}
