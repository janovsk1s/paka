# Device Testing Checklist

Use this checklist on a real Light Phone III before promoting a capture/photo
preview to stable. Emulator results are useful, but visual spacing, camera
behavior, torch state, and battery feel must be judged on hardware.

For each candidate, copy [DEVICE_TEST_RESULT_TEMPLATE.md](DEVICE_TEST_RESULT_TEMPLATE.md),
leave untested rows marked `NOT RUN`, and commit or attach the completed result
to the release record. The checklist describes what to test; a completed result
records what was actually tested.

## Before testing

- Install the release-signed APK for the candidate build.
- Confirm it upgrades over the previous stable or preview.
- Confirm the APK signing certificate SHA-256 matches `AGENTS.md`.
- Confirm requested permissions are expected. For 0.15, the APK should request
  camera only.
- Run `tools/verify_release_apk.sh path/to/Paka-vX.apk` when the candidate APK
  is available locally.
- Use synthetic passes, synthetic TOTP secrets, and non-sensitive documents.

## Idle and lifecycle

- Open Paka on the pass list and leave it idle for 10 minutes.
- Open the 2FA list and leave it idle for 10 minutes.
- Background Paka from the pass list, 2FA list, scanner, capture screen, PDF
  viewer, and photo viewer; return to each.
- Confirm Paka returns home on close by default.
- Disable return-home in Developer options and confirm the previous screen
  returns correctly.
- Confirm app/task background remains black when reopening from a sensitive
  screen.

## Battery and background work

- Verify the TOTP tick stops while Paka is backgrounded.
- Verify capture focus retry stops while Paka is backgrounded.
- Verify open PDF sessions release on background and reopen cleanly on return.
- Verify photo viewer/session memory is released when leaving foreground.
- Open stacks containing PDFs/photos and move through them repeatedly; watch for
  sluggishness or growing memory pressure.

## Camera, scanner, and torch

- Scan dense Aztec, QR, PDF417, GS1 DataBar Expanded, EAN/UPC, and damaged or
  glared print samples.
- Test genuinely low light; a sharp preview is not enough, decoding must
  succeed.
- Toggle the camera light manually and background/return.
- Confirm the light label never says "on" when the torch has been switched off
  by lifecycle.
- Test auto-light on and off from Developer options.

## Document capture and import

- Capture a one-sided document photo.
- Capture a two-sided document photo.
- Choose/import one photo from the picker.
- Choose/import two photos from the picker.
- Crop each flow, including all four corners and moving the whole frame.
- Use the explicit Rotate action repeatedly during crop; confirm no crash and
  that every preview change is an immediate hard cut.
- Confirm uncropped/unused bytes are not written to the gallery or temporary
  files.

## Localization and layout

- Switch through English, Latvian, Estonian, Lithuanian, Finnish, Swedish,
  German, and Slovak from Developer options; confirm the language page remains
  open after each switch and Back still returns one screen level at a time.
- Restart Paka in every language and confirm the selection persists.
- Confirm Hebrew, Arabic, and other unsupported languages are never listed and
  every supported language remains left-to-right.
- With both the official and fallback fonts, inspect Baltic, Nordic, German,
  and Slovak diacritics for missing or substituted glyphs.
- Inspect every menu, form, confirmation, empty/error state, viewer caption,
  scanner control, and accessibility action for untranslated or clipped text.
- Confirm lists retain five fixed row slots per page in every language, a sixth
  item starts the next page, and the edge indicator never overlaps counts or
  timers.
- Confirm a vertical drag changes only one page, and edge/non-scrollable drags
  do not vibrate.

## Pass viewing

- Open barcode, QR, PDF, one-sided photo, and two-sided photo passes.
- In a stack, cycle through barcode/PDF/photo entries and confirm transitions
  are immediate.
- Confirm fitted two-sided photos flip sides on a single tap.
- Confirm photo zoom is pinch-only and does not wait for double/triple tap.
- Confirm PDF double-tap zoom still works as an immediate hard cut.

## Brightness, clipboard, and screenshots

- Open a barcode/code with forced brightness enabled; leave and confirm display
  brightness restores.
- Disable forced brightness in Developer options and confirm it stays off.
- Copy a TOTP value, wait for the clear timeout, then verify Paka only clears a
  clipboard value it still owns.
- Confirm screenshot protection remains active for real TOTP, secret entry,
  scanning, and backup flows.
- Confirm pass barcodes remain capturable.

## Accessibility

- With TalkBack enabled, move to the next and previous hard-cut list pages and
  confirm items after the first five remain reachable.
- Confirm the current page and unavailable edge actions are announced without
  adding haptics at an edge.
- Confirm custom back, bottom-bar, and reorder glyphs have meaningful labels and
  disabled reorder actions are exposed as unavailable.
- Confirm the intentional long-press actions for manual entry and pass Details
  remain discoverable through accessibility actions.
- Confirm camera/capture light controls announce their actual on/off state.

## Backup and restore

- Export and restore a backup with barcode-only passes.
- Export and restore a backup with PDFs.
- Export and restore a backup with one- and two-sided photos.
- Confirm referenced external files remain links and are not copied into Paka
  backups.
- Restore an older stable-compatible backup.
- Try a wrong passphrase and confirm data is untouched.

## Release checklist

- Follow [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md) before tagging or
  publishing a GitHub release.
- `CHANGELOG.md` describes user-visible and security changes.
- README release/beta text is current.
- `versionCode` and `versionName` are correct.
- GitHub release is titled consistently, marked pre-release when appropriate,
  not marked latest unless stable, and has the APK asset attached.
- Published APK digest and release-certificate digest are recorded.
