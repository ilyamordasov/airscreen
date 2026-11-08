# Third-party notices and source provenance

FreeCast TV 0.1.0 (2026-11-08) is a derivative work of AirTV. It contains no code from
commercial AirScreen and does not bypass an AirScreen subscription. The combined
application is distributed under GNU GPL version 3; see LICENSE. Original source
copyright headers remain in place.

## AirTV

- Upstream: https://github.com/0xb1ob/air-tv
- Exact source revision: 80e958d380e1c9cc609d037c439faec0826bc7b7
- License: GPL-3.0; the upstream LICENSE is retained.
- Main areas changed: Russian TV UI, receiver preferences and safe display profiles,
  independent application identity and signing, service lifecycle, truthful decode
  states, network reconnection, decoder input bounds and cleanup, Unicode DNS labels,
  protocol security/compatibility fixes and additional verification.

## UxPlay protocol library

- Upstream: https://github.com/FDH2/UxPlay
- Source: app/src/main/cpp/airplay, vendored by the pinned AirTV revision.
- Original copyrights include the UxPlay, RPiPlay and ShairPlay contributors; read
  the headers of individual files. Many core files are LGPL-2.1-or-later.
- Targeted compatibility and memory-safety fixes backported from UxPlay v1.73.7.
  Additional local boundary checks are documented by their tests and build report.
- This is a legacy-compatible AirPlay receiver implementation; it is not a licensed
  Apple product and does not decode DRM-protected video.

## PlayFair, llhttp and mdnsd

- PlayFair: app/src/main/cpp/airplay/playfair/LICENSE.md (GPLv3).
- llhttp: app/src/main/cpp/airplay/llhttp/LICENSE-MIT (MIT; source headers retained).
- Embedded mdnsd: app/src/main/cpp/airplay/mdnsd; preserve individual copyright/license headers.

## Statically linked libraries

- OpenSSL libcrypto 3.0.16: Apache License 2.0.
  https://github.com/openssl/openssl/releases/tag/openssl-3.0.16
- libplist 2.6.0: LGPL-2.1-or-later (see COPYING.LESSER in corresponding source;
  COPYING contains the GPLv2 text for other parts of the upstream distribution).
  https://github.com/libimobiledevice/libplist/releases/tag/2.6.0

The source archives, original license files inside them, download provenance and
SHA-256 values are supplied in third_party_sources/. Prebuilt archives for ARMv7,
ARM64 and x86_64 are retained from the pinned AirTV source; the rebuild script
uses the matching source versions and the Android NDK. These are not modified
commercial binaries.

## Android / Kotlin dependencies

AndroidX core 1.13.1, AppCompat 1.7.0, lifecycle-service 2.8.4 and their resolved
AndroidX dependencies are Apache-2.0 licensed. Kotlin standard library is Apache-2.0.
These are resolved through the official Google Maven / Maven Central repositories.
JUnit 4.13.2 and AndroidX Test are development/test dependencies and are not in the
release APK. Dependency declarations are in app/build.gradle.kts.

The Android SDK/NDK and JDK are build tools, not included in the source delivery.
