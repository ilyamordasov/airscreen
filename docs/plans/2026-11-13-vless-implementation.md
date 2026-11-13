# Built-in VLESS Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Route FreeCast TV web pages and video streams through a user-provided VLESS connection without disrupting local iPhone handoff.

**Architecture:** Embed sing-box libbox in the Android app and run a per-app Android VpnService. Add an authenticated LAN import page to the existing pairing flow, persist a standard VLESS subscription privately, and expose connect/disconnect/status from the TV UI. Keep local addresses direct.

**Tech Stack:** Kotlin, Android VpnService, sing-box libbox, NanoHTTPD, Gradle, JUnit.

---

### Task 1: VLESS configuration

**Files:** Create `yaos/app/src/main/java/com/airtv/receiver/vpn/VlessProfile.kt`, `yaos/app/src/main/java/com/airtv/receiver/vpn/BoxConfig.kt`, and focused unit tests.

1. Add tests for plain and Base64 text subscriptions with standard `vless://` links, malformed inputs, TLS/Reality parameters, and no-secret diagnostics.
2. Parse the selected standard VLESS node, with explicit unsupported-feature errors.
3. Generate sing-box JSON with TUN inbound, VLESS outbound, direct private-network routing, and remote DNS.
4. Run `./gradlew testDebugUnitTest` and commit.

### Task 2: Android tunnel

**Files:** Modify `yaos/app/build.gradle.kts`, `yaos/settings.gradle.kts`, `yaos/app/src/main/AndroidManifest.xml`; create `yaos/app/src/main/java/com/airtv/receiver/vpn/FreeCastVpnService.kt`.

1. Pin a reproducible libbox AAR version and checksum or build it from an official source revision in CI.
2. Implement only the libbox platform hooks required for per-app VpnService and network changes; protect outbound sockets from recursive routing.
3. Add foreground notification, system consent flow, connect/disconnect, revoked connection handling, and status.
4. Run Android build/lint through GitHub Actions and commit.

### Task 3: Import and TV controls

**Files:** Create `yaos/app/src/main/java/com/airtv/receiver/ui/VpnActivity.kt` and LAN import server; modify `MainActivity.kt` and its layout.

1. Add a remote-friendly VPN tile with state and an import QR.
2. Accept one subscription/profile over an authenticated, size-limited local endpoint; never put the secret in the QR, logs, diagnostic UI, or repository.
3. Download and validate subscriptions off the UI thread; persist using app-private storage.
4. Add connect/disconnect buttons and clear error messages, commit.

### Task 4: End-to-end verification and release

1. Run focused unit tests, Android debug build, and release build/lint CI.
2. On a TV, verify VPN consent, blocked page access in WebView, Media3 playback, and iPhone Shortcut/AirPlay handoff while VPN is active.
3. Release a signed version only after CI passes and report any device-only validation still pending.
