# Local link handoff for Safari pages

Safari AirPlay on the supplied Yandex Video page sends audio only to FreeCast TV. The page is a Yandex wrapper around a VK embedded player. The pinned UxPlay receiver cannot turn this audio session into a video URL. The user approved a TV-side link handoff so the iPhone can be locked after the page starts on the TV.

Add a “Передать ссылку с телефона” tile to the existing Трансляции screen. It opens a TV screen with a QR code and a short code. The QR leads to a tiny local HTTP form served by FreeCast TV while this screen is open. The user copies a Safari page URL, opens the form, pastes the URL and enters the TV code. The TV opens the HTTPS page in the app's WebView. The phone no longer transports frames. The WebView supports HTML5 fullscreen video and TV remote Back; the TV, not the iPhone, fetches media.

The server accepts only a bounded form body and HTTPS page URLs, checks the code, and does not retain the URL after opening. No remote account, cloud relay or iPhone app is required. If the TV has no usable LAN IPv4 address or the fixed port is occupied, show an error. The TV WebView may still be unable to play a provider's codec, DRM or login-protected page; this must be checked on the actual Yandex/VK example. Keep AirPlay mirroring and the YouTube video path unchanged.

Verify parser/URL validation with focused unit tests, run release unit tests, lint and APK build in GitHub Actions, then publish a signed update. On the TV, check QR pairing, wrong code, successful link launch, fullscreen playback, lock-phone behavior and remote Back.
