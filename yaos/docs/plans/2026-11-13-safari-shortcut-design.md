# Safari share to native TV playback

The owner approved an iOS Shortcuts share-sheet command first, without an iOS app. Safari sends the current page URL to FreeCast TV over the home LAN. This replaces repeated copy/QR/code entry after one-time setup. The TV is the media client, so the iPhone can lock after playback begins.

The existing foreground AirPlay service owns a persistent, authenticated LAN command endpoint. A 128-bit pairing secret is generated on the TV and shown on the pairing screen with the TV's LAN address. Shortcuts uses POST to submit a page or direct media URL. Invalid requests must not launch anything; no browsing history is stored. The existing temporary QR form remains for phones without Shortcuts.

For direct HLS/MP4 URLs, FreeCast TV opens a native Media3 player with D-pad controls. For pages, an internal WebView searches for public HTTP(S) media URLs in video elements and observed resource requests, including embedded frames where visible. A candidate opens the native player; if none is found or playback fails, a user-visible action opens the page in the embedded browser. The browser provides a D-pad pointer for pages whose controls are not keyboard-focusable. Never send `blob:`/DRM media or cookies to the TV; those cannot be promised to work. Do not claim arbitrary Safari sites are supported without on-device testing.

Verification: focused tests for URL/candidate validation and command authorization; GitHub Actions unit tests, lint and APK build; then signed release. On TV, verify setup once, Safari Share Sheet command, remote playback controls, fallback pointer and Back, and the supplied Yandex/VK page.
