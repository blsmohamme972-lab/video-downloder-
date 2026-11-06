#!/usr/bin/env python3
"""Restore the release keystore from GitHub Actions secrets (CI only).

Accepts either one secret, STREAMSAVE_SIGNING (base64 of a JSON bundle with
the keystore, alias and passwords), or the four separate secrets
STREAMSAVE_KEYSTORE_BASE64, STREAMSAVE_KEYSTORE_PASSWORD, STREAMSAVE_KEY_ALIAS
and STREAMSAVE_KEY_PASSWORD, passed in through the environment variables read
below. Writes the keystore and a Gradle signing properties file into a private
directory. Never prints secret values; passwords are masked in the job log.

Usage: restore_signing.py OUTPUT_DIR   (prints the properties file path)
"""

import base64
import binascii
import json
import os
import re
import sys
from pathlib import Path


def fail(message):
    print(f"::error::{message}", file=sys.stderr)
    sys.exit(1)


def decode(value, what):
    try:
        return base64.b64decode("".join(value.split()), validate=True)
    except (binascii.Error, ValueError):
        fail(f"{what} is not valid base64. Copy the whole value again, with nothing added or missing.")


BUNDLE_KEYS = {"keystore", "alias", "storePassword", "keyPassword"}
BASE64_RUN = re.compile(r"[A-Za-z0-9+/]{64,}=*")


def bundle_candidates(raw):
    # The value may arrive with extra text around it (for example the whole
    # backup file pasted from a phone) or with line breaks inside it.
    compact = "".join(raw.split())
    yield compact
    runs = BASE64_RUN.findall(raw) + BASE64_RUN.findall(compact)
    for run in sorted(set(runs), key=len, reverse=True):
        core = run.rstrip("=")
        yield core + "=" * (-len(core) % 4)


def find_bundle(raw):
    for candidate in bundle_candidates(raw):
        try:
            data = json.loads(base64.b64decode(candidate, validate=True))
        except (binascii.Error, ValueError):
            continue
        if isinstance(data, dict) and BUNDLE_KEYS <= data.keys():
            return data
    return None


def describe(raw):
    """Safe hints about a bad paste: lengths only, never content."""
    compact = "".join(raw.split())
    longest = max((len(run) for run in BASE64_RUN.findall(compact)), default=0)
    return (f"Received {len(compact)} characters (ignoring spaces and line breaks); the longest "
            f"key-like part is {longest} characters. A complete StreamSave value is several "
            f"thousand characters with no spaces, so the copy was probably cut off.")


def escape(value):
    # java.util.Properties: escape the characters that change parsing.
    out = value.replace("\\", "\\\\")
    for char in ":=#!":
        out = out.replace(char, "\\" + char)
    return ("\\" + out) if out[:1] == " " else out


def main():
    if len(sys.argv) != 2:
        fail("Usage: restore_signing.py OUTPUT_DIR")
    bundle = os.environ.get("SIGNING_BUNDLE", "").strip()
    if bundle:
        data = find_bundle(bundle)
        if data is None:
            fail("STREAMSAVE_SIGNING does not contain a complete StreamSave signing value. " + describe(bundle))
        try:
            keystore = decode(data["keystore"], "The keystore inside STREAMSAVE_SIGNING")
            alias, store_password, key_password = data["alias"], data["storePassword"], data["keyPassword"]
        except (KeyError, TypeError):
            fail("STREAMSAVE_SIGNING is incomplete. Paste the full value from the StreamSave signing file.")
    else:
        names = ("KEYSTORE_BASE64", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
        values = {name: os.environ.get(name, "") for name in names}
        if not all(values.values()):
            fail("Production signing secrets are not configured. Add STREAMSAVE_SIGNING "
                 "(or the four STREAMSAVE_KEY* secrets) under Settings > Secrets and variables > Actions.")
        keystore = decode(values["KEYSTORE_BASE64"], "STREAMSAVE_KEYSTORE_BASE64")
        alias, store_password = values["KEY_ALIAS"], values["KEYSTORE_PASSWORD"]
        key_password = values["KEY_PASSWORD"]
    if not keystore or not alias or not store_password or not key_password:
        fail("The signing secret is missing the keystore, alias or a password.")
    for secret in {store_password, key_password}:
        print(f"::add-mask::{secret}")

    output = Path(sys.argv[1])
    output.mkdir(parents=True, exist_ok=True)
    os.chmod(output, 0o700)
    store = output / "streamsave-release.jks"
    store.write_bytes(keystore)
    os.chmod(store, 0o600)
    properties = output / "streamsave-signing.properties"
    properties.write_text(
        f"storeFile={escape(str(store))}\nstorePassword={escape(store_password)}\n"
        f"keyAlias={escape(alias)}\nkeyPassword={escape(key_password)}\n", encoding="utf-8")
    os.chmod(properties, 0o600)
    print(properties)


if __name__ == "__main__":
    main()
