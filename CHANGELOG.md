# Changelog

Notable changes to Paka are documented here.

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
