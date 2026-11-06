# Source, licenses, and bundled engine provenance

The StreamSave application source is supplied under GNU GPL version 3; see `LICENSE`. Dependencies retain their own copyright and license notices.

The Git repository keeps generated engine payloads out of source control. `tools/engine_payloads.json` pins their official locations, exact sizes and SHA-256 hashes. `python3 tools/prepare_engine.py` downloads the originals, reproduces the native repair described below, and verifies the finished inputs. Gradle checks those inputs before building, so a missing bootstrap cannot silently package an older extractor.

## Download engine

**youtubedl-android 0.18.1**, including its `library`, `common`, and `ffmpeg` components, is distributed under GNU GPL version 3.

- Upstream source and build documentation: <https://github.com/yausername/youtubedl-android/tree/0.18.1>
- Maven artifacts: `io.github.junkfood02.youtubedl-android:library:0.18.1` and `io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1`
- Library source archive: <https://repo.maven.apache.org/maven2/io/github/junkfood02/youtubedl-android/library/0.18.1/library-0.18.1-sources.jar>
- FFmpeg wrapper source archive: <https://repo.maven.apache.org/maven2/io/github/junkfood02/youtubedl-android/ffmpeg/0.18.1/ffmpeg-0.18.1-sources.jar>
- Native Python build instructions: <https://github.com/yausername/youtubedl-android/blob/0.18.1/BUILD_PYTHON.md>
- Native FFmpeg build instructions: <https://github.com/yausername/youtubedl-android/blob/0.18.1/BUILD_FFMPEG.md>

The upstream Android wrapper packages CPython 3.12, FFmpeg, QuickJS, and the native libraries they require. Upstream native packages and build scripts provide their original notices and source references, including the Termux packaging repository at <https://github.com/termux/termux-packages>. The native build guides contain older example versions and are references rather than a complete reproducible recipe for every bundled native binary. CPython, FFmpeg, QuickJS, OpenSSL and associated native components retain their respective licenses; the wrapper's GPL license does not replace them.

- CPython source and license: <https://github.com/python/cpython>
- FFmpeg source and license: <https://ffmpeg.org/download.html> and <https://ffmpeg.org/legal.html>
- QuickJS source and license: <https://bellard.org/quickjs/>

### ARM64 WebP compatibility repair

The ARM64 FFmpeg dependency bundle contains a targeted build-time repair for five WebP libraries that originally had 4 KB ELF load alignment. The app's `jniLibs/arm64-v8a/libffmpeg.zip.so` overrides the upstream bundle. Its WebP 1.6.0 libraries come from the official Termux package `libwebp_1.6.0.0.0.1+really1.6.0_aarch64.deb`, checked against the repository's SHA-256 metadata.

The replacements retain the original SONAMEs, required libraries, exported API and required symbol versions. They target Android API 24 and have 16 KB aligned load segments. The other 179 ZIP members retain their original compressed bytes and metadata. This repair changes native packaging; it does not change application behavior or the FFmpeg wrapper classes.

`third_party/libwebp-1.6.0/provenance.json` records the exact package/source URLs, hashes, and pinned Termux recipe commit. `tools/patch_webp_bundle.py` reproduces the overlay. The libwebp BSD 3-Clause license is included both under `third_party/libwebp-1.6.0/COPYING` and inside the APK's assets.

Overlay SHA-256: `f9c49c3ec35cecea68f49db2d334c47ec17851a65d509e1a635e3d4231298578`.

## yt-dlp release bundled in this app

- Version: **2026.10.26**, official stable release.
- Release: <https://github.com/yt-dlp/yt-dlp/releases/tag/2026.10.26>
- Original artifact: <https://github.com/yt-dlp/yt-dlp/releases/download/2026.10.26/yt-dlp>
- Official checksum list: <https://github.com/yt-dlp/yt-dlp/releases/download/2026.10.26/SHA2-256SUMS>
- Bundled path: `app/src/main/res/raw/ytdlp`
- SHA-256: `e51e69e89489f199530f13d32a4342da3ddb2cf34842f364603a066db05afbf3`

The hash was compared with the official release's SHA2-256SUMS during this build. The `yt-dlp` Unix executable is a ZIP containing its Python source; it also contains the JavaScript challenge-solver components. It overrides the Android wrapper's older bootstrap resource. yt-dlp core uses the Unlicense; bundled dependencies also contain MIT and ISC licensed components. Their notices remain embedded in the release artifact.

### StreamSave transfer adapter

Version 1.1.0 adds the app-owned `streamsave_runner.py` and `NativeTransfer.java` under the application's GPL-3.0 license. The official yt-dlp archive remains byte-for-byte unchanged. The adapter registers a dedicated downloader for eligible ordinary YouTube HTTP/HTTPS streams, retaining yt-dlp extraction, exact format selection, networking headers/cookies, native manifest downloaders, and lossless stream merging.

Its bounded parallel ranges preserve a contiguous `.part` prefix, including files created by version 1.0.0. The supervised process group provides a single termination boundary for worker threads and native descendants before another attempt can begin. Startup and engine-update validation also load the adapter against the selected yt-dlp release.

- yt-dlp source: <https://github.com/yt-dlp/yt-dlp/tree/2026.10.26>
- yt-dlp license: <https://github.com/yt-dlp/yt-dlp/blob/2026.10.26/LICENSE>
- EJS source and license: <https://github.com/yt-dlp/ejs>

## Other build and runtime dependencies

The wrapper's Maven POMs declare its Java/Kotlin dependencies, including Kotlin standard libraries, AndroidX, Jackson and Apache Commons. The Gradle dependency report is included with the validation materials when generated. These dependencies retain their upstream licenses. The Gradle wrapper/build system uses Apache License 2.0. JUnit and the JSON test implementation are test-only dependencies and are not application features.

The source archive intentionally excludes local SDK installations, Gradle caches, build intermediates, signing passwords and private signing keys. Rebuilding resolves declared dependencies from Google's Maven repository and Maven Central.
