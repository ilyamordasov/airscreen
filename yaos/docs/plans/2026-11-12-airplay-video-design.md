# Separate AirPlay video playback

The receiver should play an AirPlay video stream on the TV while the iPhone acts as a controller, so the phone screen can lock. The existing UxPlay core supplies an HLS proxy and play callbacks; Android must render that local HLS URL on the same Surface used by mirroring. The receiver must stop the media player before returning to mirroring and report playback errors. Safari and other senders need independent protocol work because the pinned core currently recognizes only YouTube HLS requests. DRM content is excluded.

An ALAC decoder is a separate audio task and does not enable video handoff.
