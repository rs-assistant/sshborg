# Hardened SSH/SFTP connection authentication — implementation specification

Status: DESIGN ONLY. Not an implemented security control. Do not ship this branch.

## Threat model
A local attacker with an unlocked phone or an application bypass must not initiate a new SSH/SFTP connection without a fresh Android biometric or device-credential confirmation. Authentication of a UI screen is insufficient if ViewModel methods can still connect independently.

## Required invariants
1. Every new network SSH/SFTP session requires a fresh, single-use authorization, including retry, reconnect, and background-triggered connections.
2. A connection attempt cannot retrieve saved private keys or passwords until authorization succeeds.
3. Cancellation, lockout, lack of enrolled authentication, or a missing Activity must fail closed.
4. A single authorization covers all hops of one connection attempt; it cannot be reused for another attempt.
5. Re-authentication is required for a new connection after disconnect; resuming an already-established session is governed by a separate session-lock policy.
6. A host with an assigned legacy plaintext key must not silently downgrade to password authentication.
7. Existing keys encrypted under the old Keystore alias must be migrated explicitly; do not silently overwrite or delete inaccessible keys.

## Implementation plan
- Implement an AndroidX BiometricPrompt adapter owned by a foreground FragmentActivity, allowing BIOMETRIC_STRONG or DEVICE_CREDENTIAL.
- Add a connection authorization coordinator shared by terminal and SFTP flows. ViewModels must await an authorization result before reading any credentials or calling SshManager. Never rely solely on a Composable dialog.
- Require single-use authorization bound to a connection-attempt identifier, consumed atomically immediately before credential access. Cancelled attempts invalidate the grant.
- For stronger at-rest enforcement, use a new Android Keystore AES key with setUserAuthenticationRequired(true) and per-use authentication; use BiometricPrompt CryptoObject when supported. Android credential fallback and CryptoObject support must be tested on supported API levels before selecting this approach. Do not switch the existing alias in place.
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
- Instrumented tests on Android 10–16, including biometric enrollment changes and device-credential fallback.

## Current implementation limitations
The branch currently changes new credential writes and rejects some plaintext reads, but **does not implement fresh per-connection authentication**. No Android build, tests, or dynamic network audit have been completed.
