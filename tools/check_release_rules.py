#!/usr/bin/env python3
"""Release-safety rules that every change must keep. Runs in CI.

An installed StreamSave can only be replaced in place by an APK with the same
package name, a higher versionCode and the same signing key. These checks stop
a change (by a person or an AI assistant) from silently breaking that chain or
from weakening the in-app updater's verification. See AGENTS.md.
"""

import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = "app.streamsave"
SECRET_PATTERNS = (
    re.compile(r"\.(jks|keystore|p12|pk8|pem)$", re.IGNORECASE),
    re.compile(r"signing.*\.(properties|zip)$", re.IGNORECASE),
    re.compile(r"(^|/)local\.properties$"),
)
UPDATER_SAFEGUARDS = {
    "getApkContentsSigners": "compares the update's signing certificate with the installed app",
    "Arrays.equals(existing, incoming)": "rejects an APK signed with a different key",
    "getLongVersionCode() <= current.getLongVersionCode()": "rejects an APK that is not newer",
    "context.getPackageName().equals(update.packageName)": "rejects an APK for another package",
    "App updates require HTTPS.": "refuses non-HTTPS update downloads",
    "Unexpected update download host.": "only downloads from GitHub hosts",
}


def main():
    errors = []
    build = (ROOT / "app/build.gradle").read_text(encoding="utf-8")

    ids = re.findall(r"(?m)^\s*applicationId\s+'([^']+)'\s*$", build)
    if ids != [PACKAGE]:
        errors.append(f"applicationId must stay '{PACKAGE}' (found {ids or 'none'}); "
                      "changing it creates a separate app that cannot update the installed one.")
    if re.search(r"(?m)^\s*applicationIdSuffix\b", build):
        errors.append("applicationIdSuffix changes the package name; remove it.")

    code = re.findall(r"(?m)^\s*versionCode\s+(\d+)\s*$", build)
    name = re.findall(r"(?m)^\s*versionName\s+'([^']+)'\s*$", build)
    if len(code) != 1:
        errors.append("app/build.gradle must declare exactly one integer versionCode.")
    if len(name) != 1 or not re.fullmatch(r"\d+\.\d+\.\d+", name[0]):
        errors.append("versionName must be one MAJOR.MINOR.PATCH value such as '1.2.1'.")

    tracked = subprocess.run(["git", "ls-files"], cwd=ROOT, capture_output=True, text=True, check=True)
    for path in tracked.stdout.splitlines():
        if any(pattern.search(path) for pattern in SECRET_PATTERNS):
            errors.append(f"Signing material or local secrets must never be committed: {path}")

    updater = (ROOT / "app/src/main/java/app/streamsave/AppUpdater.java").read_text(encoding="utf-8")
    for marker, purpose in UPDATER_SAFEGUARDS.items():
        if marker not in updater:
            errors.append(f"AppUpdater no longer {purpose} (missing `{marker}`).")
    repo = re.search(r'REPO\s*=\s*"([^"]+)"', updater)
    expected_repo = os.environ.get("GITHUB_REPOSITORY")
    if repo is None:
        errors.append("AppUpdater must declare the GitHub repository it updates from (REPO).")
    elif expected_repo and repo.group(1) != expected_repo:
        errors.append(f"AppUpdater.REPO is '{repo.group(1)}' but this repository is '{expected_repo}'.")

    release = ROOT / ".github/workflows/signed-release.yml"
    workflow = release.read_text(encoding="utf-8") if release.is_file() else ""
    for step in ("Validate release version", "Require the same signing key as the latest release"):
        if step not in workflow:
            errors.append(f"signed-release.yml must keep its '{step}' step.")

    if errors:
        print("Release rules failed:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print(f"Release rules passed: {PACKAGE} {name[0]} (versionCode {code[0]}).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
