# Changelog

## 2.1.0

- **On-device voice-over.** A study pack can include voice-over lines (`narration`). The Study player speaks each line at its time with Supertone's Supertonic 3 voice, created on the phone with no internet after a one-time 383 MB voice download. That download comes directly from Supertone, at a pinned revision checked by SHA-256.
  - While a line is spoken, the video's own sound is lowered. If a line runs past its slot, the video waits for it. Seeking, a question or pausing stops the voice.
  - Lines are prepared in the background, the upcoming ones first, and cached.
- New **Voice** toggle next to Drawings, Questions and Captions, and **More → Voice-over settings**:
  - voice F1–F5 or M1–M5;
  - quality 5, 8 or 12 steps;
  - speed;
  - download or remove the voice model.

  Defaults: **F1, English, 12 steps, 1.30×**.
- The Java pipeline follows Supertone's official SDK exactly. Locally, with the same random noise, its audio was identical to the Python SDK's (difference 0.0).
- The APK is about 13 MB larger because of ONNX Runtime; the voice model itself is never part of an update.

## 2.0.2

- **Faster app updates:** Update StreamSave app downloads the APK over six parallel range requests (like the video engine) instead of one connection, which is often much slower to GitHub's servers. Every request is still HTTPS to GitHub only, each part is checked, and the whole file must match GitHub's SHA-256 before Android is asked to install it. Servers that ignore ranges fall back to one request.
- The speed-up applies to updates downloaded by 2.0.2 and later; this update itself still arrives through the previous downloader.

## 2.0.1

- About StreamSave now describes the Study player and the study library.
- First update delivered by the automatic release: merging this version bump published it, and installed 2.0.0 copies update with Update StreamSave app.

## 2.0.0

- New **Study player** replaces Surgical Studio. It plays a download or any local or Google Drive video and reads a *study pack* (JSON or ZIP, schema version 2; version 1 ZIPs still open):
  - animated drawings: arrows that draw themselves, pulsing rings, boxes, spotlights, labels, notes and images, with keyframe tracking;
  - captions;
  - multiple-choice questions, each tied to a part of the video (normally one minute). Playback pauses when it passes that part, then shows right/wrong with an explanation, **Continue**, or **Watch this part again**. Seeking past a question does not trigger it. Answers and the score are saved; **Retake all questions** resets them.
- Your own notes: **Add note** at any moment, and the note appears on the video at that time; **Notes** lists them to jump to or delete. Notes survive replacing or removing a pack.
- The player remembers where you stopped, can be opened by sharing a video to StreamSave, and gives the video the whole screen in landscape.
- Releases publish themselves: merging a version bump to `main` builds, signs and publishes the update, which the app then offers under **Update StreamSave app**. Nobody has to start a workflow or add secrets again.
- **Study library:** an AI assistant that reads the video from Google Drive publishes its pack to `study-packs/` in this repository. The player downloads the matching pack by itself (by YouTube id or file name) when the video opens, or with **Update pack**, so no files are ever handled by the user. Newer pack versions replace older ones, saved answers are kept, and CI rejects any invalid published pack. [STUDY_PACK.md](STUDY_PACK.md) has the format and the prompt.

## 1.2.1

- The first version intended for a production-signed GitHub Release. Installed copies that came from GitHub Actions test builds use a one-time key: uninstall once and install this release from GitHub Releases; later updates then work from **Update StreamSave app**.
- The updater's wrong-key message now explains that test builds cannot be updated in place, instead of telling users not to uninstall.
- The release workflow now refuses to publish when the tag or `versionCode` is not newer than the latest release, when the tag already exists, or when the APK is signed with a different key than the latest release. It also runs the downloader integrity tests and prints the signing certificate fingerprint.
- Added `tools/check_release_rules.py` (run in both workflows): the package name stays `app.streamsave`, versions are well-formed, no signing material is committed, and the updater's package/version/signer/HTTPS/host checks stay in place.
- Added `AGENTS.md` (and `CLAUDE.md`) with release and compatibility rules for AI assistants that change this project.
- The release workflow accepts one signing secret, `STREAMSAVE_SIGNING`, instead of four, and stops before building if the key cannot be restored (it can no longer fall back to a throwaway debug key).

## 1.2.0

- Surgical Studio: play a local or downloaded video with timestamp-synchronized overlays from an AI-generated annotation ZIP (replaced by the Study player in 2.0.0).
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

The update preserves package identity, release signing identity, quality selections and download history.

## 1.0.0

Initial native Android release with source-driven quality selection, a foreground download queue, pause/resume, durable history, original-stream merging, completed-file publishing and an extractor updater.
