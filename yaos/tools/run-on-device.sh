#!/usr/bin/env bash
# Install only on a device the owner has already authorized through Android's ADB prompt.
set -euo pipefail
freecast_root="$(cd "$(dirname "$0")/.." && pwd)"
freecast_apk="${FREECAST_APK:-$freecast_root/app/build/outputs/apk/debug/app-debug.apk}"
if [[ $# -gt 0 ]]; then adb connect "$1"; fi
[[ -f "$freecast_apk" ]] || { echo "Build the APK first: ./gradlew assembleDebug" >&2; exit 1; }
adb install -r "$freecast_apk"
adb shell am start -n ru.freecast.tv/com.airtv.receiver.ui.MainActivity
adb logcat -s FreeCastReceiver FreeCastService VideoPipeline AudioPipeline AirPlayJNI
