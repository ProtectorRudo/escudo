package com.escudo.app.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Binds Escudo's biometric unlock to the biometric enrollment set that existed when the owner
 * last proved knowledge of the Escudo PIN.
 *
 * If Android enrolls a new fingerprint/strong biometric, the Keystore key becomes permanently
 * invalid. Escudo then refuses biometric unlock until the independent Escudo PIN succeeds and a
 * fresh key is enrolled. Knowing only the phone PIN is therefore not enough to silently add a new
 * fingerprint and inherit Escudo access.
 */
object BiometricEnrollmentKey {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "escudo_biometric_enrollment_v1"
    private const val CIPHER = "AES/CBC/PKCS7Padding"

    sealed interface Preparation {
        data class Ready(val cipher: Cipher) : Preparation
        data object Missing : Preparation
        data object EnrollmentChanged : Preparation
        data class Unavailable(val reason: String) : Preparation
    }

    fun resetForCurrentEnrollment(context: Context): Boolean {
        delete()
        val available = BiometricManager.from(context).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        ) == BiometricManager.BIOMETRIC_SUCCESS
        if (!available) return false

        return runCatching {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            val builder = KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setUserAuthenticationParameters(
                    0,
                    KeyProperties.AUTH_BIOMETRIC_STRONG
                )
            } else {
                @Suppress("DEPRECATION")
                builder.setUserAuthenticationValidityDurationSeconds(-1)
            }

            generator.init(builder.build())
            generator.generateKey()
            true
        }.getOrDefault(false)
    }

    fun prepareCipher(): Preparation {
        val store = keyStore()
        if (!store.containsAlias(ALIAS)) return Preparation.Missing

        val key = runCatching { store.getKey(ALIAS, null) as? SecretKey }
            .getOrElse {
                delete()
                return Preparation.EnrollmentChanged
            }
            ?: return Preparation.Missing

        return try {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            Preparation.Ready(cipher)
        } catch (_: KeyPermanentlyInvalidatedException) {
            delete()
            Preparation.EnrollmentChanged
        } catch (t: Throwable) {
            Preparation.Unavailable(t.message ?: "Android no pudo preparar la biometría segura")
        }
    }

    fun consumeAuthenticatedCipher(cipher: Cipher): Boolean = runCatching {
        cipher.doFinal(byteArrayOf(0x45, 0x53, 0x43, 0x55, 0x44, 0x4f))
        true
    }.getOrDefault(false)

    fun hasKey(): Boolean = runCatching { keyStore().containsAlias(ALIAS) }.getOrDefault(false)

    fun delete() {
        runCatching {
            val store = keyStore()
            if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS)
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
}
