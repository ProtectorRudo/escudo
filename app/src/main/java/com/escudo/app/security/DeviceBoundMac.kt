package com.escudo.app.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Device-bound secret used as a pepper for the PIN verifier.
 *
 * Escudo never stores the PBKDF2 output by itself. The stored verifier is an HMAC produced by a
 * non-exportable key in Android Keystore, so a copied SharedPreferences database is not enough to
 * test candidate PINs offline.
 */
object DeviceBoundMac {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "escudo_pin_pepper_v1"
    private const val MAC_ALGORITHM = "HmacSHA256"

    fun hasKey(): Boolean = keyStore().containsAlias(ALIAS)

    fun ensureKey(): SecretKey {
        val store = keyStore()
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
            KEYSTORE
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        return generator.generateKey()
    }

    fun sign(data: ByteArray, createIfMissing: Boolean): ByteArray {
        val store = keyStore()
        val key = (store.getKey(ALIAS, null) as? SecretKey)
            ?: if (createIfMissing) ensureKey() else error("Falta la clave de dispositivo de Escudo")
        return Mac.getInstance(MAC_ALGORITHM).run {
            init(key)
            doFinal(data)
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
}
