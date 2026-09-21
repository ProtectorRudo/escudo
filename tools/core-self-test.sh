#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

cat > "$TMP/Main.kt" <<'KOTLIN'
import com.escudo.app.security.PinBackoff
import com.escudo.app.security.PinHasher

fun requireTrue(value: Boolean, message: String) {
    if (!value) error(message)
}

fun main() {
    val salt = ByteArray(16) { it.toByte() }
    val a = PinHasher.hash("123456".toCharArray(), salt, iterations = 1_000)
    val b = PinHasher.hash("123456".toCharArray(), salt, iterations = 1_000)
    val c = PinHasher.hash("654321".toCharArray(), salt, iterations = 1_000)

    requireTrue(PinHasher.constantTimeEquals(a, b), "same PIN must match")
    requireTrue(!PinHasher.constantTimeEquals(a, c), "different PIN must not match")
    requireTrue(PinBackoff.lockDurationMs(4) == 0L, "4 failures must be free")
    requireTrue(PinBackoff.lockDurationMs(5) == 30_000L, "5 failures must lock 30s")
    requireTrue(PinBackoff.lockDurationMs(6) == 120_000L, "6 failures must lock 2m")
    requireTrue(PinBackoff.lockDurationMs(7) == 600_000L, "7+ failures must lock 10m")

    println("Escudo core self-test: PASS")
}
KOTLIN

kotlinc \
  "$ROOT/app/src/main/java/com/escudo/app/security/PinHasher.kt" \
  "$ROOT/app/src/main/java/com/escudo/app/security/PinBackoff.kt" \
  "$TMP/Main.kt" \
  -include-runtime -d "$TMP/core-test.jar"

java -jar "$TMP/core-test.jar"
