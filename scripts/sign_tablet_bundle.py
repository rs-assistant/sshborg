#!/usr/bin/env python3
"""Re-sign a verified CI bundle; never generate or silently replace a signing key."""

import argparse
import base64
import binascii
import json
import os
from pathlib import Path
import subprocess
import tempfile

from tablet_release import (apk_names, payload_names, certificate_pin, digest, require_dispatch,
                            require_stable_signing, verify_apk_certificate, verify_bundle,
                            verify_personal_bundle)


def sign_bundle(root, env):
    require_dispatch(env)
    info, _ = verify_bundle(root, env)
    verify_personal_bundle(root, env)
    pin = certificate_pin(env)
    encoded = env.get("TABLET_TEST_KEYSTORE_B64", "")
    password = env.get("TABLET_TEST_STORE_PASSWORD", "")
    tool = env.get("APKSIGNER")
    temporary_root = env.get("RUNNER_TEMP")
    if not encoded or not password or not tool or not temporary_root:
        raise ValueError("Stable tablet-test signing configuration is incomplete")
    try:
        key = base64.b64decode("".join(encoded.split()), validate=True)
    except (ValueError, binascii.Error) as error:
        raise ValueError("The configured tablet-test keystore is not valid base64") from error
    if not key or len(key) > 48 * 1024:
        raise ValueError("The configured tablet-test keystore size is invalid")

    # This runs only in the separate manual publisher job, never in Gradle or PR builds.
    # Keep the key outside the artifact directory and suppress tool output containing secrets.
    with tempfile.TemporaryDirectory(prefix="tablet-signing-", dir=temporary_root) as temporary:
        private = Path(temporary)
        keystore = private / "tablet-test.p12"
        with keystore.open("xb") as stream:
            keystore.chmod(0o600)
            stream.write(key)
        command_env = {**os.environ, "TABLET_TEST_STORE_PASSWORD": password}
        command_env.pop("TABLET_TEST_KEYSTORE_B64", None)
        for name in apk_names(env):
            command = [tool, "sign", "--ks", str(keystore),
                       "--ks-key-alias", "sshborg-tablet-test",
                       "--ks-pass", "env:TABLET_TEST_STORE_PASSWORD",
                       "--key-pass", "env:TABLET_TEST_STORE_PASSWORD",
                       "--v1-signing-enabled", "false", "--v2-signing-enabled", "true",
                       "--v3-signing-enabled", "true", "--v4-signing-enabled", "false",
                       "--out", str(private / name), str(root / name)]
            try:
                subprocess.run(command, env=command_env, capture_output=True, check=True)
            except (OSError, subprocess.CalledProcessError) as error:
                raise ValueError("Tablet-test APK signing failed; refusing publication") from error
            verify_apk_certificate(private / name, env)

        # Check every output before replacing originals; keep only public signing metadata.
        verify_personal_bundle(private, env)
        for name in apk_names(env):
            (private / name).replace(root / name)
        info["signing"] = {"kind": "stable-tablet-test", "certificate_sha256": pin}
        (root / "build-info.json").write_text(json.dumps(info, indent=2) + "\n")
        (root / "SHA256SUMS").write_text("".join(
            f"{digest(root / name)}  {name}\n" for name in payload_names(env)))
        require_stable_signing(root, info, env)
        verify_bundle(root, env)
    print("Verified stable tablet-test signer:", pin)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    try:
        sign_bundle(args.directory, os.environ)
    except (ValueError, OSError):
        # Do not include subprocess stdout/stderr, key bytes, or secret values in CI logs.
        parser.exit(1, "Tablet-test signing failed; check configuration and the pinned certificate.\n")


if __name__ == "__main__":
    main()
