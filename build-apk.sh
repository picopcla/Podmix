#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════
# PodMix — Script de build debug + upload Google Drive
# ═══════════════════════════════════════════════════════════════
#
# POURQUOI CES VARIABLES :
#   JAVA_HOME     → JBR 21 (Android Studio), évite JDK 17 Oracle
#                   qui crashe avec PipeImpl/UnixDomainSocket sur Windows 11 25H2
#   JAVA_TOOL_OPTIONS → Contourne le bug Windows 11 25H2 :
#                   PipeImpl$Initializer$LoopbackConnector → EINVAL
#                   en déplaçant le socket UDS vers C:\Temp (chemin court)
#
# UTILISATION : ./build-apk.sh [version_suffix]
#   Ex : ./build-apk.sh           → podmix-v20260416_b20.apk
#        ./build-apk.sh hotfix    → podmix-v20260416_b20_hotfix.apk
# ═══════════════════════════════════════════════════════════════

set -e

# ── Config ────────────────────────────────────────────────────
JAVA_HOME="/c/jbr"
export JAVA_HOME
export JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=C:/Temp"

DRIVE_FOLDER="G:/Mon Drive/PodMix"
DRIVE_ROOT="G:/Mon Drive/podmix-debug.apk"
APK_SRC="$(dirname "$0")/app/build/outputs/apk/debug/app-debug.apk"

mkdir -p /c/Temp

# ── Déterminer le nom de fichier ──────────────────────────────
VERSION_CODE=$(grep 'versionCode' "$(dirname "$0")/app/build.gradle.kts" | grep -oP '\d+')
DATE=$(date +%Y%m%d_%H%M)
SUFFIX="${1:+_$1}"
APK_NAME="podmix-v${DATE}_b${VERSION_CODE}${SUFFIX}.apk"
APK_DST="${DRIVE_FOLDER}/${APK_NAME}"

echo "══════════════════════════════════════════"
echo " PodMix Build — $(date)"
echo " JVM : $JAVA_HOME (JBR 21)"
echo " Build : b${VERSION_CODE}"
echo " Output : ${APK_NAME}"
echo "══════════════════════════════════════════"

# ── Build ─────────────────────────────────────────────────────
cd "$(dirname "$0")"
./gradlew assembleDebug

# ── Upload Drive ──────────────────────────────────────────────
if [ -f "$APK_SRC" ]; then
    cp "$APK_SRC" "$APK_DST"
    cp "$APK_SRC" "$DRIVE_ROOT"
    echo ""
    echo "✅ BUILD SUCCESSFUL"
    echo "   APK : ${APK_DST}"
    echo "   Root debug : ${DRIVE_ROOT}"
    ls -lh "$APK_DST"
else
    echo "❌ APK introuvable : $APK_SRC"
    exit 1
fi
