# FreeCast TV UI Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Ship a coherent TV remote interface across the main FreeCast screens using the approved visual design.

**Architecture:** Keep existing Activities and behavior. Centralize shared color, backgrounds, focus selectors and the programmatic rail in a small UI helper. Replace the main and broadcast layouts; restyle programmatic VPN, update and link-sharing screens; enlarge settings controls.

**Tech Stack:** Android XML resources, Kotlin Views, GitHub Actions Android CI.

---

### Task 1: Shared visual language and navigation

**Files:** `yaos/app/src/main/res/values/colors.xml`, `themes.xml`, `yaos/app/src/main/res/drawable/bg_*.xml`, new `yaos/app/src/main/java/com/airtv/receiver/ui/TvChrome.kt`.

1. Add the approved palette, layered dashboard gradient and focus selectors with visible focus and checked states.
2. Build a reusable side rail and page header. Each item opens the existing Activity; Settings on a subpage returns to Main with a settings request.
3. Keep widths and padding usable at 1280×720. Verify Kotlin/XML compiles in CI.

### Task 2: Main and broadcasts

**Files:** `activity_main.xml`, `MainActivity.kt`, `activity_broadcasts.xml`, `BroadcastsActivity.kt`.

1. Rebuild Main around SurfaceView, left rail, large receiver status card and three quick-action cards. Preserve all view IDs used by the receiver and the hide-controls flow.
2. Rebuild Broadcasts into a two-column, DPAD-focusable card grid. Preserve each source URL and its existing click action.
3. Verify initial focus, Back and hidden controls during playback; compile and lint.

### Task 3: Utility screens and settings

**Files:** `VpnActivity.kt`, `UpdateActivity.kt`, `LinkShareActivity.kt`, `dialog_settings.xml`, diagnostics styles.

1. Apply shared rail and card style to VPN, update and QR flows; retain their timers, servers, download and installer behavior.
2. Enlarge settings choices and make focus distinct from the stored selection. Keep name validation and service restart semantics.
3. Inspect at TV sizes and correct clipping or squeezed actions.

### Task 4: Release

1. Run `testDebugUnitTest assembleDebug lintDebug` and the release size gate in Android CI.
2. Review changed resource IDs, focus order and final diff. Keep networking and media playback unchanged.
3. Merge the PR and publish a signed GitHub Release through the existing workflow. Report any visual device checks that need the user's TV.
