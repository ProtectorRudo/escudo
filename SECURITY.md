# Escudo Security — v0.11

## Garantía de producto

Escudo no llama “protección fuerte” a un overlay, a Accessibility ni a una UI que simplemente cubra otra app.

## Consumidor Android 15+

La recomendación fuerte es Android Private Space. El aislamiento y el bloqueo los aplica el sistema operativo, no Escudo. Escudo guía la configuración pero, sin ser launcher predeterminado, no puede inspeccionar ni controlar directamente el perfil privado.

Por eso v0.11 no presenta un badge verde basado sólo en que el usuario diga que completó el setup. La validación inicial es manual y explícita.

## APK consumer

La variante `consumer` elimina del manifest los componentes de Device Admin, provisioning empresarial, bridge cross-profile, instalación/desinstalación de APKs, BootReceiver y FGS de sesión. Esos componentes existen sólo en la variante `lab`.

## Motor administrado de laboratorio

El código DPC previo se conserva para investigación: hidden+suspended+uninstallBlocked, guard de sesión, Keystore PIN, biometría invalidada al cambiar enrollment, bridge cross-profile y auditoría de copias personales. No representa la experiencia final de consumo.

## Fuera de alcance

- Root / OS comprometido.
- Ataques con ADB autorizado.
- Prometer aislamiento fuerte en Android antiguos sin soporte nativo.
- Guardar PINs o credenciales de bancos.
