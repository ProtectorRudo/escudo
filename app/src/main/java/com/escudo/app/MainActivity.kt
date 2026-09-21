package com.escudo.app

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import java.util.concurrent.atomic.AtomicBoolean
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.escudo.app.apps.InstalledApp
import com.escudo.app.clone.ApkBundleInstaller
import com.escudo.app.clone.CloneSourceActivity
import com.escudo.app.clone.ICloneBridge
import com.escudo.app.clone.RemovePersonalCopyActivity
import com.escudo.app.clone.PersonalCopyAuditActivity
import com.escudo.app.policy.PolicyController
import com.escudo.app.policy.ConsumerProtectionKind
import com.escudo.app.policy.ConsumerProtectionPlan
import com.escudo.app.policy.ConsumerProtectionController
import com.escudo.app.policy.ProvisioningController
import com.escudo.app.security.BiometricGate
import com.escudo.app.security.PinVerification
import com.escudo.app.service.VaultGuardService
import com.escudo.app.ui.EscudoViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {
    private lateinit var biometric: BiometricGate
    private val launchInFlight = AtomicBoolean(false)
    private val auditInFlight = AtomicBoolean(false)
    private val escudoViewModel: EscudoViewModel by viewModels()
    private var lastAuditElapsedMs: Long = 0L

    private val cloneSourceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val packageName = data.getStringExtra(CloneSourceActivity.EXTRA_PACKAGE)
            ?: return@registerForActivityResult
        val bridgeBundle = data.getBundleExtra(CloneSourceActivity.EXTRA_BRIDGE_BUNDLE)
            ?: return@registerForActivityResult
        val bridgeBinder = bridgeBundle.getBinder(CloneSourceActivity.EXTRA_BRIDGE_BINDER)
            ?: return@registerForActivityResult
        val bridge = ICloneBridge.Stub.asInterface(bridgeBinder)

        lifecycleScope.launch(Dispatchers.IO) {
            val install = runCatching {
                check(bridge.isInstalled(packageName)) { "La app ya no está instalada en el perfil personal" }
                val descriptors = bridge.openApks(packageName)?.toList().orEmpty()
                check(descriptors.isNotEmpty()) { "No pudimos abrir los APK de la app" }
                ApkBundleInstaller(this@MainActivity)
                    .installFromFileDescriptors(packageName, descriptors)
                    .getOrThrow()
            }
            withContext(Dispatchers.Main) {
                val text = if (install.isSuccess) {
                    "Instalando dentro de Escudo…"
                } else {
                    install.exceptionOrNull()?.message ?: "No se pudo clonar la app"
                }
                Toast.makeText(this@MainActivity, text, Toast.LENGTH_LONG).show()
            }
        }
    }

    private val personalCleanupLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val pkg = result.data?.getStringExtra(RemovePersonalCopyActivity.EXTRA_PACKAGE)
        if (result.resultCode == RESULT_OK && !pkg.isNullOrBlank()) {
            Toast.makeText(this, "Copia personal eliminada. Verificando…", Toast.LENGTH_LONG).show()
        } else if (!pkg.isNullOrBlank()) {
            Toast.makeText(this, "La copia personal puede seguir instalada. Verificando…", Toast.LENGTH_LONG).show()
        }
        auditPersonalCopies(force = true)
    }

    private val personalAuditLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        auditInFlight.set(false)
        lastAuditElapsedMs = SystemClock.elapsedRealtime()
        if (result.resultCode == RESULT_OK) {
            val installedOutside = result.data
                ?.getStringArrayListExtra(PersonalCopyAuditActivity.EXTRA_INSTALLED_PACKAGES)
                ?.toSet()
                .orEmpty()
            escudoViewModel.applyPersonalCopyAudit(installedOutside)
        } else {
            escudoViewModel.markPersonalCopyAuditFailed()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Después del provisioning el único icono visible queda en el perfil personal.
        // Si Android ya expone el forwarder al perfil Escudo, este Activity sólo enruta y sale.
        val provisioning = ProvisioningController(this)
        if (!provisioning.isManagedProfile) {
            provisioning.secureProfileEntryIntentOrNull()?.let { secureIntent ->
                startActivity(secureIntent)
                finish()
                return
            }
        }

        biometric = BiometricGate(this)

        // Escudo maneja PIN y la lista de apps sensibles: evitamos capturas, previews en Recents
        // y overlays de terceros sobre la ventana de autenticación.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.setHideOverlayWindows(true)
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    EscudoRoot(
                        biometric = biometric,
                        launchProtected = ::launchProtected,
                        openConsumerSecuritySettings = ::openConsumerSecuritySettings,
                        cloneFromPersonal = ::cloneFromPersonal,
                        removePersonalCopy = ::removePersonalCopy,
                        auditPersonalCopies = { auditPersonalCopies(force = true) },
                        vm = escudoViewModel
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Si volvimos desde una app protegida, cerramos cualquier sesión externa.
        // También limpia el candado anti doble-tap usado durante el handoff.
        if (escudoViewModel.managedControl) {
            val failures = PolicyController(this).lockAllSelected()
            if (failures.isEmpty()) {
                stopService(Intent(this, VaultGuardService::class.java))
            }
            auditPersonalCopies()
        }
        launchInFlight.set(false)
    }

    private fun auditPersonalCopies(force: Boolean = false) {
        val provisioning = ProvisioningController(this)
        if (!provisioning.isManagedProfile || !provisioning.isProfileOwner) return
        val packages = escudoViewModel.selected.value
        if (packages.isEmpty()) {
            escudoViewModel.applyPersonalCopyAudit(emptySet())
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastAuditElapsedMs < PERSONAL_AUDIT_MIN_INTERVAL_MS) return
        if (!auditInFlight.compareAndSet(false, true)) return

        runCatching {
            personalAuditLauncher.launch(
                provisioning.crossProfilePersonalCopyAuditIntent(packages)
            )
        }.onFailure {
            auditInFlight.set(false)
            escudoViewModel.markPersonalCopyAuditFailed()
        }
    }

    private fun openConsumerSecuritySettings(onError: (String) -> Unit) {
        runCatching {
            startActivity(ConsumerProtectionController(this).securityAndPrivacySettingsIntent())
        }.onFailure {
            onError(it.message ?: "No pudimos abrir Seguridad y privacidad")
        }
    }

    @Suppress("unused")
    private fun startSecureProfileProvisioning(onError: (String) -> Unit) {
        runCatching {
            val provisioning = ProvisioningController(this)
            startActivity(provisioning.managedProfileProvisioningIntent())
        }.onFailure {
            onError(it.message ?: "No pudimos iniciar la creación de la bóveda segura")
        }
    }

    private fun cloneFromPersonal(onError: (String) -> Unit) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !packageManager.canRequestPackageInstalls()
            ) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    )
                )
                onError("Activá ‘Permitir desde esta fuente’ una sola vez y volvé a tocar Clonar.")
                return
            }
            val provisioning = ProvisioningController(this)
            cloneSourceLauncher.launch(provisioning.crossProfileCloneSourceIntent())
        }.onFailure {
            onError(it.message ?: "No pudimos abrir las apps del perfil personal")
        }
    }

    private fun removePersonalCopy(packageName: String, onError: (String) -> Unit) {
        runCatching {
            val provisioning = ProvisioningController(this)
            personalCleanupLauncher.launch(
                provisioning.crossProfileRemovePersonalCopyIntent(packageName)
            )
        }.onFailure {
            onError(it.message ?: "No pudimos abrir el desinstalador del perfil personal")
        }
    }

    private fun launchProtected(packageName: String, onError: (String) -> Unit) {
        if (!launchInFlight.compareAndSet(false, true)) return

        val policy = PolicyController(this)
        policy.prepareLaunch(packageName)
            .onSuccess { launchIntent ->
                runCatching {
                    if (policy.hasManagedControl) {
                        VaultGuardService.armAndStart(this, packageName).getOrThrow()
                    }
                    startActivity(launchIntent)
                }.onFailure { error ->
                    policy.protect(packageName)
                    stopService(Intent(this, VaultGuardService::class.java))
                    launchInFlight.set(false)
                    onError(error.message ?: "No se pudo abrir la aplicación")
                }
            }
            .onFailure {
                launchInFlight.set(false)
                onError(it.message ?: "No se pudo abrir la aplicación")
            }
    }

    private companion object {
        const val PERSONAL_AUDIT_MIN_INTERVAL_MS = 15_000L
    }
}

private enum class Screen { LOCKED, VAULT, MANAGE, SETUP_ENVIRONMENT, NATIVE_VERIFY, SETUP_PIN, SETUP_APPS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EscudoRoot(
    biometric: BiometricGate,
    launchProtected: (String, (String) -> Unit) -> Unit,
    openConsumerSecuritySettings: ((String) -> Unit) -> Unit,
    cloneFromPersonal: ((String) -> Unit) -> Unit,
    removePersonalCopy: (String, (String) -> Unit) -> Unit,
    auditPersonalCopies: () -> Unit,
    vm: EscudoViewModel
) {
    val selected by vm.selected.collectAsState()
    val installed by vm.installed.collectAsState()
    val vaultApps by vm.vaultApps.collectAsState()
    val protectionProblems by vm.protectionProblems.collectAsState()
    val selfProtectionHealthy by vm.selfProtectionHealthy.collectAsState()
    val pendingPersonalCopies by vm.pendingPersonalCopies.collectAsState()
    val personalAuditHealthy by vm.personalAuditHealthy.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    var screen by remember {
        mutableStateOf(
            when {
                vm.managedControl && vm.onboardingDone -> Screen.LOCKED
                vm.managedControl -> Screen.SETUP_PIN
                else -> Screen.SETUP_ENVIRONMENT
            }
        )
    }
    var message by remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && (screen == Screen.VAULT || screen == Screen.MANAGE)) {
                // Cerramos la UI, pero NO re-protegemos aquí: ON_STOP también ocurre al
                // entregar el control a una app autorizada. El guardián de sesión y
                // MainActivity.onResume() hacen el relock correcto sin matar el handoff.
                screen = Screen.LOCKED
            }
            if (event == Lifecycle.Event.ON_RESUME) {
                vm.refreshApps()
                vm.refreshProtectionState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun unlockWithBiometric() {
        if (!biometric.isStrongBiometricAvailable()) {
            message = "No encontramos biometría fuerte disponible. Usá tu PIN de Escudo."
            return
        }
        biometric.authenticate(
            onSuccess = { screen = Screen.VAULT },
            onFailure = { reason -> message = reason }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Escudo", fontWeight = FontWeight.SemiBold) },
                actions = {
                    if (screen == Screen.VAULT || screen == Screen.MANAGE) {
                        TextButton(onClick = {
                            vm.lockAll()
                            screen = Screen.LOCKED
                        }) { Text("Cerrar") }
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
        ) {
            when (screen) {
                Screen.SETUP_ENVIRONMENT -> SetupEnvironmentScreen(
                    plan = vm.consumerProtectionPlan,
                    onOpenSecuritySettings = {
                        openConsumerSecuritySettings { message = it }
                    },
                    onConfiguredPrivateSpace = { screen = Screen.NATIVE_VERIFY }
                )
                Screen.NATIVE_VERIFY -> NativePrivateSpaceVerificationScreen(
                    onOpenSecuritySettings = {
                        openConsumerSecuritySettings { message = it }
                    },
                    onBack = { screen = Screen.SETUP_ENVIRONMENT }
                )
                Screen.SETUP_PIN -> SetupPinScreen(
                    onPinReady = { pin ->
                        vm.savePin(pin)
                        screen = Screen.SETUP_APPS
                    }
                )
                Screen.SETUP_APPS -> AppPickerScreen(
                    apps = installed,
                    selected = selected,
                    managedControl = vm.managedControl,
                    confirmLabel = "Activar Escudo",
                    onCloneFromPersonal = if (vm.runningInsideSecureProfile) {
                        { cloneFromPersonal { message = it } }
                    } else null,
                    onConfirm = { packages ->
                        vm.setSelected(packages)
                        vm.finishOnboarding()
                        screen = Screen.LOCKED
                    }
                )
                Screen.LOCKED -> LockedScreen(
                    strongProtection = vm.managedControl,
                    controlLabel = vm.controlLabel,
                    onBiometric = ::unlockWithBiometric,
                    verifyPin = vm::verifyPin,
                    onPinSuccess = { screen = Screen.VAULT }
                )
                Screen.VAULT -> VaultScreen(
                    apps = vaultApps,
                    strongProtection = vm.managedControl,
                    controlLabel = vm.controlLabel,
                    problemPackages = protectionProblems,
                    pendingPersonalCopies = pendingPersonalCopies,
                    selfProtectionHealthy = selfProtectionHealthy,
                    personalAuditHealthy = personalAuditHealthy,
                    onOpen = { pkg -> launchProtected(pkg) { message = it } },
                    onRemovePersonalCopy = { pkg ->
                        removePersonalCopy(pkg) { error -> message = error }
                    },
                    onAuditPersonalCopies = auditPersonalCopies,
                    onManage = { screen = Screen.MANAGE }
                )
                Screen.MANAGE -> AppPickerScreen(
                    apps = installed,
                    selected = selected,
                    managedControl = vm.managedControl,
                    confirmLabel = "Guardar",
                    onCloneFromPersonal = if (vm.runningInsideSecureProfile) {
                        { cloneFromPersonal { message = it } }
                    } else null,
                    onConfirm = { packages ->
                        vm.setSelected(packages)
                        vm.lockAll()
                        screen = Screen.VAULT
                    }
                )
            }

            message?.let {
                Snackbar(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    action = { TextButton(onClick = { message = null }) { Text("OK") } }
                ) { Text(it) }
            }
        }
    }
}

@Composable
private fun SetupEnvironmentScreen(
    plan: ConsumerProtectionPlan,
    onOpenSecuritySettings: () -> Unit,
    onConfiguredPrivateSpace: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text(
            "Escudo sin perfil de empresa",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )

        when (plan.kind) {
            ConsumerProtectionKind.PRIVATE_SPACE -> {
                Text(
                    "Tu teléfono tiene la ruta que queremos para usuarios normales: ${plan.title}. " +
                        "Escudo ya no va a intentar crear un perfil de trabajo administrado."
                )
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Configuración única", fontWeight = FontWeight.SemiBold)
                        Text("1. Abrí Seguridad y privacidad → Espacio privado.")
                        Text("2. Configurá un bloqueo distinto al del teléfono.")
                        Text("3. Instalá Demo Target dentro del Espacio privado.")
                        Text("4. Quitá la copia de Demo Target del espacio principal.")
                        Text("5. Configurá el bloqueo automático al bloquear el dispositivo.")
                    }
                }
                Button(onClick = onOpenSecuritySettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Abrir Seguridad y privacidad")
                }
                OutlinedButton(onClick = onConfiguredPrivateSpace, modifier = Modifier.fillMaxWidth()) {
                    Text("Ya configuré el Espacio privado")
                }
                Text(
                    "Android debe confirmar que el Espacio privado está disponible en este modelo. " +
                        "Escudo no pide permisos de administrador ni cambia tu launcher.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            ConsumerProtectionKind.MANAGED_LAB -> {
                Text(plan.detail)
                Text(
                    "Este modo existe sólo para laboratorio. El flujo de consumo no va a pedir Device Owner ni Profile Owner.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            ConsumerProtectionKind.NO_STRONG_STANDARD_PATH -> {
                Text(plan.title, fontWeight = FontWeight.SemiBold)
                Text(plan.detail)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Qué hace Escudo acá", fontWeight = FontWeight.SemiBold)
                        Text("• No activa un AppLock cosmético y lo llama ‘protección fuerte’.")
                        Text("• No crea perfiles empresariales en tu teléfono personal.")
                        Text("• Esta versión queda en modo diagnóstico mientras evaluamos la protección nativa del fabricante.")
                    }
                }
                OutlinedButton(onClick = onOpenSecuritySettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Abrir Seguridad")
                }
            }
        }
    }
}

@Composable
private fun NativePrivateSpaceVerificationScreen(
    onOpenSecuritySettings: () -> Unit,
    onBack: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            "Probemos la protección nativa",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Escudo todavía no puede inspeccionar el Espacio privado desde una app común sin convertirse en tu launcher principal. " +
                "Por eso esta prueba es explícita y no muestra un verde falso."
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Chequeo rápido", fontWeight = FontWeight.SemiBold)
                Text("1. Bloqueá el Espacio privado.")
                Text("2. Buscá Demo Target en Todas las apps: no debería aparecer fuera.")
                Text("3. Revisá Recientes y notificaciones: su contenido debe quedar oculto mientras el espacio esté bloqueado.")
                Text("4. Desbloqueá el Espacio privado con su bloqueo independiente y abrí Demo Target.")
            }
        }
        Button(onClick = onOpenSecuritySettings, modifier = Modifier.fillMaxWidth()) {
            Text("Abrir configuración de seguridad")
        }
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Volver") }
    }
}

@Composable
private fun SetupPinScreen(onPinReady: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val valid = pin.length >= 6 && pin == confirm && pin.all(Char::isDigit)

    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Una segunda puerta para tus apps sensibles", style = MaterialTheme.typography.headlineSmall)
        Text("Creá un PIN propio de Escudo. No uses el mismo PIN con el que desbloqueás el teléfono.")
        OutlinedTextField(
            value = pin,
            onValueChange = { if (it.length <= 10 && it.all(Char::isDigit)) pin = it },
            label = { Text("PIN de Escudo (6+ dígitos)") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = confirm,
            onValueChange = { if (it.length <= 10 && it.all(Char::isDigit)) confirm = it },
            label = { Text("Repetí el PIN") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = { onPinReady(pin) }, enabled = valid, modifier = Modifier.fillMaxWidth()) {
            Text("Continuar")
        }
    }
}

@Composable
private fun LockedScreen(
    strongProtection: Boolean,
    controlLabel: String,
    onBiometric: () -> Unit,
    verifyPin: (String) -> PinVerification,
    onPinSuccess: () -> Unit
) {
    var showPin by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var pinFeedback by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("🛡️", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(12.dp))
        Text("Escudo cerrado", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(if (strongProtection) "Tus apps protegidas están cerradas." else "MVP en modo demostración.")
        Spacer(Modifier.height(8.dp))
        AssistChip(onClick = {}, label = { Text(controlLabel) })
        Spacer(Modifier.height(28.dp))

        Button(onClick = onBiometric, modifier = Modifier.fillMaxWidth()) {
            Text("Abrir con huella / biometría segura")
        }
        TextButton(onClick = { showPin = !showPin }) { Text("Usar PIN de Escudo") }

        if (showPin) {
            OutlinedTextField(
                value = pin,
                onValueChange = {
                    if (it.length <= 10 && it.all(Char::isDigit)) {
                        pin = it
                        pinFeedback = null
                    }
                },
                label = { Text("PIN de Escudo") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                isError = pinFeedback != null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    when (val result = verifyPin(pin)) {
                        PinVerification.Success -> {
                            pin = ""
                            pinFeedback = null
                            onPinSuccess()
                        }
                        PinVerification.DeviceKeyMissing -> {
                            pinFeedback = "La clave segura del dispositivo no está disponible. Escudo bloqueó el acceso para no degradar la seguridad."
                        }
                        is PinVerification.Rejected -> {
                            pinFeedback = "PIN incorrecto · ${result.attemptsRemaining} intentos antes de la pausa"
                        }
                        is PinVerification.Locked -> {
                            pinFeedback = "Demasiados intentos · probá nuevamente en ${result.secondsRemaining} s"
                        }
                    }
                },
                enabled = pin.length >= 6,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Entrar") }
            pinFeedback?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun VaultScreen(
    apps: List<InstalledApp>,
    strongProtection: Boolean,
    controlLabel: String,
    problemPackages: Set<String>,
    pendingPersonalCopies: Set<String>,
    selfProtectionHealthy: Boolean,
    personalAuditHealthy: Boolean,
    onOpen: (String) -> Unit,
    onRemovePersonalCopy: (String) -> Unit,
    onAuditPersonalCopies: () -> Unit,
    onManage: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Tu bóveda", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val allProtected = strongProtection && selfProtectionHealthy && personalAuditHealthy &&
                    problemPackages.isEmpty() && pendingPersonalCopies.isEmpty()
                Text(
                    when {
                        allProtected -> "Protección fuerte activa"
                        strongProtection -> "Revisar protección"
                        else -> "Modo demostración"
                    },
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    when {
                        allProtected -> "$controlLabel · Bóveda confirmada y Escudo protegido contra desactivación desde Ajustes."
                        strongProtection && !selfProtectionHealthy -> "La bóveda puede funcionar, pero Escudo no confirmó su defensa contra force-stop/borrado de datos. Android 11+ es necesario para esa capa."
                        strongProtection && !personalAuditHealthy -> "Escudo todavía no pudo confirmar que no exista una copia accesible de tus apps protegidas en el perfil personal."
                        strongProtection && pendingPersonalCopies.isNotEmpty() -> "${pendingPersonalCopies.size} app(s) tienen una copia accesible en el perfil personal."
                        strongProtection -> "${problemPackages.size} app(s) no confirmaron el estado protegido. Escudo no las marca como seguras."
                        else -> "Escudo todavía no tiene privilegios para ocultar otras apps. La autenticación funciona, pero los iconos externos siguen accesibles."
                    }
                )
            }
        }

        if (strongProtection && !personalAuditHealthy) {
            OutlinedButton(onClick = onAuditPersonalCopies, modifier = Modifier.fillMaxWidth()) {
                Text("Revisar copias exteriores")
            }
        }

        if (apps.isEmpty()) {
            Text("Todavía no elegiste aplicaciones para proteger.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f, fill = false)) {
                items(apps, key = { it.packageName }) { app ->
                    val hasProblem = app.packageName in problemPackages
                    val hasPersonalCopy = app.packageName in pendingPersonalCopies
                    ElevatedCard(
                        onClick = { if (!hasProblem || !strongProtection) onOpen(app.packageName) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp) {
                                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                    Text(app.label.take(1).uppercase(), fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(app.label, fontWeight = FontWeight.SemiBold)
                                Text(
                                    when {
                                        hasProblem && strongProtection -> "Protección incompleta"
                                        hasPersonalCopy -> "Falta quitar la copia personal"
                                        else -> "Abrir desde Escudo"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if ((hasProblem && strongProtection) || hasPersonalCopy) {
                                        MaterialTheme.colorScheme.error
                                    } else LocalContentColor.current
                                )
                            }
                            if (hasPersonalCopy) {
                                TextButton(onClick = { onRemovePersonalCopy(app.packageName) }) {
                                    Text("Completar")
                                }
                            } else {
                                Text(
                                    if (hasProblem && strongProtection) "!" else "›",
                                    style = MaterialTheme.typography.headlineSmall
                                )
                            }
                        }
                    }
                }
            }
        }

        OutlinedButton(onClick = onManage, modifier = Modifier.fillMaxWidth()) { Text("Administrar apps") }
    }
}

@Composable
private fun AppPickerScreen(
    apps: List<InstalledApp>,
    selected: Set<String>,
    managedControl: Boolean,
    confirmLabel: String,
    onCloneFromPersonal: (() -> Unit)? = null,
    onConfirm: (Set<String>) -> Unit
) {
    var draft by remember(selected) { mutableStateOf(selected) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("¿Qué querés guardar en Escudo?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Elegí bancos, billeteras, correo, autenticadores o cualquier app sensible.")
        onCloneFromPersonal?.let { clone ->
            OutlinedButton(onClick = clone, modifier = Modifier.fillMaxWidth()) {
                Text("Clonar app desde mi teléfono")
            }
            Text(
                "La app se instala limpia dentro del perfil Escudo; no copiamos sesiones, datos ni contraseñas.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (!managedControl) {
            Text("Protección fuerte no disponible.", color = MaterialTheme.colorScheme.error)
        }
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(apps, key = { it.packageName }) { app ->
                val checked = app.packageName in draft
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { value ->
                            draft = if (value) draft + app.packageName else draft - app.packageName
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(app.label, fontWeight = FontWeight.Medium)
                        Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Button(onClick = { onConfirm(draft) }, enabled = draft.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(confirmLabel)
        }
    }
}
