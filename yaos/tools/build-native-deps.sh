#!/bin/bash
#
# Rebuilds the static native dependencies (OpenSSL libcrypto + libplist) that the AirPlay
# library links against, for every Android ABI the app ships.
#
# The results live in app/src/main/cpp/prebuilt/<abi>/{lib,include} and are committed, so a
# normal application build does not rebuild these libraries. Set FORCE_REBUILD=1 to
# replace existing archives after successfully compiling their corresponding sources.
#
# Requirements: Android NDK (set ANDROID_NDK_ROOT; NDK_ROOT is also accepted),
# Python 3, tar, make, perl, autotools.
#
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
NDK_ROOT="${ANDROID_NDK_ROOT:-${NDK_ROOT:-$HOME/Library/Android/sdk/ndk/26.1.10909125}}"
export ANDROID_NDK_ROOT="$NDK_ROOT"
API=24
freecast_force_rebuild="${FORCE_REBUILD:-0}"
case "$freecast_force_rebuild" in
    0|1) ;;
    *) echo "FORCE_REBUILD must be 0 or 1" >&2; exit 2 ;;
esac

case "$(uname -s)" in
    Darwin) HOST_TAG=darwin-x86_64 ;;
    Linux)  HOST_TAG=linux-x86_64 ;;
    *) echo "unsupported host $(uname -s)"; exit 1 ;;
esac
TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/$HOST_TAG"
[ -d "$TOOLCHAIN" ] || { echo "NDK toolchain not found at $TOOLCHAIN"; exit 1; }

OPENSSL_VER=3.0.16
PLIST_VER=2.6.0

WORK="$REPO/.native-deps-build"
OUT="$REPO/app/src/main/cpp/prebuilt"
mkdir -p "$WORK" "$OUT"
cd "$WORK"

# Exact corresponding sources are bundled; rebuilding does not silently fetch a new archive.
python3 "$REPO/tools/verify-source-archives.py"
[ -d "openssl-$OPENSSL_VER" ] || tar xzf "$REPO/third_party_sources/openssl-$OPENSSL_VER.tar.gz"
[ -d "libplist-$PLIST_VER" ] || tar xjf "$REPO/third_party_sources/libplist-$PLIST_VER.tar.bz2"

for ABI in arm64-v8a armeabi-v7a x86_64; do
    case $ABI in
        arm64-v8a)   SSL_TARGET=android-arm64;  HOST=aarch64-linux-android;    CCPFX=aarch64-linux-android$API ;;
        armeabi-v7a) SSL_TARGET=android-arm;    HOST=armv7a-linux-androideabi; CCPFX=armv7a-linux-androideabi$API ;;
        x86_64)      SSL_TARGET=android-x86_64; HOST=x86_64-linux-android;     CCPFX=x86_64-linux-android$API ;;
    esac
    DEST="$OUT/$ABI"
    mkdir -p "$DEST/lib" "$DEST/include"

    if [ "$freecast_force_rebuild" = "1" ] || [ ! -f "$DEST/lib/libcrypto.a" ]; then
        echo ">>> building openssl for $ABI"
        rm -rf "$WORK/ossl-$ABI"
        cp -R "openssl-$OPENSSL_VER" "$WORK/ossl-$ABI"
        (
            cd "$WORK/ossl-$ABI"
            PATH="$TOOLCHAIN/bin:$PATH" ./Configure "$SSL_TARGET" -D__ANDROID_API__="$API" \
                no-shared no-tests no-engine no-dso no-ui-console no-comp \
                --prefix="$DEST" >/dev/null
            PATH="$TOOLCHAIN/bin:$PATH" make -j8 build_libs >/dev/null
            cp libcrypto.a "$DEST/lib/"
            mkdir -p "$DEST/include/openssl"
            cp -R include/openssl/*.h "$DEST/include/openssl/"
        )
    fi

    if [ "$freecast_force_rebuild" = "1" ] || [ ! -f "$DEST/lib/libplist-2.0.a" ]; then
        echo ">>> building libplist for $ABI"
        rm -rf "$WORK/plist-$ABI"
        cp -R "libplist-$PLIST_VER" "$WORK/plist-$ABI"
        (
            cd "$WORK/plist-$ABI"
            export CC="$TOOLCHAIN/bin/${CCPFX}-clang"
            export CXX="$TOOLCHAIN/bin/${CCPFX}-clang++"
            export AR="$TOOLCHAIN/bin/llvm-ar"
            export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
            export STRIP="$TOOLCHAIN/bin/llvm-strip"
            export CFLAGS="-fPIC -O2"
            ./configure --host=$HOST --prefix="$DEST" \
                --enable-static --disable-shared --without-cython --without-tests >/dev/null
            make -j8 >/dev/null
            make install >/dev/null
        )
        # the C++ wrapper and libtool archives are not used by the app
        rm -f "$DEST"/lib/libplist++* "$DEST"/lib/*.la
    fi
    echo "=== $ABI ==="
    ls "$DEST/lib"
done
echo "done; build tree left in $WORK (safe to delete)"
