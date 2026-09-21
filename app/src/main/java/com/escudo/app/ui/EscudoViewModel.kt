package com.escudo.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.escudo.app.apps.AppRepository
import com.escudo.app.apps.InstalledApp
import com.escudo.app.data.AppPreferences
import com.escudo.app.policy.PolicyController
import com.escudo.app.policy.ConsumerProtectionController
import com.escudo.app.policy.ConsumerProtectionPlan
import com.escudo.app.policy.ProvisioningController
import com.escudo.app.security.PinStore
import com.escudo.app.security.PinVerification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class EscudoViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = AppPreferences(application)
    private val pins = PinStore(application)
    private val apps = AppRepository(application)
    private val policy = PolicyController(application)
    private val provisioning = ProvisioningController(application)
    private val consumerProtection = ConsumerProtectionController(application)

    private val _selected = MutableStateFlow(prefs.selectedPackages())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    private val _installed = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installed: StateFlow<List<InstalledApp>> = _installed.asStateFlow()

    private val _vaultApps = MutableStateFlow(prefs.rememberedApps())
    val vaultApps: StateFlow<List<InstalledApp>> = _vaultApps.asStateFlow()

    private val _protectionProblems = MutableStateFlow<Set<String>>(emptySet())
    val protectionProblems: StateFlow<Set<String>> = _protectionProblems.asStateFlow()

    private val _selfProtectionHealthy = MutableStateFlow(false)
    val selfProtectionHealthy: StateFlow<Boolean> = _selfProtectionHealthy.asStateFlow()

    private val _pendingPersonalCopies = MutableStateFlow(prefs.pendingPersonalCopies())
    val pendingPersonalCopies: StateFlow<Set<String>> = _pendingPersonalCopies.asStateFlow()

    private val _personalAuditHealthy = MutableStateFlow(false)
    val personalAuditHealthy: StateFlow<Boolean> = _personalAuditHealthy.asStateFlow()

    val onboardingDone: Boolean get() = prefs.onboardingDone && pins.isConfigured
    val managedControl: Boolean get() = policy.hasManagedControl
    val controlLabel: String get() = policy.controlLabel
    val canProvisionSecureProfile: Boolean get() = provisioning.canProvisionManagedProfile
    val runningInsideSecureProfile: Boolean get() = provisioning.isManagedProfile && provisioning.isProfileOwner
    val consumerProtectionPlan: ConsumerProtectionPlan get() = consumerProtection.plan

    init {
        if (policy.hasManagedControl) policy.hardenEscudo()
        if (runningInsideSecureProfile) provisioning.configureCrossProfileCloneBridge()
        refreshApps()
        refreshProtectionState()
    }

    fun refreshApps() {
        val visible = apps.launchableApps()
        val remembered = prefs.rememberedApps()
        _installed.value = (visible + remembered)
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
        _vaultApps.value = prefs.rememberedApps()
        _pendingPersonalCopies.value = prefs.pendingPersonalCopies()
    }

    fun savePin(pin: String) = pins.save(pin)
    fun verifyPin(pin: String): PinVerification = pins.verify(pin)

    fun finishOnboarding() {
        prefs.onboardingDone = true
        lockAll()
    }

    fun setSelected(packages: Set<String>) {
        val previous = _selected.value
        val byPackage = _installed.value.associateBy { it.packageName }
        val additions = packages - previous
        val requestedRemovals = previous - packages

        additions.forEach { pkg ->
            byPackage[pkg]?.let(prefs::rememberApp)
        }

        if (policy.hasManagedControl) {
            additions.forEach { policy.protect(it) }
        }

        // Fail-closed: si Android no nos deja revertir la política de una app,
        // NO la olvidamos. Así nunca queda oculta/suspendida fuera de la lista de Escudo.
        val failedRemovals = if (policy.hasManagedControl) {
            requestedRemovals.filter { policy.removeProtection(it).isFailure }.toSet()
        } else {
            emptySet()
        }

        val effectivePackages = packages + failedRemovals
        _selected.value = effectivePackages
        // Cambió el conjunto que debemos contrastar contra el perfil personal.
        // Hasta completar una nueva auditoría no declaramos "protección fuerte".
        _personalAuditHealthy.value = false
        prefs.setSelectedPackages(effectivePackages)

        (requestedRemovals - failedRemovals).forEach(prefs::forgetApp)
        refreshApps()
        refreshProtectionState()
    }

    fun confirmPersonalCopyRemoved(packageName: String) {
        prefs.clearPersonalCopyPending(packageName)
        _pendingPersonalCopies.value = prefs.pendingPersonalCopies()
    }

    fun applyPersonalCopyAudit(installedInPersonalProfile: Set<String>) {
        val outside = _selected.value.intersect(installedInPersonalProfile)
        prefs.replacePendingPersonalCopies(outside)
        prefs.lastPersonalAuditEpochMs = System.currentTimeMillis()
        _pendingPersonalCopies.value = outside
        _personalAuditHealthy.value = true
    }

    fun markPersonalCopyAuditFailed() {
        _personalAuditHealthy.value = false
    }

    fun lockAll() {
        policy.lockAllSelected()
        refreshProtectionState()
    }

    fun refreshProtectionState() {
        if (policy.hasManagedControl) policy.hardenEscudo()
        _selfProtectionHealthy.value = policy.isEscudoHardened()
        _protectionProblems.value = if (policy.hasManagedControl) {
            policy.unprotectedSelectedPackages()
        } else {
            _selected.value
        }
    }
}
