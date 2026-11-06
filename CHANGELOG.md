# Changelog

## 1.1.0

- Added up to eight parallel, bounded HTTP requests for eligible direct YouTube streams while preserving the exact selected formats.
- Continued existing version 1.0.0 partial files from their saved byte length, with an overlap check against the refreshed source.
- Validated range responses and known-size fallback results before treating a stream as complete.
- Added supervised native-process ownership, cancellation deadlines and descendant cleanup before resume or removal.
- Kept native manifest downloads and unknown-size sources on their existing download paths.
- Displayed fractional per-stream progress, such as 0.5%, more accurately.
- Added reproducible, checksum-pinned engine preparation and GitHub Actions build/verification.
- Fixed incomplete ARM64 resource packaging by disabling the affected optional Android resource optimization and verifying every ABI output.

The update preserves package identity, release signing identity, quality selections and download history. See `VALIDATION.md` for measured results and testing limits.

## 1.0.0

Initial native Android release with source-driven quality selection, a foreground download queue, pause/resume, durable history, original-stream merging, completed-file publishing and an extractor updater.
