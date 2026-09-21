# Modelo de seguridad — Escudo v0.7

## Frontera principal

Las apps sensibles viven dentro de un Managed Profile donde Escudo es Profile Owner. En estado cerrado cada paquete seleccionado debe quedar `hidden + suspended + uninstallBlocked`. Escudo no declara una app protegida si Android no confirma esas tres propiedades.

## Comunicación entre perfiles

No confiamos en la identidad aparente de la Activity que cruza perfiles: Android puede interponer su propio IntentForwarder. Antes de provisionar, la instancia personal genera un secreto aleatorio de 256 bits y lo entrega a la instancia administrada mediante `EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE`. Toda Activity auxiliar del perfil personal exige ese secreto.

La Activity de selección no transporta APKs. Devuelve un Binder de `CloneBridgeService`. Los archivos se abren como `ParcelFileDescriptor` de solo lectura y viajan por AIDL/Binder. El servicio verifica en cada IPC que `UserHandle.getAppId(Binder.getCallingUid())` coincida con el appId de Escudo.

No se copian SharedPreferences, bases de datos, cookies, cuentas, tokens, contraseñas ni archivos privados de la app origen. La copia dentro del perfil seguro arranca limpia.

## Copia personal

Tras una clonación exitosa Escudo considera que existe una ruta exterior hasta que Android confirma que la copia del perfil personal fue desinstalada. La eliminación siempre usa la UI oficial del sistema y se vuelve a consultar PackageManager al terminar.

### Limitación BYOD

El Profile Owner no administra el perfil personal. Escudo **no puede impedir** que más adelante se reinstale la misma app fuera de la bóveda. Por eso la eliminación exterior reduce superficie de ataque, pero no es una garantía permanente. Una versión de producción debe auditar periódicamente la presencia de copias exteriores y mostrar cualquier degradación con claridad.

## Fail closed

Una app liberada temporalmente conserva `uninstallBlocked`; al terminar la sesión, al apagar pantalla, al expirar la alarma, al reiniciar o al volver a Escudo, el controlador intenta restaurar el estado cerrado. Si el cierre falla no borra el estado de sesión y puede reintentar.

## Datos de Escudo

- Sin permiso INTERNET en el MVP.
- `FLAG_SECURE` para impedir capturas/preview de Recents.
- Bloqueo de overlays donde Android lo soporta.
- PIN derivado y ligado a Android Keystore.
- `allowBackup=false`.

## v0.8 — auditoría de copias exteriores

Un `Profile Owner` controla únicamente el perfil Escudo. No puede prohibir de forma permanente que el usuario instale otra copia de una app en el perfil personal.

Para no ocultar esa limitación, Escudo v0.8 hace fail-closed a nivel de estado: al volver al frente consulta al perfil personal, mediante un intent cross-profile autenticado con el secreto de provisioning, si alguno de los package names protegidos existe también afuera. Si la auditoría falla o encuentra una copia exterior, la UI deja de declarar **Protección fuerte activa**.

La auditoría sólo cruza package names y un conjunto de booleanos implícitos (instalada/no instalada). No cruza sesiones, archivos, tokens, credenciales ni contenido de las aplicaciones.

Importante: reinstalar una app bancaria afuera crea una instalación limpia; no copia la sesión autenticada que vive dentro del perfil Escudo. Aun así se considera degradación porque puede abrir una ruta de recuperación/autenticación paralela.

## v0.8 — entrada única

La instancia personal de Escudo conserva el único icono launcher. Una vez provisionado el perfil seguro, ese launcher busca exclusivamente el forwarder de sistema para `OPEN_SECURE_ESCUDO` y abre `MainActivity` dentro del perfil administrado. El alias launcher se deshabilita dentro del perfil Escudo, evitando dos iconos y reduciendo confusión de usuario.

El perfil personal sigue alojando únicamente las piezas auxiliares necesarias para clonación, auditoría y limpieza. La autenticación de la bóveda y las políticas sobre apps sensibles ocurren dentro del perfil administrado.

## Biometric enrollment changes (v0.9)

A plain `BiometricPrompt` is not sufficient for Escudo's threat model: if an attacker learns the phone unlock PIN and Android lets them enroll a new fingerprint, that new fingerprint would normally become a valid biometric for apps too.

Escudo v0.9 binds biometric unlock to a non-exportable Android Keystore AES key that:

- requires `BIOMETRIC_STRONG` authentication for every use;
- is invalidated by Android when the biometric enrollment set changes;
- is consumed through a `BiometricPrompt.CryptoObject`;
- is recreated only after the user proves knowledge of the independent Escudo PIN.

Therefore, learning only the phone PIN and registering another fingerprint must not silently grant Escudo access. A legitimate owner who adds/removes a fingerprint will be asked for the Escudo PIN once to trust the new enrollment set.
