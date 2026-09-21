package com.escudo.app.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

class BiometricGate(private val activity: FragmentActivity) {

    fun isStrongBiometricAvailable(): Boolean {
        return BiometricManager.from(activity).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun authenticate(
        title: String = "Abrir Escudo",
        subtitle: String = "Confirmá tu identidad",
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        if (!isStrongBiometricAvailable()) {
            onFailure("No encontramos una huella o biometría fuerte disponible. Usá tu PIN de Escudo.")
            return
        }

        val crypto = when (val preparation = BiometricEnrollmentKey.prepareCipher()) {
            is BiometricEnrollmentKey.Preparation.Ready -> preparation.cipher
            BiometricEnrollmentKey.Preparation.Missing -> {
                onFailure("La biometría de Escudo necesita revalidarse. Entrá una vez con tu PIN de Escudo.")
                return
            }
            BiometricEnrollmentKey.Preparation.EnrollmentChanged -> {
                onFailure("Cambió la biometría registrada en el teléfono. Por seguridad, entrá con tu PIN de Escudo para autorizar las huellas actuales.")
                return
            }
            is BiometricEnrollmentKey.Preparation.Unavailable -> {
                onFailure(preparation.reason)
                return
            }
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    val cipher = result.cryptoObject?.cipher
                    if (cipher != null && BiometricEnrollmentKey.consumeAuthenticatedCipher(cipher)) {
                        onSuccess()
                    } else {
                        onFailure("Android autenticó la biometría, pero Escudo no pudo validar su clave segura.")
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    if (errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                        errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_CANCELED
                    ) {
                        onFailure(errString.toString())
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    onFailure("La huella / biometría no coincidió")
                }
            }
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Cancelar")
            .build()

        prompt.authenticate(info, BiometricPrompt.CryptoObject(crypto))
    }
}
