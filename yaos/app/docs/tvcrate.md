# TVCrate → FreeCast TV

The TV resolves a TVCrate channel page to a temporary native HLS URL and plays
it with the existing Media3 player. No browser process is needed for resolution.

## Fullscreen TV playback

`StreamPlaybackActivity` uses its own black `Theme.FreeCast.Playback`, landscape
orientation and a `MATCH_PARENT` PlayerView. AndroidX WindowInsets hides status
and navigation bars on entry, resume and window-focus recovery. The Activity
handles keyboard/navigation-device changes so reconnecting a remote does not
needlessly recreate playback.

Video uses `RESIZE_MODE_FIT`: a 16:9 stream fills a 16:9 viewport; other aspect
ratios retain black bars instead of cropping the score or stretching the image.
The loading status disappears after the first rendered frame. Controls start
hidden, appear with the remote and hide after three seconds without input.
The first OK reveals controls without also toggling pause. Back hides visible
controls first, then returns through the existing Activity back stack.

Playback controls and error actions have separate focus handling: Retry/Open
page remain available on failure. The screen stays awake during active READY
or BUFFERING playback and is released on pause, error or exit. Physical TV
visual behavior still needs the device acceptance checks in the Codex handoff.

## Open a channel

- On the TV: **Трансляции → Матч! Премьер · TVCrate**.
- Or send `https://tvcrate.com/channel/match-premer-501` through the existing
  phone link inbox or Safari shortcut. Both routes reach the same native player.
- A `https://tvcrate.com/player/?key=<UUID>` URL is also supported.
- On failure, **Повторить** obtains a new URL. **Открыть страницу** opens the
  original page in the existing WebView and explicitly skips native routing.

The resolver accepts TVCrate `/channel/<slug>` and `/player/` URLs. A channel
must contain one unambiguous TVCrate player iframe. The native path currently
supports HTTPS HLS (`.m3u8`); changed layouts or other media types produce an
error with the page fallback available.

## Resolution

`com.airtv.receiver.link.TvCrateResolver` is blocking. Run it on a worker:

```kotlin
val stream = TvCrateResolver().resolve(TvCrateResolver.MATCH_PREMIER_URL)
// stream.mediaUrl: short-lived .m3u8 URL
// stream.sourcePage: stable channel/player page
// stream.requestHeaders: use on manifests, keys and segments
// stream.expiresAtEpochSeconds: optional absolute expiry, not a fixed TTL
```

The implementation follows the public player behavior observed on 2026-11-22:

1. Fetch the channel page and parse its player iframe. Ignore other origins,
   comments, script templates, duplicate preview frames and ambiguous players.
2. Fetch the player configuration and check its `STREAM_KEY` against the iframe.
   Parse constant values without evaluating JavaScript.
3. Request `https://live.tvcrate.com/gettoken.php?key=<UUID>&n=1` and read `url`.
   In native mode (`n=1`), the observed HLS playlist already signs its segment
   URLs. Do not strip or rebuild the query string.
4. Pass the result to Media3 with the same `User-Agent`, `Origin` and `Referer`
   used for token issuance. The app uses `FreeCast-TV/1.0` consistently.

The player also defines `PIN_SRC` and `RESOLVE_MODE`. The parser handles a
pinned HTTPS HLS source and uses `fresh=1` for retries in resolve mode. These
branches are covered by fixtures; the live Match Premier check used the
ordinary token flow.

An observed request without `Origin: https://tvcrate.com` returned HTTP403.
Changing User-Agent between token issuance and the HLS request also returned
403 for one observed URL. This establishes the headers used here, not a claim
about the provider's internal signing implementation.

## Lifetime and errors

Store the page URL, not `mediaUrl`; never persist or log signed stream URLs.
There is no in-memory stream cache. Every start, return to the screen, or manual
retry resolves again on the TV. `StreamPlaybackActivity` refreshes before
`expires`, at most six hours between scheduled refreshes, and makes one
automatic re-resolution attempt after HTTP403/410. This limit prevents error
loops; a manual retry starts a new attempt.

The six-hour interval is a refresh cadence, not a promised token lifetime.
The API's `expires` query parameter is the authority for expiration when present.
An early refresh failure leaves an otherwise playing stream running; a later
HTTP error can trigger the bounded recovery attempt.

Network requests have connection/read timeouts, a 2 MiB response limit, bounded
redirects to the page/API hosts and cancellation checks. UI callbacks use a
generation check; leaving the Activity cancels lookup and releases the player.

## Verification

Fixtures in `src/test/resources/tvcrate/` reflect the observed HTML/JSON shape.
They contain the public channel key and a fake `TEST_TOKEN`, never a working
signed stream URL.

Focused project test command from `yaos/`:

```bash
./gradlew testDebugUnitTest \
  --tests 'com.airtv.receiver.link.TvCrateResolverTest' \
  --tests 'com.airtv.receiver.link.MediaCandidateTest' \
  --tests 'com.airtv.receiver.link.SharedPageTest'
```

Validation performed during implementation:

- Kotlin 1.9.24/JVM17 compilation and 29 passing JUnit tests (24 resolver tests
  plus 5 existing link-validation tests), using a standalone compiler.
- Compile-only Android validation against real SDK34 and Media3 1.4.1 APIs for
  the changed Activities, including the fullscreen APIs from AndroidX core
  1.13.1. Manifest/theme XML and resource references were checked. This is not
  a full Gradle/NDK build or a visual check on a TV.
- Live page → public API → HLS manifest → 188-byte segment read. The response
  was a valid HLS playlist and the segment began with MPEG-TS sync byte `0x47`.
  The compiled Kotlin resolver used an injected HTTP bridge for this probe;
  Android HttpURLConnection and video decoding were not exercised.

The repository's Android debug workflow performs the full
`testDebugUnitTest assembleDebug lintDebug` gate after a push. Playback on a
physical YaOS TV and a newly installable signed release are separate checks.
