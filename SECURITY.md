# Security Policy

Paka is an offline pass wallet and TOTP authenticator for Light Phone III. Its
security model is deliberately local: no internet permission, no cloud service,
no accounts, no analytics, and no crash reporting.

## Supported versions

Security fixes target the current stable release and the active capture/photo
preview branch.

| Version | Status |
| --- | --- |
| 0.15.x beta previews | Active preview testing |
| 0.14.x | Current stable |
| Older versions | Best-effort only |

## Reporting a vulnerability

Please report security issues privately instead of opening a public issue.

- Preferred: [GitHub private vulnerability report](https://github.com/janovsk1s/paka/security/advisories/new)
- If that is unavailable, contact [@janovsk1s](https://github.com/janovsk1s)
  and ask for a private channel before sharing exploit details, live pass data,
  TOTP seeds, or backup material.

Paka does not currently publish a PGP key. Do not send real credentials or
unencrypted backups unless a private encrypted channel has been agreed first.

## Scope

In scope:

- Bypasses of encrypted on-device storage or backup authentication.
- Plaintext persistence of passes, TOTP secrets, PDFs, photos, or backups.
- Backup restore or migration bugs that can overwrite, corrupt, or leak data.
- Barcode payload fidelity failures where a rendered code does not match the
  original payload.
- Screenshot, clipboard, or demo-mode leaks across real sensitive flows.
- Permission regressions such as internet, network-state, cloud backup, or
  tracker-like dependencies.

Out of scope:

- Issues requiring a rooted device, a compromised OS, or physical extraction of
  an already-unlocked app process.
- Social engineering or phishing against the maintainer.
- Reports only about unsupported historical versions without a current-version
  reproduction path.
- Cosmetic UI issues without security impact.

## Response expectations

This is a small community project, not a commercial security team. The goal is
to acknowledge good-faith reports within a week, reproduce and scope them
carefully, and ship fixes in the smallest safe release.

Please include:

- Paka version and APK source.
- Device and Android version.
- Steps to reproduce with synthetic data.
- Whether the issue affects stable, preview, or both.
- Any logs only after removing pass payloads, TOTP secrets, backup passphrases,
  photos, and live credentials.

## Security invariants

Future changes must preserve the invariants recorded in [AGENTS.md](AGENTS.md):

- no internet, cloud, accounts, telemetry, or trackers;
- no plaintext caches for decrypted PDFs, photos, secrets, or backups;
- exact barcode payload preservation;
- encrypted pass and TOTP stores with crash-safe replacement;
- screenshot protection for real TOTP, backup, and secret-entry flows;
- demo data isolated from real data;
- Android cloud backup and device transfer disabled.
