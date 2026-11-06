# Changelog

## 1.2.1

- The first version intended for a production-signed GitHub Release. Installed copies that came from GitHub Actions test builds use a one-time key: uninstall once and install this release from GitHub Releases; later updates then work from **Update StreamSave app**.
- The updater's wrong-key message now explains that test builds cannot be updated in place, instead of telling users not to uninstall.
- The release workflow now refuses to publish when the tag or `versionCode` is not newer than the latest release, when the tag already exists, or when the APK is signed with a different key than the latest release. It also runs the downloader integrity tests and prints the signing certificate fingerprint.
- Added `tools/check_release_rules.py` (run in both workflows): the package name stays `app.streamsave`, versions are well-formed, no signing material is committed, and the updater's package/version/signer/HTTPS/host checks stay in place.
- Added `AGENTS.md` (and `CLAUDE.md`) with release and compatibility rules for AI assistants that change this project.

## 1.2.0

- Surgical Studio: play a local or downloaded video with timestamp-synchronized overlays from an AI-generated annotation ZIP. See `SURGICAL_STUDIO.md`.
- **Update StreamSave app** in Settings downloads the latest production-signed GitHub Release, verifies package, version and signing key, and opens Android's installer.

- Raised the connection limit for direct YouTube streams from 8 to 16. YouTube can limit each connection far below the home line's speed; more connections use more of the line. If the server answers 429 or 503, the limit is halved (minimum 4) and raised again by one connection every 10 seconds after 30 seconds without a refusal.
- Kept every connection busy when one range is slow or retrying. New ranges previously started only after the oldest unfinished range was saved, so the other connections waited idle. Up to 32 ranges (256 MiB of temporary space) are now requested ahead; the `.part` file is still appended in order, so resume behaviour is unchanged.
- Spread the end of each stream across all connections: the final ranges shrink (down to 1 MiB) instead of leaving the last few pieces to a few connections.
- The progress line now ends with the number of open connections, for example `(12 connections)`, or `(1 connection)` when a stream uses the standard downloader.
- Measured offline with generated data (not on YouTube or a phone): with each connection limited to 5 MiB/s, a 64 MiB file finished 12× faster than one connection (1.1.0: 7.5×). In a model of a 36 Mbit/s line where each connection averages about 245 KiB/s with uneven speeds, 1.1.0 reached 1.05–1.23 MiB/s and this version 2.6–3.1 MiB/s, with identical output hashes. Real results depend on YouTube and the line; no change can exceed the line's own speed.
- Fixed the test helper so progress hooks are attached even when the result list starts empty; the truncated-range progress check now runs.

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
