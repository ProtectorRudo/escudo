# Escudo v0.4 — plan de prueba antes de tocar dinero real

## Regla

No probar Mercado Pago, bancos ni autenticadores hasta que `Escudo Demo Target` complete toda esta matriz en un dispositivo secundario o emulador compatible con Device Owner/Profile Owner.

## E2E base

1. Instalar Escudo y Demo Target.
2. Aprovisionar Escudo como Device Owner en un equipo de laboratorio.
3. Elegir Demo Target y confirmar `Activar Escudo`.
4. Verificar que Demo Target deja de estar disponible desde el launcher.
5. Intentar abrir Demo Target directamente: debe fallar.
6. Abrir Escudo y autenticarse.
7. Abrir Demo Target desde la bóveda: debe funcionar.
8. Volver a Escudo: Demo Target debe quedar protegido otra vez.
9. Apagar la pantalla durante una sesión: debe relockear.
10. Esperar 120 s durante una sesión: debe relockear y cerrar la Activity del target.
11. Reiniciar el teléfono: debe arrancar cerrado.
12. Actualizar Escudo conservando datos: `MY_PACKAGE_REPLACED` debe relockear.

## Kill / resiliencia de proceso

- Abrir Demo Target y matar **sólo el proceso de Escudo** desde ADB. El target debe volver a cerrarse por el guardián secundario del sistema; nunca debe perderse el marcador de sesión activa.
- Repetir el kill justo antes del timeout de 120 s.
- Repetir con la pantalla apagándose inmediatamente después del kill.
- Simular un fallo de `protect()` y comprobar que `activeSessionPackage` no se borra hasta que un reintento confirma `hidden+suspended+uninstallBlocked`.
- Verificar que una recreación posterior de Escudo encuentra y cierra cualquier sesión pendiente.

## Anti-bypass

- **Launcher:** no debe poder abrir una app protegida.
- **Recents:** una app protegida no debe permanecer utilizable desde Recents.
- **Deep link:** un intent externo no debe poder iniciar una Activity de una app suspendida.
- **Settings / ficha del target:** no debe poder desinstalarse mientras pertenece a Escudo.
- **Settings / ficha de Escudo (Android 11+):** force-stop y clear-data deben quedar deshabilitados por política cuando el modo administrado lo permite.
- **Desinstalación/reinstalación del target:** debe estar bloqueada para que no se pierda la suspensión.
- **Doble tap:** nunca deben quedar dos apps sensibles liberadas al mismo tiempo.
- **Fallo de launch:** si `startActivity()` falla, el paquete debe volver a estado protegido.
- **Fallo parcial DPM:** una app no debe desaparecer de la lista de Escudo si Android no consigue revertir todas las políticas.
- **Background de Escudo sin app abierta:** la UI debe volver a pantalla cerrada.

## PIN / biometría

- PIN correcto e incorrecto.
- Backoff: 5 errores -> 30 s; 6 -> 2 min; 7+ -> 10 min.
- Confirmar que el registro nuevo usa `format_version=2`, `salt` y `verifier`; no debe persistirse el PBKDF2 crudo.
- Confirmar que existe `escudo_pin_pepper_v1` en Android Keystore y que no es exportable.
- Instalar v0.3, configurar PIN, actualizar a v0.4 e ingresar el PIN: debe migrar a formato 2 después del primer éxito.
- Simular pérdida de la clave Keystore con un entorno de prueba: Escudo debe mostrar fallo de seguridad y **no** recrear silenciosamente una clave aceptando el PIN viejo.
- La biometría no debe permitir fallback al PIN/patrón del teléfono. El fallback es únicamente el PIN propio de Escudo.

## UI / privacidad

- Las pantallas de Escudo no deben aparecer en screenshots ni preview de Recents (`FLAG_SECURE`).
- Overlays de apps de terceros deben quedar ocultos en Android 12+.
- Escudo no solicita `INTERNET`.
- Escudo no solicita permiso de notificaciones durante onboarding.
- Si las notificaciones de Escudo están denegadas, la sesión temporal igual debe funcionar y seguir figurando en el Task Manager del sistema como FGS.

## Compatibilidad mínima a validar

Antes de piloto financiero, probar por lo menos:

- Android 11/12;
- Android 13;
- Android 14;
- Android 15+;
- Samsung One UI;
- Motorola / Android cercano a stock;
- Xiaomi/HyperOS si el mercado objetivo lo justifica.

## Criterio para primer piloto con una app financiera

Todos los puntos anteriores deben pasar en un teléfono físico, tres ciclos consecutivos, sin intervención ADB durante el uso normal. El primer piloto financiero debe hacerse con saldo mínimo y nunca en el teléfono principal.

## v0.7 — puente Binder entre perfiles

- Provisionar el Managed Profile y confirmar que `PROFILE_PROVISIONING_COMPLETE` configura filtros y pairing.
- Desde el perfil Escudo abrir `Clonar desde mi teléfono`; comprobar que sólo aparece el forwarder de sistema como salto cross-profile.
- Intentar lanzar `CloneSourceActivity` directamente sin pairing secret: debe devolver `RESULT_CANCELED`.
- Obtener el Binder desde el perfil Escudo y verificar `isInstalled()` sobre Demo Target.
- Transferir base APK y split APKs mediante `ICloneBridge.openApks()` y confirmar que todos los FDs son read-only.
- Intentar usar el Binder desde un APK con appId distinto: el servicio debe rechazar el IPC.
- Matar `CloneBridgeService` antes de pedir FDs: la clonación debe fallar limpiamente, sin seleccionar/proteger un paquete incompleto.
- Completar la instalación y comprobar que `hidden+suspended+uninstallBlocked` se aplican antes de marcarla en la bóveda.
- Cancelar el desinstalador personal: `pendingPersonalCopies` debe permanecer.
- Confirmar el desinstalador personal: sólo limpiar `pendingPersonalCopies` si PackageManager ya no encuentra el paquete.

## v0.8 — reinstalación exterior

1. Completar protección de `Escudo Demo Target` y eliminar la copia personal.
2. Abrir Escudo y confirmar que la auditoría exterior queda saludable.
3. Reinstalar `Escudo Demo Target` en el perfil personal.
4. Volver a Escudo.
5. Esperado: en menos de un ciclo de resume/auditoría, el estado deja de decir `Protección fuerte activa` y la app figura con copia personal accesible.
6. Tocar `Completar`, confirmar desinstalación y volver.
7. Esperado: la auditoría confirma ausencia exterior y recupera el estado fuerte.
8. Repetir cancelando el desinstalador. Esperado: Escudo conserva la advertencia.
9. Romper deliberadamente el forwarder cross-profile / secreto. Esperado: auditoría no saludable; nunca estado verde por defecto.

## Biometric enrollment attack test (v0.9)

Goal: verify that knowledge of the phone unlock PIN does not let a newly enrolled fingerprint inherit Escudo access.

1. Configure Escudo PIN and verify biometric unlock works with the owner's enrolled fingerprint.
2. Lock Escudo.
3. Using Android settings, authenticate with the **phone** credential and add another strong fingerprint.
4. Open Escudo and request biometric unlock.
5. Expected: Escudo refuses biometric unlock because its Keystore key was invalidated by the enrollment change.
6. Verify that neither the old nor the new fingerprint can bypass this state through Escudo's biometric button.
7. Enter the independent Escudo PIN.
8. Expected: Escudo opens and establishes a new trusted biometric baseline.
9. Lock Escudo and verify strong biometric unlock works again.

Also repeat by removing an enrolled fingerprint. A legitimate enrollment change should create one PIN revalidation event, not permanent lockout.
