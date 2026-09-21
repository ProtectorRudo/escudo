# Changelog


## 0.8.0

- Agrega auditoría silenciosa del perfil personal cada vez que Escudo vuelve al frente.
- Escudo consulta si alguna app protegida volvió a instalarse fuera de la bóveda.
- La auditoría usa el mismo secreto aleatorio creado durante provisioning; no expone datos de apps ni cuentas.
- "Protección fuerte" ahora exige una auditoría exterior exitosa además de hidden+suspended+uninstall-blocked.
- Si reaparece una copia personal, Escudo la marca inmediatamente como ruta exterior y ofrece quitarla otra vez.
- Cambiar la selección de apps invalida el estado de auditoría hasta volver a verificar el perfil personal.
- La auditoría tiene throttling para evitar loops al volver desde el forwarder de Android.
- Sigue sin permiso INTERNET y no transmite inventario de apps fuera del dispositivo.

## 0.7.0

- Reemplaza el transporte de APKs por Activity result con un puente Binder/AIDL.
- Agrega `ICloneBridge` + `CloneBridgeService` y pasa `ParcelFileDescriptor` por IPC Binder.
- Cada llamada Binder valida que el caller tenga el mismo Android appId que Escudo.
- Agrega pairing aleatorio de 256 bits entre perfil personal y Managed Profile mediante `EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE`.
- El puente ya no depende de un permiso `signature`, evitando incompatibilidad con el IntentForwarder del sistema.
- Declara explícitamente `android.app.action.PROFILE_PROVISIONING_COMPLETE`.
- El forwarder cross-profile debe pertenecer a una aplicación de sistema.
- La limpieza de la copia personal verifica PackageManager después del desinstalador y sólo confirma cuando el paquete ya no existe.
- Activa AIDL en el módulo Android y eleva la versión a 0.7.0.
- Documenta que BYOD/Profile Owner no puede impedir una futura reinstalación en el perfil personal.

## 0.6.0

- Agrega puente de clonación **perfil Escudo → perfil personal** con intent filters cross-profile.
- `CloneSourceActivity` lista apps del perfil personal y entrega únicamente APK base + split APKs como `ParcelFileDescriptor` de solo lectura.
- La clonación no copia datos, sesiones, tokens ni credenciales de la app original.
- Agrega instalación del bundle dentro del perfil Escudo mediante `PackageInstaller`.
- Agrega permiso de firma propio para que las Activities de clonación/limpieza sólo puedan ser invocadas por otra copia auténtica de Escudo.
- Agrega flujo “Completar”: la bóveda avisa cuando todavía existe una copia personal accesible y abre el desinstalador oficial de Android para retirarla.
- Escudo no considera completa la protección global mientras haya copias personales pendientes.
- Agrega chequeo/flujo de “Permitir desde esta fuente” cuando Android lo exige para clonar APKs.
- El perfil Escudo deshabilita localmente las Activities que deben ejecutarse exclusivamente del lado personal, forzando el cruce por el forwarder de Android.
- Mantiene todas las defensas de v0.5/v0.4.

## 0.5.0

- Agrega provisioning oficial de Managed Profile para teléfonos personales.
- Elimina el onboarding engañoso sin control real.
- Agrega instalador base+splits como fundamento de la clonación.

## 0.9.0 - biometric enrollment lock

- Biometric unlock is now backed by an Android Keystore `CryptoObject`, not by a plain biometric prompt.
- The biometric key uses `setInvalidatedByBiometricEnrollment(true)`, so adding/removing a strong biometric invalidates Escudo biometric access.
- A biometric enrollment change now forces the independent Escudo PIN before current fingerprints can be trusted again.
- The phone PIN / pattern / password is never accepted as biometric fallback by Escudo.
- The trusted biometric enrollment baseline is established when the Escudo PIN is created and refreshed only after that Escudo PIN is verified successfully.
