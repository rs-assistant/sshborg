"""Mutation tests for lightweight CI tripwires; no app, device, or external service writes."""

import unittest

from check_security_policy import check_resources, check_sources, load_resources, load_sources


class SecurityPolicyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sources = load_sources()
        cls.resources = load_resources()

    def mutated(self, name, old, new):
        sources = dict(self.sources)
        self.assertIn(old, sources[name], "Mutation must still target real production code")
        sources[name] = sources[name].replace(old, new)
        return check_sources(sources)

    def test_current_sources_and_all_security_translations_pass(self):
        self.assertEqual([], check_sources(self.sources))
        self.assertEqual([], check_resources(self.resources))

    def test_missing_required_source_fails_closed(self):
        sources = dict(self.sources)
        del sources["migration"]
        self.assertTrue(check_sources(sources))

    def test_plaintext_key_persistence_is_detected(self):
        self.assertTrue(self.mutated("key_write", "package com.sshborg.ui.keys",
                                     "package com.sshborg.ui.keys\n// privateKeyPem = priv"))

    def test_key_creation_during_decryption_is_detected(self):
        self.assertTrue(self.mutated("keystore_v2", "fun prepareDecrypt(", "fun prepareDecrypt(/* getOrCreateKey */"))

    def test_time_window_authentication_is_detected(self):
        self.assertTrue(self.mutated("keystore_v2", ".setUserAuthenticationParameters(0,",
                                     ".setUserAuthenticationParameters(30,"))

    def test_unverified_migration_is_detected(self):
        for token in ("check(confirmed)", "MessageDigest.isEqual", "replaceIfUnchanged(original, replacement)"):
            with self.subTest(token=token):
                self.assertTrue(self.mutated("migration", token, "REMOVED"))

    def test_connection_bypasses_in_either_viewmodel_are_detected(self):
        for name in ("terminal", "sftp"):
            for token in ("ConnectionAuthorization.requireFreshAttempt()", "ConnectionAuthorization.consumeAttempt(",
                          "result.onFailure { if (it is CancellationException) throw it }"):
                with self.subTest(name=name, token=token):
                    self.assertTrue(self.mutated(name, token, "REMOVED"))

    def test_silently_skipping_a_jump_hop_is_detected(self):
        self.assertTrue(self.mutated("params", 'return raw.split(",").map {', 'return raw.split(",").mapNotNull {'))

    def test_restoring_an_imported_ciphertext_is_detected(self):
        self.assertTrue(self.mutated("backup", "encryptedPassword = current?.encryptedPassword",
                                     "encryptedPassword = imported.encryptedPassword"))

    def test_removing_room_transaction_is_detected(self):
        self.assertTrue(self.mutated("settings", "transaction = { block -> sshBorgApp.db.withTransaction { block() } }",
                                     "transaction = { block -> block() }"))

    def test_exporting_secrets_or_numeric_hop_ids_is_detected(self):
        for field in ("password", "encryptedPassword", "privateKeyPem", "encryptedBlob", "knownHostsEntry", "jumpHostKeys", "jumpHostIdList"):
            with self.subTest(field=field):
                self.assertTrue(self.mutated("settings", 'put("version", 8)',
                                             f'put("version", 8)\nput("{field}", "test")'))

    def test_exporting_app_lock_material_is_detected(self):
        self.assertTrue(self.mutated("preferences", "suspend fun exportSettingsJson()",
                                     "suspend fun exportSettingsJson(/* Keys.LOCK_SECRET_HASH */)"))

    def test_workflow_cannot_publish_on_push_or_broaden_build_permissions(self):
        for old, new in (("default: false", "default: true"), ("contents: read", "contents: write"),
                         ("needs: build-and-test", "needs: other-job"),
                         ("inputs.publish_tablet_test", "true")):
            with self.subTest(old=old):
                self.assertTrue(self.mutated("workflow", old, new))

    def test_missing_or_empty_translation_is_detected(self):
        for value in (None, ""):
            resources = {locale: list(entries) for locale, entries in self.resources.items()}
            resources["values-ja"] = [(name, text) for name, text in resources["values-ja"]
                                       if name != "credential_migration_action"]
            if value is not None:
                resources["values-ja"].append(("credential_migration_action", value))
            self.assertTrue(check_resources(resources))

    def test_signing_cannot_be_removed_or_exposed_to_build_and_pr_jobs(self):
        for old, new in (("python3 scripts/sign_tablet_bundle.py tablet-test", "REMOVED"),
                         ("vars.TABLET_TEST_CERT_SHA256", "vars.OTHER"),
                         ("secrets.TABLET_TEST_KEYSTORE_B64", "secrets.OTHER"),
                         ("secrets.TABLET_TEST_STORE_PASSWORD", "secrets.OTHER"),
                         ("jobs:\n", "jobs:\n  # secrets.TABLET_TEST_KEYSTORE_B64\n")):
            with self.subTest(old=old):
                self.assertTrue(self.mutated("workflow", old, new))

    def test_duplicate_resource_is_detected(self):
        resources = {locale: list(entries) for locale, entries in self.resources.items()}
        resources["values-de"].append(("credential_migration_action", "duplicate"))
        self.assertTrue(check_resources(resources))

    def test_wrong_format_type_or_argument_index_is_detected(self):
        for placeholder in ("%1$s", "%3$d", ""):
            resources = {locale: list(entries) for locale, entries in self.resources.items()}
            resources["values-uk"] = [(name, text.replace("%1$d", placeholder))
                                       for name, text in resources["values-uk"]]
            self.assertTrue(check_resources(resources))


if __name__ == "__main__":
    unittest.main()
