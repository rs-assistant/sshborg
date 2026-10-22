# Backup and restore safety

Host backups carry configuration, not authentication material. Passwords, encrypted password
blobs, private keys, encrypted private keys, host-key pins, and application lock secrets are
not exported or accepted from an imported host. Re-importing metadata preserves the current
local credential, including an unreadable blob; it never decrypts it or restores an older blob.
This does not remove historical plaintext from SQLite pages, journals, or old backups.

## Portable relationships: format 8

New exports use JSON `version: 8`. Key references use `keyLabel`, groups use `group`, and
host-list jump chains use ordered `jumpHostLabels` arrays. Database row IDs are installation-local
and must never be reused from another installation. Host names must be unique; referenced
key names must resolve uniquely. Missing keys, missing hops, ambiguous labels, invalid ports,
and malformed simple jump chains fail instead of becoming password authentication or a
shorter/direct route.

Import the SSH keys separately on the destination before restoring a configuration that names
them. An existing local assigned key is retained, not replaced by a key name from a backup.
Hop labels are resolved after all restored hosts have received their local IDs, so a target
can refer to a hop later in the file. Host/key names are associations, not proof of server or
key identity: review the source and verify fingerprints when connecting.

Versions 1–7 remain readable for simple chains and empty host-list chains. A non-empty
`jumpHostIdList` cannot be translated safely without the source ID-to-host mapping, so these
older host-list backups are rejected without modifying the database. Export them again from
the source installation with this version; do not strip IDs to force a shorter route.
Unknown future backup versions are rejected as well.

Restore format 8 only with an application version that understands it. Older applications
may ignore `jumpHostLabels` and cannot safely restore its host-list routes.

## Atomic database import and settings

The JSON is parsed and structurally validated before writing. All host/group reads and writes,
key-name matching, preservation of current credentials, and hop remapping execute inside one
Room transaction. An error, cancellation before commit, missing association, or write failure
rolls back host and group changes. Duplicate imported names or ambiguous existing matches are
rejected, not resolved by picking an arbitrary row. No database schema change is required.

Host-key pins are retained only for an unchanged hostname/port. Simple jump pins additionally
require unchanged chain text and mode. Repointing a host invalidates its old pin and requires
new trust verification; only restore configuration from a source you trust.

Preferences use DataStore and cannot participate in the Room transaction. They are restored
after the database commits, in DataStore's own edit transaction. A preferences failure is
reported as **hosts imported, settings not restored**, not as a rolled-back host import.
Application lock mode/secrets and acknowledgement flags are not imported.

## Verification

`HostBackupTest` executes metadata preservation, export/import round trips with different
IDs, missing/ambiguous associations, and rollback/cancellation policies with an injected store.
Run it through `scripts/run-local-security-tests.py` alongside the full JVM suite.

`HostBackupRoomTest` uses a fresh in-memory Room database to exercise real transaction rollback
and preservation of migrated passwords. It is compiled in CI but still requires execution
on a dedicated Android device. JVM tests and successful APK compilation are not Room/device
acceptance. Do not use production credentials or clear an installed app's data to run tests.
