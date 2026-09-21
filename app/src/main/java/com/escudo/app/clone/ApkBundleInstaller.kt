package com.escudo.app.clone

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.IOException

/**
 * Installs a base APK plus optional split APKs into the *current* Android profile.
 *
 * v0.5 intentionally keeps this transport-agnostic. The next stage will provide the URIs from the
 * personal-profile Escudo instance through a narrow cross-profile bridge. Keeping installer and
 * transport separate makes the security boundary auditable.
 */
class ApkBundleInstaller(private val context: Context) {
    fun install(expectedPackageName: String, apks: List<Uri>): Result<Int> = runCatching {
        require(expectedPackageName.isNotBlank()) { "Paquete inválido" }
        require(apks.isNotEmpty()) { "No hay APKs para instalar" }

        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(expectedPackageName) }
        val sessionId = installer.createSession(params)

        try {
            installer.openSession(sessionId).use { session ->
                apks.forEachIndexed { index, uri ->
                    val entryName = if (index == 0) "base.apk" else "split-$index.apk"
                    context.contentResolver.openInputStream(uri).use { input ->
                        checkNotNull(input) { "No se pudo abrir $uri" }
                        session.openWrite(entryName, 0, -1).use { output ->
                            input.copyTo(output)
                            session.fsync(output)
                        }
                    }
                }

                val callback = PendingIntent.getActivity(
                    context,
                    sessionId,
                    Intent(context, CloneInstallResultActivity::class.java)
                        .putExtra(CloneInstallResultActivity.EXTRA_EXPECTED_PACKAGE, expectedPackageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                session.commit(callback.intentSender)
            }
            sessionId
        } catch (t: Throwable) {
            runCatching { installer.abandonSession(sessionId) }
            throw if (t is IOException) t else t
        }
    }
    fun installFromFileDescriptors(
        expectedPackageName: String,
        descriptors: List<ParcelFileDescriptor>
    ): Result<Int> = runCatching {
        require(expectedPackageName.isNotBlank()) { "Paquete inválido" }
        require(descriptors.isNotEmpty()) { "No hay APKs para instalar" }

        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(expectedPackageName) }
        val sessionId = installer.createSession(params)

        try {
            installer.openSession(sessionId).use { session ->
                descriptors.forEachIndexed { index, pfd ->
                    val entryName = if (index == 0) "base.apk" else "split-$index.apk"
                    FileInputStream(pfd.fileDescriptor).use { input ->
                        session.openWrite(entryName, 0, pfd.statSize.takeIf { it >= 0 } ?: -1).use { output ->
                            input.copyTo(output)
                            session.fsync(output)
                        }
                    }
                }

                val callback = PendingIntent.getActivity(
                    context,
                    sessionId,
                    Intent(context, CloneInstallResultActivity::class.java)
                        .putExtra(CloneInstallResultActivity.EXTRA_EXPECTED_PACKAGE, expectedPackageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                session.commit(callback.intentSender)
            }
            sessionId
        } catch (t: Throwable) {
            runCatching { installer.abandonSession(sessionId) }
            throw t
        } finally {
            descriptors.forEach { runCatching { it.close() } }
        }
    }

}
