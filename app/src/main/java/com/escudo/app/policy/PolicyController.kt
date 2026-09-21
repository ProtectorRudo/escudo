package com.escudo.app.policy

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import com.escudo.app.data.AppPreferences

class PolicyController(private val context: Context) {
    private val dpm = context.getSystemService(DevicePolicyManager::class.java)
    private val admin = ComponentName(context, EscudoDeviceAdminReceiver::class.java)

    val hasManagedControl: Boolean
        get() = dpm.isDeviceOwnerApp(context.packageName) || dpm.isProfileOwnerApp(context.packageName)

    val controlLabel: String
        get() = when {
            dpm.isDeviceOwnerApp(context.packageName) -> "Device Owner"
            dpm.isProfileOwnerApp(context.packageName) -> "Profile Owner"
            else -> "Sin bóveda administrada"
        }

    /**
     * Hardens Escudo itself so Settings cannot become the trivial bypass.
     *
     * Android 11+ lets a DPC disable user control over a package, which prevents actions such as
     * force-stop / clear-data from the normal Settings UI. We also block uninstall explicitly.
     */
    fun hardenEscudo(): Result<Unit> = runCatching {
        check(hasManagedControl) { "Escudo no tiene control administrado" }

        // Device Owner already cannot be uninstalled, but applying the explicit policy keeps
        // Profile Owner / test configurations consistent and auditable.
        runCatching { dpm.setUninstallBlocked(admin, context.packageName, true) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val disabled = dpm.getUserControlDisabledPackages(admin).toMutableSet()
            if (disabled.add(context.packageName)) {
                dpm.setUserControlDisabledPackages(admin, disabled.toMutableList())
            }
        }
    }

    fun isEscudoHardened(): Boolean {
        if (!hasManagedControl) return false
        return runCatching {
            val uninstallSafe = dpm.isDeviceOwnerApp(context.packageName) ||
                dpm.isUninstallBlocked(admin, context.packageName)
            val userControlSafe = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageName in dpm.getUserControlDisabledPackages(admin)
            } else {
                // API 28-29 can still use the vault, but cannot claim the stronger anti-force-stop layer.
                false
            }
            uninstallSafe && userControlSafe
        }.getOrDefault(false)
    }

    /**
     * Estado cerrado de una app dentro de Escudo.
     *
     * Tres controles independientes:
     *  - hidden: no queda disponible para uso normal / launcher;
     *  - suspended: Android impide iniciar activities y oculta Recents/notificaciones;
     *  - uninstall blocked: evita el bypass desinstalar -> reinstalar, que limpiaría suspensión.
     */
    fun protect(packageName: String): Result<Unit> = runCatching {
        validateTarget(packageName)
        check(hasManagedControl) { "Escudo no tiene control administrado" }

        val wasHidden = runCatching { dpm.isApplicationHidden(admin, packageName) }.getOrDefault(false)
        val wasSuspended = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { dpm.isPackageSuspended(admin, packageName) }.getOrDefault(false)
        } else true
        val wasUninstallBlocked = runCatching { dpm.isUninstallBlocked(admin, packageName) }.getOrDefault(false)

        var changedHidden = false
        var changedSuspended = false
        var changedUninstall = false

        try {
            if (!wasUninstallBlocked) {
                dpm.setUninstallBlocked(admin, packageName, true)
                changedUninstall = true
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !wasSuspended) {
                val failed = dpm.setPackagesSuspended(admin, arrayOf(packageName), true)
                check(failed.isEmpty()) { "Android no permitió suspender $packageName" }
                changedSuspended = true
            }

            if (!wasHidden) {
                val hidden = dpm.setApplicationHidden(admin, packageName, true)
                check(hidden) { "Android no permitió ocultar $packageName" }
                changedHidden = true
            }

            check(isProtected(packageName)) { "Android no confirmó el estado protegido de $packageName" }
        } catch (t: Throwable) {
            // Rollback sólo de las políticas que esta llamada alcanzó a modificar.
            if (changedHidden) runCatching { dpm.setApplicationHidden(admin, packageName, false) }
            if (changedSuspended && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                runCatching { dpm.setPackagesSuspended(admin, arrayOf(packageName), false) }
            }
            if (changedUninstall) runCatching { dpm.setUninstallBlocked(admin, packageName, false) }
            throw t
        }
    }

    /** Abre temporalmente una app protegida, manteniendo bloqueada su desinstalación. */
    fun releaseForSession(packageName: String): Result<Unit> = runCatching {
        validateTarget(packageName)
        check(hasManagedControl) { "Escudo no tiene control administrado" }

        try {
            // Mantener esto incluso mientras la app está abierta evita un bypass por reinstalación.
            dpm.setUninstallBlocked(admin, packageName, true)

            val unhidden = dpm.setApplicationHidden(admin, packageName, false)
            check(unhidden) { "Android no permitió mostrar $packageName" }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val failed = dpm.setPackagesSuspended(admin, arrayOf(packageName), false)
                check(failed.isEmpty()) { "Android no permitió habilitar $packageName" }
            }

            check(isReleasedForSession(packageName)) { "Android no confirmó la apertura temporal de $packageName" }
        } catch (t: Throwable) {
            // Una apertura parcial no debe dejar el paquete expuesto.
            protect(packageName)
            throw t
        }
    }

    /** El usuario quitó voluntariamente la app de Escudo: revierte todas nuestras políticas. */
    fun removeProtection(packageName: String): Result<Unit> = runCatching {
        validateTarget(packageName)
        check(hasManagedControl) { "Escudo no tiene control administrado" }

        try {
            val unhidden = dpm.setApplicationHidden(admin, packageName, false)
            check(unhidden) { "Android no permitió mostrar $packageName" }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val failed = dpm.setPackagesSuspended(admin, arrayOf(packageName), false)
                check(failed.isEmpty()) { "Android no permitió habilitar $packageName" }
            }

            dpm.setUninstallBlocked(admin, packageName, false)
        } catch (t: Throwable) {
            // Si no podemos quitar TODA la política, restauramos el estado cerrado y
            // ViewModel conserva la app en la bóveda en lugar de dejar un huérfano.
            protect(packageName)
            throw t
        }
    }

    fun isProtected(packageName: String): Boolean {
        if (!hasManagedControl) return false
        return runCatching {
            val hidden = dpm.isApplicationHidden(admin, packageName)
            val suspended = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                dpm.isPackageSuspended(admin, packageName)
            } else true
            val uninstallBlocked = dpm.isUninstallBlocked(admin, packageName)
            hidden && suspended && uninstallBlocked
        }.getOrDefault(false)
    }

    private fun isReleasedForSession(packageName: String): Boolean {
        if (!hasManagedControl) return true
        return runCatching {
            val hidden = dpm.isApplicationHidden(admin, packageName)
            val suspended = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                dpm.isPackageSuspended(admin, packageName)
            } else false
            val uninstallBlocked = dpm.isUninstallBlocked(admin, packageName)
            !hidden && !suspended && uninstallBlocked
        }.getOrDefault(false)
    }

    fun lockAllSelected(): List<Pair<String, Throwable>> {
        if (!hasManagedControl) return emptyList()
        hardenEscudo()
        val prefs = AppPreferences(context)
        val failures = prefs.selectedPackages().mapNotNull { pkg ->
            protect(pkg).exceptionOrNull()?.let { pkg to it }
        }
        if (failures.isEmpty()) prefs.clearActiveSession()
        return failures
    }

    fun unprotectedSelectedPackages(): Set<String> {
        if (!hasManagedControl) return AppPreferences(context).selectedPackages()
        return AppPreferences(context).selectedPackages().filterNot(::isProtected).toSet()
    }

    fun prepareLaunch(packageName: String): Result<Intent> = runCatching {
        val selected = AppPreferences(context).selectedPackages()
        require(packageName in selected) { "La app no está seleccionada dentro de Escudo" }

        if (hasManagedControl) {
            hardenEscudo().getOrThrow()
            // Estado conocido antes de abrir: sólo una app sensible puede quedar liberada.
            val failures = lockAllSelected()
            check(failures.isEmpty()) {
                "No pudimos cerrar completamente la bóveda antes de abrir esta app"
            }
            releaseForSession(packageName).getOrThrow()
        }

        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (launchIntent == null) {
            if (hasManagedControl) protect(packageName)
            error("No encontramos una pantalla de inicio para esta app")
        }
        launchIntent
    }

    private fun validateTarget(packageName: String) {
        require(packageName.isNotBlank()) { "Paquete inválido" }
        require(packageName != context.packageName) { "Escudo no puede protegerse a sí mismo" }
    }
}
