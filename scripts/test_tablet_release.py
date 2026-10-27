"""Prerelease checks use only throwaway local bytes and a fake GitHub API."""
import json
from pathlib import Path
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch

import tablet_release as release


class TabletReleaseTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="sshborg-release-test-")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.env = dict(GITHUB_EVENT_NAME="workflow_dispatch", GITHUB_REF=release.BRANCH,
                        GITHUB_REPOSITORY=release.REPO, GITHUB_SHA="a" * 40,
                        GITHUB_RUN_ID="123", GITHUB_RUN_ATTEMPT="1",
                        TABLET_TEST_CERT_SHA256="b" * 64, APKSIGNER="fixture-apksigner")
        self.info = dict(commit="a" * 40, run_id="123", run_attempt="1",
                         jvm=dict(tests=179, failures=0, errors=0, skipped=0), device_acceptance="NOT EXECUTED",
                         signing=dict(kind="stable-tablet-test", certificate_sha256="b" * 64))
        self.verifier = patch("tablet_release.subprocess.run", return_value=SimpleNamespace(
            stdout="Signer #1 certificate SHA-256 digest: " + "b" * 64))
        self.mock_verifier = self.verifier.start()
        self.addCleanup(self.verifier.stop)
        for name in release.APKS:
            (self.root / name).write_bytes(b"disposable test fixture")
        self.write_info()
        self.calls = []
        self.publications = []
        self.bad_digest = False
        self.old_published = False

    def write_info(self):
        (self.root / "build-info.json").write_text(json.dumps(self.info))
        (self.root / "SHA256SUMS").write_text("".join(
            f"{release.digest(self.root / name)}  {name}\n" for name in release.PAYLOADS))

    def api(self, endpoint, data=None):
        self.calls.append((endpoint, data))
        if data and data.get("draft") is False:
            self.publications.append(data)
            return dict(draft=False, prerelease=True, html_url="https://github.com/test/prerelease")
        if data:
            return dict(id=456, draft=True, prerelease=True, tag_name=data["tag_name"])
        if endpoint.endswith("/assets"):
            _, checksums = release.verify_bundle(self.root, self.env)
            return [dict(name=name, state="uploaded", size=(self.root / name).stat().st_size,
                         digest="sha256:" + ("0" * 64 if self.bad_digest else checksum))
                    for name, checksum in checksums.items()]
        return dict(draft=not self.old_published, prerelease=True, tag_name=release.OLD_DRAFT_TAG, assets=[])

    def publish(self):
        release.publish(self.root, self.env, api=self.api, upload=lambda command: self.calls.append((command, None)))

    def test_valid_artifacts_are_verified_before_publication(self):
        self.publish()
        self.assertEqual(1, len(self.publications))
        self.assertEqual("false", self.publications[0]["make_latest"])
        self.assertTrue(self.publications[0]["prerelease"])
        upload = next(call[0] for call in self.calls if isinstance(call[0], list))
        self.assertNotIn("--clobber", upload)

    def test_pull_requests_and_other_branches_cannot_publish(self):
        for key, value in (("GITHUB_EVENT_NAME", "pull_request"), ("GITHUB_REF", "refs/heads/V1"),
                           ("GITHUB_REPOSITORY", "other/repo")):
            env = {**self.env, key: value}
            with self.assertRaises(ValueError):
                release.publish(self.root, env, api=self.api)
        self.assertEqual([], self.calls)

    def test_local_tampering_is_refused_before_any_remote_write(self):
        (self.root / release.APKS[0]).write_bytes(b"changed fixture")
        with self.assertRaises(ValueError):
            self.publish()
        self.assertEqual([], self.calls)

    def test_bundle_cannot_be_reused_for_a_different_source_or_run(self):
        for key in ("GITHUB_SHA", "GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT"):
            with self.assertRaises(ValueError):
                release.verify_bundle(self.root, {**self.env, key: "different"})

    def test_failed_skipped_or_missing_tests_are_refused(self):
        for counts in (dict(tests=0, failures=0, errors=0, skipped=0),
                       dict(tests=179, failures=1, errors=0, skipped=0),
                       dict(tests=179, failures=0, errors=1, skipped=0),
                       dict(tests=179, failures=0, errors=0, skipped=1)):
            self.info["jvm"] = counts
            self.write_info()
            with self.assertRaises(ValueError):
                self.publish()
        self.assertEqual([], self.calls)

    def test_bad_remote_digest_leaves_the_release_unpublished(self):
        self.bad_digest = True
        with self.assertRaises(ValueError):
            self.publish()
        self.assertEqual([], self.publications)

    def test_missing_or_forged_signing_metadata_cannot_publish(self):
        for signing in (None, dict(kind="stable-tablet-test", certificate_sha256="c" * 64)):
            self.info["signing"] = signing
            self.write_info()
            with self.assertRaises(ValueError):
                self.publish()
        self.assertEqual([], self.calls)

    def test_actual_apk_certificates_must_match_the_configured_pin(self):
        for stdout in ("", "certificate SHA-256 digest: " + "c" * 64,
                       "certificate SHA-256 digest: " + "b" * 64 +
                       "\ncertificate SHA-256 digest: " + "c" * 64):
            self.mock_verifier.return_value.stdout = stdout
            with self.assertRaises(ValueError):
                self.publish()
        self.assertEqual([], self.calls)

    def test_missing_certificate_pin_cannot_publish(self):
        del self.env["TABLET_TEST_CERT_SHA256"]
        with self.assertRaises(ValueError):
            self.publish()
        self.assertEqual([], self.calls)

    def test_published_old_release_is_never_updated(self):
        self.old_published = True
        self.publish()
        writes = [endpoint for endpoint, data in self.calls if data is not None]
        self.assertNotIn(f"repos/{release.REPO}/releases/{release.OLD_DRAFT_ID}", writes)

    def test_symlinks_and_unexpected_files_are_refused(self):
        (self.root / "extra.txt").write_text("test")
        with self.assertRaises(ValueError):
            release.verify_bundle(self.root, self.env)
        (self.root / "extra.txt").unlink()
        (self.root / release.APKS[0]).unlink()
        (self.root / release.APKS[0]).symlink_to(self.root / release.APKS[1])
        with self.assertRaises(ValueError):
            release.verify_bundle(self.root, self.env)

    def test_checksum_paths_cannot_escape_the_bundle(self):
        (self.root / "SHA256SUMS").write_text("0" * 64 + "  ../../outside\n")
        with self.assertRaises(ValueError):
            release.verify_bundle(self.root, self.env)

    def test_notes_link_to_the_exact_built_commit_and_warn_about_device_acceptance(self):
        info, checksums = release.verify_bundle(self.root, self.env)
        _, notes = release.release_notes(info, checksums, self.env)
        self.assertIn(self.env["GITHUB_SHA"], notes)
        self.assertIn("尚未完成真機驗收", notes)
        self.assertIn(checksums[release.APKS[0]], notes)
        self.assertIn(self.env["TABLET_TEST_CERT_SHA256"], notes)
        self.assertIn("請勿為了安裝而解除安裝或清除資料", notes)


if __name__ == "__main__":
    unittest.main()
