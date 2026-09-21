# Escudo Android MVP — v0.9

Escudo es una bóveda de aplicaciones sensibles para Android. En el camino consumidor crea un **Managed Profile** aislado, instala allí una copia limpia de las apps elegidas y usa `DevicePolicyManager` para ocultarlas/suspenderlas cuando la bóveda está cerrada.

## Flujo actual

1. Instalar Escudo en el perfil personal.
2. Crear la bóveda segura mediante el provisioning oficial de Android.
3. Android instala otra instancia de Escudo dentro del perfil administrado y la convierte en Profile Owner.
4. El launcher duplicado del perfil administrado se oculta; queda un solo icono Escudo en el teléfono.
5. Al tocar ese icono, la instancia personal reenvía automáticamente a la bóveda administrada.
6. El usuario configura PIN/biometría dentro del perfil Escudo.
7. `Clonar desde mi teléfono` abre un selector en el perfil personal.
8. Las dos instancias se autentican con un secreto aleatorio de 256 bits entregado por `EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE`.
9. La Activity personal devuelve **un Binder**, no archivos.
10. El perfil seguro obtiene `base.apk` + split APKs como `ParcelFileDescriptor` mediante AIDL/Binder.
11. `PackageInstaller` instala una copia limpia dentro del perfil Escudo; datos, sesiones y credenciales no se copian.
12. La nueva app entra inmediatamente oculta+suspendida+con desinstalación bloqueada.
13. Escudo marca la copia personal como pendiente y ofrece `Completar`, que abre el desinstalador oficial de Android en el perfil personal.
14. Si el usuario cancela, Escudo mantiene visible que todavía existe una ruta exterior.

## Qué cambió en v0.8

- Escudo audita silenciosamente el perfil personal al volver al frente.
- Si una app protegida reaparece afuera, deja de declarar `Protección fuerte activa`.
- La auditoría cross-profile usa el secreto generado durante provisioning y sólo devuelve package names presentes/ausentes.
- Un fallo del forwarder o del secreto también degrada el estado: no hay verde por defecto.
- La auditoría se invalida al modificar la selección de apps y se repite después de quitar una copia personal.
- Se agrega throttling para que el forwarder no genere un loop de `onResume()`.
- Mantiene un único icono Escudo: el launcher personal reenvía a la bóveda y el launcher del perfil seguro queda oculto.
- Sigue sin permiso `INTERNET`.

## Limitación importante

Un Profile Owner de un teléfono personal **no controla el perfil personal**. Por lo tanto Escudo no puede impedir para siempre que alguien vuelva a instalar otra copia de una app fuera de la bóveda. v0.8 detecta esa situación y la muestra como degradación de seguridad, pero no puede bloquear la reinstalación exterior.

Esa copia exterior es una instalación limpia: no hereda la sesión autenticada ni los datos que viven dentro del perfil Escudo. Aun así podría abrir una ruta paralela de recuperación de cuenta, por eso Escudo la considera un problema.

## Estado

El código pasa los checks locales de núcleo, regresión de seguridad y XML. Aún falta la validación decisiva en Android real: provisioning, Binder entre perfiles, instalación base+splits, auto-protección y eliminación exterior.

### v0.9: una huella nueva no hereda Escudo

Escudo ya no usa un `BiometricPrompt` suelto. La huella autoriza una clave criptográfica de Android Keystore ligada al conjunto biométrico existente. Si Android registra o elimina una huella fuerte, esa clave queda inválida y Escudo exige el **PIN propio de Escudo** una vez antes de aceptar nuevamente biometría. El PIN/patrón del teléfono no se usa como alternativa.
