#!/usr/bin/env python3
"""Inspect the compiled APK, not just Gradle source, before personal-build signing."""

import argparse
import re
import subprocess

ANDROID = "http://schemas.android.com/apk/res/android:"


def check_manifest_dump(dump):
    nodes = []
    stack = []
    for line in dump.splitlines():
        depth = len(line) - len(line.lstrip())
        element = re.fullmatch(r"\s*E: ([\w-]+) \(line=\d+\)", line)
        if element:
            while stack and stack[-1][0] >= depth:
                stack.pop()
            node = {"name": element[1], "attrs": {}, "parent": stack[-1][1] if stack else None}
            nodes.append(node)
            stack.append((depth, node))
        elif line.lstrip().startswith("A:"):
            attribute = re.fullmatch(r"\s*A: ([^=(]+)(?:\(0x[0-9a-fA-F]+\))?=(.+)", line)
            if not stack or not attribute or depth <= stack[-1][0]:
                raise ValueError("Unrecognized compiled manifest attribute")
            attrs = stack[-1][1]["attrs"]
            if attribute[1] in attrs:
                raise ValueError("Duplicate compiled manifest attribute")
            attrs[attribute[1]] = attribute[2].split(" (Raw:", 1)[0]

    def single(name, parent):
        matches = [node for node in nodes if node["name"] == name and node["parent"] is parent]
        if len(matches) != 1:
            raise ValueError("Missing or ambiguous compiled manifest element: " + name)
        return matches[0]

    manifest = single("manifest", None)
    app_node = single("application", manifest)
    app = app_node["attrs"]
    sdk = single("uses-sdk", manifest)["attrs"]
    version = manifest["attrs"].get(ANDROID + "versionCode", "")
    if (manifest["attrs"].get("package") != '"com.sshborg.debug"'
            or not version.isdecimal() or int(version) < 37
            or sdk.get(ANDROID + "minSdkVersion") != "30"):
        raise ValueError("Personal APK must preserve the tablet package, version floor and Android 11 minimum")
    if any(app.get(ANDROID + flag, "false") != "false" for flag in ("debuggable", "testOnly")):
        raise ValueError("Personal APK must not be debuggable or test-only")
    if (app.get(ANDROID + "allowBackup") != "false"
            or app.get(ANDROID + "fullBackupContent") != "false"
            or not re.fullmatch(r"@0x[0-9a-fA-F]+", app.get(ANDROID + "dataExtractionRules", ""))):
        raise ValueError("Personal APK must retain explicit backup exclusions")
    for node in nodes:
        if node["name"] == "profileable" and node["parent"] is app_node:
            if node["attrs"].get(ANDROID + "shell", "false") != "false":
                raise ValueError("Personal APK must not enable shell profiling")
        if any("androidx.compose.ui.tooling." in value for value in node["attrs"].values()):
            raise ValueError("Personal APK must not include a Compose tooling component")
    return {"package": "com.sshborg.debug", "version_code": int(version), "debuggable": False}


def check_apk(apk, aapt2):
    if not aapt2:
        raise ValueError("AAPT2 is required to inspect a personal APK")
    try:
        result = subprocess.run([aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", str(apk)],
                                capture_output=True, text=True, check=True)
    except (OSError, subprocess.CalledProcessError) as error:
        raise ValueError("Personal APK manifest inspection failed") from error
    return check_manifest_dump(result.stdout)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk")
    parser.add_argument("--aapt2", required=True)
    args = parser.parse_args()
    print("Verified non-debug tablet package:", check_apk(args.apk, args.aapt2))


if __name__ == "__main__":
    main()
