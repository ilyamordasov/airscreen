# AirPlay video reliability and Safari handoff

FreeCast TV already accepts AirPlay video play callbacks and renders the stream on the same TV surface used for mirroring. On Yandex YNDX-00072 with Android 9, a real YouTube handoff reaches HLS playback but Android MediaPlayer fails before the first frame with `1/-1004` (I/O error). Safari currently works through screen mirroring; no Safari video handoff has been captured yet.

Use Media3 ExoPlayer with its HLS module for TV-side playback. Keep the native AirPlay server and its FCUP proxy. Resolve that local proxy through `127.0.0.1` so playback does not depend on IPv6 localhost availability. Report player load failures with the HTTP status or Media3 error code, without exposing signed video URLs or query strings. Keep the existing mirror decoder and shared surface lifecycle. The TV activity is already landscape and will size a successful video handoff to the video's aspect ratio.

Safari's in-player AirPlay button is the intended entry point for video handoff. Existing direct HTTP(S) URL handling remains; site-specific cookies, DRM and players without AirPlay require a real sender capture before extension. Screen Mirroring cannot reveal a page's video URL to the TV receiver. A TV-side URL launch remains a separate fallback, outside this change.

Validate compilation, unit tests and release lint in GitHub Actions. On the physical TV, retry YouTube and a Safari player with an AirPlay button, check the first frame, landscape layout, sound and playback after locking the iPhone. A green build alone does not establish sender compatibility.
