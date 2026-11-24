#!/usr/bin/env python3
"""Prepare the ARM64 Ace Stream payload for a local AirScreen debug APK."""

import argparse
import hashlib
import io
from pathlib import Path
import zipfile


ENGINE_SHA256 = "a8bd48347cad2a52381da5131c315a129399c1d414b169bf301dd516df775784"
PYTHON_ARCHIVE = "arm64-v8a_private_py.zip"
ENGINE_ARCHIVE = "arm64-v8a_private_res.zip"


def prepare(apk: Path, output: Path) -> None:
    with apk.open("rb") as stream:
        digest = hashlib.sha256()
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    if digest.hexdigest() != ENGINE_SHA256:
        raise ValueError("Expected the pinned Ace Stream Engine 3.1.80.0 ARM64 APK")

    assets = output / "assets" / "ace-runtime"
    libraries = output / "jniLibs" / "arm64-v8a"
    assets.mkdir(parents=True, exist_ok=True)
    libraries.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(apk) as engine:
        python = engine.read(f"assets/engine/{PYTHON_ARCHIVE}")
        (assets / PYTHON_ARCHIVE).write_bytes(python)
        (assets / ENGINE_ARCHIVE).write_bytes(engine.read(f"assets/engine/{ENGINE_ARCHIVE}"))
        with zipfile.ZipFile(io.BytesIO(python)) as runtime:
            # Android installs this PIE executable in its read-only native library directory.
            (libraries / "libacepython.so").write_bytes(runtime.read("python/bin/python"))
            (libraries / "libpython3.8m.so").write_bytes(runtime.read("python/lib/libpython3.8m.so"))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    prepare(args.apk, args.output)
