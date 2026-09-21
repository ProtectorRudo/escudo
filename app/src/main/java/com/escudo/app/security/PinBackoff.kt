package com.escudo.app.security

object PinBackoff {
    const val FREE_ATTEMPTS = 5

    fun lockDurationMs(failedAttempts: Int): Long = when {
        failedAttempts < FREE_ATTEMPTS -> 0L
        failedAttempts == 5 -> 30_000L
        failedAttempts == 6 -> 120_000L
        else -> 600_000L
    }
}
