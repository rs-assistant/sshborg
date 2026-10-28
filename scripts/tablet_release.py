#!/usr/bin/env python3
"""Stage test evidence and publish only verified, manually requested tablet prereleases."""

import argparse
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
from xml.etree import ElementTree
from zoneinfo import ZoneInfo

from check_personal_apk import check_apk

REPO = "rs-assistant/sshborg"
BRANCH = "refs/heads/security/harden-credential-storage"
APKS = ("app-debug.apk", "app-debug-androidTest.apk")
PAYLOADS = (*APKS, "build-info.json")
OLD_DRAFT_ID = 420255693
OLD_DRAFT_TAG = "tablet-test-20261022-88c4ff11"


def build_variant(env):
    variant = env.get("TABLET_BUILD_VARIANT", "debug")
    if variant not in ("debug", "personal"):
        raise ValueError("Unknown tablet build variant")
    return variant


def apk_names(env):
    return ("app-personal.apk",) if build_variant(env) == "personal" else APKS


def payload_names(env):
    return (*apk_names(env), "build-info.json")


def verify_personal_bundle(root, env):
    if build_variant(env) == "personal":
        check_apk(root / "app-personal.apk", env.get("AAPT2"))


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def require_passing_tests(counts):
    if (set(counts) != {"tests", "failures", "errors", "skipped"}
            or any(type(n) is not int or n < 0 for n in counts.values())
            or counts["tests"] == 0
            or any(counts[name] for name in ("failures", "errors", "skipped"))):
        raise ValueError("Tests must have executed without failures, errors, or skips")


def stage(root, env):
    variant = build_variant(env)
    counts = dict(tests=0, failures=0, errors=0, skipped=0)
    required = {"KeyImportTest", "SshIntegrationTest", "SftpIntegrationTest", "TextFileTest"}
    for report in Path("app/build/test-results/testDebugUnitTest").glob("TEST-*.xml"):
        suite = ElementTree.parse(report).getroot()
        required.discard(suite.get("name", "").rsplit(".", 1)[-1])
        for name in counts:
            counts[name] += int(suite.get(name, 0))
    require_passing_tests(counts)
    if required:
        raise ValueError("Required integration suites are missing")
    root.mkdir(parents=True, exist_ok=False)
    if variant == "personal":
        shutil.copyfile("app/build/outputs/apk/personal/app-personal-unsigned.apk", root / "app-personal.apk")
        verify_personal_bundle(root, env)
    else:
        shutil.copyfile("app/build/outputs/apk/debug/app-debug.apk", root / APKS[0])
        shutil.copyfile("app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk", root / APKS[1])
    info = {"commit": env["GITHUB_SHA"], "run_id": env["GITHUB_RUN_ID"],
            "run_attempt": env["GITHUB_RUN_ATTEMPT"], "jvm": counts,
            "device_acceptance": "NOT EXECUTED"}
    if variant == "personal":
        info["build_variant"] = variant
    (root / "build-info.json").write_text(json.dumps(info, indent=2) + "\n")
    (root / "SHA256SUMS").write_text("".join(f"{digest(root / name)}  {name}\n" for name in payload_names(env)))
    print("Staged tablet-test APKs with source-bound validation evidence:", counts)


def require_dispatch(env):
    if (env.get("GITHUB_EVENT_NAME") != "workflow_dispatch"
            or env.get("GITHUB_REF") != BRANCH or env.get("GITHUB_REPOSITORY") != REPO):
        raise ValueError("Publishing is allowed only by manual dispatch on the test branch")
    if not env.get("GITHUB_RUN_ID", "").isdigit() or not env.get("GITHUB_RUN_ATTEMPT", "").isdigit():
        raise ValueError("Missing workflow run identity")
    sha = env.get("GITHUB_SHA", "")
    if len(sha) != 40 or any(c not in "0123456789abcdef" for c in sha):
        raise ValueError("Missing full source commit")


def verify_bundle(root, env):
    payloads = payload_names(env)
    if set(p.name for p in root.iterdir()) != {*payloads, "SHA256SUMS"}:
        raise ValueError("Unexpected or missing bundle files")
    if any((root / name).is_symlink() for name in (*payloads, "SHA256SUMS")):
        raise ValueError("Bundle files must not be symbolic links")
    expected = {}
    for line in (root / "SHA256SUMS").read_text().splitlines():
        checksum, name = line.split("  ", 1)
        if name not in payloads or name in expected or not re.fullmatch(r"[0-9a-f]{64}", checksum):
            raise ValueError("Invalid checksum manifest")
        expected[name] = checksum
    if set(expected) != set(payloads) or any(digest(root / name) != value for name, value in expected.items()):
        raise ValueError("Bundle checksum verification failed")
    info = json.loads((root / "build-info.json").read_text())
    if info.get("build_variant", "debug") != build_variant(env):
        raise ValueError("Bundle build variant does not match the requested publication")
    if any(info[field] != env[key] for field, key in
           (("commit", "GITHUB_SHA"), ("run_id", "GITHUB_RUN_ID"), ("run_attempt", "GITHUB_RUN_ATTEMPT"))):
        raise ValueError("Bundle does not belong to this source commit and workflow run")
    require_passing_tests(info["jvm"])
    if info.get("device_acceptance") != "NOT EXECUTED":
        raise ValueError("Device acceptance must not be misrepresented")
    expected["SHA256SUMS"] = digest(root / "SHA256SUMS")
    return info, expected


def gh_api(endpoint, data=None):
    command = ["gh", "api", endpoint]
    if data is not None:
        command += ["--method", "PATCH" if endpoint.rsplit("/", 1)[-1].isdigit() else "POST", "--input", "-"]
    result = subprocess.run(command, input=None if data is None else json.dumps(data),
                            text=True, capture_output=True, check=True)
    return json.loads(result.stdout)


def certificate_pin(env):
    pin = env.get("TABLET_TEST_CERT_SHA256", "").lower().replace(":", "")
    if not re.fullmatch(r"[0-9a-f]{64}", pin):
        raise ValueError("Configure the public TABLET_TEST_CERT_SHA256 certificate pin")
    return pin


def verify_apk_certificate(apk, env):
    pin = certificate_pin(env)
    tool = env.get("APKSIGNER")
    if not tool:
        raise ValueError("APKSIGNER must identify the Android SDK verifier")
    try:
        result = subprocess.run([tool, "verify", "--print-certs", str(apk)],
                                capture_output=True, text=True, check=True)
    except (OSError, subprocess.CalledProcessError) as from_error:
        raise ValueError("APK signature verification failed") from from_error
    certificates = re.findall(r"certificate SHA-256 digest: ([0-9a-fA-F]{64})", result.stdout)
    if not certificates or {value.lower() for value in certificates} != {pin}:
        raise ValueError("APK signer does not match the pinned tablet-test certificate")


def require_stable_signing(root, info, env):
    pin = certificate_pin(env)
    if info.get("signing") != {"kind": "stable-tablet-test", "certificate_sha256": pin}:
        raise ValueError("The bundle must have verified stable tablet-test signing")
    verify_personal_bundle(root, env)
    for name in apk_names(env):
        verify_apk_certificate(root / name, env)


def release_notes(info, checksums, env):
    date = datetime.now(ZoneInfo("Asia/Taipei")).strftime("%Y-%m-%d")
    if build_variant(env) == "personal":
        return date, f"""Android 11+ 非 debug 自用驗收版，不是商店正式發行版，尚未完成真機驗收。

下載 app-personal.apk，直接嘗試覆蓋目前的 SSHBorgDebug；名稱保留以避免混淆現有資料。
套件 com.sshborg.debug、固定測試簽章與下載資料夾不變；關閉 debuggable，使用 R8／資源縮減。
請勿為了安裝而解除安裝或清除資料。若更新失敗，保留舊 app 並回報錯誤。
同套件、同憑證、版本碼未降低只是保留資料更新的必要條件，不是 Keystore 相容性保證。
請用可拋棄憑證測試既有設定與金鑰、指紋／PIN、SSH／SFTP／重連、取消／切背景拒絕連線。
設定匯出不含憑證；請另行保管原始私鑰，不要提供 PIN、私鑰或帳密。
這份非 debug APK 不啟用測試 instrumentation，沒有對應的 device-test APK。

JVM／loopback SSH/SFTP：{info['jvm']['tests']} 項通過，零失敗、零跳過（debug JVM 測試，非 R8 裝置驗收）。
來源提交：{info['commit']}
建置紀錄：https://github.com/{REPO}/actions/runs/{env['GITHUB_RUN_ID']}
APK SHA-256：{checksums['app-personal.apk']}
測試簽章 SHA-256：{info['signing']['certificate_sha256']}
完整校驗碼與來源資訊見 SHA256SUMS、build-info.json 附件。
"""
    return date, f"""Android 11+ 平板驗收測試版。僅供測試，尚未完成真機驗收；請勿使用重要憑證或正式伺服器帳密。

下載 **app-debug.apk**，安裝後開啟 **SSHBorgDebug**。不需要電腦或 ADB。
1. 到「金鑰 → 產生」，名稱填 TEST，類型選 Ed25519。
2. 系統認證第一次先取消：應沒有新增金鑰。
3. 再試一次，以系統 PIN／指紋完成認證，回報是否成功新增。
不要提供 PIN、私鑰或帳密，也不要停用 Play Protect。

嚴格 ON_STOP 拒絕規則仍保留；某些裝置切換 PIN 畫面時可能被拒絕，請回報畫面錯誤，不要繞過認證。
這是獨立的 debug 套件 com.sshborg.debug，不是正式發行版。測試版使用固定專用簽章。
先前使用隨機 CI 簽章的版本不能直接覆蓋更新；請勿為了安裝而解除安裝或清除資料。
之後僅同套件、相同簽章且版本碼未降低的測試 APK 才符合保留資料更新的必要條件，仍需實機驗證。
另一份 app-debug-androidTest.apk 是供日後 ADB 驗收使用，平板手動測試不需要安裝它。

JVM／loopback SSH/SFTP：{info['jvm']['tests']} 項通過，零失敗、零跳過。Android 裝置測試僅編譯，尚未執行。
來源提交：{info['commit']}
建置紀錄：https://github.com/{REPO}/actions/runs/{env['GITHUB_RUN_ID']}
APK SHA-256：{checksums[APKS[0]]}
測試簽章 SHA-256：{info['signing']['certificate_sha256']}
完整校驗碼與建置資訊見 SHA256SUMS、build-info.json 附件。
"""


def publish(root, env, api=gh_api, upload=None):
    require_dispatch(env)
    info, checksums = verify_bundle(root, env)
    require_stable_signing(root, info, env)
    prefix = "personal-test" if build_variant(env) == "personal" else "tablet-test"
    tag = f"{prefix}-{env['GITHUB_RUN_ID']}-{env['GITHUB_RUN_ATTEMPT']}"
    date, notes = release_notes(info, checksums, env)
    endpoint = f"repos/{REPO}/releases"
    title = "personal non-debug test" if build_variant(env) == "personal" else "tablet test"
    data = dict(tag_name=tag, target_commitish=env["GITHUB_SHA"], name=f"Android 11+ {title} — {date}",
                body=notes, draft=True, prerelease=True, make_latest="false")
    old = api(f"{endpoint}/{OLD_DRAFT_ID}")
    if old.get("draft") is True and old.get("prerelease") is True and old.get("tag_name") == OLD_DRAFT_TAG and old.get("assets") == []:
        release = api(f"{endpoint}/{OLD_DRAFT_ID}", data)
    else:
        # Never modify a published release or overwrite an existing attachment.
        release = api(endpoint, data)
    if release.get("draft") is not True or release.get("prerelease") is not True or release.get("tag_name") != tag:
        raise ValueError("Unexpected release state; refusing upload/publication")
    release_id = release["id"]
    command = ["gh", "release", "upload", tag, "--repo", REPO, *(str(root / name) for name in checksums)]
    if upload is None:
        subprocess.run(command, check=True)
    else:
        upload(command)
    assets = api(f"{endpoint}/{release_id}/assets")
    if len(assets) != len(checksums) or {a["name"] for a in assets} != set(checksums):
        raise ValueError("Uploaded asset list differs; leaving release unpublished")
    for asset in assets:
        name = asset["name"]
        if (asset.get("state") != "uploaded" or asset.get("size") != (root / name).stat().st_size
                or asset.get("digest") != "sha256:" + checksums[name]):
            raise ValueError("Uploaded asset integrity differs; leaving release unpublished")
    published = api(f"{endpoint}/{release_id}", {"draft": False, "prerelease": True, "make_latest": "false"})
    if published.get("draft") is not False or published.get("prerelease") is not True:
        raise ValueError("Publication was not confirmed")
    print("Published verified test prerelease:", published["html_url"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("stage", "publish"))
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    (stage if args.action == "stage" else publish)(args.directory, os.environ)


if __name__ == "__main__":
    main()
