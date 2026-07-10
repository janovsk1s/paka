# Paka Threat Model

Paka is a local-first pass wallet and TOTP authenticator. This document makes
the security model explicit so future changes can be judged against it.

## Assets

- Pass payloads and barcode formats.
- TOTP account names and shared secrets.
- Imported PDFs and document photos.
- Portable encrypted backups and their passphrases.
- Notes, stack names, and reference metadata.
- Clipboard values created by Paka.
- Signing keys and release artifacts.

## Trust boundaries

- Android app sandbox: Paka stores private encrypted files in its sandbox.
- Android Keystore: stores or wraps AES keys used for on-device stores.
- External document providers: `content://` references remain outside Paka and
  are not copied into portable backups.
- Camera and document picker: input surfaces that can provide malformed or
  malicious files.
- Clipboard: shared OS surface; Paka treats it as leaky and clears only values
  it still owns.
- GitHub release channel: source, tags, APK assets, and release notes.

## In scope attackers

- A person with temporary access to an unlocked device.
- A malicious or malformed PDF/photo/backup/barcode import.
- A compromised or confusing release process.
- Another app observing shared surfaces such as clipboard or screenshots, within
  Android's normal permission model.
- Storage corruption, interrupted writes, and failed migrations.

## Out of scope attackers

- A rooted or kernel-compromised device.
- Hardware extraction attacks against an already-compromised OS.
- Android Keystore implementation compromise.
- Shoulder-surfing while the user intentionally displays a pass.
- Malware with accessibility or screen-capture privileges granted by the user.

## Security goals

- No internet, cloud, account, telemetry, or tracker dependency.
- Pass and TOTP stores remain encrypted at rest with separate Keystore keys.
- PDFs and photos are stored only as ciphertext and never through plaintext
  cache files.
- Portable backups are encrypted and authenticated offline with a user
  passphrase.
- Restores are transactional: a failed restore must not destroy the existing
  store.
- Barcode rendering preserves payload fidelity exactly.
- Demo mode never writes synthetic content into real stores.
- Screenshot protection covers real TOTP, backup, scanning, and secret-entry
  flows.
- Clipboard clearing does not erase content copied later by another app.

## Non-goals

- Hiding a pass from someone the user intentionally shows it to.
- Cloud sync or remote wipe.
- Play Integrity, SafetyNet, or Google account-based attestation.
- Protection from a fully compromised Android device.

## Current gaps and planned mitigations

- A borrowed unlocked phone can open Paka. Planned mitigation: optional app lock
  using `BiometricPrompt` and device credential fallback, off by default.
- Clipboard TOTP remains inherently leaky. Planned mitigation: optional
  reveal-only mode.
- Backup KDF uses PBKDF2-HMAC-SHA256 with 600,000 rounds. Planned mitigation:
  versioned Argon2id for new backups while keeping legacy restore.
- Release reproducibility is not yet documented. Planned mitigation:
  reproducible-build script and published verification instructions.
- Release permission regressions are guarded in CI for debug, preview, and
  unsigned release APKs, and release-signed APKs are checked locally before
  publishing.

## Review questions for future changes

- Does this add any network, cloud, account, telemetry, or tracker path?
- Can plaintext sensitive data outlive the screen/session that needed it?
- Does a failed write or restore preserve the previous usable store?
- Can demo data cross into real data?
- Does any rendered barcode decode to something other than the original payload?
- Does the change alter hidden gestures, hard cuts, or Light Phone-oriented UI
  constraints without explicit maintainer approval?
