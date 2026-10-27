# Hardened SSH/SFTP connection authentication — implementation specification

Status: IMPLEMENTED ON TEST BRANCH; DEVICE ACCEPTANCE REQUIRED. Android 11+ authenticated
Keystore v2 is integrated into new credential writes and SSH/SFTP reads. Existing v1-encrypted
records require explicit, individually verified migration in Settings. No records are migrated
on startup. A test prerelease is available; do not merge or ship a production release until the
remaining device/security checks pass. See [the 2026-10-22 follow-up review](security-review-2026-10-22.md)
for subsequent fixes and validation, and [backup safety](backup-safety.md) for format 8 compatibility.
The [2026-10-27 non-device validation](non-device-validation-2026-10-27.md) records subsequent
local signing-regression coverage, build/test results, fixed translation lint errors, and remaining warnings.

## Threat model
A local attacker with an unlocked phone or an application bypass must not initiate a new SSH/SFTP connection without a fresh Android biometric or device-credential confirmation. Authentication of a UI screen is insufficient if ViewModel methods can still connect independently.

## Required invariants
1. Every new network SSH/SFTP session requires a fresh, single-use authorization, including retry, reconnect, and background-triggered connections.
2. A connection attempt cannot retrieve saved private keys or passwords until authorization succeeds.
3. Cancellation, lockout, lack of enrolled authentication, or a missing Activity must fail closed.
4. A single-use transport grant covers one connection attempt and its hops. Each saved credential additionally requires its own per-operation CryptoObject authorization; no grant or cipher is reused across attempts.
5. Re-authentication is required for a new connection after disconnect; resuming an already-established session is governed by a separate session-lock policy.
6. A host with an assigned legacy plaintext key must not silently downgrade to password authentication.
7. Existing keys encrypted under the old Keystore alias must be migrated explicitly; do not silently overwrite or delete inaccessible keys.

## Implementation plan
- Implement an AndroidX BiometricPrompt adapter owned by a foreground FragmentActivity, allowing BIOMETRIC_STRONG or DEVICE_CREDENTIAL.
- Add a connection authorization coordinator shared by terminal and SFTP flows. ViewModels must await an authorization result before reading any credentials or calling SshManager. Never rely solely on a Composable dialog.
- Require a single-use transport grant bound to its attempt and foreground generation, consumed atomically immediately before transport creation. Each credential access separately requires an authenticated CryptoObject operation. Cancellation invalidates the attempt.
- Use a separate Android Keystore AES key with setUserAuthenticationRequired(true) and per-use authentication. Each operation uses a newly initialized Cipher and accepts only that exact BiometricPrompt CryptoObject. The existing alias is read-only and is never replaced in place.
- Fail closed on Keystore errors. Do not generate a replacement encryption key for an existing encrypted record.
- Update Settings UI: encryption must not be presented as a user-disableable option. Provide explicit migration or re-import guidance.
- Reject rather than use legacy plaintext passwords and private keys, including jump hosts.

## Regression tests (required before merge)
- New key generation and import never populate plaintext database columns.
- Saved host passwords never populate plaintext database columns.
- Legacy plaintext records are refused; wrong-key/decryption failure is not treated as an absent key.
- No SSH/SFTP connection starts before authorization; cancel, failure, retry and reconnect are covered.
- One authorization is not reusable; jump-host chain uses only the grant for its initiating attempt.
- Release and debug manifests have allowBackup=false; exported components are reviewed.
- Existing active session resume vs new session creation is tested separately.
- Instrumented tests on supported Android 11+ devices, including biometric enrollment changes and device-credential fallback.

## Current implementation limitations
- Terminal and SFTP request a new single-use transport grant before credential resolution,
  including password retries. It is consumed immediately before transport creation and expires
  if the activity stops or is replaced. Returning to the foreground does not revive it.
  Stored keys/passwords are read through v2 only, with a separate per-operation CryptoObject
  prompt for every credential, including jump hosts. Unmigrated v1 records fail with Settings/
  re-import guidance rather than falling back to the old key or an interactive password.
- Both prompt bridges use `AuthenticationPromptRequest` to complete each attempt only once,
  invalidate it immediately on cancellation/stop, and run observer/prompt cleanup on the UI
  thread before delivering a result. JVM tests cover late callbacks, concurrent rejection,
  background-thread cancellation, activity replacement, and mismatched crypto objects using
  an injected UI executor; they do not exercise the Android prompt itself.
- V2 decryption reads the existing key once and has no key-creation fallback. Existing/generated
  keys are checked through Android KeyInfo for required per-operation authentication, the
  strong-biometric-or-device-credential policy, AES-256, GCM, and no padding. Incompatible aliases
  are refused rather than replaced. Hardware backing remains device-dependent.
  `CredentialCipherV2Test` runs real JCA AES-256-GCM encryption/decryption to verify byte-preserving
  round trips, wrong-key refusal, IV/ciphertext/tag tampering, truncation, and malformed inputs.
  These tests do not validate Android's per-operation authentication or hardware-backed keys.
- Minimum supported API is now 30 (Android 11), as confirmed by the user. Android 10 is not
  supported: the pinned AndroidX Biometric 1.1.0 rejects the required
  `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` combination on API 29. This follows its
  `AuthenticatorUtils.isSupportedCombination` contract;
  see [PromptInfo.Builder.setAllowedAuthenticators](https://developer.android.com/reference/androidx/biometric/BiometricPrompt.PromptInfo.Builder#setAllowedAuthenticators(int)).
- Stopping or destroying the activity rejects the prompt attempt. Since a system device-credential
  screen may itself stop the host activity, PIN/pattern/password success across that transition
  is not established. This strict stop rule remains in place pending a device-tested flow that
  distinguishes credential UI from an unrelated trip to the background.
- Both connection paths use `StoredConnectionCredentials` for target and jump-host credentials.
  Missing assigned keys, legacy plaintext credentials, and decryption errors fail instead of
  selecting another authentication method. Only a host with no stored credential requests an
  interactive password.
- Host-list jump chains require every configured ID to be valid and every hop to exist. A
  missing or invalid hop stops connection setup instead of silently shortening the route.
  The editor retains invalid tokens as unselected hops and refuses saving until each hop is
  explicitly selected or removed; metadata edits cannot silently erase a damaged hop.
- The host editor does not decrypt saved passwords. Metadata edits retain the original
  ciphertext; replacing a password or explicitly removing it changes the credential. Legacy
  plaintext passwords require re-entry or explicit removal. Save failures leave the editor
  open with an error, and cancellation is propagated.
- The key list shows public metadata without decrypting private keys. Legacy/empty records
  are marked for re-import from their storage metadata; the list no longer tries to inspect
  encrypted PEM contents to diagnose old passphrase imports. Those errors are diagnosed only
  during an authorized connection attempt.
- Generated/imported keys share `persistEncryptedKey`, which refuses empty private material
  or ciphertext and passes only ciphertext/public metadata to persistence. Encryption/storage
  failures keep the dialog open with a non-sensitive error. Duplicate submissions are blocked,
  and cancellation is propagated and checked again before persistence.
- Settings exposes explicit per-record v1-to-v2 migration. The user confirms the named record,
  authenticates the legacy read, authorizes v2 encryption, then authorizes a separate readback.
  Only a byte-exact verified result reaches an atomic SQL compare-and-replace of that encrypted
  column. Concurrent edits/deletion, cancellation, authentication failure, malformed output,
  decryption errors, and verification mismatch never commit a replacement. No old alias is
  deleted and no plaintext record is silently adopted. Temporary byte arrays are cleared on
  exit; this is not a guarantee of JVM String/heap erasure or historical database-page cleanup.
  Until migration, v1 blobs retain their old at-rest protection: refusing them in connection
  code does not cryptographically upgrade the existing blobs or authenticate the old key.
- Key renames and session metadata updates touch only their own columns, not credential blobs.
  Host-editor saves reject a password changed since loading, inside a Room transaction. Backup
  import preserves current credentials in a transaction so it cannot restore a stale v1 blob
  over a concurrently migrated record. These updates do not change the Room schema version.
- Target and jump-host connections enforce saved host-key pins with strict checking. Only a
  null pin requests first-use trust; non-null empty/whitespace pins on the target or any hop
  are rejected before opening any transport. Changed or malformed nonblank pins cannot be
  accepted through the first-use trust prompt. Loopback SSH/SFTP integration tests cover
  these refusals, including a blank pin after an earlier first-use hop, along with successful
  pinned reconnection and refusal when first-use trust is declined.
- JVM regression tests exercise credential selection, decryption errors, cancellation, and
  complete jump-chain resolution without requiring a server or device. These tests use injected
  decryption/lookup functions; they do not validate Android Keystore or biometric hardware.
- Local debug APK compilation, JVM tests, and the merged debug `allowBackup=false` check have
  passed. The disposable-fixture runner executes the key-import, editor-corpus, real SSH shell,
  jump-host, and SFTP integration tests without skips, and is wired into CI. Ordinary Gradle
  invocations still skip fixture-dependent tests when fixtures are absent. The loopback tests
  validate the transport implementation, not Android UI authorization or hardware Keystore.
  Android biometric/device-credential tests and an application-level dynamic network audit
  have not been completed.
- Legacy database contents, actual device migration, prompt transitions, and background/session
  behavior still require review and device validation. Rejecting plaintext records does not
  remove historical plaintext from database pages, journals, or existing backups.

## Reproducible JVM and transport validation

Local validation on 2026-10-20: 179 JVM/transport tests passed, zero failures/errors/skips.
`assembleDebug assembleDebugAndroidTest` succeeded. Both APK signatures verified with the
same debug signer. The merged debug manifest has `allowBackup=false` and minimum API 30.
Actual DAO conditional-update SQL also passed local SQLite checks (not Android Room execution).
Those dated results describe local execution, not device acceptance. Later remote CI runs for
the test prerelease and first follow-up commit succeeded; current validation is recorded in
the follow-up review and GitHub Actions, not inferred from this historical test total.

On Linux, install JDK 21, the Android SDK packages required by the Gradle project, Python 3.10+,
OpenSSH `ssh-keygen`, and OpenSSL. Then, from the repository root:

```sh
python3 -m venv /tmp/sshborg-security-test-venv
/tmp/sshborg-security-test-venv/bin/python -m pip install -r scripts/requirements-security-tests.txt
/tmp/sshborg-security-test-venv/bin/python scripts/run-local-security-tests.py
./gradlew --no-daemon assembleDebug
```

Set `JAVA_HOME`, `ANDROID_HOME`, and `GRADLE_USER_HOME` if needed. In restricted environments,
set `ANDROID_USER_HOME` to a writable task-specific directory for debug/test signing keys.
Use the runner's `--offline`
option after Gradle dependencies have been cached. It forces test re-execution, verifies the
required suites exist, and fails if any test fails, errors, or is skipped. Results are written
under `app/build/test-results/testDebugUnitTest` and `app/build/reports/tests/testDebugUnitTest`.

The runner creates fresh keys, passphrases, text fixtures, an ephemeral server host key, and a
temporary SFTP root. It binds only to IPv4 loopback on a random port and authorizes only its
generated test keys for a logical fixture username, not a real system login. Jump forwarding
is restricted to that same server. The shell runs as the invoking OS user; this is a test
fixture, not an OS security sandbox. Never expose it to untrusted code or clients. It refuses
an existing `test.properties` to avoid accidentally using a real server. It removes its own
temporary fixtures on exit and does not edit system authorized keys or production credentials.

## Confirmed policy and remaining device acceptance

The user confirmed Android 11/API 30+ and explicit, verified migration preserving originals on
failure. This is implemented; no old alias is deleted. Missing v1 keys cannot be recovered by
migration. Plaintext records still require re-import/re-entry. A time-window key or application
PIN is not substituted for per-operation cryptographic authorization.
See [setUserAuthenticationParameters](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder#setUserAuthenticationParameters(int,int)).

`CredentialProtectionTest`, `CredentialMigrationTest`, and `ConnectionAttemptGrantTest` cover
the operation boundary, exact-cipher matching, per-use prompts, real JCA AES-GCM migration,
verification failures, cancellation, conditional-write conflicts, byte-buffer cleanup, grant
consumption races, and foreground invalidation. JVM authorizers are injected: these tests do
not prove Android hardware/biometric behavior.

Device tests added (not executed in this environment):

- `com.sshborg.data.CredentialKeyPolicyTest`: policy metadata, unauthorized encryption refusal,
  and refusal of an unprotected key. Uses only unique, disposable test aliases; never production
  aliases. Configure a secure device lock first (otherwise protected-key cases are skipped).
- `com.sshborg.data.db.CredentialUpdateTest`: in-memory Room conditional updates, stale/missing
  rows, preservation of unrelated fields, and session metadata not rolling back a migrated blob.

Build with `./gradlew assembleDebug assembleDebugAndroidTest`, then run selected tests on a
dedicated configured device using `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.sshborg.data.CredentialKeyPolicyTest,com.sshborg.data.db.CredentialUpdateTest`.
The workflow compiles device tests but does not execute them; compiling APKs is not device acceptance.
Include `com.sshborg.data.db.HostBackupRoomTest` in dedicated-device validation for the new backup importer.

Before release, use disposable credentials on supported Android devices to verify strong
biometric and PIN/pattern/password success, refusal/cancellation/lockout, missing keys, enrollment
changes, separate confirmation for every credential/hop and migration readback, backgrounding
before transport start, and retry/reconnect. Verify that metadata edits during a migration do
not lose the new blob. Check the system credential UI's Activity lifecycle: the strict ON_STOP
rule is intentionally retained, so any device where PIN itself stops the Activity currently
fails closed. Do not weaken that rule or mark PIN supported without device-tested handling.
An application-level dynamic network audit and release acceptance remain outstanding.

Before production publishing, align the website's translated security text (which still describes
the released app's optional encryption policy) with the accepted device behavior. Core migration,
prompt and backup messages now have translations in the nine existing non-English locales;
human language/layout and TV acceptance remain required. Minimum Android badges are updated for
this branch. A test APK was published, but no production release or website deployment was made.

## Tablet-test prerelease delivery

An explicitly authorized test APK based on `ee6fbcf173227d8dcfad0d3d59b91f9b41b690c8`
was published through GitHub Actions, without merging into `V1`. This historical approval is
not standing permission for another publication. When separately authorized, run the existing
`Hardened Android validation` workflow manually on that test branch with
`publish_tablet_test=true`. Its default is false; ordinary push/PR validation cannot publish.
Only the separate publication job receives `contents: write`, after the build/test job passes.

The artifact contains both signed debug APKs, `build-info.json` (exact source commit, run,
attempt, passing JVM test totals, and an explicit NOT EXECUTED device-acceptance status), and
SHA256SUMS. The publisher verifies the complete bundle against this workflow run, uploads to
an unpublished prerelease, verifies GitHub's recorded asset sizes and SHA-256 digests, then
publishes it without marking it latest. No attachment is overwritten. A mismatched bundle,
non-test branch, PR event, failed/skipped tests, failed upload, or digest mismatch cannot publish.
The earlier empty distribution draft can be repurposed only while still empty and unpublished;
an already published or modified release is left alone. Tags identify the actual CI-built commit,
not the old local-worktree build's base. Python publication safeguard tests use a fake API and never
contact GitHub. Earlier prereleases used CI-specific debug keys. New manual publications require
the dedicated fixed test key and public certificate pin described in
[tablet-test signing](tablet-test-signing.md); ordinary build artifacts still use ephemeral debug
keys. Fixed-key configuration was verified by successful manual run `40452951192` on 2026-10-27;
release `tablet-test-40452951192-1` is the first fixed-signer delivery. Public attachments were
downloaded and their checksums and both APK signatures verified; details are in the signing guide.
The added secret-free real-APK signing regression is locally validated and awaits a future CI run.
Two-version device update acceptance remains outstanding.
This does not produce a production-signed APK or make earlier mismatched signers compatible.
