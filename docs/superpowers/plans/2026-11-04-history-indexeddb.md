# Podmix history IndexedDB implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move Podmix playback history persistence from quota-limited localStorage to IndexedDB without deleting any legacy data.

**Architecture:** The existing `podmix-studio` IndexedDB database gains one dedicated history object store. A repository copies legacy history and sessions exactly on first load, reads IndexedDB first after a successful migration, and writes only to IndexedDB. The catalogue store and its legacy backup remain unchanged.

**Tech Stack:** TypeScript, React, Vitest, browser IndexedDB.

**Spec:** User-approved scope in this conversation, 2026-11-04.

## Global Constraints

- Base commit is `38b3667`; do not modify `catalogs` or `podmix-catalog-v1`.
- Preserve `podmix-history-v1` and `podmix-listening-sessions-v1` byte-for-byte in localStorage.
- No deletion, compaction, cache clearing, or changes to favorites, settings, markers, or downloads.
- Successful history IndexedDB writes clear only that history failure; failures retain the warning.
- Build only locally; install only as an ADB update with no uninstall or data clearing.

## Review Focus

- Migration has only one history payload available: preserve the existing fallback from sessions to playback history.
- IndexedDB write failure leaves both legacy localStorage strings unchanged.
- A corrupted legacy value does not overwrite a valid IndexedDB record.
- Saving one history payload must not clear a failure for the other payload.
- Large legacy history remains bounded by the existing 1,000/2,000 entry rules without new reduction.

### Task 1: History persistence repository

**Files:**
- Modify: `src/history.ts`
- Test: `tests/unit/history-storage.test.ts`

**Interfaces:**
- Produces `createHistoryRepository(store)` and async load/save functions consumed by `App.tsx`.

- [ ] Write failing unit tests for exact migration, legacy preservation, successful restart read, and IndexedDB failure fallback.
- [ ] Run the targeted test and verify the failures are caused by the missing repository.
- [ ] Implement the minimal IndexedDB repository and async history load/save operations.
- [ ] Run the targeted test and verify it passes.

### Task 2: Application hydration and failure reporting

**Files:**
- Modify: `src/App.tsx`
- Test: `tests/unit/history-storage.test.ts`

**Interfaces:**
- Consumes the async history APIs from Task 1.
- Produces guarded initial hydration without empty-state overwrites.

- [ ] Extend the failing unit tests for independent history failure/success reporting.
- [ ] Run the targeted test and verify the new assertion fails.
- [ ] Gate history persistence until asynchronous hydration completes and retry failed IndexedDB saves without touching localStorage.
- [ ] Run targeted unit tests and verify they pass.

### Task 3: Verification and signed local update

**Files:**
- Test: `tests/e2e/podmix.spec.ts`

- [ ] Add an end-to-end history migration/restart assertion before changing implementation behavior beyond Tasks 1–2.
- [ ] Run unit tests, build, and selected end-to-end tests.
- [ ] Build a locally signed APK with the existing release certificate; verify its certificate digest.
- [ ] Revalidate the target model, install only with `adb install -r`, then check process launch and non-destructive package update metadata.
