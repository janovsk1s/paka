# Changelog

Notable changes to Paka are documented here.

## 0.15.3 — 2026-07-24

- Version-bump re-release of 0.15.2 (`versionCode` 55). No functional, security,
  or compatibility changes; built from the same source as 0.15.2.

## 0.15.2 — 2026-07-15

- Scanning a document that shows several barcodes now captures only the code
  aimed inside the on-screen guide square. A single visible code still scans
  instantly from anywhere in the frame.
- New developer setting: gesture hints, off by default. Pass, stack, PDF, and
  photo screens show a one-line caption naming the hold-to-edit action, in all
  supported languages. With the setting off, screens render exactly as before.
- 2FA setup QR labels are no longer percent-decoded twice, which corrupted
  issuer names containing literal percent sequences.
- Decoded TOTP key bytes are zeroed after code generation and validation, and
  the security policy's supported-versions table names 0.15.x as current.

## 0.15.1 — 2026-07-12

The last release line supporting Light Phone 2. Later versions ship only the
arm64 APK.

- QR and Aztec codes now display on a centred compact stage surrounded by
  screen background instead of an almost edge-to-edge white panel. The rendered
  symbol is unchanged: full quiet zone, exact whole-module grid, and decode
  verification before display.
- Developer settings gain a persistent app-wide light mode — black text on
  paper-white — for e-ink displays such as Light Phone 2. The camera, capture
  review, and full-screen document viewers keep their dark backdrop, and
  rendered codes remain dark modules on a white field in both modes.
- Release APKs now ship one CPU architecture instead of four, roughly halving
  the download. Stable releases attach two APKs: the arm64 build for Light
  Phone III and other modern phones, and a 32-bit `arm32` build for Light
  Phone 2. On Light Phone 2, scanning, in-app capture, and PDF passes are
  unavailable (no camera; Android 8.1); manual entry, code display, 2FA,
  photo import, and backups work.
- Interrupted-write temporaries are now removed together with their store and
  recognised by orphan cleanup; a narrow race between a pass save's document
  cleanup and a concurrently starting restore is closed; per-photo lock objects
  no longer accumulate for the process lifetime.
- Tagged-release verification now checks that stable tags carry a development
  version suffix and validates preview naming without hardcoding the cycle
  name.
- The README again documents clipboard TOTP handling and capture metadata
  stripping.
- Long translated menu labels now measure their fitted size before first
  draw instead of visibly shrinking into place when a page is revisited.

## 0.15.0 — 2026-07-11

*Codename: Paperlight.*

### Stable changes after Beta 3.3

- Tagged-release checks now finish before the owner creates a GitHub release;
  CI retains the unsigned comparison APK but no longer creates a bot-authored
  draft with an inconsistent preview title.
- About now identifies signed preview builds with the capture-preview label and
  `versionCode`, while keeping their production package and signing identity.
- Added production-path barcode render regression coverage across supported
  formats, panel widths, dense binary payloads, integer module grids, and cache
  reuse.
- Added a per-candidate Light Phone III test-results template so hardware,
  lifecycle, scanner, memory, and release sign-off status can be recorded
  without treating an unfilled checklist as evidence.
- Backup-only pass and 2FA stores now recover as writable stores. Recovery
  promotes the known-good encrypted generation without rotating a corrupt
  primary over it, and retains corrupt encrypted evidence until the next
  successful save.
- Portable restore now uses encrypted pre-restore snapshots and a durable
  PREPARED/ROLLED_BACK/COMMITTED journal. Process or power loss rolls back before commit or
  completes cleanup after commit before normal store loading and orphan removal;
  terminal cleanup remains durably retryable if snapshot removal is interrupted.
- Restore owns and clears decrypted PDF/photo arrays in the process-scoped
  coordinator; Back and cancel are blocked while those buffers are in use.
- Restored PDFs are opened and page 1 is rendered before any store changes on
  API 30+, matching interactive PDF import validation.
- Photo review, crop, display copies, and stored page geometry now agree on all
  eight EXIF orientations, including mirrored images on Android 8/8.1. Edited
  photos use one bounded decode and one encode; untouched imports retain their
  original encrypted bytes.
- The camera and torch now release before capture review, Back stays blocked
  while plaintext work is active, and nested sensitive screens share secure
  window ownership without clearing each other's screenshot protection.
- Decoded identity photos are owned only by the open viewer or stack, prefetched
  front/back there, and recycled on close or background; the pass list no longer
  retains them through a process-global cache.
- Data Matrix and Aztec now receive explicit quiet zones, linear formats keep
  full per-side quiet zones, rectangular symbols retain their natural aspect
  ratio, and DataBar is drawn directly on an integer module grid before decode
  verification.
- Aztec/PDF417 reject text that cannot be encoded byte-exactly as ISO-8859-1;
  scanning now prefers their original bytes over decoder text, and GS1
  verification preserves variable-field separators.
- Manual barcode entry verifies against the real pass display width, and hard-cut
  pages expose boundary-aware previous/next accessibility actions.
- Paka now follows the first supported device language on first run, falling
  back to English for unsupported locales. Developer settings offer an explicit,
  persistent LTR-only choice of English, Latvian, Estonian, Lithuanian, Finnish,
  Swedish, German, or Slovak; every menu, form, viewer state, warning, and
  accessibility action is localized.
- Menus and viewers now share full-slot touch targets, fixed centred top bars,
  bounded translated labels, pinned confirmation actions, and consistent
  loading/error/detail access without changing five-row paging or hard-cut swipes.
- Two-sided photo passes retain their optional page caption and immediate
  tap/swipe side changes without showing an edge scrollbar over the photo.
- Manual entry now uses content-appropriate keyboards and stable validation
  space; camera scanning has stronger monochrome framing, and document crop
  rotation is an explicit action instead of a hidden tap-anywhere gesture.

### Beta 3.3 — changes since `v0.15.0-beta.3.1`

- Rendered barcodes and QR codes now snap to an exact whole-module pixel grid,
  so every module is an identical width with no leftover quiet-zone pixels and
  no sub-pixel draw drift. Payload verification is unchanged.
- Raised the internal render cap so a future enlarged/zoom code view can be
  drawn at native resolution instead of upscaling.
- Added a security policy, security contact metadata, threat model, persistence
  format registry, device testing checklist, release checklist, and
  security/longevity roadmap.
- Added a CI APK-permission guard so debug, preview, and unsigned release APKs
  fail checks if internet, network-state, or any unexpected permission appears.
- Corrected the photo format registry to list every supported image header
  (JPEG, PNG, GIF, WebP, HEIF/HEIC, AVIF).
- Hardened the APK verification scripts: `aapt2` fallback and version-ordered
  build-tools selection.

### Beta 3.1 — changes since `v0.15.0-beta.3`

- Kotlin updated to 2.4.0 (dependency-verification checksums regenerated); no behavior change.
- Battery/lifecycle pass: work now pauses when Paka is backgrounded instead of relying on Compose keeping effects alive.
  - The per-second 2FA code tick and the capture screen's focus-retry loop stop while Paka is not in the foreground.
  - An open PDF releases its decrypted session and native memory when backgrounded (like photos already do) and reopens on return.
  - Stack previews now recycle their rendered PDF page bitmaps instead of leaking them until garbage collection.
  - The scanner and capture light label no longer reads "on" after returning from the background, where the torch was actually switched off.

### Beta 3 — changes since `v0.15.0-beta.2`

- Chosen/imported photos now use the same review and crop screen before they are saved, including two-photo front/back batches.
- Crop corners use larger invisible touch targets so resizing feels snappier on the Light Phone III.
- While cropping, tapping the photo rotates the working image 90° counter-clockwise with an immediate hard-cut preview, and rapid repeated taps no longer crash the crop screen.
- Details, manual entry, settings, and submenus now share the app typography more consistently, with an optional official-font toggle in hidden Developer settings.
- The bottom controls were sharpened and rebalanced without changing their original sizes: gear, plus, barcode, and code/asterisk glyphs now snap more cleanly to the same visual envelope.
- The original Paka gear is again the default settings icon; the Light SDK gear is available only as an optional Developer setting.
- The transitive `ACCESS_NETWORK_STATE` permission from CameraX/Media3 is explicitly removed from Paka's merged manifest; the APK should request camera only.
- Before stable 0.15.0, beta 3 still needs a hardware battery/lifecycle pass covering camera, torch, brightness, TOTP ticking, and PDF/photo rerender cleanup.

### Added

- Document photos can be taken directly inside Paka when creating a photo pass, alongside choosing existing images.
- The capture screen reuses the scanner's camera: tap to focus, a manual light toggle, and a retake/use confirmation before anything is stored.
- Passes inside a stack can be reordered from a pass's Details: a sort action next to the stack field opens the familiar reorder screen scoped to that stack.
- Captured photos can be cropped before saving: drag the scanner-style corner frame or move the whole selection, then confirm. Cropping happens entirely in memory before encryption, and the buffers of the uncropped capture are zeroed.
- Chosen/imported photos now use the same review and crop screen before they are saved, including two-photo front/back batches.
- Crop corners use larger invisible touch targets so resizing feels snappier on the Light Phone III.
- While cropping, tapping the photo rotates the working image 90° counter-clockwise with an immediate hard-cut preview.
- The capture screen keeps nudging centre focus on the scanner's retry cadence, so close-up documents settle without repeated manual taps; a tap-to-focus still takes priority.

### Security

- Captured frames travel sensor → RAM → encrypted store; no temporary file, media-store entry, or gallery thumbnail is ever created.
- Captures are re-encoded in memory before storage, which bakes in the sensor rotation, bounds the longest edge, and strips all camera metadata (EXIF timestamps and device identifiers).
- The capture screen blocks screenshots and screen recording while a document is in front of the camera.
- Every intermediate capture buffer is zeroed, including on failure paths.
- The transitive `ACCESS_NETWORK_STATE` permission from CameraX/Media3 is explicitly removed from Paka's merged manifest; the APK should request camera only.

## 0.14.0 — 2026-07-04

### Security

- New portable backups use 600,000 PBKDF2-HMAC-SHA256 rounds; existing 210,000-round backups remain restorable.
- New backup passphrases require at least 12 characters, while restore continues accepting older 8-character passphrases.
- New on-device encryption keys prefer StrongBox with a safe fallback and require an unlocked device on Android 15+, where Android's earlier key-loss and authorization bugs are fixed.
- Gradle dependency verification pins SHA-256 checksums for the complete build graph.
- CI actions are pinned to immutable commits and the Gradle wrapper is validated before use.
- Photo imports are capped at 10 MB each and reject unsupported headers, corrupt decodes, excessive dimensions, and decompression-bomb-sized pixel counts.
- Imported photos are copied into Paka only as AES-256-GCM ciphertext; plaintext exists only while validating, displaying, or creating/restoring an encrypted backup.
- Photo files use envelope encryption: a random data key encrypts the bytes in-process while the hardware Keystore key only wraps that data key, so StrongBox-backed devices decrypt photos quickly; existing files migrate on first read.
- 2FA stores from before the versioned layout are re-encrypted with the authenticated format on first load instead of waiting for the next edit.
- PDF and photo imports read into bounded buffers whose every intermediate copy is zeroed, including on failure; the photo display-copy compression buffer is zeroed as well.

### Reliability

- Restore skips pass types from newer Paka versions and reports how many will be dropped, instead of rejecting the whole backup.
- Atomic store replacement now flushes the replacement directory after rename and flushes the previous-generation backup file.
- Pass, 2FA, and PDF recovery share one tested primary/backup selection path without modifying corrupt evidence.
- CI now builds the minified release variant in addition to debug, tests, and lint.

### Development

- Gradle build caching and configuration caching reduce repeat build time.
- Dependabot watches both Gradle libraries and GitHub Actions for updates.
- Added tests for atomic replacement, corrupt-primary recovery, corrupt-both preservation, PBKDF iteration headers, and legacy-backup compatibility.
- Added compact-backup payload tests for round trips, truncation, trailing data,
  per-entry limits, and stable-compatible schema selection.
- Updated AndroidX, Compose, and CameraX to current stable releases (Compose BOM 2026.03.01, CameraX 1.6.1, core-ktx 1.18.0, lifecycle 2.10.0, activity-compose 1.12.4, ZXing 3.5.4, OkapiBarcode 0.5.6) and regenerated the dependency-verification checksums.
- Added bounded-import reader tests for exact limits, growth boundaries, short reads, and oversize rejection.
- MainActivity's screens were extracted verbatim into focused files (list paging, home lists, settings, backup, manual entry, details, pass viewing) with identical behavior and navigation.
- The toolchain moved to Gradle 9.6.1, AGP 9.2.1 with built-in Kotlin, and compileSdk 37; targetSdk stays 36 so runtime behavior is unchanged.
- Robolectric now tests the recovery paths end to end: legacy plaintext pass migration, corrupt-primary recovery, immediate re-encryption of pre-versioned 2FA stores, and restore success/rollback in the write coordinator.
- detekt runs in CI against a recorded baseline, so only newly introduced findings fail the build.
- A tagged-release workflow runs the full check suite for v* tags, uploads the unsigned release APK, and opens a draft GitHub release; signing stays local.
- CI now also runs on pushes to preview/ and fix/ branches, not only main and pull requests.
- Added F-Droid-compatible fastlane metadata: title, descriptions, screenshots, and a release changelog.

### Added

- Encrypted photo passes can store one or two imported images for items such as photo ID fronts/backs or proof of insurance.
- Imported photo originals use a dedicated Android Keystore key and are included in encrypted portable backups.
- Viewing prefetches both sides from pre-scaled encrypted display copies into a session cache, so opening a pass and flipping sides is immediate; decoded photos are released when Paka leaves the foreground or memory runs low.
- The pass list quietly pre-decodes photo passes on the visible page as it appears, so even their first open of a session is immediate.
- A fitted photo flips sides on an immediate tap; pinch zooms with finger-anchored panning, while PDF double-tap zoom remains unchanged.
- Stacks prefetch both photo sides, show them at their natural aspect ratio, and weave them into the existing tap cycle before moving to the next pass.
- Photo passes show page numbers below the photo and PDF passes in the top bar, with a developer-options toggle to hide both.
- The manual add screen lists QR, PDF document, and photo pass together on its first page.
- Photo-containing backups use a compact binary payload instead of Base64-expanded JSON; barcode-only and PDF-only backups retain stable-compatible schemas.

### Changed

- Backup passphrase entry uses the same focused full-screen text editor as manual entry, with masked values on the export and unlock forms; passphrases are saved exactly as typed.
- Exporting a backup that contains photo passes notes that restoring requires Paka 0.14 or newer.
- A pass opened on its own now shows its barcode at the same height as when it is part of a stack.

## 0.13.0 — 2026-07-02

### Added

- Encrypted PDF passes imported through Android's permissionless document picker.
- RAM-only PDF viewing on Android 11+ using `memfd` and the platform `PdfRenderer`.
- Multi-page hard-cut paging, pinch/pan, and instant anchored double-tap zoom.
- A debounced screen-sized rerender keeps settled zoomed text sharp without large full-page bitmaps.
- PDF passes can be renamed, stacked, reordered, backed up, restored, and deleted like barcode passes.
- A dedicated third Details page can retain up to two external references of any file type and open them through Android's document provider.

### Security

- PDFs are validated by opening and rendering page 1 before they can be saved.
- Each PDF is capped at 10 MB and stored under a dedicated AES-256-GCM Android Keystore key.
- Plaintext PDF bytes exist only briefly in RAM and anonymous `memfd`; no plaintext cache file is created.
- Encrypted backup schema 2 embeds referenced PDFs and restores documents, passes, and 2FA accounts transactionally.
- Orphaned encrypted PDF blobs are removed only after a healthy pass-store load.
- Referenced files remain in their original external provider; Paka does not copy, encrypt, or include them in portable backups.

### Changed

- Details fields now use the same focused full-screen text editor as manual entry, including multiline notes.
- The reference picker accepts two files in one operation, with individual open, replace, and remove controls afterward.
- PDF pages open edge-to-edge within the viewer while preserving the complete page aspect ratio.
- The default page is locked in place; free panning starts only after pinch or 3× double-tap zoom.
- Double-tap returns a zoomed document to its complete-page view.
- The multi-page indicator sits on a subtle scrim so it stays readable over white page content.

### Fixed

- Transparent PDF page backgrounds now render as white paper instead of disappearing into Paka's black canvas.
- PDF pages share a stable top content edge with opened barcode passes instead of being vertically centered by page height.
- Landscape PDF pages are vertically centered so their shallow layout does not appear top-heavy.
- Page swiping works on multi-page PDFs: the zoom layer now consumes only pinches and zoomed-in pans instead of every drag.
- Closing a PDF pass while a page was still rendering no longer races the renderer teardown.
- PDF open and render failures show a message instead of crashing the app.
- A cancelled PDF open can no longer leak the in-memory document session.
- Stack previews render a PDF's first page once per visit instead of re-decrypting the document every time it becomes visible.
- PDF import is blocked in demo mode so real encrypted blobs cannot be orphaned by temporary demo passes.

## 0.12.9 — 2026-07-02

### Changed

- Pass names can now be edited directly on the Details screen.
- A right-aligned delete action on Details now requires explicit confirmation.
- Reorder is movement-only; rename and delete actions were removed from it.
- Reorder uses balanced outer spacing so its movement arrows sit naturally at the right edge.
- Reorder arrows keep a consistent white treatment, including non-interactive boundary arrows.
- Ordered encrypted writes now use a process-scoped coordinator so Activity teardown cannot discard queued edits.
- Details long-press is available only after opening a pass, not from the main list.

## 0.12.8 — 2026-07-02

### Added

- Developer demo mode with freshly generated synthetic passes and 2FA accounts.
- A subtle `Paka · demo` title so temporary data cannot be mistaken for the real store.

### Security

- Demo data exists only in memory: adding, editing, reordering, or deleting it never writes to the encrypted real stores.
- Backup export and restore are unavailable while demo mode is active.
- Enabling demo mode clears a Paka-owned real 2FA code from the clipboard.
- Screenshots of the synthetic 2FA list are allowed in demo mode, while manual 2FA entry, scanning, backups, and the real 2FA list remain protected.

## 0.12.7 — 2026-07-02

### Added

- GitHub Actions checks for unit tests, Android lint, and debug builds.
- Unit tests for barcode payload validation.

### Changed

- Encrypted pass and 2FA storage now loads and saves away from the UI thread.
- Backup restoration performs its encrypted disk work away from the UI thread.
- Manual pass and 2FA entry now use the same five-item hard-cut paging behaviour as the rest of Paka.
- Manual passes are saved only after the selected barcode format renders and verifies its payload exactly.
- Clipboard cleanup safely retries when Paka returns to the foreground and never clears newer clipboard content.
- Interactive elements expose consistent button semantics to accessibility services.
- Removed unused Material icon and Navigation Compose dependencies without changing Paka's navigation.

### Fixed

- Removed the inconsistent smooth/free scrolling from manual-add screens.
- Prevented invalid or non-renderable manually entered barcodes from being stored.
- Fixed 2FA clipboard cleanup silently failing after Paka moved to the background.
