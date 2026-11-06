# StreamSave for Android

A native Android app for saving public YouTube videos with source quality selection, background progress, pause/resume, and a persistent download list.

Project repository: <https://github.com/blsmohamme972-lab/video-downloder->

## Install and use

Install the APK from the repository's [**Releases**](https://github.com/blsmohamme972-lab/video-downloder-/releases) page on a 64-bit ARM phone running Android 8.0 or newer. If Android asks, allow installation from the app you used to open it. From then on, updates come from **Settings → Update StreamSave app** inside the app; there is no need to uninstall.

A copy installed earlier from a GitHub Actions test build, or from a file an AI assistant sent you, is signed with a one-time key and cannot be updated in place. Uninstall it once, then install from Releases.

1. Copy a YouTube video or Shorts link, or use YouTube's **Share** menu and choose **StreamSave**.
2. Paste the link and choose **Find video**. Available resolutions come from that video.
3. Choose **Best available**, a specific resolution, or **Audio only**.
4. Start the download. Permit notifications if you want progress and a pause control outside the app.
5. Use the **Library** tab to pause, resume, retry, open, share, or remove a download from the list.

On Android 10 and newer, finished files are saved in **Downloads/StreamSave**. On Android 8–9 they stay in the app's saved-files folder; use **Share** to copy them elsewhere before uninstalling. Removing a completed entry from the list leaves its saved file intact. Removing an unfinished download deletes its partial working files.

## What the app does

- Native dark interface with mint accents, readable quality rows, and Android sharing integration.
- Reads the actual formats YouTube exposes for the selected video, including higher resolutions when available.
- Saves original video/audio streams. Separate streams are merged into MKV without a lossy re-encode; already combined streams and audio-only downloads retain their source container.
- Uses up to sixteen parallel HTTP requests for supported direct media streams, and halves that number (minimum four) if the server reports too many requests. Each request downloads a bounded range of at most 8 MiB; verified pieces are appended in order, so the partial file remains safe to resume. When one range is slow or retrying, the other connections continue with later ranges, up to 32 ranges ahead (at most 256 MiB of temporary space), and the final ranges of each stream are split across all connections. The progress line shows how many connections are open. Sources that do not support reliable byte ranges use the normal downloader. Fragmented sources retain up to eight concurrent fragment requests.
- Keeps the exact selected video and audio formats while accelerating transfers. It does not lower the resolution, substitute a smaller codec, or re-encode the media to make the download appear faster. Actual throughput depends on the connection, YouTube, the selected format, and the phone. Files run in a queue to limit competing storage and memory use.
- Keeps `.part` files and fragment state on pause. Resuming refreshes expired media URLs while retaining the exact selected format IDs.
- Writes download history atomically and restores interrupted work as paused when the app reopens.
- Exports only completed media. A separate completion marker distinguishes a complete merge from a partially written file after a crash.
- Uses a foreground service, progress notification, and a bounded wake lock while downloading. Android's long-running service timeout is handled by checkpointing work for resume.
- Includes Python, FFmpeg, QuickJS, the JavaScript solver, and a verified yt-dlp engine. The first launch needs no engine download.
- Offers an engine update from Settings. It downloads from the official stable release, verifies SHA-256, validates the staged engine, and restores the previous engine if the new one fails.
- Requires no account sign-in, broad storage access, media proxy, or application backend. There are no ads or analytics in the app code.

## Practical limits

The app supports individual publicly available videos and Shorts. Live broadcasts, playlists, private/account-only media, and DRM-protected downloads are outside this version's scope. YouTube may require a bot check, change formats, impose regional restrictions, or refuse a request. Settings contains an engine updater for compatibility changes. No application can guarantee every video will download without errors.

Pause is available during transfer. The app lets merging and final file saving finish rather than interrupting those operations. An interruption by Android is handled with completion markers and resume state. If YouTube removes the exact selected format, the app asks for a new quality selection rather than appending a different format to an old partial file.

The displayed progress is for the current stream; a high-quality video can download video first and audio second. Temporary source streams and the exported copy can require about two to three times the final file size. Temporary files are removed after a successful export. MKV and some source codecs require a compatible media player.

## Study player — drawings, questions every minute, and your notes

Tap **Study** on a completed download, or **Study player** on the main screen for any video on the phone or in Google Drive. Add the video's *study pack* (a JSON file made by an AI that watched and listened to the video) with **Study pack**. While the video plays, the app draws animated arrows, circles, spotlights and labels in sync, shows captions, and **pauses after each part to ask a multiple-choice question about what was just shown**. Pause anywhere and tap **Add note** to keep your own timestamped notes. Packs, answers and notes are saved per video. See [STUDY_PACK.md](STUDY_PACK.md) for the format and the exact prompt to give an AI that reads the video from Google Drive.

## Update StreamSave from inside the app

From StreamSave **1.2.0 onward**, open the gear icon and tap **Update StreamSave app**. The app checks the repository's latest stable GitHub Release, automatically downloads the suitable production APK, checks its version and package identity, verifies that its signing certificate matches the installed app, and opens Android's system installation confirmation. Android may request a one-time **Install unknown apps** permission. Updates never require uninstalling StreamSave when signed with the original key; downloading data and saved files are preserved by the Android package manager.

This is separate from **Update download engine**, which refreshes yt-dlp and does **not** update the Android application. The menu cannot be added remotely to an existing version 1.1.0 binary; users need a compatible signed app update to get the button once, and can use it for later releases.

The in-app updater intentionally **does not** install GitHub Actions test-signed artifacts. To publish an actual update, maintainers must merge the code to main, configure the repository Actions secret `STREAMSAVE_SIGNING` (one value with the keystore, alias and passwords; the four separate `STREAMSAVE_KEY*` secrets also work) using the **same private signing identity** as the APK already installed, then run **Publish production-signed StreamSave APK update** from the Actions tab with a matching tag such as `v1.2.0`. The workflow also runs by itself on every push to `main` and publishes whenever `app/build.gradle` declares a version that has no release yet, so merging a version bump is enough. It generates the official ARM64 APK as a GitHub Release asset. Never commit a private keystore, key passwords, or a signing backup to the repository. If there is no published compatible release, the updater explains that no update is available.

**Test builds cannot update.** Every GitHub Actions run signs its test APKs with a new throwaway key, so a copy installed from a CI artifact can never be replaced in place. Uninstall it once, install the first release APK from the repository's **Releases** page, and use **Update StreamSave app** from then on. The release workflow refuses to publish when the version is not newer than the latest release or when the signing key differs from it. Rules for AI assistants that change this project are in [AGENTS.md](AGENTS.md).

## Build the project

Open this directory in Android Studio, or use a JDK 17 command line with Android SDK Platform 35 and Build Tools 35.0.0 installed. The Gradle wrapper is included.

First prepare the pinned engine files with Python 3.10 or newer. The script downloads official dependencies, verifies their SHA-256 hashes, and reproduces the audited ARM64 WebP repair. Generated payloads are kept out of Git. Gradle checks that the required files match the expected hashes before building.

```sh
python3 tools/prepare_engine.py
./gradlew assembleDebug
./gradlew testDebugUnitTest lintDebug
./gradlew connectedDebugAndroidTest
```

The project includes the recipe for an audited ARM64 native-library overlay for 16 KB load alignment. Its source, provenance and reproduction script are documented in `THIRD_PARTY.md`; the preparation step performs this repair automatically.

The build produces separate `arm64-v8a` and `x86_64` APKs. The latter is useful for an Android emulator. A 32-bit ARM APK is not included in this project configuration.

For your own signed release, create a private signing-properties file outside the project:

```properties
storeFile=/absolute/path/to/your-signing-key.jks
storePassword=YOUR_PRIVATE_PASSWORD
keyAlias=YOUR_ALIAS
keyPassword=YOUR_PRIVATE_PASSWORD
```

Then build:

```sh
./gradlew clean assembleRelease -PstreamsaveSigningProperties=/absolute/path/to/private.properties
```

Without that property, local release builds use the local development key. The private signing key for the supplied APK is not in the source archive. It is preserved in the separate `StreamSave-signing-backup.zip` for compatible future updates. Its properties file uses a key path relative to that file. An APK signed by a different key cannot update an installed copy in place.

## GitHub builds

The Android workflow prepares the verified dependencies, runs local tests and lint, and builds both ABI variants. Its downloadable APK artifacts use a development signing key and are labeled accordingly. They cannot replace the separately signed app supplied in chat without changing the installed signing identity. Production signing passwords and keys are never stored in this repository or its workflow.

To run the network-independent transfer checks locally:

```sh
python3 -m unittest discover -s tests -p 'test_*.py' -v
```

The checks use generated bytes on a local HTTP server. They verify concurrent range transfers, exact output bytes, version 1.0 partial-file resumes, interrupted writes, invalid or unsupported ranges, retries and native process cleanup. The controlled speed comparison is a fixture benchmark; it does not predict a particular YouTube connection's speed.

## Source map

| File | Responsibility |
| --- | --- |
| `MainActivity.java`, `Ui.java` | Native screens, quality picker, job controls, share intents, accessibility and window insets |
| `Engine.java` | YouTube link validation, metadata parsing, exact format selection, native download calls, completion markers and engine updates |
| `NativeTransfer.java`, `app/src/main/assets/streamsave_runner.py` | Owned download process, bounded parallel HTTP ranges, contiguous resume checkpoints and cancellation |
| `App.java` | Engine lifecycle, user actions, service startup, shared state notifications and media opening |
| `Job.java`, `JobStore.java`, `State.java` | Durable queue data, guarded transitions and atomic persistence |
| `DownloadService.java` | Foreground transfers, pause control, retries, progress notifications and Android timeout handling |
| `Publisher.java`, `DownloadProvider.java` | Completed-file publishing, export recovery and read-only media sharing |
| `app/src/test` | Local parser and quality-selection tests |
| `app/src/androidTest` | Device UI and durable-state tests |

See `THIRD_PARTY.md` and `LICENSE` for dependency attribution and source locations.
