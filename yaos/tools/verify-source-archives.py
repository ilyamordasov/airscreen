#!/usr/bin/env python3
"""Verify the exact dependency sources supplied with the FreeCast TV source package."""
from pathlib import Path
import hashlib
import json

root = Path(__file__).resolve().parent.parent / "third_party_sources"
records = json.loads((root / "SOURCES.json").read_text())
for item in records:
    path = root / item["name"]
    if path.parent != root or not path.is_file():
        raise SystemExit(f"Missing source archive: {item['name']}")
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if digest != item["sha256"]:
        raise SystemExit(f"SHA-256 mismatch: {item['name']}")
    print(f"OK {item['name']} ({path.stat().st_size} bytes)")
