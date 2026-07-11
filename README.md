# Paka

Paka is a small, offline pass wallet and TOTP authenticator made for Light
Phone III. It keeps barcodes, PDFs, document photos, and 2FA codes close at
hand without accounts, cloud services, analytics, or Google Play Services.

## Download

Latest stable release: **Paka 0.15.1**

- [Release notes](https://github.com/janovsk1s/paka/releases/tag/v0.15.1)
- [Download Paka-v0.15.1.apk](https://github.com/janovsk1s/paka/releases/download/v0.15.1/Paka-v0.15.1.apk)
  — Light Phone III and other 64-bit phones
- [Download Paka-v0.15.1-arm32.apk](https://github.com/janovsk1s/paka/releases/download/v0.15.1/Paka-v0.15.1-arm32.apk)
  — 32-bit phones such as Light Phone 2

0.15.x is the last release line supporting Light Phone 2. On that device Paka
offers manual pass entry, code display, 2FA, photo import, backups, and a
Developer light mode for its e-ink screen; scanning, in-app capture, and PDF
passes need a camera and Android 11.

## What it does

- Scans and displays common QR, Aztec, PDF417, Data Matrix, GS1, and linear
  barcodes. Every rendered code is decoded and checked against its source
  payload.
- Stores encrypted PDF passes on Android 11 or newer.
- Captures or imports encrypted one- and two-sided document photos, with a
  review, rotate, and crop step before saving.
- Generates RFC 6238 TOTP codes entirely on the device.
- Creates authenticated, encrypted portable backups protected by a passphrase.
- Supports English, Latvian, Estonian, Lithuanian, Finnish, Swedish, German,
  and Slovak inside the app.

## Photos

<p align="center">
  <img src="docs/screenshots/home.jpeg" width="23%" alt="Paka pass list on Light Phone III">
  <img src="docs/screenshots/two-factor.jpeg" width="23%" alt="Paka 2FA code screen on Light Phone III">
  <img src="docs/screenshots/settings.jpeg" width="23%" alt="Paka settings on Light Phone III">
  <img src="docs/screenshots/scanner.jpeg" width="23%" alt="Paka scanner on Light Phone III">
</p>

## Privacy by design

- Paka has no internet or network-state permission, advertising, analytics,
  account, or cloud service.
- Camera access is requested only for scanning codes or taking a document
  photo inside the app.
- Passes, TOTP secrets, PDFs, and document photos are encrypted at rest with
  AES-256-GCM and keys protected by Android Keystore.
- In-app captures travel from the camera sensor to memory, review, and the
  encrypted store. They are re-encoded before storage, stripping camera
  metadata, and Paka creates no gallery item or plaintext temporary photo.
- TOTP codes copied to the clipboard are marked sensitive and cleared after
  30 seconds while Paka has focus, or on the next return to Paka if the code
  is still present.
- Optional file references remain external links. Their files are not copied,
  encrypted, or included in Paka backups.
- Android cloud backup and device transfer are disabled. Portable backups are
  encrypted and authenticated offline with a key derived from your passphrase.

Export a backup before uninstalling Paka or moving devices. An invalidated
Android Keystore key leaves only that backup as a recovery path, and Paka
cannot recover a forgotten backup passphrase.

## Using Paka

- Tap `+` to scan; long-press it for manual entry.
- Open a pass, then long-press its displayed code to edit its name, stack,
  notes, and references.
- Lists keep five fixed rows per page and move in immediate full-page steps.
- PDF passes support pinch and instant double-tap zoom. A fitted document photo
  switches sides on a tap and uses pinch to zoom.
- Paka follows a supported device language on first run and otherwise uses
  English. Developer options can set an explicit language, change return-home
  behaviour, and enable an isolated in-memory demo mode.

## Compatibility and independence

Paka is an independent, unofficial community project designed for Light Phone
III. It is not affiliated with, endorsed by, sponsored by, or published by The
Light Phone, Inc.

“Light Phone,” “Light Phone III,” “LightOS,” and related marks belong to their
owner and are used only to describe compatibility. Paka contains no LightOS
source code, Light branding, or proprietary Light assets.

## Building

Paka requires JDK 17 and the Android SDK version declared by the app module.

```sh
./gradlew test lint detekt assembleDebug assemblePreview assembleRelease
tools/verify_release_apk.sh app/build/outputs/apk/release/app-release.apk
```

Release builds use the ignored local `keystore.properties` file and its
referenced keystore. Keep both backed up together: Android updates must use the
same signing identity. Follow the [release checklist](docs/RELEASE_CHECKLIST.md)
before tagging or publishing. LightOS SDK integration should use the official
Compose design library when developer-program dependencies are available.

## Documentation

- [Security policy](SECURITY.md)
- [Threat model](docs/THREAT_MODEL.md)
- [Data and backup formats](docs/FORMATS.md)
- [Device testing](docs/DEVICE_TESTING.md)
- [Security roadmap](docs/SECURITY_ROADMAP.md)
- [Changelog](CHANGELOG.md)

## License

Copyright © 2026 Adrians Janovskis
([@janovsk1s](https://github.com/janovsk1s)).

Paka is licensed under [GPL-3.0-only](LICENSE), with the attribution and branding
terms in [ADDITIONAL_TERMS.md](ADDITIONAL_TERMS.md). See [NOTICE](NOTICE),
[ACKNOWLEDGMENTS.md](ACKNOWLEDGMENTS.md), and
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for authorship, AI-assistance,
and dependency information.
