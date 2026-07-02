# Paka — AI Project Handoff

Read this file before changing Paka. It records the product decisions that are
easy to mistake for bugs or unfinished work. Improve the app from its current
state without sanding away its identity.

## What Paka is

Paka is a small, offline pass wallet and TOTP authenticator designed for the
Light Phone III. It is an independent community project, not an official Light
Phone or LightOS product. Its interface intentionally follows the quiet,
high-contrast character of LightOS without copying proprietary source or assets.

The product goal is not to become a general Android wallet. Paka should remain
fast, legible, monochrome, local-first, and unusually restrained.

Current baseline: **0.12.8**, 2026-07-02. Consult `CHANGELOG.md` for recent work.

## Non-negotiable design language

- Use a pure black background, white primary content, and restrained grey
  secondary content. Do not introduce colour, gradients, cards, shadows, or
  Material surfaces.
- Keep typography light and spacious. Main list text defaults to 30 sp; compact
  centred page titles use 16 sp. Preserve the existing hierarchy rather than
  applying generic Android typography.
- Do not add ripples, bounce, easing, crossfades, or smooth scrolling. Page
  transitions are immediate hard cuts.
- Lists use exactly five fixed row slots per page. A sixth item starts the next
  page. Empty slots remain empty; rows should not redistribute unpredictably.
- Vertical gestures change one full page only after crossing the threshold.
  Haptic feedback occurs only when a real page change is available—never when
  swiping at an edge or on content that cannot scroll.
- Scroll/page indicators belong at the outside edge, away from counts and TOTP
  timers. The thin track must run through the centre of the thicker thumb.
  Never show an indicator when there is only one page.
- Top bars use a centred, capitalised screen title and the custom back glyph at
  the far left. Back means one level back, not “jump to home.”
- Bottom controls and reorder controls use the existing custom-drawn glyph
  language. Avoid importing a mismatched icon pack for convenience.
- Hidden long-press actions are intentional. Long-press `+` opens manual entry;
  card long-press reveals details. Preserve these unless the owner explicitly
  changes the interaction model.
- Lone barcode and QR displays do not vibrate on ordinary taps. Haptics belong
  to actual actions, long-press discovery, and successful page changes.
- Hardware photographs and official LightOS screens supplied by the owner are
  the strongest visual references. Judge spacing on the Light Phone III, not
  only an Android emulator.

## Navigation behaviour

The current explicit route/state structure in `MainActivity.kt` is intentional.
Do not migrate it to Navigation Compose or redesign the navigation unless the
owner explicitly asks. Small internal extraction is fine if behaviour remains
identical.

- Android back and the drawn back arrow must agree and return one screen level.
- Leaving the app returns Paka to its pass home by default.
- “return home” can be disabled in Developer options.
- Developer options are intentionally hidden behind a triple tap in About.
- App/task backgrounds are black so reopening a code never produces a grey flash.

## Pass and barcode invariants

Pass payload fidelity is critical. A visually similar QR, Aztec, PDF417, or
barcode is not sufficient: scanners must receive the exact original payload.

- Never derive a replacement payload from what a barcode looks like.
- Every rendered code must be decoded again and compared with its source payload
  before it is displayed or saved.
- Binary Aztec/PDF417 payloads must retain byte-exact ISO-8859-1 behaviour.
- Keep integer module scaling, proper quiet zones, high contrast, and minimal
  unused white space. Do not smoothly scale a finished bitmap.
- Manual pass entry cannot save until the selected format renders and verifies.
- Keep barcode rendering and verification off the UI thread and use the bounded
  cache for frequently viewed passes.
- Scanner changes must be tested against dense Aztec, GS1 DataBar Expanded,
  EAN/UPC, damaged print, glare, and genuinely low-light samples. A sharp camera
  preview does not prove that decoding succeeds.

## Security and privacy invariants

- Paka has no internet permission, analytics, advertising, accounts, or cloud
  service. Do not add any silently.
- Passes and TOTP accounts are encrypted separately with AES-256-GCM keys held
  by Android Keystore. Atomic replacement and recovery behaviour must survive
  failed writes and corrupted primary files.
- Encrypted storage reads/writes and backup restoration stay off the UI thread.
- Portable backups remain authenticated, encrypted offline, and protected by a
  user passphrase. Never log pass payloads, TOTP secrets, plaintext backups,
  derived keys, or clipboard codes.
- Real TOTP screens, manual secret entry, TOTP scanning, and backup flows use
  screenshot protection. Pass barcodes remain capturable because pass image
  export is a legitimate use case.
- Copied TOTP values are marked sensitive. Clear only a clipboard value still
  owned by Paka; never erase content copied later by another app. Android 10+
  focus restrictions require retrying safely when Paka resumes.
- Android cloud backup and device transfer stay disabled.
- Never commit `keystore.properties`, release keystores, passwords, private
  signing material, real passes, TOTP seeds, or photographs containing live
  credentials.

## Demo mode

Developer demo mode exists so the owner can show and photograph Paka safely.

- Each activation generates fresh synthetic passes and TOTP secrets.
- Demo content exists only in memory. Add, edit, reorder, stack, and delete
  operations must never reach the real encrypted stores.
- Backup export/restore is unavailable during demo mode.
- Enabling demo mode clears a still-owned real Paka clipboard code.
- `Paka · demo` remains visible so synthetic content is never mistaken for real
  data.
- Screenshots are allowed for the synthetic TOTP list only. Other sensitive
  entry/scanning/backup flows remain protected.
- Turning demo mode off reveals the untouched real store.

## Performance expectations

- Never perform encryption, file I/O, backup work, barcode rendering, or heavy
  decoding on the main thread.
- Storage mutations are optimistic but ordered. Preserve generation checks so a
  failed or restored store cannot be overwritten by an older queued save.
- Pre-render the visible and next likely pass when useful, but keep caches
  bounded and do not delay initial interaction.
- Avoid broad dependency upgrades in the same commit as behavioural changes.
  Upgrade and verify CameraX, Compose, Kotlin, and AGP separately on hardware.

## Important files

- `app/src/main/java/com/paka/app/MainActivity.kt` — screen state, routing, lists,
  manual entry, settings, backup UI, and pass/code presentation.
- `app/src/main/java/com/paka/app/Ui.kt` — colours, haptics, click semantics, and
  shared custom interaction modifiers.
- `app/src/main/java/com/paka/app/Barcodes.kt` — barcode validation, exact render,
  cache, GS1 conversion, and render verification entry points.
- `app/src/main/java/com/paka/app/BarcodePayloadVerifier.kt` — decoded payload
  comparison rules.
- `app/src/main/java/com/paka/app/Scanner.kt` — CameraX analysis, focus, low-light,
  and torch behaviour.
- `app/src/main/java/com/paka/app/CardStore.kt` and `SecureStore.kt` — encrypted
  on-device stores.
- `app/src/main/java/com/paka/app/AtomicStore.kt` — crash-safe file replacement.
- `app/src/main/java/com/paka/app/BackupStore.kt` — encrypted portable backups.
- `app/src/main/java/com/paka/app/Totp.kt` — URI parsing and RFC 6238 generation.
- `app/src/main/java/com/paka/app/DemoData.kt` — synthetic in-memory demo data.
- `app/src/main/java/com/paka/app/Prefs.kt` — intentionally small local settings.
- `README.md`, `CHANGELOG.md`, `NOTICE`, and `ADDITIONAL_TERMS.md` — public-facing
  behaviour, history, authorship, and licensing.

## Working rules for future AI agents

1. Inspect `git status`, the active branch, this file, and `CHANGELOG.md` first.
2. Work on a separate feature branch unless the owner explicitly requests a
   direct update. Preserve unrelated user changes.
3. State assumptions before making any product-level reinterpretation.
4. Do not “modernise” navigation, add generic Material UI, expose hidden
   gestures, or add animation simply because those are common Android patterns.
5. Keep security boundaries explicit. Demo data, real data, clipboard data, and
   backup data must not leak into one another.
6. Add focused tests for parsers, validators, encryption, migration, and payload
   fidelity. Visual changes also require real-device inspection.
7. Before handing off a release, bump both `versionCode` and `versionName`, update
   `CHANGELOG.md` and the README release number, then run:

   ```sh
   ./gradlew test lint assembleDebug assembleRelease
   ```

8. Verify the release APK with Android `apksigner` and confirm it uses the same
   release certificate. The known certificate SHA-256 is:

   ```text
   098fbb0a5455ec00dbafae93f16bff74b048e5bde7a824fac9ecf42effad0019
   ```

9. Do not push, publish a release, rewrite history, or rotate signing material
   without explicit owner approval.
10. Record meaningful user-visible or security changes in `CHANGELOG.md`.

When a proposed improvement conflicts with this document, pause and explain the
trade-off. The owner’s explicit instruction wins; otherwise preserve Paka’s core:
exact codes, quiet pages, deliberate gestures, local security, and a Light way.
