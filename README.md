# Paka

Paka is an intentionally small, offline card-and-authenticator tool designed for
Light Phone III. It scans and renders common barcode formats and generates TOTP
codes without Google Play Services.

## Privacy

- Paka requests camera access only while scanning.
- Paka does not request internet access and contains no analytics or advertising.
- Card data stays inside the application sandbox.
- TOTP secrets are encrypted with AES-256-GCM using a key held by Android Keystore.
- Android cloud backup and device transfer are disabled.
- User-created portable backups are encrypted and authenticated offline with an
  AES-256-GCM key derived from the user's passphrase.
- TOTP codes copied to the clipboard are marked sensitive and cleared after 30 seconds.

Uninstalling Paka permanently removes data that was not exported first. If the
Android Keystore key is invalidated, on-device encrypted TOTP accounts can only
be recovered from a user-created encrypted backup. Paka cannot recover a forgotten
backup passphrase.

## Interaction

Tap `+` to scan. Long-press `+` for intentional manual entry. Long-press a card
to edit its stack and notes. These restrained secondary gestures are deliberate.
Lists show five entries at a time and snap vertically between full pages.
Vibration feedback can be enabled or disabled in settings. The scanner uses a
higher-resolution analysis stream, retries focus, detects sustained low light,
and can engage the camera light automatically. Rendered barcodes are decoded and
payload-checked before display; a bounded memory cache keeps common passes fast.

## Building

The project requires JDK 17 and the Android SDK declared by `compileSdk` in the
app module. A public release must be signed with a protected release key; release
builds are deliberately not debug-signed.

```sh
./gradlew test lint assembleDebug
```

LightOS SDK integration should use the official Compose design library and
emulator when those developer-program dependencies are available.

Before publishing to the Tool Library, the project owner should choose and add
an explicit open-source license.
