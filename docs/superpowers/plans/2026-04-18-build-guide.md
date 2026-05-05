# Build Guide & JVM Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fixer le bug JVM Windows 11 25H2 dans tous les projets Android, puis documenter la règle une fois pour toutes dans un guide central et dans chaque CLAUDE.md.

**Architecture:** Fixes directs dans les fichiers de config existants (gradle.properties, build.bat), création d'un guide central C:/APP/BUILD_GUIDE.md, puis prepend d'une section BUILD dans chaque CLAUDE.md.

**Tech Stack:** Gradle, Android SDK, ADB, Batch scripts, Markdown

---

## Files

| Action | Fichier |
|--------|---------|
| Modify | `C:/APP/Podmix/gradle.properties` |
| Modify | `C:/APP/Finance/Fonds a promesse/build.bat` |
| Create | `C:/APP/BUILD_GUIDE.md` |
| Modify | `C:/APP/Podmix/CLAUDE.md` |
| Modify | `C:/APP/Track2Train/CLAUDE.md` |
| Modify | `C:/APP/Finance/Fonds a promesse/CLAUDE.md` |
| Modify | `C:/APP/Finance/Fonds a promesse/fonds-promesse/CLAUDE.md` |

---

### Task 1: Fix Podmix gradle.properties — JDK 17 → JBR

**Files:**
- Modify: `C:/APP/Podmix/gradle.properties`

- [ ] **Step 1: Vérifier la ligne actuelle**

```bash
grep "java.home" C:/APP/Podmix/gradle.properties
```
Expected: `org.gradle.java.home=C:/Program Files/Java/jdk-17`

- [ ] **Step 2: Appliquer le fix**

Dans `C:/APP/Podmix/gradle.properties`, remplacer :
```
org.gradle.java.home=C:/Program Files/Java/jdk-17
```
par :
```
org.gradle.java.home=C:/Program Files/Android/Android Studio/jbr
```

- [ ] **Step 3: Vérifier le fix**

```bash
grep "java.home" C:/APP/Podmix/gradle.properties
```
Expected: `org.gradle.java.home=C:/Program Files/Android/Android Studio/jbr`

- [ ] **Step 4: Vérifier que JBR existe bien**

```bash
ls "/c/Program Files/Android/Android Studio/jbr/bin/java.exe"
```
Expected: fichier présent.

---

### Task 2: Fix Finance build.bat — Ajouter JAVA_HOME

**Files:**
- Modify: `C:/APP/Finance/Fonds a promesse/build.bat`

- [ ] **Step 1: Lire le fichier actuel**

Contenu actuel :
```bat
@echo off
cd /d "C:\APP\Finance\Fonds a promesse\fonds-promesse\android"
call gradlew.bat assembleRelease -Dorg.gradle.daemon=false > "C:\Users\Emmanuel_PC\gradle_build.log" 2>&1
if %ERRORLEVEL% == 0 (
  echo BUILD_OK
) else (
  echo BUILD_FAILED
  echo See C:\Users\Emmanuel_PC\gradle_build.log for details
)
```

- [ ] **Step 2: Réécrire le fichier avec fix JVM**

Remplacer le contenu complet par :
```bat
@echo off
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set PATH=%JAVA_HOME%\bin;%PATH%
set JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:/Temp
mkdir C:\Temp 2>nul
cd /d "C:\APP\Finance\Fonds a promesse\fonds-promesse\android"
call gradlew.bat assembleRelease -Dorg.gradle.daemon=false > "C:\Users\Emmanuel_PC\gradle_build.log" 2>&1
if %ERRORLEVEL% == 0 (
  echo BUILD_OK
) else (
  echo BUILD_FAILED
  echo See C:\Users\Emmanuel_PC\gradle_build.log for details
)
```

- [ ] **Step 3: Vérifier**

```bash
head -5 "C:/APP/Finance/Fonds a promesse/build.bat"
```
Expected: les 4 premières lignes sont les `set JAVA_HOME` / `set PATH` / `set JAVA_TOOL_OPTIONS` / `mkdir`.

---

### Task 3: Créer C:/APP/BUILD_GUIDE.md

**Files:**
- Create: `C:/APP/BUILD_GUIDE.md`

- [ ] **Step 1: Créer le fichier**

Créer `C:/APP/BUILD_GUIDE.md` avec ce contenu exact :

```markdown
# Build Guide — Android Projects (C:/APP)

> **AGENTS CLAUDE : lire cette section avant tout build Gradle.**

---

## RÈGLE JVM — Windows 11 25H2

### Problème
JDK 17 Oracle et JDK 21 standalone crashent sur Windows 11 25H2 :
`WEPollSelectorImpl` / `PipeImpl$Initializer$LoopbackConnector` → `EINVAL` (Unix Domain Sockets).

### Fix obligatoire — toujours
```
JVM     : C:\Program Files\Android\Android Studio\jbr
JAVA_TOOL_OPTIONS : -Djdk.net.unixdomain.tmpdir=C:/Temp
```

Dans `gradle.properties` de chaque projet Android :
```
org.gradle.java.home=C:/Program Files/Android/Android Studio/jbr
```

Dans tout script batch/shell qui lance Gradle :
```bat
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:/Temp
mkdir C:\Temp 2>nul
```

### NE JAMAIS FAIRE
- Changer `org.gradle.java.home` vers JDK 17 Oracle (`C:/Program Files/Java/jdk-17`)
- Utiliser JDK 21 standalone
- Lancer Gradle sans `JAVA_TOOL_OPTIONS` sur cette machine
- "Essayer d'autres flags JVM" pour fixer le crash UDS — le vrai fix est JBR

---

## Index des projets Android

| Projet | Type | Build | Install téléphone |
|--------|------|-------|------------------|
| Track2Train | Android Kotlin/Compose | `cmd /c build_t2t.bat` | inclus dans build_t2t.bat (`installDebug`) |
| Podmix | Android Kotlin/Compose | `./build-apk.sh` | `./gradlew installDebug --no-daemon` |
| Finance/Fonds-à-Promesse | Expo React Native | `build.bat` (local) ou `eas build --platform android --profile preview` (cloud) | `adb install -r fonds-promesse/android/app/build/outputs/apk/release/app-release.apk` |

**Répertoires :**
- `C:/APP/Track2Train`
- `C:/APP/Podmix`
- `C:/APP/Finance/Fonds a promesse`

---

## ADB Cheatsheet

```bash
# Vérifier connexion (doit afficher le device, pas "unauthorized")
adb devices

# Installer / mettre à jour APK
adb install -r <chemin>.apk

# Logs de l'app en live
adb logcat --pid=$(adb shell pidof <package>)

# Forcer arrêt
adb shell am force-stop <package>
```

**Packages :**
- Track2Train : `com.music.track2train`
- Podmix : `com.podmix`
- Finance : `com.emmanuelwife.fondspromesse`

**Téléphone :** Samsung SM-S916B, USB ADB

---

## Commandes directes (sans scripts)

### Track2Train
```bash
cd C:/APP/Track2Train
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" \
JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=C:/Temp" \
./gradlew installDebug --no-daemon
```

### Podmix
```bash
cd C:/APP/Podmix
./build-apk.sh
# ou install direct :
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" \
JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=C:/Temp" \
./gradlew installDebug --no-daemon
```

### Finance (local)
```bash
cd "C:/APP/Finance/Fonds a promesse"
cmd /c build.bat
# APK → fonds-promesse/android/app/build/outputs/apk/release/app-release.apk
adb install -r "fonds-promesse/android/app/build/outputs/apk/release/app-release.apk"
```
```

- [ ] **Step 2: Vérifier**

```bash
head -5 C:/APP/BUILD_GUIDE.md
```
Expected: `# Build Guide — Android Projects (C:/APP)`

---

### Task 4: Mettre à jour Podmix/CLAUDE.md — Section BUILD en tête

**Files:**
- Modify: `C:/APP/Podmix/CLAUDE.md`

- [ ] **Step 1: Prepend la section BUILD**

Ajouter au **tout début** de `C:/APP/Podmix/CLAUDE.md` (avant la ligne `# PROTOCOLE`) :

```markdown
## ⚡ BUILD & INSTALL — LIRE EN PREMIER

**JVM OBLIGATOIRE :** `C:\Program Files\Android\Android Studio\jbr`
**NE PAS UTILISER :** JDK 17 Oracle, JDK 21 standalone → bug Windows 11 25H2 (WEPollSelectorImpl EINVAL)
**JAVA_TOOL_OPTIONS :** `-Djdk.net.unixdomain.tmpdir=C:/Temp`

### Build (génère APK + copie dans Google Drive)
```bash
cd C:/APP/Podmix
./build-apk.sh
```

### Install direct sur téléphone (Samsung SM-S916B, USB ADB)
```bash
cd C:/APP/Podmix
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=C:/Temp" ./gradlew installDebug --no-daemon
```

### Vérifier device
```bash
adb devices  # doit afficher SM-S916B, pas "unauthorized"
```

**Package :** `com.podmix`
**Guide complet :** `C:/APP/BUILD_GUIDE.md`

---

```

- [ ] **Step 2: Vérifier**

```bash
head -3 C:/APP/Podmix/CLAUDE.md
```
Expected: `## ⚡ BUILD & INSTALL — LIRE EN PREMIER`

---

### Task 5: Mettre à jour Track2Train/CLAUDE.md — Section BUILD en tête

**Files:**
- Modify: `C:/APP/Track2Train/CLAUDE.md`

- [ ] **Step 1: Prepend la section BUILD**

Ajouter au **tout début** de `C:/APP/Track2Train/CLAUDE.md` (avant la ligne `# PROTOCOLE`) :

```markdown
## ⚡ BUILD & INSTALL — LIRE EN PREMIER

**JVM OBLIGATOIRE :** `C:\Program Files\Android\Android Studio\jbr`
**NE PAS UTILISER :** JDK 17 Oracle, JDK 21 standalone → bug Windows 11 25H2 (WEPollSelectorImpl EINVAL)
**JAVA_TOOL_OPTIONS :** `-Djdk.net.unixdomain.tmpdir=C:/Temp`

### Build + Install (une seule commande)
```bash
cd C:/APP/Track2Train
cmd /c build_t2t.bat
# ou depuis Git Bash :
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=C:/Temp" ./gradlew installDebug --no-daemon
```

### Vérifier device
```bash
adb devices  # doit afficher SM-S916B, pas "unauthorized"
```

**Package :** `com.music.track2train`
**Guide complet :** `C:/APP/BUILD_GUIDE.md`

---

```

- [ ] **Step 2: Vérifier**

```bash
head -3 C:/APP/Track2Train/CLAUDE.md
```
Expected: `## ⚡ BUILD & INSTALL — LIRE EN PREMIER`

---

### Task 6: Mettre à jour Finance root CLAUDE.md — Section BUILD en tête

**Files:**
- Modify: `C:/APP/Finance/Fonds a promesse/CLAUDE.md`

- [ ] **Step 1: Prepend la section BUILD**

Ajouter au **tout début** de `C:/APP/Finance/Fonds a promesse/CLAUDE.md` (avant `# Fonds à Promesse — Root`) :

```markdown
## ⚡ BUILD & INSTALL — LIRE EN PREMIER

**JVM OBLIGATOIRE :** `C:\Program Files\Android\Android Studio\jbr`
**NE PAS UTILISER :** JDK 17 Oracle, JDK 21 standalone → bug Windows 11 25H2 (WEPollSelectorImpl EINVAL)
**JAVA_TOOL_OPTIONS :** `-Djdk.net.unixdomain.tmpdir=C:/Temp`

### Build local (Gradle)
```bat
cd C:/APP/Finance/Fonds a promesse
cmd /c build.bat
# APK → fonds-promesse/android/app/build/outputs/apk/release/app-release.apk
```

### Build cloud (recommandé)
```bash
cd "C:/APP/Finance/Fonds a promesse/fonds-promesse"
eas build --platform android --profile preview
```

### Install sur téléphone (Samsung SM-S916B, USB ADB)
```bash
adb install -r "C:/APP/Finance/Fonds a promesse/fonds-promesse/android/app/build/outputs/apk/release/app-release.apk"
```

### Vérifier device
```bash
adb devices  # doit afficher SM-S916B, pas "unauthorized"
```

**Package :** `com.emmanuelwife.fondspromesse`
**Guide complet :** `C:/APP/BUILD_GUIDE.md`

---

```

- [ ] **Step 2: Vérifier**

```bash
head -3 "C:/APP/Finance/Fonds a promesse/CLAUDE.md"
```
Expected: `## ⚡ BUILD & INSTALL — LIRE EN PREMIER`

---

### Task 7: Mettre à jour fonds-promesse/CLAUDE.md — Section BUILD en tête

**Files:**
- Modify: `C:/APP/Finance/Fonds a promesse/fonds-promesse/CLAUDE.md`

- [ ] **Step 1: Prepend la section BUILD**

Ajouter au **tout début** de `C:/APP/Finance/Fonds a promesse/fonds-promesse/CLAUDE.md` (avant `# Fonds à Promesse — Developer Guide`) :

```markdown
## ⚡ BUILD & INSTALL — LIRE EN PREMIER

**JVM OBLIGATOIRE :** `C:\Program Files\Android\Android Studio\jbr`
**NE PAS UTILISER :** JDK 17 Oracle, JDK 21 standalone → bug Windows 11 25H2 (WEPollSelectorImpl EINVAL)
**JAVA_TOOL_OPTIONS :** `-Djdk.net.unixdomain.tmpdir=C:/Temp`

### Build local (Gradle natif)
```bat
cd "C:/APP/Finance/Fonds a promesse"
cmd /c build.bat
# Log → C:\Users\Emmanuel_PC\gradle_build.log
# APK → fonds-promesse/android/app/build/outputs/apk/release/app-release.apk
```

### Build cloud EAS (recommandé)
```bash
cd "C:/APP/Finance/Fonds a promesse/fonds-promesse"
eas build --platform android --profile preview
```

### Install sur téléphone (Samsung SM-S916B, USB ADB)
```bash
adb install -r "C:/APP/Finance/Fonds a promesse/fonds-promesse/android/app/build/outputs/apk/release/app-release.apk"
```

### Vérifier device
```bash
adb devices  # doit afficher SM-S916B, pas "unauthorized"
```

**Package :** `com.emmanuelwife.fondspromesse`
**Guide complet :** `C:/APP/BUILD_GUIDE.md`

---

```

- [ ] **Step 2: Vérifier**

```bash
head -3 "C:/APP/Finance/Fonds a promesse/fonds-promesse/CLAUDE.md"
```
Expected: `## ⚡ BUILD & INSTALL — LIRE EN PREMIER`
