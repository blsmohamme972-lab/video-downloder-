package app.streamsave;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-app updater for explicitly published, production-signed GitHub Release APKs.
 *
 * Never installs GitHub Actions artifacts: those are test-signed and cannot
 * update the user's existing privately signed StreamSave installation.
 *
 * The user taps Update once, then Android's trusted system installer asks for
 * confirmation. Android does not permit silent sideload installation.
 */
final class AppUpdater {
    private static final String REPO = "blsmohamme972-lab/video-downloder-";
    private static final String RELEASES_API =
            "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String ASSET_PREFIX =
            "https://github.com/" + REPO + "/releases/download/";
    private static final long MAX_APK_BYTES = 350L * 1024 * 1024;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);
    private static final Pattern VERSION = Pattern.compile("^[vV]?(\\d+)\\.(\\d+)\\.(\\d+)$");
    private static final Pattern DIGEST = Pattern.compile("^sha256:([0-9a-fA-F]{64})$");

    interface Callback {
        void progress(String message);
        void upToDate(String message);
        void ready(String message);
        void failed(String message);
    }

    private AppUpdater() { }

    static void checkAndDownload(Activity activity, Callback callback) {
        if (!BUSY.compareAndSet(false, true)) {
            MAIN.post(() -> callback.failed("An update download is already running."));
            return;
        }
        Context context = activity.getApplicationContext();
        Thread task = new Thread(() -> {
            try {
                progress(callback, "Checking published releases…");
                PackageInfo installed = currentInfo(context);
                String currentVersion = installed.versionName;
                JSONObject release = fetchRelease();
                if (release.optBoolean("draft") || release.optBoolean("prerelease"))
                    throw new IOException("No stable update has been published yet.");
                String tag = release.optString("tag_name", "");
                if (compareVersions(tag, currentVersion) <= 0) {
                    MAIN.post(() -> callback.upToDate("StreamSave " + currentVersion + " is already up to date."));
                    return;
                }
                JSONObject asset = findApk(release.getJSONArray("assets"));
                if (asset == null) {
                    throw new IOException("The latest release does not include an APK for this phone.");
                }
                String url = asset.getString("browser_download_url");
                if (!url.startsWith(ASSET_PREFIX))
                    throw new IOException("The update asset is not hosted under this repository's releases.");
                String checksum = asset.optString("digest", "");
                if (!checksum.isEmpty() && !DIGEST.matcher(checksum).matches())
                    throw new IOException("The release has an unsupported APK checksum.");
                progress(callback, "Downloading StreamSave " + tag + "…");
                File candidate = download(context, url, checksum, callback);
                verifyPackage(context, installed, candidate);
                File staged = AppUpdateProvider.stagedApk(context);
                if (staged.exists() && !staged.delete()) {
                    throw new IOException("Cannot replace the previous staged update.");
                }
                if (!candidate.renameTo(staged)) {
                    throw new IOException("Cannot finish staging the verified update.");
                }
                MAIN.post(() -> callback.ready("Verified StreamSave " + tag + ". Android will ask you to install it."));
            } catch (Exception ex) {
                String detail = ex.getMessage();
                MAIN.post(() -> callback.failed(detail == null || detail.isEmpty()
                        ? "Unable to check for app updates." : detail));
            } finally {
                BUSY.set(false);
            }
        }, "StreamSave-AppUpdate");
        task.start();
    }

    static void install(Activity activity) {
        try {
            File file = AppUpdateProvider.stagedApk(activity);
            if (!file.isFile()) throw new IOException("No downloaded update is available.");
            verifyPackage(activity, currentInfo(activity), file);
            if (Build.VERSION.SDK_INT >= 26
                    && !activity.getPackageManager().canRequestPackageInstalls()) {
                new AlertDialog.Builder(activity)
                        .setTitle("Allow StreamSave updates")
                        .setMessage("Android requires one-time permission to install APK updates from StreamSave. "
                                + "Allow this app, return here, and tap Install update. "
                                + "You do not need to uninstall the existing app.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Open settings", (dialog, which) -> {
                            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + activity.getPackageName()));
                            activity.startActivity(settings);
                        }).show();
                return;
            }
            Uri uri = AppUpdateProvider.contentUri(activity);
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            install.setClipData(ClipData.newUri(activity.getContentResolver(), "StreamSave update", uri));
            activity.startActivity(install);
        } catch (ActivityNotFoundException ex) {
            showError(activity, "No system APK installer is available on this phone.");
        } catch (Exception ex) {
            showError(activity, "Cannot install update: " + ex.getMessage());
        }
    }

    private static void showError(Activity activity, String message) {
        new AlertDialog.Builder(activity).setTitle("App update")
                .setMessage(message).setPositiveButton("OK", null).show();
    }

    private static void progress(Callback callback, String text) {
        MAIN.post(() -> callback.progress(text));
    }

    private static JSONObject fetchRelease() throws Exception {
        HttpURLConnection connection = open(new URL(RELEASES_API), "application/vnd.github+json");
        try (InputStream in = connection.getInputStream()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (output.size() + n > 1024 * 1024)
                    throw new IOException("GitHub update metadata is unexpectedly large.");
                output.write(buffer, 0, n);
            }
            return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            connection.disconnect();
        }
    }

    private static JSONObject findApk(JSONArray assets) {
        boolean wantsX86 = Build.SUPPORTED_ABIS.length > 0
                && Build.SUPPORTED_ABIS[0].startsWith("x86");
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;
            String name = asset.optString("name", "").toLowerCase(Locale.ROOT);
            if (!name.endsWith(".apk") || name.contains("debug")
                    || asset.optString("state", "uploaded").equals("deleted")) continue;
            if (wantsX86 ? name.contains("x86_64") : name.contains("arm64")) return asset;
        }
        return null;
    }

    private static File download(Context context, String source, String checksum, Callback callback)
            throws Exception {
        File dir = AppUpdateProvider.stagedApk(context).getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create update cache.");
        File candidate = new File(dir, "update-candidate.apk");
        if (candidate.exists() && !candidate.delete())
            throw new IOException("Cannot remove unfinished update.");
        if (dir.getUsableSpace() < 150L * 1024 * 1024)
            throw new IOException("Not enough free space to download the update.");
        HttpURLConnection connection = open(new URL(source), "application/octet-stream");
        try {
            long expectedSize = connection.getContentLengthLong();
            if (expectedSize > MAX_APK_BYTES)
                throw new IOException("Update APK exceeds the allowed size.");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long received = 0;
            long reported = -1;
            try (InputStream in = connection.getInputStream();
                 FileOutputStream out = new FileOutputStream(candidate)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    received += n;
                    if (received > MAX_APK_BYTES)
                        throw new IOException("Update APK exceeds the allowed size.");
                    out.write(buffer, 0, n);
                    digest.update(buffer, 0, n);
                    long bucket = received / (2 * 1024 * 1024);
                    if (bucket != reported) {
                        reported = bucket;
                        final long bytes = received;
                        progress(callback, expectedSize > 0
                                ? String.format(Locale.US, "Downloading update… %d%%",
                                    (int)(bytes * 100 / expectedSize))
                                : String.format(Locale.US, "Downloading update… %d MiB",
                                    bytes / (1024 * 1024)));
                    }
                }
                out.getFD().sync();
            }
            if (received < 1024 || expectedSize >= 0 && received != expectedSize)
                throw new IOException("The APK download was incomplete.");
            if (!checksum.isEmpty()) {
                Matcher match = DIGEST.matcher(checksum);
                if (!match.matches()) throw new IOException("Invalid SHA-256 digest.");
                StringBuilder hex = new StringBuilder();
                for (byte value : digest.digest()) hex.append(String.format(Locale.US, "%02x", value & 0xff));
                if (!hex.toString().equalsIgnoreCase(match.group(1)))
                    throw new IOException("The APK SHA-256 checksum did not match GitHub.");
            }
            return candidate;
        } catch (Exception ex) {
            candidate.delete();
            throw ex;
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(URL first, String accept) throws IOException {
        URL current = first;
        for (int i = 0; i < 6; i++) {
            if (!"https".equalsIgnoreCase(current.getProtocol()))
                throw new IOException("App updates require HTTPS.");
            String host = current.getHost().toLowerCase(Locale.ROOT);
            if (!host.equals("api.github.com") && !host.equals("github.com")
                    && !host.endsWith(".githubusercontent.com"))
                throw new IOException("Unexpected update download host.");
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(45000);
            connection.setRequestProperty("Accept", accept);
            connection.setRequestProperty("User-Agent", "StreamSave-Android-Updater");
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null) throw new IOException("Update redirect is missing.");
                current = new URL(current, location);
                continue;
            }
            if (status == 404 && first.toString().equals(RELEASES_API)) {
                connection.disconnect();
                throw new IOException("No app update published yet. A signed GitHub Release is required.");
            }
            if (status != 200) {
                connection.disconnect();
                throw new IOException("GitHub update check failed (HTTP " + status + ").");
            }
            return connection;
        }
        throw new IOException("Too many update download redirects.");
    }

    private static PackageInfo currentInfo(Context context) throws PackageManager.NameNotFoundException {
        int flags = Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        return context.getPackageManager().getPackageInfo(context.getPackageName(), flags);
    }

    private static void verifyPackage(Context context, PackageInfo current, File candidate) throws IOException {
        PackageManager manager = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo update = manager.getPackageArchiveInfo(candidate.getAbsolutePath(), flags);
        if (update == null || !context.getPackageName().equals(update.packageName)) {
            throw new IOException("APK does not belong to StreamSave.");
        }
        if (Build.VERSION.SDK_INT >= 28
                ? update.getLongVersionCode() <= current.getLongVersionCode()
                : update.versionCode <= current.versionCode) {
            throw new IOException("The downloaded APK is not newer than the installed app.");
        }
        Signature[] existing = Build.VERSION.SDK_INT >= 28 && current.signingInfo != null
                ? current.signingInfo.getApkContentsSigners() : current.signatures;
        Signature[] incoming = Build.VERSION.SDK_INT >= 28 && update.signingInfo != null
                ? update.signingInfo.getApkContentsSigners() : update.signatures;
        if (existing == null || incoming == null || existing.length == 0
                || !Arrays.equals(existing, incoming)) {
            throw new IOException("Update signing key differs from the installed app. "
                    + "A release signed with the original key is required; do not uninstall.");
        }
    }

    static int compareVersions(String left, String right) throws IOException {
        Matcher a = VERSION.matcher(left == null ? "" : left.trim());
        Matcher b = VERSION.matcher(right == null ? "" : right.trim());
        if (!a.matches() || !b.matches())
            throw new IOException("Cannot compare published and installed app versions.");
        for (int i = 1; i <= 3; i++) {
            int difference = Long.compare(Long.parseLong(a.group(i)), Long.parseLong(b.group(i)));
            if (difference != 0) return difference;
        }
        return 0;
    }
}
