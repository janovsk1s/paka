# Paka Format Registry

This registry documents persistence and backup formats so future migrations stay
intentional and backward-readable.

## On-device pass store

- File: `cards.enc`
- Owner: `CardStore`
- Key alias: `paka_card_key`
- Container: `PKC` magic version `1`, followed by 12-byte AES-GCM IV and
  ciphertext.
- AAD: `paka-cards-v1`
- JSON schema: current `5`

Schema history:

| Schema | Meaning |
| --- | --- |
| Legacy array | Plain JSON barcode list from early Paka builds; migrated to encrypted storage. |
| 1 | Versioned JSON envelope for barcode passes. |
| 2 | Added PDF pass content. |
| 3 | Added one external reference field. |
| 4 | Replaced single reference with up to two `PassReference` values. |
| 5 | Added one- or two-sided photo pass content. |

Current pass content variants:

- `barcode`: exact payload plus `PakaFormat`.
- `pdf`: 64-character document id plus page count.
- `photos`: one or two `PhotoPage` entries, each with document id, width, and
  height.

External references are `content://` links. Paka stores URI metadata only; the
referenced file is not copied, encrypted, or included in portable backups.

## Atomic generations and restore journal

Encrypted stores use a primary file plus a `.bak` previous generation. If only
the backup is valid, it is promoted without overwriting that known-good backup;
a corrupt encrypted primary is retained as `.corrupt` until a successful save.
Legacy plaintext migration securely erases all three possible generations.

A portable restore spanning cards, 2FA, PDFs, and photos uses these private
files:

- `restore.journal`: schema 1 phase marker and old/restored content identifiers.
- `restore.cards.enc`: encrypted pre-restore pass-store snapshot.
- `restore.otp.enc`: encrypted pre-restore 2FA-store snapshot.

`PREPARED` means startup restores both encrypted snapshots and deletes only PDF
or photo identifiers introduced by the interrupted restore. `COMMITTED` means
the new metadata stores are authoritative and startup finishes removing old
unreferenced blobs. The journal is resolved before ordinary store loading or
orphan cleanup. It contains no pass payloads, TOTP secrets, or document/photo
plaintext.

## On-device TOTP store

- File: `otp.enc`
- Owner: `SecureStore`
- Key alias: `paka_otp_key`
- Container: `PKA` magic version `1`, followed by 12-byte AES-GCM IV and
  ciphertext.
- AAD: `paka-otp-v1`
- JSON schema: current `1`

Pre-versioned encrypted stores without magic/AAD are readable and are
immediately rewritten into the current authenticated layout on successful load.

## PDF blobs

- Owner: `PdfStore`
- Identifier: SHA-256 hex of the original PDF bytes.
- Limit: 10 MB per document.
- Runtime: API 30+ only, opened through anonymous `memfd` and `PdfRenderer`.
- Plaintext cache files are forbidden.

PDF bytes are included in encrypted portable backups and restored
transactionally. Orphan cleanup runs only after a healthy pass-store load.

## Photo blobs

- Owner: `PhotoStore`
- Identifier: SHA-256 hex of the original normalized image bytes.
- Limit: 10 MB per image, bounded dimensions, and bounded pixel count.
- Supported headers: JPEG, PNG, GIF, WebP, HEIF/HEIC, and AVIF (matched by
  `PhotoStore.hasSupportedHeader`).
- Current container: `PKI` magic version `2`.
- Legacy container: `PKI` magic version `1`.
- Data-key wrapper: `PKD` magic version `1`.

Version 1 encrypted photo bytes directly with the Keystore key. Version 2 uses a
random data key for photo bytes and wraps that key with the Keystore key so bulk
photo decryption stays fast while the master key remains hardware-backed where
available.

Photo originals are included in encrypted portable backups. Viewer bitmaps are
session-scoped and must be released on close, background, or memory trim.

## Compact photo-backup payload

- Owner: `BackupPayloadCodec`
- Magic: `PKB4`
- Version: `1`
- Purpose: compact binary payload inside `BackupCrypto` for backups containing
  photos.

Layout:

1. magic
2. version
3. metadata length and JSON metadata
4. PDF blob entries
5. photo blob entries

Each blob entry contains a 64-byte ASCII hex id, a byte length, and raw bytes.
The decoder enforces per-entry, total-size, duplicate-id, and trailing-data
checks.

## Portable encrypted backup container

- Owner: `BackupCrypto`
- Magic: `PAKAB` version `1`
- KDF: PBKDF2-HMAC-SHA256
- New backup iterations: 600,000
- Accepted restore range: 100,000 to 1,000,000
- Salt: 16 bytes
- IV: 12 bytes
- Cipher: AES-256-GCM with 128-bit tag
- AAD: magic, iteration count, and salt

Backup metadata schemas:

| Schema | Payload |
| --- | --- |
| 1 | Barcode passes and TOTP accounts. |
| 2 | Adds embedded PDF documents. |
| 3 | Adds photo pass metadata and photo blobs. |

Backups containing photos use the compact binary payload. Barcode-only and
PDF-only backups retain stable-compatible JSON schemas when possible.

## Release artifacts

- Stable APKs use application id `com.paka.app`.
- Preview/debug capture-photo APKs use the `.photopreview` suffix and the
  `Paka Photo Test` label unless explicitly changed for a release.
- The known release certificate SHA-256 is recorded in `AGENTS.md`.
- Release APK assets should be named `Paka-vX.apk`, marked pre-release for beta
  tags, and never marked Latest unless the tag is stable.
