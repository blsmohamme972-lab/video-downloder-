#!/usr/bin/env python3
"""Reproduce StreamSave's five-file ARM64 WebP alignment repair.

Only Python's standard library is required. The original bundle is the member
jni/arm64-v8a/libffmpeg.zip.so of the published ffmpeg-0.18.1.aar.
Unchanged ZIP members keep their exact compressed local records and metadata.
The official Termux package and every replacement ELF are SHA-256 pinned.

Example:
  python tools/patch_webp_bundle.py --original-zip original-libffmpeg.zip.so \
    --output app/src/main/jniLibs/arm64-v8a/libffmpeg.zip.so
"""

import argparse
import hashlib
import io
import json
from pathlib import Path
import struct
import tarfile
import urllib.request
import zipfile
import zlib


ORIGINAL_SHA256 = "4664a895283f9f31786be39b0c484c1f96631f02c7c7f9c70a4a4a2da324a092"
TERMUX_URL = (
    "https://packages.termux.dev/apt/termux-main/pool/main/libw/libwebp/"
    "libwebp_1.6.0.0.0.1%2Breally1.6.0_aarch64.deb"
)
TERMUX_SHA256 = "8ba7fcfadd1a9957a7632c6407d6ef3e41d34dbd96a41769b245592ed4559045"
ELF_SHA256 = {
    "libwebp.so": "631ec2d7cc1a10b7092c1133edd3e3d825edabb8bfd9bdfe8fbaa38607737781",
    "libwebpdecoder.so": "d0c6fb4024de7afc13b5d0920b125fde6f13c52dfccea847c66752f38950fef7",
    "libwebpdemux.so": "ff7995219068660d194e0083c4d4cc76665a646d58f7eb8d15b15d5a3fb288b9",
    "libwebpmux.so": "9401964e6dc15c10d5954c700f2c8c651b2a964177af73d2ef1e746d046ded04",
    "libsharpyuv.so": "c31e871967115c7cd7ea4933c92c546d2fe8bc4a600871b965fb35574a19ced9",
}


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def check_elf(data):
    if data[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", data, 18)[0] != 183:
        raise ValueError("Replacement must be a little-endian 64-bit AArch64 ELF")
    offset = struct.unpack_from("<Q", data, 32)[0]
    size, count = struct.unpack_from("<HH", data, 54)
    for index in range(count):
        header = struct.unpack_from("<IIQQQQQQ", data, offset + index * size)
        if header[0] == 1 and (header[7] < 16384 or (header[3] - header[2]) % 16384):
            raise ValueError("Replacement has an incompatible PT_LOAD segment")


def replacements_from_deb(deb):
    if sha256(deb) != TERMUX_SHA256 or deb[:8] != b"!<arch>\n":
        raise ValueError("Official Termux package checksum does not match")
    position, archive = 8, None
    while position < len(deb):
        header = deb[position:position + 60]
        name = header[:16].decode("ascii").strip().rstrip("/")
        size = int(header[48:58])
        if name.startswith("data.tar"):
            archive = deb[position + 60:position + 60 + size]
        position += 60 + size + size % 2
    if archive is None:
        raise ValueError("Debian package has no data archive")
    replacements = {}
    with tarfile.open(fileobj=io.BytesIO(archive), mode="r:*") as package:
        members = package.getmembers()
        for name, expected in ELF_SHA256.items():
            member = next(item for item in members if item.name.endswith("/lib/" + name))
            data = package.extractfile(member).read()
            if sha256(data) != expected:
                raise ValueError("Replacement checksum does not match: " + name)
            check_elf(data)
            replacements["usr/lib/" + name] = data
    return replacements


def patch_zip(original, replacements):
    if sha256(original) != ORIGINAL_SHA256:
        raise ValueError("Expected the original ARM64 FFmpeg bundle from youtubedl-android 0.18.1")
    end = original.rfind(b"PK\x05\x06")
    if end < 0:
        raise ValueError("Missing ZIP end record")
    signature, disk, cd_disk, disk_count, count, cd_size, cd_offset, comment_size = struct.unpack_from(
        "<4s4H2IH", original, end)
    if disk or cd_disk or disk_count != count or count == 65535 or end + 22 + comment_size != len(original):
        raise ValueError("Only the original, single-volume ZIP32 layout is supported")
    with zipfile.ZipFile(io.BytesIO(original)) as archive:
        members = sorted(archive.infolist(), key=lambda item: item.header_offset)
        names = {item.header_offset: item.filename for item in members}
        if not set(replacements).issubset({item.filename for item in members}):
            raise ValueError("Original bundle lacks a required WebP member")
        output = bytearray(original[:members[0].header_offset])
        offsets, changed = {}, {}
        for index, member in enumerate(members):
            start = member.header_offset
            stop = members[index + 1].header_offset if index + 1 < len(members) else cd_offset
            offsets[start] = len(output)
            if member.filename not in replacements:
                output.extend(original[start:stop])
                continue
            if member.flag_bits & 9 or member.compress_type not in (0, 8):
                raise ValueError("Unexpected flags/compression for " + member.filename)
            filename_size, extra_size = struct.unpack_from("<HH", original, start + 26)
            header = bytearray(original[start:start + 30 + filename_size + extra_size])
            data = replacements[member.filename]
            if member.compress_type == 8:
                compressor = zlib.compressobj(9, zlib.DEFLATED, -15)
                compressed = compressor.compress(data) + compressor.flush()
            else:
                compressed = data
            crc = zlib.crc32(data) & 0xffffffff
            struct.pack_into("<III", header, 14, crc, len(compressed), len(data))
            output.extend(header)
            output.extend(compressed)
            changed[start] = (crc, len(compressed), len(data))

    new_cd_offset = len(output)
    cursor = cd_offset
    for _ in range(count):
        if original[cursor:cursor + 4] != b"PK\x01\x02":
            raise ValueError("Unexpected ZIP central directory")
        name_size, extra_size, comment_size = struct.unpack_from("<HHH", original, cursor + 28)
        length = 46 + name_size + extra_size + comment_size
        record = bytearray(original[cursor:cursor + length])
        old_offset = struct.unpack_from("<I", record, 42)[0]
        struct.pack_into("<I", record, 42, offsets[old_offset])
        if old_offset in changed:
            struct.pack_into("<III", record, 16, *changed[old_offset])
        output.extend(record)
        cursor += length
    if cursor != cd_offset + cd_size:
        raise ValueError("Unexpected data after ZIP directory")
    trailer = bytearray(original[end:])
    struct.pack_into("<II", trailer, 12, len(output) - new_cd_offset, new_cd_offset)
    output.extend(trailer)

    # Validate the archive and every decoded member; no unrelated content changes.
    with zipfile.ZipFile(io.BytesIO(original)) as before, zipfile.ZipFile(io.BytesIO(output)) as after:
        if after.testzip() is not None:
            raise ValueError("Generated ZIP failed its CRC check")
        for item in before.infolist():
            expected = replacements.get(item.filename)
            if expected is None:
                expected = before.read(item.filename)
            if after.read(item.filename) != expected:
                raise ValueError("Unexpected member contents: " + item.filename)
            updated = after.getinfo(item.filename)
            for field in ("date_time", "compress_type", "flag_bits", "external_attr", "internal_attr", "extra", "comment"):
                if getattr(item, field) != getattr(updated, field):
                    raise ValueError("Unexpected metadata change: " + item.filename + ": " + field)
    return bytes(output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--original-zip", type=Path, required=True)
    parser.add_argument("--termux-deb", type=Path, help="Use an already downloaded, checksum-verified package")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.original_zip.resolve() == args.output.resolve():
        parser.error("Use a separate output path; preserve the original bundle")
    if args.termux_deb:
        package = args.termux_deb.read_bytes()
    else:
        with urllib.request.urlopen(TERMUX_URL, timeout=30) as response:
            package = response.read(2 * 1024 * 1024)
    patched = patch_zip(args.original_zip.read_bytes(), replacements_from_deb(package))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(patched)
    print(json.dumps({"output": str(args.output), "bytes": len(patched),
                      "sha256": sha256(patched), "replaced_members": len(ELF_SHA256)}))


if __name__ == "__main__":
    main()
