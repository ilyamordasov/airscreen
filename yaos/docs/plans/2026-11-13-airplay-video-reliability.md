# AirPlay Video Reliability Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make AirPlay video handoff more reliable on Android TV and expose useful failures while retaining screen mirroring.

**Architecture:** Keep UxPlay's FCUP proxy and callback bridge. Replace the Android framework MediaPlayer used only for AirPlay video with Media3 ExoPlayer plus HLS; preserve shared Surface and session generation. Use an IPv4 loopback URL for the local proxy.

**Tech Stack:** Kotlin, AndroidX Media3 1.4.1, UxPlay C, GitHub Actions.

---

### Task 1: Local HLS URL

**Files:** `app/src/main/cpp/airplay/airplay_video.c`

1. Change the local FCUP proxy prefix from `http://localhost:` to `http://127.0.0.1:`; leave remote media URLs intact.
2. Run the existing native host tests `ASAN_OPTIONS=detect_leaks=0 bash app/src/main/cpp/airplay/tests/run_host_tests.sh` when its dependencies are present.

### Task 2: TV video player

**Files:** `app/build.gradle.kts`, `app/src/main/java/com/airtv/receiver/media/HlsPipeline.kt`, `app/src/main/java/com/airtv/receiver/airplay/AirPlayReceiver.kt`

1. Add Media3 ExoPlayer and HLS artifacts at the same version compatible with the current Kotlin build.
2. Give `HlsPipeline` an Android `Context`; build ExoPlayer on the main looper, attach the existing Surface, set a `MediaItem`, seek to the supplied start position, prepare and play.
3. Forward actual video size, first rendered frame and player errors to the existing receiver callbacks. Include HTTP response status or Media3 error code in the failure message; omit URL query parameters.
4. Release the previous player on stop or new play; ignore callbacks from previous generations.

### Task 3: Verify and publish

**Files:** `.github/workflows/android-release.yml`, documentation as needed

1. Run `./gradlew testReleaseUnitTest assembleRelease lintRelease` in CI with the stable signing key.
2. Check the signed APK fingerprint against `docs/verification/apk-signature.txt` and publish the next release tag.
3. On the physical TV, retry the YouTube AirPlay button and then a Safari video AirPlay button; report compatibility from observed results only.
