# Paka — Security & Longevity Roadmap

This roadmap describes how Paka moves from very good to reference-grade
security while staying maintainable and extensible over time. It is scoped to
preserve Paka's identity: offline, monochrome, restrained, and local-first.

Status: living roadmap, revised for the 0.15.0 stable release.

## Branch base and caveat

- Stable development is based on `main`; preview branches remain isolated until
  their changes have passed the release checklist.
- Branch implementation work from the current stable base unless the maintainer
  explicitly asks to continue an active preview line.
- Keep each release scoped: storage, cryptography, dependency, and UI changes
  should remain independently reviewable whenever possible.

## Guardrails

- No internet permission, cloud, accounts, telemetry, anonymous crash reporting,
  or trackers.
- No Google Play Services, Play Integrity, or SafetyNet.
- No navigation/UI modernization for its own sake.
- No plaintext caches for decrypted PDFs, photos, secrets, or backups.
- Security-relevant changes ship with tests; formats stay versioned and
  backward-readable.

## Milestones

### 0.15 stable — capture/photo, localization, and restore hardening

- Ship capture/photo reliability and bounded EXIF-aware review/crop handling.
- Keep decoded identity photos viewer-scoped and release them on close or
  background.
- Add the fixed eight-language LTR allowlist, automatic supported-device-locale
  selection, translated warnings/accessibility text, and overflow-safe labels.
- Make portable restore interruption-safe with encrypted snapshots and a durable
  journal, while preserving corrupt encrypted evidence for recovery.
- Keep release hygiene current: `CHANGELOG.md`, README release number,
  `versionCode`, `versionName`, release title/body, pre-release/latest state,
  and APK asset name. Use [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md) before
  tagging or publishing.
- Add `SECURITY.md` and `.well-known/security.txt`.
- Add `docs/THREAT_MODEL.md`.
- Add `docs/FORMATS.md`.
- Verify published APK permissions are expected and the signing certificate
  SHA-256 matches the value recorded in `AGENTS.md`; use
  `tools/verify_release_apk.sh` for local release candidates.
- Keep the CI permission guard enabled for debug, preview, and unsigned release
  APKs so dependency manifest regressions fail before publishing.

### 0.16 — optional app lock and accessibility start

- Optional app-level lock, off by default: `BiometricPrompt` plus device
  credential fallback, timeout options, and no Keystore key migration.
- Content descriptions for custom glyphs and basic TalkBack sweep.
- Dependency signature-verification experiment on an isolated branch.

### 0.17 — translation maintenance and accessibility foundation

- Add pseudolocalization and screenshot/layout regression coverage.
- Full TalkBack pass of scan, TOTP, Details, and backup flows.
- Establish native-speaker review and a contributor translation workflow while
  preserving the fixed supported-language allowlist.

### 0.18 — crypto upgrades and parser assurance

- Argon2id backup KDF with a versioned format bump and legacy backup restore.
- Passphrase strength checks beyond minimum length.
- Parser fuzzing for backups, TOTP URI parsing, and barcode/GS1 conversion.
- Optional no-clipboard reveal-only mode for TOTP.

### 0.19+ — high-assurance mode and verifiable trust

- Auth-bound Keystore keys only as opt-in high-security mode, once invalidation
  recovery is airtight.
- Reproducible-build script and documentation.
- External/community security audit.
- F-Droid or IzzyOnDroid listing and tracker verification.

## Explicit non-goals

- Internet, cloud, accounts, telemetry, or crash reporting.
- Google attestation services.
- Navigation Compose migration or generic Material UI.
- Plaintext caches or a PDF plaintext fallback below API 30.
