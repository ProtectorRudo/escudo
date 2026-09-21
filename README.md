# Escudo Android MVP v0.11

Escudo investiga una forma de proteger apps financieras y sensibles frente a robo del teléfono sin convertir el dispositivo personal en un equipo empresarial.

## Dirección v0.11: consumer-first

La ruta normal ya **no provisiona Work Profile / Profile Owner**. En Android 15+ Escudo prioriza el **Espacio privado nativo de Android**, que ofrece un perfil separado, bloqueo independiente y oculta apps, notificaciones y Recientes cuando está bloqueado.

Escudo no puede controlar programáticamente ese Espacio privado desde una app común sin asumir el rol de launcher principal. Por eso v0.11 funciona como detector y asistente de configuración y no afirma una protección que no pueda verificar.

El motor DPC anterior se conserva únicamente como laboratorio técnico para estudiar políticas de Android, no como onboarding de consumo.

## Principios

- No Internet.
- Nunca guardar credenciales bancarias.
- No usar el PIN del teléfono como PIN de Escudo.
- No mostrar “protección fuerte” sin evidencia real.
- Un fallo de Escudo no debe bloquear al dueño fuera de su dinero.
- Evitar Accessibility/overlays como falsa bóveda fuerte.

## Variantes

- `consumer`: producto normal. Sin Device Admin, sin provisioning empresarial, sin instalación/desinstalación de APKs.
- `lab`: conserva el motor administrado para investigación y pruebas de seguridad.

## Build

Requisitos: JDK 17, Gradle 8.9, Android SDK 35.

```bash
gradle :app:testConsumerDebugUnitTest :app:testLabDebugUnitTest
gradle :app:lintConsumerDebug :app:lintLabDebug :demo-target:lintDebug
gradle :app:assembleConsumerDebug :app:assembleLabDebug :demo-target:assembleDebug
```

La app demo `com.escudo.demo` existe para probar flujos sin arriesgar apps con dinero real.
