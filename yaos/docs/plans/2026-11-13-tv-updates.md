# TV Updates Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Let FreeCast TV find, download, and install signed updates from GitHub Releases without a USB drive.

**Architecture:** A small update client reads the public latest-release API, validates the expected APK asset and digest, and downloads to app-private cache. A TV screen invokes Android's package installer through FileProvider. A tag-triggered workflow builds and publishes a release APK signed with a key supplied through GitHub Secrets.

**Tech Stack:** Kotlin, Android API 24+, HttpURLConnection, FileProvider, Gradle 8.4.2, GitHub Actions.

---

### Task 1: Version and release validation

**Files:** Create `yaos/app/src/main/java/com/airtv/receiver/update/ReleaseVersion.kt` and `ReleaseClient.kt`; test `yaos/app/src/test/java/com/airtv/receiver/update/ReleaseVersionTest.kt`.

1. Parse `vMAJOR.MINOR.PATCH`; compare numeric components; reject malformed tags. Require a single asset named `FreeCast-TV-X.Y.Z.apk` under the repository release URL and a SHA-256 digest.
2. Test ordering (`0.2.0 > 0.1.0`), malformed tags, and asset selection. Run `./gradlew testDebugUnitTest` in `yaos`; tests must pass.
3. Commit the model and client.

### Task 2: TV update UI and installation

**Files:** Create `yaos/app/src/main/java/com/airtv/receiver/ui/UpdateActivity.kt` and `yaos/app/src/main/res/xml/update_paths.xml`; modify `MainActivity.kt`, `activity_main.xml`, `strings.xml`, and `AndroidManifest.xml`.

1. Add a focusable «Обновление» button. Check latest on app start and show the available version on the button. Button opens a simple status/progress screen with a download action.
2. Stream the APK to a temporary private file with size limit. Check length, SHA-256, package ID `ru.freecast.tv`, and a higher version code; then atomically move it to the FileProvider cache path.
3. Launch `ACTION_INSTALL_PACKAGE` with a content URI and read grant. On API 26+ handle `canRequestPackageInstalls()` by opening the app-specific unknown-sources setting. Keep the old app usable on cancel or error.
4. Run `./gradlew testDebugUnitTest assembleDebug` in `yaos`; verify the manifest and APK upload in Actions. Commit.

### Task 3: Signed release publication

**Files:** Create `.github/workflows/android-release.yml`; modify `yaos/README.md` and `yaos/app/build.gradle.kts` only if needed for version code mapping.

1. Trigger on `vX.Y.Z` tags; validate the tag and compute a strictly increasing version code. Decode the keystore from GitHub Secrets into runner temp storage and build `testReleaseUnitTest assembleRelease` with matching version properties. Fail closed when secrets are absent.
2. Publish the signed `FreeCast-TV-X.Y.Z.apk` to GitHub Releases with `contents: write`; never expose the keystore or password in logs or artifacts. Document one-time installation after uninstalling debug builds and the ongoing tag workflow.
3. Run the release workflow with the permanent key, inspect APK metadata/signature and published asset, then exercise check/download/installer on a TV. If key or TV is unavailable, report that verification explicitly and keep release unpublished.
