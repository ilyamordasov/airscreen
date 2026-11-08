#!/usr/bin/env bash
# Run the real parser and packet/identity/pairing boundary regressions on Linux.
# Requires a C compiler, libcrypto/OpenSSL headers, ASan, and UBSan.
set -euo pipefail
native_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
test_build_dir="$(mktemp -d "${TMPDIR:-/tmp}/freecast-native-tests.XXXXXX")"
trap 'rm -rf -- "$test_build_dir"' EXIT
test_cc="${CC:-cc}"
test_flags=(-std=c11 -D_POSIX_C_SOURCE=200809L -g -O1 -fsanitize=address,undefined -fno-omit-frame-pointer -I "$native_dir")

"$test_cc" "${test_flags[@]}" "$native_dir/tests/test_http_request.c" \
    "$native_dir/http_request.c" "$native_dir/llhttp/api.c" \
    "$native_dir/llhttp/http.c" "$native_dir/llhttp/llhttp.c" \
    -o "$test_build_dir/http_request"
"$test_cc" "${test_flags[@]}" "$native_dir/tests/test_packet_bounds.c" -o "$test_build_dir/packet_bounds"
"$test_cc" "${test_flags[@]}" "$native_dir/tests/test_service_name.c" -o "$test_build_dir/service_name"
"$test_cc" "${test_flags[@]}" -Wno-deprecated-declarations \
    "$native_dir/tests/test_pairing.c" "$native_dir/pairing.c" \
    "$native_dir/crypto.c" "$native_dir/srp.c" "$native_dir/utils.c" \
    -lcrypto -o "$test_build_dir/pairing"

# Under a ptrace-based runner LeakSanitizer cannot start. For that environment:
# ASAN_OPTIONS=detect_leaks=0 bash app/src/main/cpp/airplay/tests/run_host_tests.sh
"$test_build_dir/http_request"
"$test_build_dir/packet_bounds"
"$test_build_dir/service_name"
"$test_build_dir/pairing"
