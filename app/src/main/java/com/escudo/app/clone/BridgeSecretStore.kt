package com.escudo.app.clone

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Pairs the personal Escudo helper with the managed-profile Escudo instance.
 *
 * A random 256-bit secret is generated before managed-profile provisioning and passed through
 * Android's official EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE. Both Escudo instances then know the
 * secret, while unrelated apps do not. This avoids trusting the apparent cross-profile caller,
 * which may be Android's own intent forwarder.
 */
class BridgeSecretStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getOrCreate(): String {
        prefs.getString(KEY_SECRET, null)?.let { return it }
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE)
        prefs.edit().putString(KEY_SECRET, encoded).commit()
        return encoded
    }

    fun current(): String? = prefs.getString(KEY_SECRET, null)

    fun provisioningExtras(): PersistableBundle = PersistableBundle().apply {
        putString(EXTRA_PAIRING_SECRET, getOrCreate())
    }

    fun importFromProvisioning(intent: Intent?): Boolean {
        if (intent == null) return false
        val extras = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
                PersistableBundle::class.java
            )
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE)
        }
        val secret = extras?.getString(EXTRA_PAIRING_SECRET)?.takeIf(::looksValid) ?: return false
        prefs.edit().putString(KEY_SECRET, secret).commit()
        return true
    }

    fun matches(candidate: String?): Boolean {
        val stored = current() ?: return false
        if (candidate == null) return false
        return MessageDigest.isEqual(
            stored.toByteArray(Charsets.UTF_8),
            candidate.toByteArray(Charsets.UTF_8)
        )
    }

    private fun looksValid(secret: String): Boolean = runCatching {
        Base64.decode(secret, Base64.NO_WRAP or Base64.URL_SAFE).size == 32
    }.getOrDefault(false)

    companion object {
        const val EXTRA_PAIRING_SECRET = "escudo_pairing_secret"
        private const val PREFS = "escudo_bridge_security"
        private const val KEY_SECRET = "pairing_secret"
    }
}
