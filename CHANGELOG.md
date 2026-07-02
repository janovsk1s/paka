# Changelog

Notable changes to Paka are documented here.

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
