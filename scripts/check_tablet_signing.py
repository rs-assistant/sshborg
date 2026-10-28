#!/usr/bin/env python3
"""Offline real-APK signing regression with a disposable key, never repository secrets."""

import argparse
import base64
import hashlib
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import tempfile
import zipfile

from sign_tablet_bundle import sign_bundle
import tablet_release as release


def expect_refusal(operation, label):
    try:
        operation()
    except ValueError:
        print("PASS:", label)
        return
    raise RuntimeError("Expected refusal: " + label)


def check_signing(apksigner, keytool, variant="debug", aapt2=None):
    if variant == "personal":
        expect_refusal(lambda: release.check_apk(
            Path("app/build/outputs/apk/debug/app-debug.apk"), aapt2),
            "an actual debug APK cannot masquerade as a non-debug personal APK")
    # Neither the source key nor password comes from Actions Secrets. All test outputs,
    # including the private key, stay in this disposable directory and are never uploaded.
    with tempfile.TemporaryDirectory(prefix="sshborg-signing-regression-") as temporary:
        root = Path(temporary)
        password = secrets.token_urlsafe(32)
        command_env = {**os.environ, "FIXTURE_STORE_PASSWORD": password}
        command_env.pop("TABLET_TEST_KEYSTORE_B64", None)
        command_env.pop("TABLET_TEST_STORE_PASSWORD", None)
        key = root / "fixture.p12"
        subprocess.run([keytool, "-genkeypair", "-storetype", "PKCS12", "-keystore", str(key),
                        "-alias", "sshborg-tablet-test", "-keyalg", "RSA", "-keysize", "2048",
                        "-validity", "2", "-dname", "CN=Disposable signing regression",
                        "-storepass:env", "FIXTURE_STORE_PASSWORD"],
                       env=command_env, capture_output=True, check=True)
        certificate = subprocess.run(
            [keytool, "-exportcert", "-keystore", str(key), "-alias", "sshborg-tablet-test",
             "-storepass:env", "FIXTURE_STORE_PASSWORD"],
            env=command_env, capture_output=True, check=True).stdout
        pin = hashlib.sha256(certificate).hexdigest()
        # Local fixture identity only; this script has no GitHub client or publication calls.
        env = dict(GITHUB_EVENT_NAME="workflow_dispatch", GITHUB_REF=release.BRANCH,
                   GITHUB_REPOSITORY=release.REPO, GITHUB_SHA="a" * 40,
                   GITHUB_RUN_ID="123", GITHUB_RUN_ATTEMPT="1", RUNNER_TEMP=temporary,
                   APKSIGNER=apksigner, TABLET_TEST_CERT_SHA256=pin,
                   TABLET_TEST_KEYSTORE_B64=base64.b64encode(key.read_bytes()).decode(),
                   TABLET_TEST_STORE_PASSWORD=password)
        env.update(TABLET_BUILD_VARIANT=variant, AAPT2=aapt2)
        apks, payloads = release.apk_names(env), release.payload_names(env)
        if variant == "personal":
            debug_env = {**env, "TABLET_BUILD_VARIANT": "debug"}
            debug_bundle = root / "debug-baseline"
            release.stage(debug_bundle, debug_env)
            sign_bundle(debug_bundle, debug_env)
            debug_info, _ = release.verify_bundle(debug_bundle, debug_env)
            release.require_stable_signing(debug_bundle, debug_info, debug_env)
        bundles = []
        for attempt in (1, 2):
            bundle = root / f"bundle-{attempt}"
            release.stage(bundle, env)
            before, _ = release.verify_bundle(bundle, env)
            sign_bundle(bundle, env)
            after, _ = release.verify_bundle(bundle, env)
            release.require_stable_signing(bundle, after, env)
            if {path.name for path in bundle.iterdir()} != {*payloads, "SHA256SUMS"}:
                raise RuntimeError("Signing left unexpected files in the artifact bundle")
            if {k: v for k, v in after.items() if k != "signing"} != before:
                raise RuntimeError("Signing changed source, test, or device-acceptance metadata")
            bundles.append(bundle)
        print(f"PASS: all {variant} APKs in two independent bundles use the same disposable signer")
        if variant == "personal":
            print("PASS: debug and non-debug personal bundles share the same disposable signer; no device update tested")

        bundle = bundles[0]
        original = {name: release.digest(bundle / name) for name in (*payloads, "SHA256SUMS")}
        wrong_pin = "0" * 64 if pin != "0" * 64 else "1" * 64
        expect_refusal(lambda: sign_bundle(bundle, {**env, "TABLET_TEST_CERT_SHA256": wrong_pin}),
                       "a different pinned certificate cannot re-sign the bundle")
        expect_refusal(lambda: sign_bundle(bundle, {**env, "TABLET_TEST_STORE_PASSWORD": ""}),
                       "missing signing configuration never falls back to another key")
        if original != {name: release.digest(bundle / name) for name in original}:
            raise RuntimeError("A refused operation modified the original bundle")
        if {path.name for path in bundle.iterdir()} != set(original):
            raise RuntimeError("A refused operation left unexpected artifact files")

        # Change a real signed ZIP entry, not just its checksum manifest. Actual signature
        # verification must reject it even though the pinned certificate remains unchanged.
        for name in apks:
            apk = bundles[1] / name
            with zipfile.ZipFile(apk, "a") as archive:
                archive.writestr("assets/sshborg-signing-regression.txt", b"tampered fixture")
            expect_refusal(lambda: release.verify_apk_certificate(apk, env),
                           "modified signed " + name + " is rejected")
        if list(root.glob("tablet-signing-*")):
            raise RuntimeError("Temporary signing key directories were not cleaned up")
    print("PASS: disposable signing fixtures cleaned up; no device update was executed")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apksigner", required=True, help="Android SDK apksigner executable")
    parser.add_argument("--keytool", default="keytool", help="JDK keytool executable")
    parser.add_argument("--variant", choices=("debug", "personal"), default="debug")
    parser.add_argument("--aapt2", help="Required compiled-manifest verifier for personal APKs")
    args = parser.parse_args()
    tool = shutil.which(args.apksigner)
    keytool = shutil.which(args.keytool)
    if not tool or not keytool:
        parser.error("Both apksigner and keytool must be installed")
    try:
        check_signing(tool, keytool, args.variant, args.aapt2)
    except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError):
        parser.exit(1, "FAIL: real APK signing regression; no production key was used.\n")


if __name__ == "__main__":
    main()
