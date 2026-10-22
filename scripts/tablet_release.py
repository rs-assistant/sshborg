#!/usr/bin/env python3
"""Stage test evidence and publish only verified, manually requested tablet prereleases."""

import argparse
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
from xml.etree import ElementTree
from zoneinfo import ZoneInfo

REPO = "rs-assistant/sshborg"
BRANCH = "refs/heads/security/harden-credential-storage"
APKS = ("app-debug.apk", "app-debug-androidTest.apk")
PAYLOADS = (*APKS, "build-info.json")
OLD_DRAFT_ID = 420255693
OLD_DRAFT_TAG = "tablet-test-20261022-88c4ff11"


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
    shutil.copyfile("app/build/outputs/apk/debug/app-debug.apk", root / APKS[0])
    shutil.copyfile("app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk", root / APKS[1])
    info = {"commit": env["GITHUB_SHA"], "run_id": env["GITHUB_RUN_ID"],
            "run_attempt": env["GITHUB_RUN_ATTEMPT"], "jvm": counts,
            "device_acceptance": "NOT EXECUTED"}
    (root / "build-info.json").write_text(json.dumps(info, indent=2) + "\n")
    (root / "SHA256SUMS").write_text("".join(f"{digest(root / name)}  {name}\n" for name in PAYLOADS))
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
    if set(p.name for p in root.iterdir()) != {*PAYLOADS, "SHA256SUMS"}:
        raise ValueError("Unexpected or missing bundle files")
    if any((root / name).is_symlink() for name in (*PAYLOADS, "SHA256SUMS")):
        raise ValueError("Bundle files must not be symbolic links")
    expected = {}
    for line in (root / "SHA256SUMS").read_text().splitlines():
        checksum, name = line.split("  ", 1)
        if name not in PAYLOADS or name in expected or len(checksum) != 64:
            raise ValueError("Invalid checksum manifest")
        expected[name] = checksum
    if set(expected) != set(PAYLOADS) or any(digest(root / name) != value for name, value in expected.items()):
        raise ValueError("Bundle checksum verification failed")
    info = json.loads((root / "build-info.json").read_text())
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


def release_notes(info, checksums, env):
    date = datetime.now(ZoneInfo("Asia/Taipei")).strftime("%Y-%m-%d")
    return date, f"""Android 11+ 平板驗收測試版。僅供測試，尚未完成真機驗收；請勿使用重要憑證或正式伺服器帳密。

下載 **app-debug.apk**，安裝後開啟 **SSHBorgDebug**。不需要電腦或 ADB。
1. 到「金鑰 → 產生」，名稱填 TEST，類型選 Ed25519。
2. 系統認證第一次先取消：應沒有新增金鑰。
3. 再試一次，以系統 PIN／指紋完成認證，回報是否成功新增。
不要提供 PIN、私鑰或帳密，也不要停用 Play Protect。

嚴格 ON_STOP 拒絕規則仍保留；某些裝置切換 PIN 畫面時可能被拒絕，請回報畫面錯誤，不要繞過認證。
這是獨立的 debug 套件 com.sshborg.debug，不是正式發行版。CI 的 debug 簽章可能因建置而變更。
另一份 app-debug-androidTest.apk 是供日後 ADB 驗收使用，平板手動測試不需要安裝它。

JVM／loopback SSH/SFTP：{info['jvm']['tests']} 項通過，零失敗、零跳過。Android 裝置測試僅編譯，尚未執行。
來源提交：{info['commit']}
建置紀錄：https://github.com/{REPO}/actions/runs/{env['GITHUB_RUN_ID']}
APK SHA-256：{checksums[APKS[0]]}
完整校驗碼與建置資訊見 SHA256SUMS、build-info.json 附件。
"""


def publish(root, env, api=gh_api, upload=None):
    require_dispatch(env)
    info, checksums = verify_bundle(root, env)
    tag = f"tablet-test-{env['GITHUB_RUN_ID']}-{env['GITHUB_RUN_ATTEMPT']}"
    date, notes = release_notes(info, checksums, env)
    endpoint = f"repos/{REPO}/releases"
    data = dict(tag_name=tag, target_commitish=env["GITHUB_SHA"], name=f"Android 11+ tablet test — {date}",
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
