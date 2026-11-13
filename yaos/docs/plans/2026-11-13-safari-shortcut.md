# Safari Shortcut Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Send a Safari URL to FreeCast TV with one Share Sheet command and play accessible media with the TV remote.

**Architecture:** An authenticated HTTP receiver lives in `AirPlayService`; the temporary QR form remains separate. The TV validates URLs, discovers public media URLs in a WebView, and plays them in Media3, with an embedded-page fallback.

**Tech Stack:** Kotlin, NanoHTTPD, Android WebView, Media3, iOS Shortcuts.

---

### Task 1: Persistent command endpoint

**Files:** `yaos/app/src/main/java/com/airtv/receiver/service/AirPlayService.kt`, `yaos/app/src/main/java/com/airtv/receiver/link/RemoteCommandServer.kt`, `yaos/app/src/test/java/com/airtv/receiver/link/RemoteCommandServerTest.kt`.

1. Write authorization and URL tests; verify they fail before implementation.
2. Generate and persist a random 128-bit pairing token; start and stop NanoHTTPD with the foreground service.
3. Accept bounded POST form data containing token and HTTPS URL; launch the receiving Activity on the UI thread.
4. Run focused tests and commit.

### Task 2: Setup and Shortcut instructions

**Files:** `yaos/app/src/main/java/com/airtv/receiver/ui/LinkShareActivity.kt`, `yaos/README.md`.

1. Show the permanent endpoint and token on the TV alongside the existing QR handoff.
2. Document exact iOS Shortcuts actions: receive URL from Share Sheet, POST form `url` and `token` to the TV, and show result.
3. Check text and layout for Android TV; commit.

### Task 3: Media discovery and player

**Files:** `yaos/app/src/main/java/com/airtv/receiver/link/MediaCandidate.kt`, `yaos/app/src/test/java/com/airtv/receiver/link/MediaCandidateTest.kt`, `yaos/app/src/main/java/com/airtv/receiver/ui/BroadcastWebActivity.kt`, `yaos/app/src/main/java/com/airtv/receiver/ui/StreamPlaybackActivity.kt`, `yaos/app/build.gradle.kts`, `yaos/app/src/main/AndroidManifest.xml`.

1. Test accept/reject rules for HTTPS HLS/MP4 candidates; verify red.
2. Add native Media3 player and WebView discovery through visible video URLs and network requests.
3. Give the remote an explicit Open Page action when media cannot be extracted.
4. Add D-pad pointer for browser fallback and retain Back navigation.
5. Run tests and commit.

### Task 4: Release

**Files:** `.github/workflows/android-release.yml` (only if required by checks).

1. Run `git diff --check` and inspect changed files.
2. Push main, confirm GitHub Actions debug build and release checks.
3. Tag the next version, confirm signed APK in GitHub Release, report limits requiring TV validation.
