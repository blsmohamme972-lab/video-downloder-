# StreamSave 1.0.0 — delivery validation

Verified on 6 November 2026. The supplied file is an independently signed Android APK, not a Play Store publication.

## Delivered package

| Property | Verified value |
| --- | --- |
| File | `StreamSave-1.0.0-arm64.apk` |
| Package | `app.streamsave` |
| Version | 1.0.0 |
| Size | 56,027,703 bytes |
| Device architecture | ARM64 (`arm64-v8a`) |
| Minimum Android | Android 8.0 / API 26 |
| Target API | 35 |
| Debuggable | No |
| APK signature | Verified, v2 |
| ZIP alignment | Verified |
| Bundled yt-dlp | 2026.10.26, official release hash verified |

APK SHA-256:

`ebc12de6ba3899251e97c6d1884bca54b873d71785d2d227ad66eb8cbf7e9334`

Signing-certificate SHA-256:

`2fea903c5f282f63bb6562c27de65a09579305f4325d370227eca600fed48f3d`

## Checks that passed

| Area | Evidence |
| --- | --- |
| Release and debug compilation | Successful builds using JDK 17, Gradle 8.10.2, Android Gradle Plugin 8.7.3 and SDK 35 |
| Android lint | Zero errors and zero warnings in the final release and debug checks |
| APK freshness | Clean output rebuild; release ARM64 and x86_64 APKs contain identical latest DEX, including the queue-ownership and quality-reveal fixes |
| JVM tests | 6 passed: canonical links, deceptive hosts, format filtering, exact stream IDs, fallback selection, portrait and live metadata |
| Android device tests | 15 unique tests passed across the final regression run and preceding unchanged UI cases |
| Android native runtime | Bundled CPython/yt-dlp initialized; yt-dlp version matched; FFmpeg executed; QuickJS version matched and executed JavaScript producing `2` |
| State persistence | Pause and exact format IDs survived store recreation; stale queue/progress actions were rejected; returned records could not mutate stored state |
| Finalization races | Saving jobs cannot be removed by a stale action; completed jobs cannot be overwritten by an old shutdown transition |
| UI | Input validation, real-model quality rows, selection retention, share intents, notification navigation, recreation and post-layout quality reveal passed |
| Visual inspection | Clean Android Home and quality screens inspected for readable content and controls, scrolling and system-bar insets |
| HTTP resume integrity | Actual bundled engine resumed a 16 MiB transfer at byte 1,047,552; completed SHA-256 matched the source and completion checkpoint existed |
| Live YouTube extraction | Host engine extracted the upstream README sample Big Buck Bunny, 597 seconds, 36 formats; no media content was downloaded |
| Native alignment | All 254 packaged ARM64 ELF files passed 16 KB load-segment alignment checks after the targeted WebP repair |
| Embedded payloads | Final APK contains the verified yt-dlp resource and exact audited FFmpeg dependency overlay |

The final device regression run covered the native runtime, eight persistence/state tests, and the quality-reveal case. Five other UI cases passed in the preceding run and their behavior was unchanged. Machine-readable summaries are in `verification/`.

## Scope and remaining limits

The Android runtime tests used an Android 15 x86_64 emulator with 4 KB memory pages. A physical ARM64 phone and a 16 KB ARM64 device were not available. The ARM64 package was compiled, signed, checked for the latest DEX, and structurally validated; it was not executed on physical ARM64 hardware.

The live YouTube lookup and controlled HTTP resume checks used the actual bundled engine on the build host. The live lookup used host Python and Node with TLS verification enabled. Android separately verified its packaged Python, FFmpeg and QuickJS runtimes. A full real YouTube transfer through the Android UI was not performed in this environment.

The initial software-emulator setup produced system-process timeouts and a legacy test-runner startup race. These were isolated from application failures; the runner wait/discovery setup was corrected and the relevant final checks rerun. The clean screenshots contain no system error dialog.

These checks do not guarantee every video, network, codec/player or Android vendor configuration. YouTube can restrict or change access. The app provides clear retry/resume behavior and an official, checksum-verified engine updater. Live broadcasts, playlists, account-only videos and DRM-protected media are not supported in this version.

## Build and review decisions

The app preserves source codecs and merges separate video/audio streams without re-encoding. An eight-fragment setting speeds supported fragmented sources; this is not a benchmark claim of maximum throughput for every connection. Exact format IDs, `.part` files and completion markers protect resume integrity. Finished files publish through MediaStore on Android 10+; Android 8–9 uses read-only sharing from app storage.

The queue is serialized across service instances. Conditional state changes protect pause/resume, removal and finalization from late callbacks. Source, licenses, native repair provenance and build instructions are included with this report. The separate signing backup preserves the identity needed for compatible future updates.
