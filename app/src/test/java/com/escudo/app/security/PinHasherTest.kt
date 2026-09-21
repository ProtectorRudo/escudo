package com.escudo.app.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinHasherTest {
    @Test
    fun samePinAndSaltProduceSameHash() {
        val salt = ByteArray(16) { it.toByte() }
        val a = PinHasher.hash("123456".toCharArray(), salt, iterations = 1_000)
        val b = PinHasher.hash("123456".toCharArray(), salt, iterations = 1_000)
        assertTrue(PinHasher.constantTimeEquals(a, b))
    }

    @Test
    fun differentPinProducesDifferentHash() {
        val salt = ByteArray(16) { it.toByte() }
        val a = PinHasher.hash("123456".toCharArray(), salt, iterations = 1_000)
        val b = PinHasher.hash("654321".toCharArray(), salt, iterations = 1_000)
        assertFalse(PinHasher.constantTimeEquals(a, b))
    }
}
