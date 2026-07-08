# Paka — Security & Longevity Roadmap

This roadmap describes how Paka moves from very good to reference-grade
security while staying maintainable and extensible over time. It is scoped to
preserve Paka's identity: offline, monochrome, restrained, and local-first.

Status: draft, revised after review.

## Branch base and caveat

- Live development is on `preview/document-capture`, the 0.15 capture/photo
  beta, not `main`.
- Branch implementation work from the current beta base unless the maintainer
  explicitly asks for a stable-branch fix.
- The danger is release scope, not the ideas. 0.15 is already a capture/photo
  release; do not stuff deep security changes into it.

## Guardrails

- No internet permission, cloud, accounts, telemetry, anonymous crash reporting,
  or trackers.
- No Google Play Services, Play Integrity, or SafetyNet.
- No navigation/UI modernization for its own sake.
- No plaintext caches for decrypted PDFs, photos, secrets, or backups.
- Security-relevant changes ship with tests; formats stay versioned and
  backward-readable.

## Milestones

### 0.15 stable — capture/photo release plus security-doc hygiene

No behavioral security changes. Docs and verification only.

- Finish capture/photo reliability.
- Complete the battery and lifecycle device test pass.
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

### 0.17 — internationalization and accessibility foundation

- Extract hardcoded UI strings into Android resources with plurals and
  placeholders.
- Add pseudolocalization checks and fix layout overflow.
- Full TalkBack pass of scan, TOTP, Details, and backup flows.
- Latvian first; consider Weblate once the translation surface is stable.

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
