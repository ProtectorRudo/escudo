#!/usr/bin/env bash
set -euo pipefail

ESCUDO_APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
DEMO_APK="${2:-demo-target/build/outputs/apk/debug/demo-target-debug.apk}"

adb install -r "$ESCUDO_APK"
adb install -r "$DEMO_APK"

echo "Intentando activar Escudo como Device Owner en un dispositivo/emulador de PRUEBA..."
adb shell dpm set-device-owner com.escudo.app/.policy.EscudoDeviceAdminReceiver

echo
echo "Owner actual:"
adb shell dpm list-owners || true

echo
echo "Abrí Escudo, elegí 'Escudo Demo Target' y activá la protección."
echo "Prueba esperada: el icono desaparece; desde Escudo se libera y abre; al volver o apagar pantalla vuelve a desaparecer."
