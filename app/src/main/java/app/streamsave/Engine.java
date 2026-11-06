package app.streamsave;

import android.content.Context;

import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Local yt-dlp/FFmpeg engine. No download proxy or account credentials are used.
 * Exact format IDs and stable per-job filenames keep partial downloads safe to resume.
 * Upstream: https://github.com/yausername/youtubedl-android (GPL-3.0)
 * Extractor: https://github.com/yt-dlp/yt-dlp (Unlicense; bundled notices apply).
 */
public final class Engine {
    public interface ProgressListener {
        void onProgress(float percent, long eta, String line);
    }

    private static final Pattern URL_CANDIDATE = Pattern.compile(
            "(?i)https?://[^\\s<>\"']+|(?<!\\S)(?:www\\.|m\\.|music\\.)?"
                    + "(?:youtube\\.com|youtu\\.be|youtube-nocookie\\.com)/[^\\s<>\"']+");
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Pattern FORMAT_ID = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final Pattern PROGRESS = Pattern.compile("\\[download]\\s+([0-9.]+)%");
    private static final String STABLE_API =
            "https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest";
    private static final String FINAL_MARKER = "STREAMSAVE_FILE:";

    private final Context context;
    private final NativeTransfer transfers;
    private final ReentrantReadWriteLock operations = new ReentrantReadWriteLock(true);
    private volatile boolean ready;
    private volatile String engineVersion = "Bundled engine";

    public Engine(Context context) {
        this.context = context.getApplicationContext();
        transfers = new NativeTransfer(this.context);
    }

    public void initialize() throws Exception {
        operations.writeLock().lockInterruptibly();
        try {
            if (ready) return;
            File engineDir = new File(context.getNoBackupFilesDir(), "youtubedl-android/yt-dlp");
            File installed = new File(engineDir, "yt-dlp");
            File previous = new File(engineDir, "yt-dlp.previous");
            // Recover the narrow power-loss window between backup and replacement.
            if (!installed.isFile() && previous.isFile() && !previous.renameTo(installed)) {
                throw new IOException("The saved engine update could not be restored. Please restart the app.");
            }
            YoutubeDL.getInstance().init(context);
            FFmpeg.getInstance().init(context);
            transfers.initialize();
            try {
                engineVersion = probeVersion();
            } catch (Exception error) {
                if (!previous.isFile() || !installed.delete() || !previous.renameTo(installed)) throw error;
                engineVersion = probeVersion();
            }
            ready = true;
            previous.delete();
            File[] leftovers = engineDir.listFiles();
            if (leftovers != null) for (File file : leftovers) {
                if (file.isFile() && file.getName().matches("update-[0-9]+\\.zip")) file.delete();
            }
        } finally {
            operations.writeLock().unlock();
        }
    }

    public boolean isReady() { return ready; }

    public String version() { return engineVersion; }

    public VideoDetails analyze(String pastedUrl) throws Exception {
        String url = normalizeUrl(pastedUrl);
        operations.readLock().lockInterruptibly();
        try {
            requireReady();
            YoutubeDLRequest request = baseRequest(url);
            request.addOption("--dump-single-json");
            request.addOption("--skip-download");
            request.addOption("--no-progress");
            request.addOption("-f", "bv*+ba/b");
            YoutubeDLResponse response = YoutubeDL.getInstance().execute(request, null, null);
            return parseDetails(url, response.getOut());
        } finally {
            operations.readLock().unlock();
        }
    }

    /** Package-visible so local tests exercise the exact production metadata parser. */
    static VideoDetails parseDetails(String url, String rawJson) throws Exception {
            String json = rawJson.trim();
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start < 0 || end < start) throw new IOException("Video information was empty. Try again.");
            JSONObject data = new JSONObject(json.substring(start, end + 1));
            String live = data.optString("live_status", "");
            if (data.optBoolean("is_live") || "is_live".equals(live) || "is_upcoming".equals(live)) {
                throw new IOException("Live streams are not supported. Choose a completed video.");
            }

            JSONArray formats = data.optJSONArray("formats");
            Map<String, Stream> byId = new HashMap<>();
            Map<Integer, Stream> byResolution = new TreeMap<>(Collections.reverseOrder());
            Stream bestAudio = null;
            Stream bestVideo = null;
            if (formats != null) {
                // yt-dlp supplies formats from least to most preferred.
                for (int i = 0; i < formats.length(); i++) {
                    JSONObject object = formats.optJSONObject(i);
                    if (object == null || !playableFormat(object)) continue;
                    Stream stream = new Stream(object);
                    if (!FORMAT_ID.matcher(stream.id).matches() || (!stream.video && !stream.audio)) continue;
                    byId.put(stream.id, stream);
                    if (stream.audio && !stream.video) bestAudio = stream;
                    if (stream.video) {
                        bestVideo = stream;
                        if (stream.resolution > 0) byResolution.put(stream.resolution, stream);
                    }
                }
            }
            if (bestVideo == null && bestAudio == null) {
                throw new IOException("No downloadable quality is available. Update the engine and try again.");
            }

            List<Quality> qualities = new ArrayList<>();
            String selected = data.optString("format_id", "");
            String bestFormat = validSelection(selected, byId) ? selected : combine(bestVideo, bestAudio);
            if (bestFormat != null) {
                Stream selectedVideo = videoFromSelection(bestFormat, byId);
                if (selectedVideo != null) {
                    qualities.add(new Quality("best", "Best available",
                            detail(selectedVideo, bestFormat, byId), bestFormat, false,
                            selectedVideo.height, sizeOf(bestFormat, byId)));
                }
            }
            for (Map.Entry<Integer, Stream> entry : byResolution.entrySet()) {
                Stream video = entry.getValue();
                String exact = combine(video, bestAudio);
                if (exact == null) continue;
                String label = entry.getKey() + "p";
                if (entry.getKey() == 2160) label += " · 4K";
                else if (entry.getKey() == 1440) label += " · QHD";
                else if (entry.getKey() == 1080) label += " · Full HD";
                else if (entry.getKey() == 720) label += " · HD";
                qualities.add(new Quality("video_" + entry.getKey(), label,
                        detail(video, exact, byId), exact, false, video.height, sizeOf(exact, byId)));
            }
            if (bestAudio != null) {
                String detail = bestAudio.ext.toUpperCase(Locale.ROOT) + " · Original audio";
                if (bestAudio.bytes > 0) detail += " · ~" + readableBytes(bestAudio.bytes);
                qualities.add(new Quality("audio", "Audio only", detail, bestAudio.id,
                        true, 0, bestAudio.bytes));
            }
            if (qualities.isEmpty()) throw new IOException("No playable video or audio is available for this link.");
            long duration = Math.max(0L, (long) data.optDouble("duration", 0));
            return new VideoDetails(data.optString("id", ""),
                    data.optString("title", "YouTube video"),
                    data.optString("channel", data.optString("uploader", "YouTube")),
                    durationLabel(duration), safeThumbnail(data.optString("thumbnail", "")),
                    url, duration, qualities);
    }

    public File download(Job job, File workDir, ProgressListener listener,
                         BooleanSupplier canceled) throws Exception {
        if (canceled.getAsBoolean()) throw new CancellationException("Paused");
        operations.readLock().lockInterruptibly();
        try {
            requireReady();
            if (!workDir.isDirectory() && !workDir.mkdirs()) throw new IOException("Cannot create the download folder.");
            if (job.format == null || !job.format.matches("[A-Za-z0-9_.-]+(?:\\+[A-Za-z0-9_.-]+)*")) {
                throw new IOException("Choose a video quality again before downloading.");
            }
            if (canceled.getAsBoolean()) throw new CancellationException("Paused");
            File complete = completedFile(workDir);
            if (complete != null) return complete;
            // An interrupted FFmpeg merge can leave a final-looking but incomplete file.
            // Only yt-dlp's after_move checkpoint makes such a file eligible for export.
            File[] previousFiles = workDir.listFiles();
            if (previousFiles != null) for (File file : previousFiles) {
                if ((file.getName().equals("complete.txt") || file.getName().matches("media\\.[A-Za-z0-9]{1,8}"))
                        && file.isFile() && !file.delete()) {
                    throw new IOException("Cannot prepare the saved download for resume.");
                }
            }
            YoutubeDLRequest request = baseRequest(normalizeUrl(job.url));
            request.addOption("-f", job.format);
            request.addOption("-o", new File(workDir, "media.%(ext)s").getAbsolutePath());
            request.addOption("--continue");
            request.addOption("--part");
            request.addOption("--no-overwrites");
            request.addOption("--no-mtime");
            request.addOption("--keep-video");
            request.addOption("--newline");
            request.addOption("--progress");
            request.addOption("--no-simulate");
            request.addOption("--concurrent-fragments", 8);
            request.addOption("--abort-on-unavailable-fragments");
            request.addOption("--retry-sleep", "fragment:exp=1:8");
            request.addOption("--print", "after_move:" + FINAL_MARKER + "%(filepath)s");
            request.addCommands(Arrays.asList("--print-to-file", "after_move:%(filepath)s",
                    new File(workDir, "complete.txt").getAbsolutePath()));
            if (!job.audioOnly) request.addOption("--merge-output-format", "mkv");
            File binaries = new File(context.getApplicationInfo().nativeLibraryDir);
            request.addOption("--js-runtimes", "quickjs:" + new File(binaries, "libqjs.so").getAbsolutePath());
            request.addOption("--ffmpeg-location", new File(binaries, "libffmpeg.so").getAbsolutePath());

            String response;
            try {
                response = transfers.execute(job.id, request.buildCommand(), line -> {
                    if (canceled.getAsBoolean()) {
                        cancel(job.id);
                        return;
                    }
                    float value = -1f;
                    Matcher matcher = PROGRESS.matcher(line);
                    if (matcher.find()) {
                        try { value = Float.parseFloat(matcher.group(1)); } catch (NumberFormatException ignored) { }
                    }
                    // 100% here can be one stream; completion is announced only after merging/saving.
                    if (value >= 100f) value = 99f;
                    if (line.startsWith("[Merger]") || line.startsWith("[Fixup")) {
                        listener.onProgress(99f, -1L, line);
                    } else if (!line.startsWith(FINAL_MARKER) && !line.trim().isEmpty()) {
                        listener.onProgress(value, -1L, line);
                    }
                }, canceled);
            } catch (Exception error) {
                if (canceled.getAsBoolean() || error instanceof CancellationException) {
                    throw new CancellationException("Paused");
                }
                throw error;
            }
            if (canceled.getAsBoolean()) throw new CancellationException("Paused");
            File checkpoint = completedFile(workDir);
            if (checkpoint != null) return checkpoint;
            for (String line : response.split("\\r?\\n")) {
                if (line.startsWith(FINAL_MARKER)) {
                    File result = new File(line.substring(FINAL_MARKER.length()).trim()).getCanonicalFile();
                    if (!workDir.getCanonicalFile().equals(result.getParentFile())) {
                        throw new IOException("The downloaded file has an unexpected location.");
                    }
                    if (result.isFile() && result.length() > 0) return result;
                }
            }
            // Never treat a surviving .part or separate fNNN stream as a completed download.
            throw new IOException("The download ended before a completed media file was produced. Tap Resume to retry.");
        } finally {
            operations.readLock().unlock();
        }
    }

    public void cancel(String processId) {
        if (processId == null) return;
        transfers.cancel(processId);
    }

    /** Install an official, checksum-verified engine update, rolling back on failed validation. */
    public String update() throws Exception {
        operations.writeLock().lockInterruptibly();
        File staged = null;
        try {
            requireReady();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
            JSONObject release = new JSONObject(new String(fetch(STABLE_API, 2 * 1024 * 1024, deadline), StandardCharsets.UTF_8));
            JSONArray assets = release.getJSONArray("assets");
            String engineUrl = null;
            String sumsUrl = null;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                String name = asset.optString("name");
                if ("yt-dlp".equals(name)) engineUrl = asset.getString("browser_download_url");
                if ("SHA2-256SUMS".equals(name)) sumsUrl = asset.getString("browser_download_url");
            }
            if (engineUrl == null || sumsUrl == null) throw new IOException("The update is missing its engine or verification file.");
            requireOfficialAsset(engineUrl);
            requireOfficialAsset(sumsUrl);
            String sums = new String(fetch(sumsUrl, 1024 * 1024, deadline), StandardCharsets.UTF_8);
            Matcher match = Pattern.compile("(?m)^([a-fA-F0-9]{64})\\s+\\*?yt-dlp\\s*$").matcher(sums);
            if (!match.find()) throw new IOException("The engine checksum is missing. The current engine has been kept.");
            String expected = match.group(1);
            byte[] binary = fetch(engineUrl, 64 * 1024 * 1024, deadline);
            if (!expected.equalsIgnoreCase(sha256(binary))) {
                throw new IOException("Update verification failed. The current engine has been kept.");
            }
            File engineDir = new File(context.getNoBackupFilesDir(), "youtubedl-android/yt-dlp");
            if (!engineDir.isDirectory()) throw new IOException("Initialize the download engine first.");
            staged = File.createTempFile("update-", ".zip", engineDir);
            try (FileOutputStream output = new FileOutputStream(staged)) {
                output.write(binary);
                output.getFD().sync();
            }
            try (ZipFile zip = new ZipFile(staged)) {
                ZipEntry version = zip.getEntry("yt_dlp/version.py");
                if (version == null || zip.getEntry("yt_dlp/__init__.py") == null) {
                    throw new IOException("The downloaded engine is incomplete. The current engine has been kept.");
                }
            }
            File installed = new File(engineDir, "yt-dlp");
            File previous = new File(engineDir, "yt-dlp.previous");
            if (previous.exists() && !previous.delete()) throw new IOException("Cannot prepare the engine update.");
            if (!installed.renameTo(previous)) throw new IOException("Cannot back up the current engine.");
            try {
                if (!staged.renameTo(installed)) throw new IOException("Cannot install the engine update.");
                String verifiedVersion = probeVersion();
                engineVersion = verifiedVersion;
                previous.delete();
            } catch (Exception error) {
                installed.delete();
                if (!previous.renameTo(installed)) {
                    ready = false;
                    throw new IOException("The engine update could not be restored. Restart the app to repair it.", error);
                }
                throw new IOException("The update could not run; the previous engine was restored.", error);
            }
            return engineVersion;
        } finally {
            if (staged != null && staged.exists()) staged.delete();
            operations.writeLock().unlock();
        }
    }

    public static String normalizeUrl(String pastedText) throws IllegalArgumentException {
        if (pastedText == null || pastedText.trim().isEmpty()) {
            throw new IllegalArgumentException("Paste a YouTube video link first.");
        }
        if (pastedText.length() > 16_384) throw new IllegalArgumentException("That link is too long. Share one YouTube video link.");
        Matcher matcher = URL_CANDIDATE.matcher(pastedText.trim());
        while (matcher.find()) {
            String candidate = matcher.group().replaceAll("[).,;!\\]}]+$", "");
            if (!candidate.regionMatches(true, 0, "http", 0, 4)) candidate = "https://" + candidate;
            try {
                URI uri = new URI(candidate);
                if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) continue;
                String host = uri.getHost();
                if (host == null || uri.getRawUserInfo() != null) continue;
                host = host.toLowerCase(Locale.ROOT);
                boolean shortLink = "youtu.be".equals(host) || "www.youtu.be".equals(host);
                boolean youtube = "youtube.com".equals(host) || "www.youtube.com".equals(host)
                        || "m.youtube.com".equals(host) || "music.youtube.com".equals(host)
                        || "youtube-nocookie.com".equals(host) || "www.youtube-nocookie.com".equals(host);
                if (!shortLink && !youtube) continue;
                if (uri.getPort() != -1 && uri.getPort() != 443 && uri.getPort() != 80) continue;
                String path = uri.getPath() == null ? "" : uri.getPath();
                String id = null;
                if (shortLink) {
                    String[] segments = path.split("/");
                    if (segments.length == 2) id = segments[1];
                } else if ("/watch".equals(path) || "/watch/".equals(path)) {
                    String query = uri.getRawQuery();
                    if (query != null) for (String pair : query.split("&")) {
                        String[] parts = pair.split("=", 2);
                        if (parts.length == 2 && "v".equals(URLDecoder.decode(parts[0], "UTF-8"))) {
                            id = URLDecoder.decode(parts[1], "UTF-8");
                            break;
                        }
                    }
                } else {
                    String[] segments = path.split("/");
                    if (segments.length == 3 && ("shorts".equals(segments[1])
                            || "embed".equals(segments[1]) || "live".equals(segments[1])
                            || "v".equals(segments[1]))) id = segments[2];
                }
                if (id != null && VIDEO_ID.matcher(id).matches()) {
                    return "https://www.youtube.com/watch?v=" + id;
                }
            } catch (Exception ignored) { }
        }
        throw new IllegalArgumentException("Use a single YouTube video or Shorts link, for example youtube.com/watch?v=…");
    }

    public static String friendlyError(Throwable error) {
        if (error instanceof CancellationException || error instanceof YoutubeDL.CanceledException) return "Paused. Your partial download is saved.";
        String message = error == null || error.getMessage() == null ? "" : error.getMessage();
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("no space left") || lower.contains("enospc")) return "Your phone is out of storage. Free up some space, then tap Resume.";
        if (lower.contains("requested format is not available")) return "This exact quality is no longer available. Analyze the link again and choose a new download; the old partial file is kept.";
        if (lower.contains("sign in to confirm") || lower.contains("not a bot") || lower.contains("captcha")) return "YouTube blocked this request. Try a different connection, update the engine, and retry later.";
        if (lower.contains("private video") || lower.contains("members-only") || lower.contains("premium") || lower.contains("login required") || lower.contains("sign in")) return "This video requires an account or special access. Choose a publicly available video.";
        if (lower.contains("age-restricted") || lower.contains("age restricted")) return "This video requires age verification and cannot be downloaded without signing in.";
        if (lower.contains("http error 403") || lower.contains("http error 429")) return "YouTube refused the request. Update the engine or try again later; your partial download is kept.";
        if (lower.contains("not available in your country") || lower.contains("geo restricted")) return "This video is not available in your region.";
        if (lower.contains("video unavailable") || lower.contains("has been removed")) return "This video is unavailable or has been removed.";
        if (lower.contains("timed out") || lower.contains("timeout") || lower.contains("unable to resolve")
                || lower.contains("name resolution") || lower.contains("network is unreachable")
                || lower.contains("connection reset") || lower.contains("failed to connect")) return "The connection was interrupted. Check your internet and tap Resume; downloaded parts are kept.";
        if (lower.contains("permission denied") || lower.contains("read-only file")) return "The app could not write the video. Check available storage and try again.";
        if (lower.contains("unsupported url")) return "That YouTube link is not supported. Share a single video or Shorts link.";
        if (lower.contains("javascript") || lower.contains("n challenge") || lower.contains("signature extraction")) return "YouTube changed its video format. Update the engine in Settings, then try again.";
        // Keep concise application-authored messages; avoid exposing giant native tracebacks.
        if (!message.isEmpty() && message.length() <= 240 && !message.contains("Traceback") && !message.contains("/data/")) {
            return message.replace("ERROR: ", "").trim();
        }
        return "The download could not finish. Check your connection, update the engine, and try again. Your partial download is kept.";
    }

    private YoutubeDLRequest baseRequest(String url) {
        YoutubeDLRequest request = new YoutubeDLRequest(url);
        request.addOption("--ignore-config");
        request.addOption("--no-playlist");
        request.addOption("--no-colors");
        request.addOption("--socket-timeout", 25);
        request.addOption("--retries", 5);
        request.addOption("--fragment-retries", 8);
        request.addOption("--extractor-retries", 3);
        request.addOption("--retry-sleep", "http:exp=1:8");
        request.addOption("--cache-dir", new File(context.getCacheDir(), "extractor").getAbsolutePath());
        request.addOption("--remote-components", "ejs:github");
        return request;
    }

    private String probeVersion() throws Exception {
        // Startup and updater rollback both validate adapter imports/registration
        // against the installed engine before it becomes available to transfers.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        String result = transfers.execute("engine-probe", Arrays.asList("--ignore-config", "--version"),
                null, () -> System.nanoTime() > deadline).trim();
        if (!result.matches("[A-Za-z0-9_.+-]{3,80}")) throw new IOException("The download engine did not start correctly.");
        return result;
    }

    private void requireReady() throws IOException {
        if (!ready) throw new IOException("The download engine is still preparing. Please try again in a moment.");
    }

    private static final class Stream {
        final String id, ext;
        final boolean audio, video;
        final int width, height, resolution;
        final double fps;
        final long bytes;
        Stream(JSONObject object) {
            id = object.optString("format_id", "");
            ext = object.optString("ext", "media");
            video = codecPresent(object.optString("vcodec", "none"));
            audio = codecPresent(object.optString("acodec", "none"));
            width = object.optInt("width", 0);
            height = object.optInt("height", 0);
            resolution = width > 0 && height > 0 ? Math.min(width, height) : height;
            fps = object.optDouble("fps", 0d);
            long exact = object.optLong("filesize", 0L);
            bytes = Math.max(0L, exact > 0 ? exact : object.optLong("filesize_approx", 0L));
        }
    }

    private static boolean codecPresent(String codec) {
        return codec != null && !codec.isEmpty() && !"none".equals(codec) && !"null".equals(codec);
    }

    private static boolean playableFormat(JSONObject object) {
        if (object.optBoolean("has_drm", false)) return false;
        if ("mhtml".equalsIgnoreCase(object.optString("protocol"))
                || "mhtml".equalsIgnoreCase(object.optString("ext"))
                || "images".equalsIgnoreCase(object.optString("vcodec"))) return false;
        String url = object.optString("url", "");
        String manifest = object.optString("manifest_url", "");
        return url.startsWith("https://") || url.startsWith("http://")
                || manifest.startsWith("https://") || manifest.startsWith("http://");
    }

    private static String combine(Stream video, Stream audio) {
        if (video == null) return null;
        return video.audio ? video.id : audio != null ? video.id + "+" + audio.id : null;
    }

    private static boolean validSelection(String selection, Map<String, Stream> streams) {
        if (selection.isEmpty()) return false;
        boolean audio = false, video = false;
        for (String id : selection.split("\\+")) {
            Stream stream = streams.get(id);
            if (stream == null) return false;
            audio |= stream.audio;
            video |= stream.video;
        }
        return audio && video;
    }

    private static Stream videoFromSelection(String selection, Map<String, Stream> streams) {
        for (String id : selection.split("\\+")) {
            Stream stream = streams.get(id);
            if (stream != null && stream.video) return stream;
        }
        return null;
    }

    private static long sizeOf(String selection, Map<String, Stream> streams) {
        long total = 0L;
        for (String id : selection.split("\\+")) {
            Stream stream = streams.get(id);
            if (stream == null || stream.bytes <= 0) return 0L;
            total += stream.bytes;
        }
        return total;
    }

    private static String detail(Stream video, String selection, Map<String, Stream> streams) {
        String container = selection.contains("+") ? "MKV" : video.ext.toUpperCase(Locale.ROOT);
        String description = video.resolution > 0 ? video.resolution + "p · " : "";
        if (video.fps > 30.5) description += Math.round(video.fps) + " fps · ";
        description += container + " · Original quality";
        long bytes = sizeOf(selection, streams);
        if (bytes > 0) description += " · ~" + readableBytes(bytes);
        return description;
    }

    private static String readableBytes(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) return String.format(Locale.US, "%.1f GB", bytes / (1024d * 1024d * 1024d));
        if (bytes >= 1024L * 1024L) return String.format(Locale.US, "%.0f MB", bytes / (1024d * 1024d));
        return String.format(Locale.US, "%.0f KB", bytes / 1024d);
    }

    private static String durationLabel(long seconds) {
        if (seconds <= 0) return "Video";
        if (seconds >= 3600) return String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }

    private static String safeThumbnail(String thumbnail) {
        try {
            URI uri = new URI(thumbnail);
            return "https".equals(uri.getScheme()) && uri.getHost() != null ? thumbnail : "";
        } catch (Exception ignored) { return ""; }
    }

    private static File completedFile(File workDir) throws IOException {
        File marker = new File(workDir, "complete.txt");
        if (!marker.isFile() || marker.length() <= 0 || marker.length() > 8192) return null;
        byte[] bytes = new byte[(int) marker.length()];
        int used = 0;
        try (InputStream input = new FileInputStream(marker)) {
            int count;
            while (used < bytes.length && (count = input.read(bytes, used, bytes.length - used)) > 0) used += count;
        }
        String[] paths = new String(bytes, 0, used, StandardCharsets.UTF_8).trim().split("\\r?\\n");
        for (int i = paths.length - 1; i >= 0; i--) {
            File result = new File(paths[i]).getCanonicalFile();
            if (workDir.getCanonicalFile().equals(result.getParentFile()) && result.isFile() && result.length() > 0
                    && result.getName().matches("media\\.[A-Za-z0-9]{1,8}")) return result;
        }
        return null;
    }

    private static void requireOfficialAsset(String url) throws IOException {
        if (!url.startsWith("https://github.com/yt-dlp/yt-dlp/releases/download/")) {
            throw new IOException("The engine update did not come from its official source.");
        }
    }

    private static byte[] fetch(String target, int limit, long deadline) throws Exception {
        URL url = new URL(target);
        for (int redirect = 0; redirect < 6; redirect++) {
            if (System.nanoTime() > deadline) throw new IOException("Engine update timed out. The current engine has been kept.");
            String host = url.getHost().toLowerCase(Locale.ROOT);
            if (!"https".equals(url.getProtocol()) || !("github.com".equals(host)
                    || "api.github.com".equals(host) || host.endsWith(".githubusercontent.com"))) {
                throw new IOException("The update server returned an unexpected address.");
            }
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(20_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "StreamSave/1.0");
            connection.setRequestProperty("Accept", "application/octet-stream, application/json");
            try {
                int status = connection.getResponseCode();
                if (status >= 300 && status <= 399) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("The update redirect was incomplete.");
                    url = new URL(url, location);
                    continue;
                }
                if (status != 200) throw new IOException("The update server returned " + status + ". Try again later.");
                if (connection.getContentLengthLong() > limit) throw new IOException("The update file is unexpectedly large.");
                try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[32 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (output.size() + count > limit) throw new IOException("The update file is unexpectedly large.");
                        if (System.nanoTime() > deadline) throw new IOException("Engine update timed out. The current engine has been kept.");
                        output.write(buffer, 0, count);
                    }
                    return output.toByteArray();
                }
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Too many update redirects. The current engine has been kept.");
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder(64);
        for (byte item : digest) result.append(String.format(Locale.US, "%02x", item & 255));
        return result.toString();
    }
}
