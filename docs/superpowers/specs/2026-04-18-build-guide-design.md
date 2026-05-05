# Design : Guide Build & Install Android — C:/APP

**Date :** 2026-04-18  
**Scope :** Tous les projets Android dans `C:/APP` (Podmix, Track2Train, Finance/Fonds-à-Promesse)  
**Objectif :** Empêcher les agents Claude de redécouvrir le fix JVM à chaque session

---

## 1. Root Cause

**Windows 11 25H2 bug :** `WEPollSelectorImpl` / `PipeImpl$Initializer$LoopbackConnector` utilise Unix Domain Sockets pour les pipes internes JVM → `EINVAL` sur ce build Windows.

**JDKs cassés :** JDK 17 Oracle ET JDK 21 standalone → les deux touchés.  
**JDK qui marche :** JBR (JetBrains Runtime) bundlé avec Android Studio → `C:\Program Files\Android\Android Studio\jbr`

**Fix obligatoire pour tout build Gradle Android sur cette machine :**
```
JAVA_HOME = C:\Program Files\Android\Android Studio\jbr
JAVA_TOOL_OPTIONS = -Djdk.net.unixdomain.tmpdir=C:/Temp
mkdir C:\Temp (si absent)
```

`org.gradle.java.home` dans `gradle.properties` **doit pointer vers JBR**, pas JDK Oracle.

---

## 2. État par projet

| Projet | Type | Build actuel | Statut JVM | Fix nécessaire |
|--------|------|-------------|------------|----------------|
| Track2Train | Android Kotlin/Compose | `build_t2t.bat` → `installDebug` | ✅ JBR | Aucun |
| Podmix | Android Kotlin/Compose | `build-apk.sh` → `assembleDebug` + Drive | ❌ JDK 17 Oracle dans gradle.properties | Fix gradle.properties |
| Finance/Fonds-à-Promesse | Expo React Native | `build.bat` → Gradle local + `eas build` (cloud) | ❌ Pas de JAVA_HOME dans build.bat | Fix build.bat |

---

## 3. Fixes à appliquer

### 3.1 Podmix — `gradle.properties`

Ligne à modifier :
```
# AVANT
org.gradle.java.home=C:/Program Files/Java/jdk-17

# APRÈS
org.gradle.java.home=C:/Program Files/Android/Android Studio/jbr
```

### 3.2 Finance — `build.bat`

Ajouter en tête du fichier avant `cd` :
```bat
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set PATH=%JAVA_HOME%\bin;%PATH%
set JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:/Temp
mkdir C:\Temp 2>nul
```

---

## 4. Guide central — `C:/APP/BUILD_GUIDE.md`

Contenu :
- Section **RÈGLE JVM** : explication Windows 11 25H2 + fix (une fois pour toutes)
- **Index projets** : tableau nom / type / commande build / commande install
- **ADB cheatsheet** : vérifier device (`adb devices`), installer APK, voir logs (`adb logcat`)
- **Ne jamais faire** : liste des pièges (changer java.home, utiliser JDK Oracle, lancer Gradle sans JAVA_TOOL_OPTIONS)

---

## 5. Section BUILD dans chaque CLAUDE.md

Placée **en haut**, avant tout autre contenu, format fixe :

```markdown
## ⚡ BUILD & INSTALL — LIRE EN PREMIER

**JVM OBLIGATOIRE :** `C:\Program Files\Android\Android Studio\jbr`
**NE PAS UTILISER :** JDK 17 Oracle, JDK 21 standalone → bug Windows 11 25H2
**JAVA_TOOL_OPTIONS :** `-Djdk.net.unixdomain.tmpdir=C:/Temp`

### Build
[commande exacte]

### Install sur téléphone (Samsung SM-S916B, USB ADB)
[commande exacte]

### Vérifier device
adb devices   # doit afficher le device, pas "unauthorized"
```

Projets à mettre à jour : `Podmix/CLAUDE.md`, `Track2Train/CLAUDE.md`, `Finance/Fonds a promesse/CLAUDE.md`, `Finance/Fonds a promesse/fonds-promesse/CLAUDE.md`

---

## 6. Commandes exactes par projet

### Track2Train
- Build + Install : `build_t2t.bat` (depuis Git Bash : `cmd /c build_t2t.bat`)
- Ou direct : `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=C:/Temp" ./gradlew installDebug --no-daemon`

### Podmix
- Build : `./build-apk.sh` (copie APK dans Google Drive)
- Install direct : `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=C:/Temp" ./gradlew installDebug --no-daemon`

### Finance/Fonds-à-Promesse
- Build local : `build.bat` (après fix JAVA_HOME)
- Build cloud (recommandé) : `eas build --platform android --profile preview`
- Install APK local : `adb install -r fonds-promesse/android/app/build/outputs/apk/release/app-release.apk`

---

## 7. ADB Cheatsheet

```bash
adb devices                          # vérifier connexion
adb install -r <chemin>.apk         # installer/mettre à jour APK
adb logcat --pid=$(adb shell pidof <package>) # logs app
adb shell am force-stop <package>   # forcer arrêt
```

Packages :
- Track2Train : `com.music.track2train`
- Podmix : `com.podmix`
- Finance : vérifier `app.json` → `android.package`
