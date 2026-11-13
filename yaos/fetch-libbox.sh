#!/usr/bin/env bash
set -euo pipefail
version=1.14.3
expected=6f1f295b1d988d4875546a48cb90a90e2b5f70ca10432f1a8d95e757a76f01d9
target="$(dirname "$0")/app/libs/libbox.aar"
mkdir -p "$(dirname "$target")"
if [[ -f "$target" ]] && [[ "$(shasum -a 256 "$target" | cut -d' ' -f1)" == "$expected" ]]; then
  exit 0
fi
temp="${target}.download"
trap 'rm -f "$temp"' EXIT
curl -fL --retry 3 --connect-timeout 20 \
  "https://github.com/singbox-android/libbox/releases/download/${version}/libbox.aar" -o "$temp"
actual="$(shasum -a 256 "$temp" | cut -d' ' -f1)"
[[ "$actual" == "$expected" ]] || { echo 'libbox checksum mismatch' >&2; exit 1; }
mv "$temp" "$target"
