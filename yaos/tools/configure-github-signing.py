#!/usr/bin/env python3
"""Validate the owner's signing archive and upload it to GitHub Actions Secrets.

Requires an authenticated GitHub CLI. Secret values are sent through stdin and
are never printed or written to the repository.
"""

import base64
import os
import pathlib
import re
import subprocess
import sys
import tempfile
import zipfile


REPOSITORY = "ilyamordasov/airscreen"
ALIAS = "freecast"
EXPECTED_CERTIFICATE = "f7b5180056f212c59085b6ac0e3e9f92f06411f85ce83bfb8bf29e0af17f373b"


def main() -> int:
    if len(sys.argv) != 2:
        print("Usage: python3 tools/configure-github-signing.py /path/to/FreeCast-TV-signing-key.zip")
        return 2
    archive = pathlib.Path(sys.argv[1]).expanduser()
    if not archive.is_file():
        print("Signing archive not found", file=sys.stderr)
        return 1
    with zipfile.ZipFile(archive) as z:
        key_names = [name for name in z.namelist() if name.endswith("/freecast-tv.jks")]
        password_names = [name for name in z.namelist() if name.endswith("/password.txt")]
        if len(key_names) != 1 or len(password_names) != 1:
            print("Archive must contain one freecast-tv.jks and one password.txt", file=sys.stderr)
            return 1
        key_data = z.read(key_names[0])
        password = z.read(password_names[0]).decode("utf-8").strip()
    if not key_data or not password:
        print("Signing archive is incomplete", file=sys.stderr)
        return 1

    with tempfile.TemporaryDirectory(prefix="freecast-signing-") as directory:
        key_path = pathlib.Path(directory) / "release.jks"
        key_path.write_bytes(key_data)
        key_path.chmod(0o600)
        env = {**os.environ, "FREECAST_SIGNING_PASSWORD": password}
        check = subprocess.run(
            ["keytool", "-list", "-v", "-alias", ALIAS, "-keystore", str(key_path),
             "-storepass:env", "FREECAST_SIGNING_PASSWORD", "-J-Duser.language=en"],
            env=env, capture_output=True, text=True,
        )
        fingerprint = re.search(r"SHA256:\s*([0-9A-F:]+)", check.stdout)
        actual = fingerprint.group(1).replace(":", "").lower() if fingerprint else ""
        if check.returncode != 0 or "PrivateKeyEntry" not in check.stdout or actual != EXPECTED_CERTIFICATE:
            print("Archive does not contain the expected FreeCast TV private key", file=sys.stderr)
            return 1
        signing_check = subprocess.run(
            ["keytool", "-certreq", "-alias", ALIAS, "-file", str(pathlib.Path(directory) / "check.csr"),
             "-keystore", str(key_path), "-storepass:env", "FREECAST_SIGNING_PASSWORD",
             "-keypass:env", "FREECAST_SIGNING_PASSWORD", "-J-Duser.language=en"],
            env=env, input="", capture_output=True, text=True,
        )
        if signing_check.returncode != 0:
            print("Private key password does not match the archive", file=sys.stderr)
            return 1

    if subprocess.run(["gh", "auth", "status"], capture_output=True).returncode != 0:
        print("GitHub CLI is not logged in. Run: gh auth login -h github.com", file=sys.stderr)
        return 1

    values = {
        "SIGNING_KEYSTORE_BASE64": base64.b64encode(key_data).decode("ascii"),
        "SIGNING_KEYSTORE_PASSWORD": password,
        "SIGNING_KEY_ALIAS": ALIAS,
        "SIGNING_KEY_PASSWORD": password,
    }
    for name, value in values.items():
        result = subprocess.run(
            ["gh", "secret", "set", name, "--repo", REPOSITORY],
            input=value, capture_output=True, text=True,
        )
        if result.returncode != 0:
            print(f"Could not set {name}; check GitHub repository access", file=sys.stderr)
            return 1
    print("FreeCast TV signing secrets configured in GitHub Actions")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
