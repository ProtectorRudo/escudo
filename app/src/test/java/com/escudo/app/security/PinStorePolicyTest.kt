package com.escudo.app.security

import org.junit.Assert.assertEquals
import org.junit.Test

class PinStorePolicyTest {
    @Test
    fun lockDurationsGrowAfterFiveFailures() {
        assertEquals(0L, PinBackoff.lockDurationMs(4))
        assertEquals(30_000L, PinBackoff.lockDurationMs(5))
        assertEquals(120_000L, PinBackoff.lockDurationMs(6))
        assertEquals(600_000L, PinBackoff.lockDurationMs(7))
    }
}
