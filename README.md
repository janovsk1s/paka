# Paka

Paka is an intentionally small, offline pass-and-authenticator tool designed for
Light Phone III. It scans and renders common barcode formats, carries encrypted
PDF passes, encrypted one- or two-sided document photos, and generates TOTP
codes without Google Play Services.

Current stable release: **0.15.0**

## Photos

<p align="center">
  <img src="docs/screenshots/home.jpeg" width="23%" alt="Paka pass list on Light Phone III">
  <img src="docs/screenshots/two-factor.jpeg" width="23%" alt="Paka 2FA code screen on Light Phone III">
  <img src="docs/screenshots/settings.jpeg" width="23%" alt="Paka settings on Light Phone III">
  <img src="docs/screenshots/scanner.jpeg" width="23%" alt="Paka scanner on Light Phone III">
</p>

## Compatibility and independence

Paka is an independent, unofficial community tool designed for compatibility
with Light Phone III. It is not affiliated with, endorsed by, sponsored by, or
published by The Light Phone, Inc.

The names “Light Phone,” “Light Phone III,” “LightOS,” and related marks belong
to their respective owner and are used here only to describe compatibility.
Paka does not contain LightOS source code, Light branding, or proprietary Light
assets. Its original interface follows the public platform conventions and the
intentional design principles described by the LightOS Developer Program.

## Privacy

- Paka requests camera access only while scanning codes or taking document
  photos inside the app.
- Paka does not request internet or network-state access and contains no
  analytics or advertising.
- Pass data and TOTP secrets are encrypted with separate AES-256-GCM keys held
  by Android Keystore. Existing plaintext pass stores migrate automatically.
- Imported PDFs use their own Android Keystore key. Their encrypted copies are
  stored privately; viewing decrypts them into anonymous RAM through `memfd`,
  never a plaintext file. PDF passes require Android 11 or newer.
- Imported document photos are encrypted with a dedicated random data key that
  is itself wrapped by a hardware-backed Android Keystore key, so bulk
  decryption stays fast while the master key never leaves the hardware. One or
  two sides are copied into Paka as encrypted originals and included in
  encrypted portable backups. Each photo also keeps a pre-scaled display copy,
  encrypted the same way; only the open viewer or stack owns decoded copies,
  which are released when it closes or Paka leaves the foreground.
- In Paka 0.15, document photos can also be captured directly inside
  Paka. Captures travel sensor → memory → encrypted store, are never written to
  the gallery or a temporary file, and are re-encoded before storage so camera
  metadata is stripped. Captured and chosen photos can be reviewed and cropped
  before they are saved.
- Up to two optional file references in pass Details are external links. Paka stores only
  the link metadata in its encrypted pass database; the referenced file itself
  is not copied, encrypted, or included in Paka backups.
- Android cloud backup and device transfer are disabled.
- User-created portable backups are encrypted and authenticated offline with an
  AES-256-GCM key derived from the user's passphrase.
- TOTP codes copied to the clipboard are marked sensitive and cleared after 30 seconds while Paka has focus, or safely on the next return to Paka if the code is still present.

Security policy, threat model, format notes, and device testing guidance live in:

- [SECURITY.md](SECURITY.md)
- [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md)
- [docs/FORMATS.md](docs/FORMATS.md)
- [docs/DEVICE_TESTING.md](docs/DEVICE_TESTING.md)
- [docs/SECURITY_ROADMAP.md](docs/SECURITY_ROADMAP.md)
- [docs/RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md)

Uninstalling Paka permanently removes data that was not exported first. If an
Android Keystore key is invalidated, on-device encrypted data can only
be recovered from a user-created encrypted backup. Paka cannot recover a forgotten
backup passphrase.

## Interaction

Tap `+` to scan. Long-press `+` for intentional manual entry. Open a pass, then
long-press its displayed code to edit its name, stack, and notes. These
restrained secondary gestures are deliberate.
Lists show five entries at a time and snap vertically between full pages.
Vibration feedback can be enabled or disabled in settings. The scanner uses a
higher-resolution analysis stream, retries focus, detects sustained low light,
and can engage the camera light automatically. Rendered barcodes are decoded and
payload-checked before display; a bounded memory cache keeps common passes fast.
PDF pages open at a fitted overview, support pinch and instant double-tap zoom,
and rerender the settled viewport for sharp text without giant zoom bitmaps.
Photo passes prefetch one or two sides from encrypted display copies; a fitted
photo flips sides immediately when tapped, while zoom-in is pinch-only so the
tap never waits on a double/triple-tap detector. PDF double-tap zoom is unchanged.
Paka returns to the pass list after leaving the app by default; the hidden
Developer screen can disable that behavior.
On first run Paka follows the first supported device language: English, Latvian,
Estonian, Lithuanian, Finnish, Swedish, German, or Slovak. Unsupported device
languages use English. A choice made in the hidden Developer screen remains in
effect until another language is selected. Paka deliberately does not expose
right-to-left locales.
The same screen can enable an isolated demo mode with freshly generated,
in-memory passes and 2FA accounts; demo changes never touch the real stores.

## Building

The project requires JDK 17 and the Android SDK declared by `compileSdk` in the
app module. Release builds use the local `keystore.properties` configuration;
that ignored file and its referenced keystore must be backed up together because
future upgrades require the same signing identity.

```sh
./gradlew test lint detekt assembleDebug assemblePreview assembleRelease
tools/verify_release_apk.sh app/build/outputs/apk/release/app-release.apk
```

See [docs/RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md) before tagging or
publishing a GitHub release. The tagged-release workflow must finish green and
retains an unsigned comparison APK; the owner then creates the release and
attaches the locally signed APK.

LightOS SDK integration should use the official Compose design library and
emulator when those developer-program dependencies are available.

## License

Copyright © 2026 Adrians Janovskis ([@janovsk1s](https://github.com/janovsk1s)).

Paka is licensed under [GPL-3.0-only](LICENSE), with the attribution and branding
terms in [ADDITIONAL_TERMS.md](ADDITIONAL_TERMS.md). Distributed modifications
must remain open source under GPLv3, disclose their corresponding source, retain
the required attribution, and identify themselves as modified.

See [NOTICE](NOTICE), [ACKNOWLEDGMENTS.md](ACKNOWLEDGMENTS.md), and
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for authorship, AI-assistance,
and dependency information.
