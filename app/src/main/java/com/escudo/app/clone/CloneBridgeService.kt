package com.escudo.app.clone

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Runs only in the personal profile and exposes installed APK files through Binder/AIDL.
 *
 * ParcelFileDescriptor is explicitly designed for Binder transport. Every IPC also verifies that
 * the calling appId matches Escudo's appId, so possession of the Binder alone is not enough for an
 * unrelated package to read APKs.
 */
class CloneBridgeService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val stopTask = Runnable { stopSelf() }

    private val binder = object : ICloneBridge.Stub() {
        override fun openApks(packageName: String): Array<ParcelFileDescriptor> {
            enforceEscudoCaller()
            require(packageName.isNotBlank() && packageName != this@CloneBridgeService.packageName) {
                "Paquete inválido"
            }

            @Suppress("DEPRECATION")
            val info = packageManager.getApplicationInfo(packageName, 0)
            val paths = buildList {
                add(info.sourceDir)
                info.splitSourceDirs?.forEach(::add)
            }
            check(paths.isNotEmpty()) { "La app no expone APKs instalados" }
            scheduleStop()
            return paths.map { path ->
                ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
            }.toTypedArray()
        }

        override fun isInstalled(packageName: String): Boolean {
            enforceEscudoCaller()
            if (packageName.isBlank() || packageName == this@CloneBridgeService.packageName) return false
            scheduleStop()
            return runCatching {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, 0)
                true
            }.getOrDefault(false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        scheduleStop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scheduleStop()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        scheduleStop()
        return binder
    }

    private fun enforceEscudoCaller() {
    val callerPackages = packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
    check(packageName in callerPackages) { "Caller no autorizado" }
}

    private fun scheduleStop() {
        handler.removeCallbacks(stopTask)
        handler.postDelayed(stopTask, 30_000L)
    }

    override fun onDestroy() {
        handler.removeCallbacks(stopTask)
        super.onDestroy()
    }
}
