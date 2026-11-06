# Instructions for AI assistants working on StreamSave

Read this before changing code, building, or giving the owner an APK. These rules exist because one wrong step makes the installed app impossible to update, and the owner then has to uninstall it and lose their download Library.

## How Android updates work here

Android replaces an installed app only when the new APK has:

1. the same package name, `app.streamsave`;
2. a higher `versionCode`;
3. the same signing key.

If any of these differ, the update is refused. Nothing in the app can work around that.

**Every GitHub Actions run and every AI sandbox signs builds with a new, throwaway debug key.** An APK from a CI artifact or built in your own environment can never be updated later. It is for testing only.

## Rules

1. **Never change** `applicationId 'app.streamsave'` or add `applicationIdSuffix`.
2. **Every release bumps both versions** in `app/build.gradle` (the only place they are declared):
   - `versionCode`: +1;
   - `versionName`: a higher `MAJOR.MINOR.PATCH`.

   `tools/verify_apks.py` reads them from Gradle; do not hard-code versions elsewhere.
3. **Ship updates only through the release workflow.**
   - Merge to `main`, wait for "Android build and checks" to pass, then run **Publish production-signed StreamSave APK update** (`.github/workflows/signed-release.yml`) from `main` with the tag `v<versionName>`.
   - The in-app updater installs only those GitHub Releases.
   - Do not hand the owner CI artifacts or sandbox-built APKs as "the update". If you share one for testing, say plainly that installing it requires uninstalling StreamSave and loses the Library.
4. **Never create, replace, rotate or commit signing keys.**
   - The release keystore exists only in the repository secret `STREAMSAVE_SIGNING` (one value holding the keystore, alias and passwords; `tools/restore_signing.py` reads it) and in the owner's private backup in Google Drive. The four older secrets `STREAMSAVE_KEYSTORE_BASE64`, `STREAMSAVE_KEYSTORE_PASSWORD`, `STREAMSAVE_KEY_ALIAS`, `STREAMSAVE_KEY_PASSWORD` are still accepted.
   - If the secrets are missing or the release workflow reports a different key, stop and tell the owner. A new key forces every installed copy to be uninstalled once.
   - Never commit `*.jks`, `*.keystore`, `*.p12`, passwords, or signing property files.
5. **Do not weaken `AppUpdater.java`.** It must keep checking:
   - the package name;
   - a newer `versionCode`;
   - the same signer;
   - HTTPS, GitHub hosts only, and the SHA-256 digest.

   Do not remove the "Validate release version" or "Require the same signing key as the latest release" steps from `signed-release.yml`. `tools/check_release_rules.py` enforces these points in CI.
6. **Keep saved data compatible.**
   - Download history (`JobStore`/`Job` JSON), work folders, `.part` files and quality IDs must still load after an update.
   - Add new JSON fields as optional, with defaults; never rename or remove existing ones.
7. **Keep the download engine's guarantees** (`app/src/main/assets/streamsave_runner.py`):
   - exact selected formats, no re-encoding;
   - `.part` is always a contiguous verified prefix, so pause/resume survives updates;
   - connections back off on HTTP 429/503.

   Change it only with tests in `tests/test_parallel_transfer.py`.
8. **Keep study packs readable.** `StudyPack` must keep reading every pack it read before (schema versions 1 and 2). Add new pack fields as optional, and update `STUDY_PACK.md` and `StudyTest` with any change. Never execute anything from a pack.
9. **Record user-visible changes** in `CHANGELOG.md` under the new version.

## Checks before pushing

```sh
python3 tools/prepare_engine.py          # pinned engine payloads (needed by tests and builds)
python3 tools/check_release_rules.py
python3 -m unittest discover -s tests -p 'test_*.py' -v
./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug lintRelease   # when an Android SDK is available
```

CI runs all of these on pull requests and on `main`. Do not merge or publish a release while CI is failing.

## One-time setup the owner must do

The release workflow needs the signing secret `STREAMSAVE_SIGNING` (repository **Settings → Secrets and variables → Actions**). Its value is in the owner's Google Drive backup.

- Use the keystore that signed the APK currently installed, if the owner still has it.
- If the installed copy came from a CI artifact, its key is gone. The owner must uninstall once and install the first release APK from **GitHub Releases**; later updates then work in the app.
