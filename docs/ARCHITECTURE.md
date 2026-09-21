# Escudo v0.4 architecture

## Closed state

For every selected package in managed mode:

`hidden = true` + `suspended = true` + `uninstallBlocked = true`

Escudo itself additionally requests `userControlDisabled` on Android 11+ so ordinary Settings controls cannot trivially force-stop or clear the DPC.

## Open-session state

Only the requested package is released:

`hidden = false` + `suspended = false` + `uninstallBlocked = true`

Before that release, Escudo attempts to close all selected packages so there is never an intentional multi-app open session.

## Session guards

Two independent mechanisms remember the temporary release:

1. `VaultGuardService`: foreground service, screen-off receiver, in-process 120 s timer and manual close action.
2. `SessionExpiryScheduler` + `SessionExpiryReceiver`: an OS AlarmManager fail-safe that survives Escudo process death.

The stored `activeSessionPackage` is security state, not cosmetic state. It is cleared only after Android confirms the package is protected again.

## Authentication

Biometric access uses `BIOMETRIC_STRONG` without device-credential fallback.

PIN access uses:

`PIN -> PBKDF2-HMAC-SHA256(salt, 120k iterations) -> HMAC-SHA256(Android Keystore secret) -> stored verifier`

The Keystore secret is non-exportable. v0.3 PBKDF2-only records are migrated after one successful legacy PIN verification.

## No network trust

The MVP has no `INTERNET` permission. Vault decisions, PIN verification, app policy state and relocking are local.

## Deployment boundary

`DevicePolicyManager` policy is scoped to the user/profile in which Escudo is the Device Owner/Profile Owner. A Profile Owner cannot magically hide the user's personal-profile copy of Mercado Pago; the protected copy must live inside the managed profile. A Device Owner controls a fully managed device and therefore has a much heavier provisioning model.
