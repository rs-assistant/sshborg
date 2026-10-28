"""Compiled-manifest parser safeguards; no device or permanent signing key needed."""

from pathlib import Path
import subprocess
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from check_personal_apk import check_apk, check_manifest_dump

MANIFEST = '''N: android=http://schemas.android.com/apk/res/android (line=2)
  E: manifest (line=2)
    A: package="com.sshborg.debug" (Raw: "com.sshborg.debug")
    A: http://schemas.android.com/apk/res/android:versionCode(0x0101021b)=37
      E: uses-sdk (line=7)
        A: http://schemas.android.com/apk/res/android:minSdkVersion(0x0101020c)=30
      E: application (line=20)
        A: http://schemas.android.com/apk/res/android:allowBackup(0x01010280)=false
        A: http://schemas.android.com/apk/res/android:fullBackupContent(0x010104eb)=false
        A: http://schemas.android.com/apk/res/android:dataExtractionRules(0x0101063e)=@0x7f130000
'''


class PersonalApkTest(unittest.TestCase):
    def test_non_debug_defaults_and_explicit_false_pass(self):
        for extra in ("", "        A: http://schemas.android.com/apk/res/android:debuggable(0x0101000f)=false\n"):
            with self.subTest(extra=extra):
                self.assertEqual(dict(package="com.sshborg.debug", version_code=37, debuggable=False),
                                 check_manifest_dump(MANIFEST + extra))

    def test_package_version_min_sdk_and_backup_changes_fail_closed(self):
        for old, new in (("com.sshborg.debug", "com.sshborg"), ("=37", "=36"), ("=37", "=garbage"),
                         ("=30", "=29"), ("=false", "=true"),
                         ("dataExtractionRules", "otherRules"), ("=@0x7f130000", "=false")):
            with self.subTest(old=old, new=new), self.assertRaises(ValueError):
                check_manifest_dump(MANIFEST.replace(old, new))

    def test_debuggable_test_only_profileable_and_tooling_are_refused(self):
        for extra in (
            "        A: http://schemas.android.com/apk/res/android:debuggable(0x0101000f)=true\n",
            "        A: http://schemas.android.com/apk/res/android:testOnly(0x01010272)=true\n",
            "          E: profileable (line=21)\n            A: http://schemas.android.com/apk/res/android:shell(0x0101061e)=true\n",
            '          E: activity (line=21)\n            A: http://schemas.android.com/apk/res/android:name(0x01010003)="androidx.compose.ui.tooling.PreviewActivity"\n',
        ):
            with self.subTest(extra=extra), self.assertRaises(ValueError):
                check_manifest_dump(MANIFEST + extra)

    def test_missing_ambiguous_duplicate_or_misnested_data_is_refused(self):
        for dump in ("", MANIFEST.replace("E: application", "E: other"),
                     MANIFEST + "      E: application (line=30)\n",
                     MANIFEST + "        A: http://schemas.android.com/apk/res/android:allowBackup(0x01010280)=false\n",
                     MANIFEST.replace("    A: package", "  A: package"),
                     MANIFEST.replace("A: package=", "A: package???"),
                     MANIFEST.replace("      E: uses-sdk", "          E: uses-sdk")):
            with self.subTest(dump=dump), self.assertRaises(ValueError):
                check_manifest_dump(dump)

    def test_inspector_tool_failure_and_missing_tool_are_refused(self):
        with self.assertRaises(ValueError):
            check_apk(Path("fixture.apk"), None)
        with patch("check_personal_apk.subprocess.run", side_effect=subprocess.CalledProcessError(1, ["fixture-aapt2"])):
            with self.assertRaises(ValueError):
                check_apk(Path("fixture.apk"), "fixture-aapt2")

    def test_tool_inspects_the_actual_manifest_in_the_requested_apk(self):
        with patch("check_personal_apk.subprocess.run", return_value=SimpleNamespace(stdout=MANIFEST)) as run:
            check_apk(Path("fixture.apk"), "fixture-aapt2")
        self.assertEqual(["fixture-aapt2", "dump", "xmltree", "--file", "AndroidManifest.xml", "fixture.apk"],
                         run.call_args.args[0])
        self.assertTrue(run.call_args.kwargs["check"])


if __name__ == "__main__":
    unittest.main()
