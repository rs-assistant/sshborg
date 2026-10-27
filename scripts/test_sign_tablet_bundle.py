"""Signing orchestration tests use disposable bytes and a fake apksigner, never real keys."""

import base64
import json
from pathlib import Path
import stat
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import sign_tablet_bundle as signing
import tablet_release as release


class TabletSigningTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="tablet-signing-test-")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name) / "bundle"
        self.root.mkdir()
        self.env = dict(GITHUB_EVENT_NAME="workflow_dispatch", GITHUB_REF=release.BRANCH,
                        GITHUB_REPOSITORY=release.REPO, GITHUB_SHA="a" * 40,
                        GITHUB_RUN_ID="123", GITHUB_RUN_ATTEMPT="1",
                        TABLET_TEST_CERT_SHA256="b" * 64, APKSIGNER="fixture-apksigner",
                        RUNNER_TEMP=self.tmp.name,
                        TABLET_TEST_KEYSTORE_B64=base64.b64encode(b"disposable fixture").decode(),
                        TABLET_TEST_STORE_PASSWORD="disposable-fixture-password")
        info = dict(commit="a" * 40, run_id="123", run_attempt="1",
                    jvm=dict(tests=1, failures=0, errors=0, skipped=0), device_acceptance="NOT EXECUTED")
        (self.root / "build-info.json").write_text(json.dumps(info))
        for name in release.APKS:
            (self.root / name).write_bytes(b"disposable APK fixture")
        (self.root / "SHA256SUMS").write_text("".join(
            f"{release.digest(self.root / name)}  {name}\n" for name in release.PAYLOADS))
        self.original = {name: (self.root / name).read_bytes() for name in (*release.PAYLOADS, "SHA256SUMS")}
        self.calls = []
        self.private_paths = []
        self.fail_second_sign = False
        self.wrong_certificate = False

    def tool(self, command, **kwargs):
        self.calls.append(command)
        self.assertEqual("fixture-apksigner", command[0])
        if command[1] == "sign":
            keystore = Path(command[command.index("--ks") + 1])
            self.private_paths.append(keystore)
            self.assertEqual(0o600, stat.S_IMODE(keystore.stat().st_mode))
            self.assertNotEqual(self.root, keystore.parent)
            self.assertNotIn(self.env["TABLET_TEST_STORE_PASSWORD"], " ".join(command))
            self.assertNotIn("TABLET_TEST_KEYSTORE_B64", kwargs["env"])
            self.assertEqual(self.env["TABLET_TEST_STORE_PASSWORD"], kwargs["env"]["TABLET_TEST_STORE_PASSWORD"])
            if self.fail_second_sign and len(self.private_paths) == 2:
                raise subprocess.CalledProcessError(1, command, stderr=b"fixture failure")
            Path(command[command.index("--out") + 1]).write_bytes(Path(command[-1]).read_bytes() + b" signed")
            return SimpleNamespace(stdout="")
        self.assertEqual("verify", command[1])
        pin = "c" * 64 if self.wrong_certificate else "b" * 64
        return SimpleNamespace(stdout="Signer #1 certificate SHA-256 digest: " + pin)

    def sign(self):
        with patch("subprocess.run", side_effect=self.tool):
            signing.sign_bundle(self.root, self.env)

    def assert_original(self):
        self.assertEqual(self.original, {name: (self.root / name).read_bytes() for name in self.original})
        self.assertTrue(all(not path.exists() for path in self.private_paths))

    def test_both_apks_are_resigned_verified_and_bound_to_public_metadata(self):
        self.sign()
        info, _ = release.verify_bundle(self.root, self.env)
        self.assertEqual(dict(kind="stable-tablet-test", certificate_sha256="b" * 64), info["signing"])
        self.assertEqual("NOT EXECUTED", info["device_acceptance"])
        self.assertEqual("a" * 40, info["commit"])
        for name in release.APKS:
            self.assertEqual(self.original[name] + b" signed", (self.root / name).read_bytes())
        self.assertEqual(2, len(self.private_paths))
        self.assertTrue(all(not path.exists() for path in self.private_paths))
        self.assertEqual(set(self.original), {path.name for path in self.root.iterdir()})

    def test_missing_configuration_never_falls_back_to_an_ephemeral_key(self):
        for field in ("TABLET_TEST_KEYSTORE_B64", "TABLET_TEST_STORE_PASSWORD", "TABLET_TEST_CERT_SHA256",
                      "APKSIGNER", "RUNNER_TEMP"):
            with self.subTest(field=field), patch.dict(self.env, {field: ""}):
                with self.assertRaises(ValueError):
                    self.sign()
                self.assert_original()
        self.assertEqual([], self.calls)

    def test_prs_and_other_branches_cannot_access_signing(self):
        for field, value in (("GITHUB_EVENT_NAME", "pull_request"), ("GITHUB_REF", "refs/heads/V1"),
                             ("GITHUB_REPOSITORY", "other/repo")):
            with self.subTest(field=field), patch.dict(self.env, {field: value}):
                with self.assertRaises(ValueError):
                    self.sign()
        self.assertEqual([], self.calls)

    def test_bad_base64_is_refused_without_running_the_signer(self):
        self.env["TABLET_TEST_KEYSTORE_B64"] = "not base64!"
        with self.assertRaises(ValueError):
            self.sign()
        self.assertEqual([], self.calls)
        self.assert_original()

    def test_tampered_bundle_is_refused_before_loading_the_key(self):
        (self.root / release.APKS[0]).write_bytes(b"tampered")
        with self.assertRaises(ValueError):
            self.sign()
        self.assertEqual([], self.calls)

    def test_wrong_key_is_refused_and_private_files_are_removed(self):
        self.wrong_certificate = True
        with self.assertRaises(ValueError):
            self.sign()
        self.assert_original()

    def test_second_signing_failure_leaves_original_bundle_and_cleans_the_key(self):
        self.fail_second_sign = True
        with self.assertRaises(ValueError):
            self.sign()
        self.assert_original()

    def test_invalid_signature_exit_status_is_not_treated_as_a_certificate_match(self):
        with patch("subprocess.run", side_effect=subprocess.CalledProcessError(1, ["fixture-apksigner"])):
            with self.assertRaises(ValueError):
                release.verify_apk_certificate(self.root / release.APKS[0], self.env)


if __name__ == "__main__":
    unittest.main()
