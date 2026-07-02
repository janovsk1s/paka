# Changelog

Notable changes to Paka are documented here.

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
