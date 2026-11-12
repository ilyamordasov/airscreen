# Separate AirPlay Video Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Play supported AirPlay HLS video on the TV without mirroring.

**Architecture:** Forward native HLS callbacks to Kotlin. Use a TV-side media player on the existing Surface. Preserve mirror playback, lifecycle, and error reporting. Add broader senders only after protocol captures establish their format.

**Tech Stack:** UxPlay C, JNI, Android Kotlin, Android MediaPlayer.

---

### Task 1: HLS callback bridge

Modify `app/src/main/cpp/airplay_jni.c` and `app/src/main/java/com/airtv/receiver/airplay/NativeAirPlay.kt`: enable HLS, forward play and stop, validate the local playback URL. Run `./gradlew testDebugUnitTest`.

### Task 2: TV player

Create `app/src/main/java/com/airtv/receiver/media/HlsPipeline.kt`. Connect it in `AirPlayReceiver.kt` to the shared Surface and playback state. Allow cleartext only to localhost for the local proxy. Run `./gradlew testDebugUnitTest assembleDebug`.

### Task 3: Real sender compatibility

Check YouTube, Safari, and one other video app on an iPhone, including playback after lock. Record exact supported sources and errors. Extend the native protocol only from observed requests. Do not claim universal compatibility or DRM playback.
