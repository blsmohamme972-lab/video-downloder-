#!/usr/bin/env python3
"""Fetch checksum-pinned engine inputs and reproduce the ARM64 WebP repair.

Run before Gradle on a fresh checkout. Existing verified payloads are retained.
Only Python 3.10+ and its standard library are needed; HTTPS verification stays on.
Use --check for an offline check, or the explicit input-file options to reproduce
the bundle from already downloaded, checksum-verified upstream artifacts.
"""

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import sys
import tempfile
import urllib.request
import zipfile

import patch_webp_bundle


TOOLS = Path(__file__).resolve().parent
PINS = json.loads((TOOLS / "engine_payloads.json").read_text(encoding="utf-8"))


def digest(path):
    checksum = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            checksum.update(block)
    return checksum.hexdigest()


def valid(path, pin):
    return path.is_file() and path.stat().st_size == pin["bytes"] and digest(path) == pin["sha256"]


def require_valid(path, pin):
    if not valid(path, pin):
        raise ValueError(f"Checksum or size mismatch: {path}")


def atomic_copy(source, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=destination.parent, suffix=".tmp", delete=False) as output:
        temporary = Path(output.name)
        try:
            with source.open("rb") as stream:
                shutil.copyfileobj(stream, output, length=1024 * 1024)
        except BaseException:
            temporary.unlink(missing_ok=True)
            raise
    try:
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)


def obtain(pin, cache, name, supplied=None):
    if supplied is not None:
        require_valid(supplied, pin)
        return supplied
    cached = cache / name
    if valid(cached, pin):
        return cached
    cache.mkdir(parents=True, exist_ok=True)
    print(f"Fetching pinned {name} ...", flush=True)
    request = urllib.request.Request(pin["url"], headers={"User-Agent": "StreamSave-source-bootstrap/1"})
    with tempfile.NamedTemporaryFile(dir=cache, suffix=".download", delete=False) as output:
        temporary = Path(output.name)
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                count = 0
                while True:
                    block = response.read(1024 * 1024)
                    if not block:
                        break
                    count += len(block)
                    if count > pin["bytes"]:
                        raise ValueError(f"Unexpected download size: {name}")
                    output.write(block)
        except BaseException:
            temporary.unlink(missing_ok=True)
            raise
    try:
        require_valid(temporary, pin)
        temporary.replace(cached)
    finally:
        temporary.unlink(missing_ok=True)
    return cached


def prepare(args):
    project = args.project_root.resolve()
    cache = (args.cache_dir or project / ".engine-cache").resolve()
    ytdlp = project / PINS["yt_dlp"]["destination"]
    overlay = project / PINS["ffmpeg_overlay"]["destination"]

    if args.check:
        require_valid(ytdlp, PINS["yt_dlp"])
        require_valid(overlay, PINS["ffmpeg_overlay"])
    else:
        if not valid(ytdlp, PINS["yt_dlp"]):
            source = obtain(PINS["yt_dlp"], cache, "yt-dlp-2026.10.26", args.yt_dlp_file)
            atomic_copy(source, ytdlp)
        if not valid(overlay, PINS["ffmpeg_overlay"]):
            assert PINS["termux_webp"]["sha256"] == patch_webp_bundle.TERMUX_SHA256
            assert PINS["termux_webp"]["url"] == patch_webp_bundle.TERMUX_URL
            aar = obtain(PINS["ffmpeg_aar"], cache, "ffmpeg-0.18.1.aar", args.ffmpeg_aar)
            package = obtain(PINS["termux_webp"], cache, "libwebp-1.6.0-aarch64.deb", args.termux_deb)
            with zipfile.ZipFile(aar) as archive:
                original = archive.read(PINS["ffmpeg_aar"]["member"])
            repaired = patch_webp_bundle.patch_zip(
                original, patch_webp_bundle.replacements_from_deb(package.read_bytes()))
            if len(repaired) != PINS["ffmpeg_overlay"]["bytes"] or hashlib.sha256(repaired).hexdigest() != PINS["ffmpeg_overlay"]["sha256"]:
                raise ValueError("Reproduced FFmpeg archive does not match the reviewed payload")
            overlay.parent.mkdir(parents=True, exist_ok=True)
            with tempfile.NamedTemporaryFile(dir=overlay.parent, suffix=".tmp", delete=False) as output:
                temporary = Path(output.name)
                output.write(repaired)
            try:
                temporary.replace(overlay)
            finally:
                temporary.unlink(missing_ok=True)
        require_valid(ytdlp, PINS["yt_dlp"])
        require_valid(overlay, PINS["ffmpeg_overlay"])
    print(json.dumps({"verified": True, "yt_dlp_version": PINS["yt_dlp"]["version"],
                      "yt_dlp_sha256": digest(ytdlp), "ffmpeg_overlay_sha256": digest(overlay)}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Only check existing files; never download or write")
    parser.add_argument("--project-root", type=Path, default=TOOLS.parent)
    parser.add_argument("--cache-dir", type=Path)
    parser.add_argument("--yt-dlp-file", type=Path)
    parser.add_argument("--ffmpeg-aar", type=Path)
    parser.add_argument("--termux-deb", type=Path)
    args = parser.parse_args()
    try:
        prepare(args)
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        print(f"Engine preparation failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
