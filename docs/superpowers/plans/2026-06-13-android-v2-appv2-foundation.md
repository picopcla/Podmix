# Podmix Android V2 `appv2` Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a separate Android V2 APK in `appv2/` with clean architecture foundations, a unified hub UI, and a persisted `GET /health` vertical slice against `https://podmix.mb4.fr`.

**Architecture:** Keep V1 intact in `app/` and add a new Android application module `appv2/`. Inside `appv2/`, structure code by `core/domain/data/features/playback/sync`, store backend state in Room before display, and keep screens dependent only on ViewModels and use cases.

**Tech Stack:** Android Gradle Plugin, Kotlin, Jetpack Compose, Hilt, Retrofit, Room, Coroutines, Navigation Compose, Media3 shell

---

## Files

| Action | File |
|--------|------|
| Modify | `settings.gradle.kts` |
| Create | `appv2/build.gradle.kts` |
| Create | `appv2/proguard-rules.pro` |
| Create | `appv2/src/main/AndroidManifest.xml` |
| Create | `appv2/src/main/java/com/podmix/v2/PodmixV2Application.kt` |
| Create | `appv2/src/main/java/com/podmix/v2/MainActivity.kt` |
| Create | `appv2/src/main/java/com/podmix/v2/core/config/BackendConfig.kt` |
| Create | `appv2/src/main/java/com/podmix/v2/core/result/AppResult.kt` |
| Create | `appv2/src/main/java/com/podmix/v2/core/logging/AppLogger.kt` |
| Create | `appv2/src/main/java/com/podmix/v2/core/network/NetworkMonitor.kt` |
| Create | `appv2/src/main/java/com/podmix/v2/core/database/PodmixV2Database.kt` |
| Create | `appv2/src/main/java/com/podmix/v2/data/local/entity/BackendHealthEntity.kt` |
| Create | `appv2/src/main/java/com/podmix/v2/data/local/dao/BackendHealthDao.kt` |
| Create | `appv2/src/main/java/com/podmix/v2/data/local/mapper/BackendHealthMapper.kt` |
| Create | `appv2/src/main/java/com/podmix/v2/data/remote/dto/BackendHealthDto.kt` |
| Create | `appv2/src/main/java/com/podmix/v2/data/remote/api/PodmixBackendApi.kt` |
| Create | `appv2/src/main/java/com/podmix/v2/data/repository/BackendHealthRepositoryImpl.kt` |
| Create | `appv2/src/main/java/com/podmix/v2/domain/model/*.kt` |
| Create | `appv2/src/main/java/com/podmix/v2/domain/repository/BackendHealthRepository.kt` |
| Create | `appv2/src/main/java/com/podmix/v2/domain/usecase/health/*.kt` |
| Create | `appv2/src/main/java/com/podmix/v2/di/*.kt` |
| Create | `appv2/src/main/java/com/podmix/v2/features/hub/*.kt` |
| Create | `appv2/src/main/java/com/podmix/v2/features/*/Placeholder*.kt` |
| Create | `appv2/src/main/java/com/podmix/v2/navigation/PodmixV2NavGraph.kt` |
| Create | `appv2/src/main/java/com/podmix/v2/playback/*.kt` |
| Create | `appv2/src/main/java/com/podmix/v2/sync/BackendHealthSyncWorker.kt` |
| Create | `appv2/src/main/res/values/strings.xml` |
| Create | `appv2/src/main/res/values/colors.xml` |
| Create | `appv2/src/main/res/values/themes.xml` |
| Create | `appv2/src/main/res/xml/backup_rules.xml` |
| Create | `appv2/src/main/res/xml/data_extraction_rules.xml` |
| Create | `appv2/src/test/java/com/podmix/v2/domain/usecase/health/ObserveBackendHealthUseCaseTest.kt` |
| Create | `appv2/src/test/java/com/podmix/v2/data/local/mapper/BackendHealthMapperTest.kt` |
| Create | `appv2/src/androidTest/java/com/podmix/v2/features/hub/HubScreenTest.kt` |
| Modify | `C:\Users\Emmanuel_PC\Documents\PodmixV2\STATUS.md` |

---

### Task 1: Register the new Android module

**Files:**
- Modify: `settings.gradle.kts`
- Create: `appv2/build.gradle.kts`
- Create: `appv2/proguard-rules.pro`

- [ ] **Step 1: Add `appv2` to Gradle settings**
- [ ] **Step 2: Create the new application module build file with Compose, Hilt, Retrofit, Room, Navigation, WorkManager, and Media3 shell dependencies**
- [ ] **Step 3: Add a minimal `proguard-rules.pro`**
- [ ] **Step 4: Run `./gradlew :appv2:tasks` to verify the module resolves**
- [ ] **Step 5: Commit the module registration**

### Task 2: Create the V2 app identity and resources

**Files:**
- Create: `appv2/src/main/AndroidManifest.xml`
- Create: `appv2/src/main/java/com/podmix/v2/PodmixV2Application.kt`
- Create: `appv2/src/main/java/com/podmix/v2/MainActivity.kt`
- Create: `appv2/src/main/res/values/strings.xml`
- Create: `appv2/src/main/res/values/colors.xml`
- Create: `appv2/src/main/res/values/themes.xml`
- Create: `appv2/src/main/res/xml/backup_rules.xml`
- Create: `appv2/src/main/res/xml/data_extraction_rules.xml`

- [ ] **Step 1: Create a separate manifest with V2 application class, launcher activity, and internet/network permissions**
- [ ] **Step 2: Create the V2 application class annotated for Hilt**
- [ ] **Step 3: Create the V2 activity hosting the Compose root**
- [ ] **Step 4: Create V2 strings, theme, and backup resources**
- [ ] **Step 5: Run `./gradlew :appv2:processDebugMainManifest` to verify the Android shell**
- [ ] **Step 6: Commit the app identity**

### Task 3: Define the domain model and repository contracts

**Files:**
- Create: `appv2/src/main/java/com/podmix/v2/domain/model/*.kt`
- Create: `appv2/src/main/java/com/podmix/v2/domain/repository/BackendHealthRepository.kt`
- Create: `appv2/src/main/java/com/podmix/v2/playback/*.kt`

- [ ] **Step 1: Create V2 enums for content type, analysis status, timestamp quality, download status, and playback source type**
- [ ] **Step 2: Create focused domain models for content, health, favorites, saved tracks, and playable media**
- [ ] **Step 3: Create the backend health repository contract and playback shell contracts**
- [ ] **Step 4: Run `./gradlew :appv2:compileDebugKotlin` to verify the domain layer compiles**
- [ ] **Step 5: Commit the domain foundation**

### Task 4: Add Room and Retrofit for the health slice

**Files:**
- Create: `appv2/src/main/java/com/podmix/v2/core/config/BackendConfig.kt`
- Create: `appv2/src/main/java/com/podmix/v2/core/result/AppResult.kt`
- Create: `appv2/src/main/java/com/podmix/v2/core/logging/AppLogger.kt`
- Create: `appv2/src/main/java/com/podmix/v2/core/network/NetworkMonitor.kt`
- Create: `appv2/src/main/java/com/podmix/v2/core/database/PodmixV2Database.kt`
- Create: `appv2/src/main/java/com/podmix/v2/data/local/entity/BackendHealthEntity.kt`
- Create: `appv2/src/main/java/com/podmix/v2/data/local/dao/BackendHealthDao.kt`
- Create: `appv2/src/main/java/com/podmix/v2/data/local/mapper/BackendHealthMapper.kt`
- Create: `appv2/src/main/java/com/podmix/v2/data/remote/dto/BackendHealthDto.kt`
- Create: `appv2/src/main/java/com/podmix/v2/data/remote/api/PodmixBackendApi.kt`
- Create: `appv2/src/main/java/com/podmix/v2/data/repository/BackendHealthRepositoryImpl.kt`
- Create: `appv2/src/main/java/com/podmix/v2/domain/usecase/health/*.kt`
- Create: `appv2/src/main/java/com/podmix/v2/di/*.kt`
- Create: `appv2/src/main/java/com/podmix/v2/sync/BackendHealthSyncWorker.kt`

- [ ] **Step 1: Add BuildConfig-backed backend base URL config defaulting to `https://podmix.mb4.fr`**
- [ ] **Step 2: Create the Room database, DAO, entity, and mapper for persisted backend health**
- [ ] **Step 3: Create Retrofit DTO and API interface for `GET /health`**
- [ ] **Step 4: Implement the repository so refresh stores local state before exposing it**
- [ ] **Step 5: Add Hilt modules and a lightweight worker/use-case shell for syncing health**
- [ ] **Step 6: Run `./gradlew :appv2:testDebugUnitTest` and `./gradlew :appv2:compileDebugKotlin`**
- [ ] **Step 7: Commit the health data slice**

### Task 5: Build the unified hub UI and navigation shell

**Files:**
- Create: `appv2/src/main/java/com/podmix/v2/navigation/PodmixV2NavGraph.kt`
- Create: `appv2/src/main/java/com/podmix/v2/features/hub/*.kt`
- Create: `appv2/src/main/java/com/podmix/v2/features/*/Placeholder*.kt`

- [ ] **Step 1: Create hub state, hub viewmodel, and a screen contract driven by use cases**
- [ ] **Step 2: Create the unified hub UI with backend status banner, content-family cards, and mini-player shell**
- [ ] **Step 3: Add placeholder destination screens for Podcast, Liveset, Emission, Radio, Favoris, Tracks, Settings, and Player**
- [ ] **Step 4: Wire Compose navigation from the activity through the V2 nav graph**
- [ ] **Step 5: Run `./gradlew :appv2:connectedDebugAndroidTest` if a device is available, otherwise `./gradlew :appv2:compileDebugKotlin`**
- [ ] **Step 6: Commit the UI shell**

### Task 6: Add focused tests for the new slice

**Files:**
- Create: `appv2/src/test/java/com/podmix/v2/domain/usecase/health/ObserveBackendHealthUseCaseTest.kt`
- Create: `appv2/src/test/java/com/podmix/v2/data/local/mapper/BackendHealthMapperTest.kt`
- Create: `appv2/src/androidTest/java/com/podmix/v2/features/hub/HubScreenTest.kt`

- [ ] **Step 1: Write unit tests for the mapper and backend health use case**
- [ ] **Step 2: Run the unit tests and watch them fail for the expected reason**
- [ ] **Step 3: Add the minimal production code required to satisfy the tests**
- [ ] **Step 4: Re-run `./gradlew :appv2:testDebugUnitTest` to verify green**
- [ ] **Step 5: Add a basic Compose screen test for the hub status presentation**
- [ ] **Step 6: Run Android tests if a device is available, otherwise document that limitation**
- [ ] **Step 7: Commit the tests**

### Task 7: Verify, update status, and publish the branch

**Files:**
- Modify: `C:\Users\Emmanuel_PC\Documents\PodmixV2\STATUS.md`

- [ ] **Step 1: Update `STATUS.md` with Android Done, In Progress, Next, and Backend needs based on actual implementation**
- [ ] **Step 2: Run `./gradlew :appv2:assembleDebug` for build verification**
- [ ] **Step 3: Run `git status --short`, inspect the diff, and confirm only intended Android/STATUS changes are included**
- [ ] **Step 4: Commit the final implementation changes**
- [ ] **Step 5: Push `codex/android-v2` to `origin`**
