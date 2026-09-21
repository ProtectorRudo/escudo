# Escudo v0.11 — arquitectura consumer-first

## Problema

Una app Android común no puede ocultar/suspender otras apps con garantías fuertes. Device Owner/Profile Owner sí puede, pero su provisioning es una experiencia empresarial y no es aceptable como onboarding masivo.

## Estrategias

### 1. Android 15+ — Private Space

Ruta de consumo recomendada:

- Perfil separado propiedad de Android.
- Puede usar bloqueo distinto al del teléfono.
- Al bloquearse, las apps del espacio se detienen y se ocultan de launcher/Recientes/notificaciones/otras apps.
- Las apps se instalan como copias nuevas; sus datos no se mueven desde el perfil principal.
- Escudo no asume ROLE_HOME, por lo que no enumera/controla el perfil privado programáticamente.

### 2. Managed DPC — laboratorio

El motor existente de hidden+suspended+uninstallBlocked, bridge cross-profile y session guard se mantiene para pruebas técnicas. No se ofrece como onboarding de usuarios comunes.

### 3. Android <15 / Private Space bloqueado

No hay una ruta universal de aislamiento fuerte accesible a una app común. Escudo muestra la limitación y no degrada silenciosamente a un overlay AppLock.

## Variantes de build

- `consumer`: superficie mínima, sin administración empresarial.
- `lab`: DPC y herramientas de investigación.

## Caminos descartados como base

- Work Profile para consumidor.
- Accessibility/overlay AppLock presentado como aislamiento fuerte.
- Convertir Escudo en launcher sólo para obtener ACCESS_HIDDEN_PROFILES/LOCK_APPS.

## Futuro

Android está incorporando App Lock de sistema, pero sus APIs de control actuales están ligadas al rol HOME/launcher. Escudo seguirá observando APIs públicas que permitan solicitar protección nativa sin reemplazar el launcher.
