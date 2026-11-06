# Changelog

## Unreleased

- Kept all eight connections busy when one range is slow or retrying. New ranges previously started only after the oldest unfinished range was saved, so the other connections waited idle. Up to 24 ranges (192 MiB of temporary space) are now requested ahead; at most eight requests are open at once, and the `.part` file is still appended in order, so resume behaviour is unchanged.
- In an offline benchmark where every range had a different speed, the same 128 MiB file finished 1.3–1.55× faster than with 1.1.0, with identical output hashes. Real gains depend on how uneven YouTube's per-connection speeds are; if the phone already downloads at the full internet speed, no scheduling change can make it faster.

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
