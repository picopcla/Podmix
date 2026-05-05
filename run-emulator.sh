#!/usr/bin/env bash
# PodMix — Build + Install sur émulateur Android
set -e

EMULATOR="C:/Users/Emmanuel_PC/AppData/Local/Android/Sdk/emulator/emulator.exe"
ADB="C:/Users/Emmanuel_PC/AppData/Local/Android/Sdk/platform-tools/adb.exe"
APP_ID="com.podmix"

export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
export JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=C:/Temp"
mkdir -p /c/Temp

# ── Démarrer émulateur si pas lancé ──────────────────────────
EMU_DEVICE=$("$ADB" devices | grep "emulator" | awk '{print $1}' | head -1)
if [ -z "$EMU_DEVICE" ]; then
    AVD=$("$EMULATOR" -list-avds | head -1)
    if [ -z "$AVD" ]; then
        echo "❌ Aucun AVD trouvé. Crée un émulateur dans Android Studio."
        exit 1
    fi
    echo "🚀 Démarrage émulateur: $AVD"
    "$EMULATOR" -avd "$AVD" -no-snapshot-load &
    echo "⏳ Attente boot Android..."
    "$ADB" wait-for-device
    until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null)" = "1" ]; do
        sleep 2
    done
    echo "✅ Émulateur prêt"
    EMU_DEVICE=$("$ADB" devices | grep "emulator" | awk '{print $1}' | head -1)
fi
echo "📱 Device: $EMU_DEVICE"

# ── Build ─────────────────────────────────────────────────────
cd "$(dirname "$0")"
echo "🔨 Build..."
./gradlew assembleDebug -q

# ── Install + Launch ──────────────────────────────────────────
echo "📦 Install..."
"$ADB" -s "$EMU_DEVICE" install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" -s "$EMU_DEVICE" shell am start -n "$APP_ID/.MainActivity"
echo "✅ PodMix lancé sur l'émulateur"
