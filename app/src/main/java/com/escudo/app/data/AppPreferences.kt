package com.escudo.app.data

import android.content.Context
import com.escudo.app.apps.InstalledApp

class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("escudo_prefs", Context.MODE_PRIVATE)

    var onboardingDone: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING, value).apply()

    fun selectedPackages(): Set<String> =
        prefs.getStringSet(KEY_PACKAGES, emptySet())?.toSet() ?: emptySet()

    fun setSelectedPackages(packages: Set<String>) {
        prefs.edit().putStringSet(KEY_PACKAGES, packages.toSet()).apply()
    }

    fun rememberApp(app: InstalledApp) {
        prefs.edit().putString(labelKey(app.packageName), app.label).apply()
    }

    fun forgetApp(packageName: String) {
        prefs.edit()
            .remove(labelKey(packageName))
            .putStringSet(KEY_PENDING_PERSONAL_COPIES, pendingPersonalCopies() - packageName)
            .apply()
    }

    fun rememberedApps(): List<InstalledApp> = selectedPackages()
        .map { pkg ->
            InstalledApp(
                packageName = pkg,
                label = prefs.getString(labelKey(pkg), null)?.takeIf { it.isNotBlank() } ?: pkg
            )
        }
        .sortedBy { it.label.lowercase() }

    fun pendingPersonalCopies(): Set<String> =
        prefs.getStringSet(KEY_PENDING_PERSONAL_COPIES, emptySet())?.toSet() ?: emptySet()

    fun markPersonalCopyPending(packageName: String) {
        prefs.edit().putStringSet(
            KEY_PENDING_PERSONAL_COPIES,
            pendingPersonalCopies() + packageName
        ).apply()
    }

    fun clearPersonalCopyPending(packageName: String) {
        prefs.edit().putStringSet(
            KEY_PENDING_PERSONAL_COPIES,
            pendingPersonalCopies() - packageName
        ).apply()
    }

    fun replacePendingPersonalCopies(packages: Set<String>) {
        prefs.edit().putStringSet(KEY_PENDING_PERSONAL_COPIES, packages.toSet()).apply()
    }

    var lastPersonalAuditEpochMs: Long
        get() = prefs.getLong(KEY_LAST_PERSONAL_AUDIT_EPOCH_MS, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_PERSONAL_AUDIT_EPOCH_MS, value).apply()

    var activeSessionPackage: String?
        get() = prefs.getString(KEY_ACTIVE_PACKAGE, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_ACTIVE_PACKAGE) else putString(KEY_ACTIVE_PACKAGE, value)
            }.apply()
        }

    var activeSessionUntilEpochMs: Long
        get() = prefs.getLong(KEY_ACTIVE_UNTIL, 0L)
        set(value) = prefs.edit().putLong(KEY_ACTIVE_UNTIL, value).apply()

    fun clearActiveSession() {
        prefs.edit().remove(KEY_ACTIVE_PACKAGE).remove(KEY_ACTIVE_UNTIL).apply()
    }

    private fun labelKey(packageName: String) = "$LABEL_PREFIX$packageName"

    private companion object {
        const val KEY_ONBOARDING = "onboarding_done"
        const val KEY_PACKAGES = "selected_packages"
        const val KEY_PENDING_PERSONAL_COPIES = "pending_personal_copies"
        const val KEY_ACTIVE_PACKAGE = "active_session_package"
        const val KEY_ACTIVE_UNTIL = "active_session_until_epoch_ms"
        const val KEY_LAST_PERSONAL_AUDIT_EPOCH_MS = "last_personal_audit_epoch_ms"
        const val LABEL_PREFIX = "protected_label:"
    }
}
