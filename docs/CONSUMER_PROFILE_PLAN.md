# Escudo v0.5 — ruta para teléfonos personales

## Hallazgo que cambia la arquitectura

Las APIs fuertes (`setApplicationHidden`, `setPackagesSuspended`, `setUninstallBlocked`) pertenecen a un Device/Profile Owner. Un Device Owner está pensado para un dispositivo completamente administrado y no es una instalación normal sobre un teléfono personal ya configurado.

La ruta de producto pasa entonces por **Managed Profile / Work Profile en un dispositivo personal**:

1. La instancia personal de Escudo inicia `ACTION_PROVISION_MANAGED_PROFILE` con consentimiento del usuario.
2. Android crea un perfil aislado y una segunda instancia de Escudo se convierte en Profile Owner dentro de ese perfil.
3. Las apps sensibles deben existir **dentro de ese perfil**. Sólo allí Escudo puede ocultarlas/suspenderlas de forma fuerte.
4. La copia personal de la app bancaria debe eliminarse o quedar fuera de uso; de lo contrario seguiría existiendo una ruta no protegida.

## Qué implementa v0.5

- Detección de si el dispositivo permite crear un Managed Profile.
- Inicio del flujo oficial `ACTION_PROVISION_MANAGED_PROFILE`.
- Compatibilidad con las callbacks de provisioning modernas (`GET_PROVISIONING_MODE` y `ADMIN_POLICY_COMPLIANCE`).
- Activación/nombre del perfil desde `onProfileProvisioningComplete`.
- Se elimina el onboarding engañoso en modo no administrado: Escudo no permite completar una bóveda “fuerte” si Android no le dio control real.

## Próximo bloque técnico: clonación de apps

La referencia conceptual es el patrón usado por proyectos como Shelter: obtener el APK base + split APKs de la app personal, transferirlos al perfil administrado y reinstalarlos mediante `PackageInstaller` dentro del perfil. Escudo debe implementar este mecanismo desde cero con APIs públicas y sin copiar código GPL.

Pruebas necesarias:

- Pixel / Android AOSP.
- Samsung One UI.
- Motorola.
- Xiaomi/HyperOS (hay antecedentes de restricciones OEM en clonación entre perfiles).
- Mercado Pago, Cuenta DNI, BIP Móvil, Brubank, Ualá: comprobar si ejecutan normalmente dentro de un Managed Profile.

## Regla de seguridad

Hasta que la app protegida esté instalada dentro del perfil y la copia personal se haya eliminado/deshabilitado, Escudo NO debe mostrar “Protegida”.

## Instalador ya preparado en v0.5

`ApkBundleInstaller` ya sabe instalar un bundle compuesto por `base.apk` + split APKs en el perfil donde corre Escudo usando `PackageInstaller`. El callback es explícito y no exportado. Si Android exige confirmación del usuario, `CloneInstallResultActivity` delega a la pantalla oficial del sistema. Tras una instalación exitosa, Escudo intenta dejar la nueva app inmediatamente en estado protegido.

Aún falta el **puente de origen** que entregue esos APKs desde el perfil personal al perfil Escudo. Ese puente será el foco de la próxima tanda.

## v0.6 — puente implementado

Se implementó un primer puente sin AIDL ni servidor:

- el Profile Owner permite dos intents managed→parent;
- la Activity fuente sólo existe de forma utilizable en el perfil personal;
- entrega `ParcelFileDescriptor` read-only del APK base y splits;
- el perfil Escudo escribe esos streams en una `PackageInstaller.Session`;
- tras el éxito la app entra inmediatamente a la lista protegida;
- queda marcada una tarea pendiente de eliminar la copia personal;
- el botón Completar cruza otra vez al perfil personal y abre `ACTION_UNINSTALL_PACKAGE` con UI oficial.

Este diseño debe validarse en hardware real antes de considerarse portable.
