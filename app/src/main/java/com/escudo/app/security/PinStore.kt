package com.escudo.app.security

import android.content.Context
import android.util.Base64
import kotlin.math.max

sealed interface PinVerification {
    data object Success : PinVerification
    data object DeviceKeyMissing : PinVerification
    data class Rejected(val attemptsRemaining: Int) : PinVerification
    data class Locked(val secondsRemaining: Long) : PinVerification
}

class PinStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("escudo_pin", Context.MODE_PRIVATE)

    val isConfigured: Boolean
        get() = prefs.contains(KEY_SALT) &&
            (prefs.contains(KEY_VERIFIER) || prefs.contains(KEY_LEGACY_HASH))

    fun save(pin: String) {
        require(pin.length >= 6) { "El PIN debe tener al menos 6 dígitos" }
        require(pin.all(Char::isDigit)) { "El PIN sólo puede contener dígitos" }

        val salt = PinHasher.newSalt()
        val derived = PinHasher.hash(pin.toCharArray(), salt)
        val verifier = try {
            DeviceBoundMac.sign(derived, createIfMissing = true)
        } finally {
            derived.fill(0)
        }

        prefs.edit()
            .putInt(KEY_FORMAT_VERSION, FORMAT_DEVICE_BOUND)
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_VERIFIER, Base64.encodeToString(verifier, Base64.NO_WRAP))
            .remove(KEY_LEGACY_HASH)
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_LOCK_UNTIL, 0L)
            .apply()

        // Establish the trusted biometric enrollment set at the same moment the independent
        // Escudo PIN is created. Failure is non-fatal: PIN access remains available.
        BiometricEnrollmentKey.resetForCurrentEnrollment(appContext)
    }

    fun verify(pin: String, nowEpochMs: Long = System.currentTimeMillis()): PinVerification {
        val lockUntil = prefs.getLong(KEY_LOCK_UNTIL, 0L)
        if (lockUntil > nowEpochMs) {
            return PinVerification.Locked(max(1L, (lockUntil - nowEpochMs + 999L) / 1000L))
        }

        val saltText = prefs.getString(KEY_SALT, null)
            ?: return PinVerification.Rejected(PinBackoff.FREE_ATTEMPTS)
        val salt = Base64.decode(saltText, Base64.NO_WRAP)
        val format = prefs.getInt(KEY_FORMAT_VERSION, FORMAT_LEGACY)

        val matched = when (format) {
            FORMAT_DEVICE_BOUND -> verifyDeviceBound(pin, salt)
            else -> verifyLegacyAndMigrate(pin, salt)
        }

        if (matched == null) return PinVerification.DeviceKeyMissing
        if (matched) {
            prefs.edit().putInt(KEY_FAILED_ATTEMPTS, 0).putLong(KEY_LOCK_UNTIL, 0L).apply()
            // A correct Escudo PIN is the only recovery path that may trust a new biometric set.
            // This intentionally happens after PIN verification, never after the phone PIN alone.
            BiometricEnrollmentKey.resetForCurrentEnrollment(appContext)
            return PinVerification.Success
        }

        val failed = prefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
        val lockMs = PinBackoff.lockDurationMs(failed)
        prefs.edit()
            .putInt(KEY_FAILED_ATTEMPTS, failed)
            .putLong(KEY_LOCK_UNTIL, if (lockMs > 0L) nowEpochMs + lockMs else 0L)
            .apply()

        return if (lockMs > 0L) {
            PinVerification.Locked(lockMs / 1000L)
        } else {
            PinVerification.Rejected((PinBackoff.FREE_ATTEMPTS - failed).coerceAtLeast(0))
        }
    }

    /** Returns null when a device-bound record exists but its Keystore key disappeared. */
    private fun verifyDeviceBound(pin: String, salt: ByteArray): Boolean? {
        val verifierText = prefs.getString(KEY_VERIFIER, null) ?: return false
        if (!DeviceBoundMac.hasKey()) return null

        val expected = Base64.decode(verifierText, Base64.NO_WRAP)
        val derived = PinHasher.hash(pin.toCharArray(), salt)
        val actual = try {
            DeviceBoundMac.sign(derived, createIfMissing = false)
        } finally {
            derived.fill(0)
        }
        return PinHasher.constantTimeEquals(expected, actual)
    }

    /** v0.3 migration: verify the old PBKDF2 hash once, then replace it with a Keystore-bound record. */
    private fun verifyLegacyAndMigrate(pin: String, salt: ByteArray): Boolean {
        val legacyText = prefs.getString(KEY_LEGACY_HASH, null) ?: return false
        val expected = Base64.decode(legacyText, Base64.NO_WRAP)
        val actual = PinHasher.hash(pin.toCharArray(), salt)
        val matches = PinHasher.constantTimeEquals(expected, actual)
        actual.fill(0)
        if (matches) save(pin)
        return matches
    }

    private companion object {
        const val FORMAT_LEGACY = 1
        const val FORMAT_DEVICE_BOUND = 2
        const val KEY_FORMAT_VERSION = "format_version"
        const val KEY_SALT = "salt"
        const val KEY_VERIFIER = "verifier"
        const val KEY_LEGACY_HASH = "hash"
        const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        const val KEY_LOCK_UNTIL = "lock_until_epoch_ms"
    }
}
