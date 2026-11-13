# Local Link Handoff Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Open a Safari page in FreeCast TV using a local link handoff, so the iPhone can be locked.

**Architecture:** A TV activity runs a small LAN HTTP form with a short code and QR entry point. A validated HTTPS URL is passed to the existing in-app WebView, extended for fullscreen video. The server stops when the handoff activity closes.

**Tech Stack:** Android Kotlin, NanoHTTPD 2.3.1, ZXing core 3.5.4, WebView.

---

### Task 1: Safe URL input

**Files:** Create `app/src/main/java/com/airtv/receiver/link/SharedPage.kt` and `app/src/test/java/com/airtv/receiver/link/SharedPageTest.kt`.

1. Test and implement validation for a nonempty HTTPS URL of at most 2048 characters with a host, and reject other schemes, credentials and control characters.
2. Return the validated URL verbatim to the TV WebView; do not extract or store signed media URLs.

### Task 2: LAN handoff screen

**Files:** Create `app/src/main/java/com/airtv/receiver/link/LinkShareServer.kt`, `app/src/main/java/com/airtv/receiver/ui/LinkShareActivity.kt`; modify `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/airtv/receiver/ui/BroadcastsActivity.kt`, `app/src/main/res/values/strings.xml`, `THIRD_PARTY_NOTICES.md`.

1. Show a QR URL, six-digit code and connection instructions in a TV activity opened from Трансляции.
2. Serve a bounded local HTML form with NanoHTTPD; accept POST only with the current code and a validated HTTPS URL; stop on activity close.
3. Open the URL inside FreeCast TV and leave the existing AirPlay service running.

### Task 3: TV page playback

**Files:** Modify `app/src/main/java/com/airtv/receiver/ui/BroadcastWebActivity.kt`.

1. Accept validated HTTPS URLs from the local handoff and retain existing Match TV fallback.
2. Support HTML5 fullscreen video and remote Back; disable WebView file/content access.

### Task 4: Release

1. Run focused JVM tests and `./gradlew testReleaseUnitTest assembleRelease lintRelease` in the existing signed release workflow.
2. Verify the release certificate and public APK asset, then test the supplied Yandex/VK page on the actual TV.
