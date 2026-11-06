#!/usr/bin/env python3
"""Verify current split APKs, embedded engines, signing, and ARM64 ELF alignment.

Run after a clean assembleDebug/assembleRelease build. No private signing data is
read. CI builds use test keys; --expected-release-signer optionally checks the
public certificate fingerprint used for an install-compatible private update.
"""

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import zipfile


ROOT = Path(__file__).resolve().parent.parent
PINS = json.loads((ROOT / "tools/engine_payloads.json").read_text(encoding="utf-8"))


def read_app_version():
    """Use the version declared by Gradle so future releases need no script edits."""
    build = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
    code = re.search(r"(?m)^\s*versionCode\s+(\d+)\s*$", build)
    name = re.search(r"(?m)^\s*versionName\s+'([^']+)'\s*$", build)
    if code is None or name is None:
        raise ValueError("Cannot read versionCode/versionName from app/build.gradle")
    return int(code.group(1)), name.group(1)


VERSION_CODE, VERSION_NAME = read_app_version()


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def run(tool, *arguments):
    result = subprocess.run([str(tool), *map(str, arguments)], capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(f"{tool.name} failed: {result.stdout}\n{result.stderr}")
    return result.stdout + result.stderr


def elf_alignment(name, data):
    if not data.startswith(b"\x7fELF"):
        return None
    if data[4:6] != b"\x02\x01":
        raise ValueError(f"Expected little-endian ELF64: {name}")
    table = struct.unpack_from("<Q", data, 32)[0]
    size, count = struct.unpack_from("<HH", data, 54)
    segments = []
    for index in range(count):
        offset = table + index * size
        if struct.unpack_from("<I", data, offset)[0] == 1:
            file_offset, address = struct.unpack_from("<QQ", data, offset + 8)
            alignment = struct.unpack_from("<Q", data, offset + 48)[0]
            segments.append(alignment >= 16384 and (file_offset - address) % 16384 == 0)
    if not segments or not all(segments):
        raise ValueError(f"Incompatible ARM64 16 KB load alignment: {name}")
    return name


def verify_arm64(archive):
    checked = []
    for name in archive.namelist():
        if not name.startswith("lib/arm64-v8a/"):
            continue
        data = archive.read(name)
        if data.startswith(b"\x7fELF"):
            checked.append(elf_alignment(name, data))
        elif zipfile.is_zipfile(io.BytesIO(data)):
            with zipfile.ZipFile(io.BytesIO(data)) as nested:
                if nested.testzip() is not None:
                    raise ValueError(f"Invalid native package CRC: {name}")
                for member in nested.infolist():
                    if not member.is_dir() and ".so" in member.filename:
                        item = elf_alignment(name + "!" + member.filename, nested.read(member))
                        if item:
                            checked.append(item)
    if len(checked) != 254:
        raise ValueError(f"Unexpected native engine payload count: {len(checked)} (expected 254)")
    return len(checked)


def verify(apk, variant, abi, tools, expected_release_signer):
    signature = run(tools / "apksigner", "verify", "--verbose", "--print-certs", apk)
    if "Verified using v2 scheme (APK Signature Scheme v2): true" not in signature:
        raise ValueError(f"APK v2 signature is missing: {apk.name}")
    signer = re.search(r"Signer #1 certificate SHA-256 digest: (.+)", signature).group(1)
    if variant == "release" and expected_release_signer and signer != expected_release_signer.lower():
        raise ValueError("Release signer differs from the existing installed application")
    badging = run(tools / "aapt", "dump", "badging", apk)
    for required in (f"package: name='app.streamsave' versionCode='{VERSION_CODE}' versionName='{VERSION_NAME}'",
                     "sdkVersion:'26'", "targetSdkVersion:'35'"):
        if required not in badging:
            raise ValueError(f"APK metadata mismatch: {required}")
    if variant == "release" and "application-debuggable" in badging:
        raise ValueError("Release APK must not be debuggable")
    manifest = run(tools / "aapt", "dump", "xmltree", apk, "AndroidManifest.xml")
    if not re.search(r"extractNativeLibs.*0xffffffff", manifest):
        raise ValueError("Native executable extraction must remain enabled")
    run(tools / "zipalign", "-c", "4", apk)
    resources = run(tools / "aapt2", "dump", "resources", apk)
    resource = re.search(r"raw/ytdlp\s+\(\) \(file\) (\S+)", resources)
    if not resource:
        raise ValueError("The yt-dlp resource override is missing")
    with zipfile.ZipFile(apk) as archive:
        if any(name.startswith("assets/") and ("__pycache__" in name or name.endswith((".pyc", ".pyo")))
               for name in archive.namelist()):
            raise ValueError("Host-generated Python bytecode must not be packaged as an Android asset")
        abis = sorted({name.split("/")[1] for name in archive.namelist() if name.startswith("lib/")})
        if abis != [abi]:
            raise ValueError(f"Unexpected APK architecture: {abis}")
        if sha256(archive.read(resource.group(1))) != PINS["yt_dlp"]["sha256"]:
            raise ValueError("Packaged yt-dlp differs from the pinned current release")
        runner = ROOT / "app/src/main/assets/streamsave_runner.py"
        runner_digest = sha256(runner.read_bytes())
        if sha256(archive.read("assets/streamsave_runner.py")) != runner_digest:
            raise ValueError("Packaged transfer supervisor differs from current source")
        dex = {name: sha256(archive.read(name)) for name in archive.namelist()
               if re.fullmatch(r"classes\d*\.dex", name)}
        bytecode = b"".join(archive.read(name) for name in dex)
        for marker in (b"revealQualityAfterLayout", b"transferGate", b"NativeTransfer",
                       b"AppUpdater", b"StudyActivity"):
            if marker not in bytecode:
                raise ValueError(f"Expected current application code is missing: {marker!r}")
        native_count = None
        if abi == "arm64-v8a":
            if sha256(archive.read("lib/arm64-v8a/libffmpeg.zip.so")) != PINS["ffmpeg_overlay"]["sha256"]:
                raise ValueError("The reviewed ARM64 WebP repair was not packaged")
            if "assets/licenses/libwebp-BSD-3-Clause.txt" not in archive.namelist():
                raise ValueError("The native replacement license is missing")
            native_count = verify_arm64(archive)
    return {"path": str(apk.relative_to(ROOT)), "variant": variant, "abi": abi,
            "size_bytes": apk.stat().st_size, "apk_sha256": sha256(apk.read_bytes()),
            "signer_certificate_sha256": signer, "signature_v2_verified": True,
            "zip_alignment_verified": True, "dex_sha256": dex,
            "yt_dlp_sha256": PINS["yt_dlp"]["sha256"], "runner_sha256": runner_digest,
            "arm64_native_elf_files_checked": native_count}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path,
                        default=os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME"))
    parser.add_argument("--expected-release-signer", help="Public SHA-256 certificate fingerprint")
    args = parser.parse_args()
    if not args.sdk:
        parser.error("Set ANDROID_HOME/ANDROID_SDK_ROOT or supply --sdk")
    tools = args.sdk / "build-tools/35.0.0"
    rows = []
    for variant in ("debug", "release"):
        pair = []
        for abi in ("arm64-v8a", "x86_64"):
            apk = ROOT / f"app/build/outputs/apk/{variant}/app-{abi}-{variant}.apk"
            pair.append(verify(apk, variant, abi, tools, args.expected_release_signer))
        if pair[0]["dex_sha256"] != pair[1]["dex_sha256"]:
            raise ValueError(f"{variant} ABI splits contain different Java bytecode; do a clean rebuild")
        if pair[0]["signer_certificate_sha256"] != pair[1]["signer_certificate_sha256"]:
            raise ValueError(f"{variant} ABI splits have different signing identities")
        rows.extend(pair)
    report = {"package": "app.streamsave", "version_name": VERSION_NAME, "version_code": VERSION_CODE,
              "min_sdk": 26, "target_sdk": 35,
              "all_checks_passed": True, "both_abis_have_identical_current_dex_per_variant": True,
              "arm64_native_load_alignment_16kb": True, "apks": rows}
    output = ROOT / "app/build/reports/apk-verification.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
