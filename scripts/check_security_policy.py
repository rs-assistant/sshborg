#!/usr/bin/env python3
"""Fast source/resource tripwires, not a substitute for JVM, transport or device tests."""

from collections import Counter
from pathlib import Path
import re
import sys
from xml.etree import ElementTree

REPO = Path(__file__).resolve().parent.parent
KOTLIN = "app/src/main/kotlin/com/sshborg/"
FILES = {
    "workflow": ".github/workflows/security-validation.yml",
    "build": "app/build.gradle.kts",
    "key_vm": KOTLIN + "ui/keys/KeysViewModel.kt",
    "key_write": KOTLIN + "ui/keys/EncryptedKeyWrite.kt",
    "key_screen": KOTLIN + "ui/keys/KeysScreen.kt",
    "host_editor": KOTLIN + "ui/hosts/AddEditHostViewModel.kt",
    "store": KOTLIN + "data/KeystoreManager.kt",
    "settings": KOTLIN + "ui/settings/SettingsViewModel.kt",
    "preferences": KOTLIN + "data/AppPreferences.kt",
    "keystore_v2": KOTLIN + "data/KeystoreV2.kt",
    "terminal": KOTLIN + "ui/terminal/TerminalViewModel.kt",
    "sftp": KOTLIN + "ui/sftp/SftpViewModel.kt",
    "protected": KOTLIN + "data/ProtectedCredentials.kt",
    "migration": KOTLIN + "data/CredentialMigration.kt",
    "backup": KOTLIN + "data/HostBackup.kt",
    "params": KOTLIN + "data/ssh/SshConnectionParams.kt",
}
LOCALES = ("values", "values-de", "values-es", "values-fr", "values-it", "values-ja",
           "values-pt", "values-ru", "values-uk", "values-zh-rCN")
SECURITY_STRINGS = {
    "host_password_reentry_required", "host_key_required", "host_save_failed",
    "keys_reimport_required", "host_jump_selection_invalid", "host_jump_chain_invalid",
    "keys_save_failed", "keys_generate_failed", "credential_migration_title",
    "credential_auth_required", "credential_migration_explanation", "credential_migration_key",
    "credential_migration_password", "credential_migration_action", "credential_migration_confirm_title",
    "credential_migration_confirm_body", "credential_migration_success", "credential_migration_failed",
    "backup_import_failed", "backup_export_failed", "backup_settings_import_failed",
    "settings_backup_export_subtitle",
    "connection_authorization_title", "connection_authorization_subtitle", "credential_authorization_title",
}


def load_sources(repo=REPO):
    return {name: (repo / path).read_text(encoding="utf-8") for name, path in FILES.items()}


def check_sources(sources):
    errors = []

    def require(name, condition):
        if not condition:
            errors.append(name)

    missing = FILES.keys() - sources.keys()
    if missing:
        return ["missing source: " + name for name in sorted(missing)]
    s = sources
    require("Android 11 minimum and backups disabled", "minSdk = 30" in s["build"] and
            s["build"].count('manifestPlaceholders["allowBackup"] = "false"') == 2)
    require("encrypted-only key persistence", "privateKeyPem = priv" not in s["key_vm"] and
            "privateKeyPem = imported.pem" not in s["key_vm"] and "privateKeyPem =" not in s["key_write"] and
            "persistEncryptedKey(" in s["key_vm"] and "ProtectedCredentials::encrypt" in s["key_vm"])
    require("metadata does not decrypt keys", "KeystoreManager.decrypt" not in s["key_vm"] and
            "getPrivateKeyPem" not in s["key_vm"] and "needsReimport(key)" not in s["key_screen"])
    require("no legacy plaintext fallback", "else -> rawPassword to null" not in s["host_editor"] and
            "entity.privateKeyPem.isNotBlank() -> entity.privateKeyPem" not in s["store"] and
            'else -> h.password ?: ""' not in s["host_editor"] and
            "!host.password.isNullOrEmpty()" not in s["host_editor"])
    require("host editor preserves encrypted credentials", "ProtectedCredentials::encrypt" in s["host_editor"] and
            "KeystoreManager.decrypt" not in s["host_editor"])
    require("complete jump selection", "mapNotNull" not in s["host_editor"] and
            "encodeJumpHostSelection(jumpSelection)" in s["host_editor"] and
            "parseJumpHosts(newJumpHosts, null)" in s["host_editor"])
    require("no encryption off switch", "fun disableKeystoreEncryption()" not in s["settings"] and
            "enableKeystoreEncryption" not in s["settings"])
    decrypt = s["keystore_v2"].partition("fun prepareDecrypt(")[2].split("/**", 1)[0]
    require("decryption never creates keys", bool(decrypt) and "getOrCreateKey" not in decrypt and
            "CredentialCipherV2.prepareDecrypt" in decrypt)
    require("per-operation authenticated Keystore", ".setUserAuthenticationRequired(true)" in s["keystore_v2"] and
            ".setUserAuthenticationParameters(0," in s["keystore_v2"] and
            "setUserAuthenticationValidityDurationSeconds" not in s["keystore_v2"])
    require("legacy alias stays read only", all(token not in s["store"]
            for token in ("fun encrypt(", "generateKey", "deleteEntry")))
    require("migration requires consent and exact verified replacement", all(token in s["migration"] for token in
            ("check(confirmed)", "MessageDigest.isEqual", "replaceIfUnchanged(original, replacement)")))
    require("legacy decrypt is migration-only", s["protected"].count("KeystoreManager::decrypt") == 1)
    for name in ("terminal", "sftp"):
        vm = s[name]
        require(name + ": fresh authorization including retries", vm.count("ConnectionAuthorization.requireFreshAttempt()") >= 2)
        consume = vm.find("ConnectionAuthorization.consumeAttempt(")
        transport = vm.find("SshManager.open")
        parse = vm.find("parseJumpHosts(")
        require(name + ": validate route then consume grant before transport", 0 <= parse < consume < transport)
        require(name + ": cancellation propagates", "result.onFailure { if (it is CancellationException) throw it }" in vm)
        require(name + ": only protected credential resolution", "ProtectedCredentials::decrypt" in vm and
                all(token not in vm for token in ("KeystoreManager", "hostDao.upsert", "SshAuth.Password(host.password)")))
    require("simple jump chain cannot skip hops", bool(re.search(r'return\s+raw\.split\(","\)\.map\s*\{', s["params"])))
    restore = s["backup"].partition("suspend fun restore(")[2]
    require("backup reads current rows inside transaction", 0 <= restore.find("return transaction {") < restore.find("readHosts()"))
    require("backup keeps current ciphertext", "encryptedPassword = current?.encryptedPassword" in s["backup"])
    require("backup fails on unresolved named credentials", "backupRequire(keys.size == 1)" in restore and
            "backupRequire(matches.size == 1)" in restore)
    require("Room owns host and group import transaction", "HostBackupImporter(" in s["settings"] and
            "transaction = { block -> sshBorgApp.db.withTransaction { block() } }" in s["settings"])
    export = s["settings"].partition("fun exportHosts(")[2].split("fun importHosts(", 1)[0]
    forbidden = r'put\("(?:password|encryptedPassword|privateKeyPem|encryptedBlob|knownHostsEntry|jumpHostKeys|jumpHostIdList)"'
    require("export excludes secrets pins and installation-local hop ids", not re.search(forbidden, export))
    require("export uses portable format", 'put("version", 8)' in export and 'put("jumpHostLabels"' in export)
    preference_backup = s["preferences"].partition("suspend fun exportSettingsJson(")[2]
    require("preference backup excludes lock secrets and mode", not re.search(r"Keys\.LOCK_(?:SECRET|MODE)", preference_backup))
    before_publish, separator, publish = s["workflow"].partition("\n  publish-tablet-test:\n")
    require("build has read-only repository permissions", "permissions:\n  contents: read" in before_publish and
            "contents: write" not in before_publish)
    require("prerelease is opt-in and build-gated", bool(separator) and "default: false" in before_publish and
            "needs: build-and-test" in publish and
            "github.event_name == 'workflow_dispatch' && inputs.publish_tablet_test" in publish and
            "github.ref == 'refs/heads/security/harden-credential-storage'" in publish)
    require("signing secrets are isolated from build and PR jobs", "secrets." not in before_publish and
            "secrets.TABLET_TEST_KEYSTORE_B64" in publish and
            "secrets.TABLET_TEST_STORE_PASSWORD" in publish)
    require("stable signing precedes publication with a configured public pin",
            "vars.TABLET_TEST_CERT_SHA256" in publish and
            0 <= publish.find("python3 scripts/sign_tablet_bundle.py tablet-test") <
            publish.find("python3 scripts/tablet_release.py publish tablet-test"))
    return errors


def load_resources(repo=REPO):
    resources = {}
    for locale in LOCALES:
        entries = []
        for path in sorted((repo / "app/src/main/res" / locale).glob("*.xml")):
            entries.extend((node.attrib["name"], "".join(node.itertext()))
                           for node in ElementTree.parse(path).getroot() if node.tag == "string")
        resources[locale] = entries
    return resources


def check_resources(resources):
    errors = []
    default = dict(resources.get("values", []))
    for locale in LOCALES:
        entries = resources.get(locale, [])
        values = dict(entries)
        duplicates = {name for name, count in Counter(name for name, _ in entries).items() if count > 1}
        for name in sorted(SECURITY_STRINGS):
            label = locale + ": " + name
            if name in duplicates:
                errors.append(label + " duplicated")
            if not values.get(name, "").strip():
                errors.append(label + " missing/empty")
            elif Counter(re.findall(r"%[1-9][0-9]*\$[sd]", values[name])) != Counter(
                    re.findall(r"%[1-9][0-9]*\$[sd]", default.get(name, ""))):
                errors.append(label + " format arguments differ")
    return errors


def main():
    try:
        errors = check_sources(load_sources()) + check_resources(load_resources())
    except (OSError, ElementTree.ParseError, KeyError):
        print("FAIL: required source or resource file is missing or malformed", file=sys.stderr)
        return 1
    if errors:
        for error in errors:
            print("FAIL:", error, file=sys.stderr)
        return 1
    print("PASS: source policy tripwires and security resources (10 locales)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
