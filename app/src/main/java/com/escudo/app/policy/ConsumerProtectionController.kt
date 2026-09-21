package com.escudo.app.policy

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import android.provider.Settings

enum class ConsumerProtectionKind {
    PRIVATE_SPACE,
    MANAGED_LAB,
    NO_STRONG_STANDARD_PATH
}

data class ConsumerProtectionPlan(
    val kind: ConsumerProtectionKind,
    val title: String,
    val detail: String,
    val systemMustConfirmAvailability: Boolean = false
)

/**
 * Chooses the strongest honest path Escudo can offer without turning a personal phone into an
 * enterprise-managed device.
 *
 * Managed Profile / Device Owner remains a laboratory path. Android 15+ Private Space is the
 * preferred consumer isolation mechanism.
 */
class ConsumerProtectionController(private val context: Context) {
    private val userManager = context.getSystemService(UserManager::class.java)
    private val provisioning = ProvisioningController(context)

    val plan: ConsumerProtectionPlan
        get() {
            if (provisioning.isProfileOwner || provisioning.isDeviceOwner) {
                return ConsumerProtectionPlan(
                    kind = ConsumerProtectionKind.MANAGED_LAB,
                    title = "Motor administrado de laboratorio",
                    detail = "Este dispositivo ya le dio a Escudo control administrado. Se mantiene para pruebas técnicas, no como onboarding de consumo."
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                val blocked = runCatching {
                    userManager.hasUserRestriction(UserManager.DISALLOW_ADD_PRIVATE_PROFILE)
                }.getOrDefault(false)
                val tooManyProfiles = runCatching { userManager.userProfiles.size > 4 }
                    .getOrDefault(false)

                if (!blocked && !tooManyProfiles) {
                    return ConsumerProtectionPlan(
                        kind = ConsumerProtectionKind.PRIVATE_SPACE,
                        title = "Espacio privado de Android",
                        detail = "Android 15+ puede aislar apps sensibles con un bloqueo independiente, sin convertir tu teléfono en un dispositivo de empresa.",
                        systemMustConfirmAvailability = true
                    )
                }

                val reason = when {
                    blocked -> "Android o la administración del dispositivo bloquea la creación de un espacio privado."
                    tooManyProfiles -> "El dispositivo ya tiene demasiados usuarios o perfiles para crear un espacio privado."
                    else -> "Android no confirmó la disponibilidad del espacio privado."
                }
                return ConsumerProtectionPlan(
                    kind = ConsumerProtectionKind.NO_STRONG_STANDARD_PATH,
                    title = "Protección fuerte no disponible",
                    detail = reason
                )
            }

            return ConsumerProtectionPlan(
                kind = ConsumerProtectionKind.NO_STRONG_STANDARD_PATH,
                title = "Android 15+ requerido para la ruta fuerte estándar",
                detail = "En esta versión de Android una app común no puede aislar otras apps con garantías fuertes sin privilegios empresariales. Escudo no va a fingir que sí."
            )
        }

    fun securityAndPrivacySettingsIntent(): Intent = Intent(Settings.ACTION_SECURITY_SETTINGS)
}
