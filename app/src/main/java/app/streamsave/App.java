package app.streamsave;

import android.app.Application;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.MimeTypeMap;
import android.widget.Toast;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class App extends Application {
    public volatile boolean ready;
    public volatile boolean initializing;
    public volatile String engineStatus = "Preparing download engine…";
    public volatile String initializationError = "";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final ScheduledExecutorService controls = Executors.newSingleThreadScheduledExecutor();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Set<String> canceledIds = ConcurrentHashMap.newKeySet();
    private final AtomicInteger analyses = new AtomicInteger();
    final ReentrantReadWriteLock engineGate = new ReentrantReadWriteLock(true);
    // A replacement Service must wait for the previous native transfer to unwind.
    final ReentrantLock transferGate = new ReentrantLock(true);
    private Engine engine;
    private JobStore store;
    private boolean notificationPending;
    volatile String activeId = "";

    @Override public void onCreate() {
        super.onCreate();
        store = new JobStore(this);
        engine = new Engine(this);
        for (Job job : store.list()) {
            if (job.active()) {
                try {
                    store.change(job.id, j -> {
                        j.state = State.PAUSED;
                        j.statusLine = "Interrupted · tap Resume";
                        j.message = "Your saved progress is ready to continue.";
                    });
                } catch (IOException e) { initializationError = e.getMessage(); }
            }
        }
        initEngine();
    }

    public synchronized void initEngine() {
        if (initializing || ready) return;
        initializing = true;
        initializationError = "";
        engineStatus = "Preparing download engine…";
        changed();
        io.execute(() -> {
            engineGate.writeLock().lock();
            try {
                Publisher.recover(this);
                engine.initialize();
                ready = true;
                engineStatus = "Ready · yt-dlp " + engine.version();
                if (!store.warning.isEmpty()) toast(store.warning);
            } catch (Exception e) {
                ready = false;
                initializationError = friendly(e);
                engineStatus = "Engine needs attention";
            } finally {
                initializing = false;
                engineGate.writeLock().unlock();
                changed();
            }
        });
    }

    public void analyze(String pasted, AnalysisCallback callback) {
        final String url;
        try { url = normalizeUrl(pasted); }
        catch (IllegalArgumentException e) { main.post(() -> callback.failure(e.getMessage())); return; }
        synchronized (this) {
            if (!ready || initializing) {
                main.post(() -> callback.failure(initializationError.isEmpty()
                        ? "The download engine is still preparing. Please try again in a moment."
                        : initializationError));
                return;
            }
            analyses.incrementAndGet();
        }
        io.execute(() -> {
            engineGate.readLock().lock();
            try {
                VideoDetails result = engine.analyze(url);
                main.post(() -> callback.success(result));
            } catch (Exception e) {
                String message = friendly(e);
                main.post(() -> callback.failure(message));
            } finally {
                analyses.decrementAndGet();
                engineGate.readLock().unlock();
            }
        });
    }

    public static String normalizeUrl(String text) { return Engine.normalizeUrl(text); }

    public List<Job> jobs() { return store.list(); }
    Job job(String id) { return store.get(id); }
    Job nextQueued() { return store.next(); }
    Engine engine() { return engine; }

    public synchronized void enqueue(VideoDetails video, Quality quality) {
        if (!ready || initializing) { toast("Wait until the download engine is ready."); return; }
        for (Job existing : jobs()) {
            if (existing.url.equals(video.url) && existing.format.equals(quality.format)
                    && existing.state != State.COMPLETED && existing.state != State.FAILED) {
                toast(existing.state == State.PAUSED ? "This download is paused. Tap Resume in your list."
                        : "This quality is already in your download list.");
                return;
            }
        }
        Job j = new Job();
        j.id = UUID.randomUUID().toString();
        j.url = video.url; j.title = video.title; j.format = quality.format;
        j.qualityLabel = quality.label; j.audioOnly = quality.audioOnly;
        j.createdAt = System.currentTimeMillis();
        try { store.add(j); }
        catch (IOException e) { toast(e.getMessage()); return; }
        changed();
        startDownloads(j.id);
    }

    public void pause(String id) {
        try {
            boolean paused = store.transition(id, j -> j.state == State.QUEUED || j.state == State.DOWNLOADING,
                    j -> { j.state = State.PAUSED; j.statusLine = "Paused · progress saved"; j.message = ""; });
            if (!paused) return;
            if (activeId.equals(id)) requestCancel(id);
            changed();
        } catch (IOException e) { toast(e.getMessage()); }
    }

    public synchronized void resume(String id) {
        if (!ready || initializing) { toast("Wait until the download engine is ready."); return; }
        try {
            boolean queued = store.transition(id, j -> j.state == State.PAUSED || j.state == State.FAILED,
                    j -> { j.state = State.QUEUED; j.statusLine = "Queued to resume"; j.message = ""; });
            if (!queued) return;
            if (!activeId.equals(id)) canceledIds.remove(id);
            changed();
            startDownloads(id);
        } catch (IOException e) { toast(e.getMessage()); }
    }

    public void remove(String id) {
        Job job = store.get(id);
        if (job == null) return;
        if (job.state == State.MERGING || job.state == State.SAVING) {
            toast("Wait for this file to finish saving, then remove it from the list."); return;
        }
        try {
            if (!store.removeIf(id, j -> j.state != State.MERGING && j.state != State.SAVING)) {
                toast("Wait for this file to finish saving, then remove it from the list."); return;
            }
        }
        catch (IOException e) { toast(e.getMessage()); return; }
        if (activeId.equals(id)) requestCancel(id);
        else io.execute(() -> cleanupWork(id));
        changed();
    }

    public synchronized void updateEngine(Callback callback) {
        if (initializing || analyses.get() != 0 || jobs().stream().anyMatch(Job::active)) {
            main.post(() -> callback.complete("Pause your downloads and wait for video details to finish loading first.", false));
            return;
        }
        if (!activeId.isEmpty()) {
            main.post(() -> callback.complete("The current download is stopping. Try again in a moment.", false)); return;
        }
        initializing = true; ready = false;
        engineStatus = "Checking for an engine update…";
        changed();
        io.execute(() -> {
            engineGate.writeLock().lock();
            String result;
            boolean success;
            try {
                engine.initialize();
                result = engine.update();
                success = true;
                initializationError = "";
            } catch (Exception e) {
                result = friendly(e); success = false;
                if (!engine.isReady()) initializationError = result;
            }
            finally {
                ready = engine.isReady(); initializing = false;
                engineStatus = ready ? "Ready · yt-dlp " + engine.version() : "Engine needs attention";
                engineGate.writeLock().unlock();
                changed();
            }
            String message = result; boolean ok = success;
            main.post(() -> callback.complete(message, ok));
        });
    }

    void requireChange(String id, Consumer<Job> updater) throws IOException {
        store.change(id, updater);
        changed();
    }

    boolean transition(String id, Predicate<Job> allowed, Consumer<Job> updater) throws IOException {
        boolean result = store.transition(id, allowed, updater);
        if (result) changed();
        return result;
    }

    boolean canceled(String id) {
        return canceledIds.contains(id) || store.get(id) == null;
    }

    void requestCancel(String id) {
        canceledIds.add(id);
        // Cover a pause that races the engine's registration of its child process.
        for (long delay : new long[]{0, 150, 600, 1500}) {
            controls.schedule(() -> {
                if (canceledIds.contains(id) && activeId.equals(id)) {
                    try { engine.cancel(id); } catch (Exception ignored) { }
                }
            }, delay, TimeUnit.MILLISECONDS);
        }
    }

    void finishedAttempt(String id) {
        canceledIds.remove(id);
        if (activeId.equals(id)) activeId = "";
        if (store.get(id) == null) io.execute(() -> cleanupWork(id));
    }

    File workDir(String id) throws IOException {
        if (!id.matches("[a-f0-9-]{36}")) throw new IOException("Invalid download identifier.");
        File root = getExternalFilesDir("work");
        if (root == null) root = new File(getFilesDir(), "work");
        File dir = new File(root, id);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Not enough storage to create the download folder.");
        return dir;
    }

    void cleanupWork(String id) {
        try { deleteTree(workDir(id)); } catch (IOException ignored) { }
    }

    static void deleteTree(File file) {
        File[] contents = file.listFiles();
        if (contents != null) for (File child : contents) deleteTree(child);
        file.delete();
    }

    private void startDownloads(String id) {
        try { startForegroundService(new Intent(this, DownloadService.class).setAction(DownloadService.ACTION_RUN)); }
        catch (Exception e) {
            try { store.change(id, j -> { j.state = State.PAUSED; j.statusLine = "Tap Resume with the app open";
                j.message = "Android could not start the background download. Keep the app open and tap Resume."; }); }
            catch (IOException ignored) { }
            changed(); toast("Keep StreamSave open and tap Resume to start the download.");
        }
    }

    public void open(Context context, String id) { launchFile(context, id, false); }
    public void share(Context context, String id) { launchFile(context, id, true); }

    private void launchFile(Context context, String id, boolean share) {
        Job job = store.get(id);
        if (job == null || job.contentUri.isEmpty()) { toast("This file is not ready yet."); return; }
        Uri uri = Uri.parse(job.contentUri);
        try (android.os.ParcelFileDescriptor ignored = getContentResolver().openFileDescriptor(uri, "r")) {
            if (ignored == null) throw new IOException("Missing file");
        } catch (Exception e) { toast("The saved file was moved or deleted. Download it again to make a new copy."); return; }
        Intent intent = new Intent(share ? Intent.ACTION_SEND : Intent.ACTION_VIEW);
        String mime = getContentResolver().getType(uri);
        if (job.audioOnly && "video/webm".equals(mime)) mime = "audio/webm";
        if (mime == null || mime.equals("application/octet-stream")) mime = job.audioOnly ? "audio/*" : "video/*";
        if (share) { intent.setType(mime); intent.putExtra(Intent.EXTRA_STREAM, uri); }
        else intent.setDataAndType(uri, mime);
        intent.setClipData(ClipData.newRawUri("Downloaded media", uri));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent launch = Intent.createChooser(intent, share ? "Share downloaded file" : "Open downloaded file");
        if (!(context instanceof android.app.Activity)) launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { context.startActivity(launch); }
        catch (ActivityNotFoundException e) { toast("Install a media player that supports this file format."); }
        catch (Exception e) { toast("Android could not open the file. Try sharing it from your download list."); }
    }

    static String mimeFor(File file, boolean audio) {
        String name = file.getName();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT) : "";
        if (ext.equals("mkv")) return "video/x-matroska";
        if (ext.equals("opus")) return "audio/opus";
        if (ext.equals("m4a")) return "audio/mp4";
        if (ext.equals("webm")) return audio ? "audio/webm" : "video/webm";
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime == null ? "application/octet-stream" : mime;
    }

    public void addListener(Runnable listener) { listeners.addIfAbsent(listener); }
    public void removeListener(Runnable listener) { listeners.remove(listener); }

    void changed() {
        synchronized (this) {
            if (notificationPending) return;
            notificationPending = true;
        }
        main.post(() -> {
            synchronized (App.this) { notificationPending = false; }
            for (Runnable listener : listeners) {
                try { listener.run(); } catch (RuntimeException ignored) { }
            }
        });
    }

    void toast(String text) { main.post(() -> Toast.makeText(this, text, Toast.LENGTH_LONG).show()); }

    static String friendly(Throwable e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) return "The operation could not finish. Please try again.";
        message = message.replaceAll("\\u001B\\[[;\\d]*m", "").trim();
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("no space left") || lower.contains("enospc"))
            return "Your device is out of storage. Free some space, then resume the download.";
        if (lower.contains("sign in") || lower.contains("confirm you’re not") || lower.contains("confirm you're not"))
            return "YouTube requires sign-in or a bot check for this video or connection. Try a public video or a different network.";
        if (lower.contains("private video")) return "This video is private. Choose a publicly available video.";
        if (lower.contains("requested format is not available"))
            return "YouTube no longer offers the selected format. Load this link again and choose an available quality.";
        if (lower.contains("unsupported url")) return "Paste a YouTube video or Shorts link.";
        if (lower.contains("timed out") || lower.contains("unable to resolve host") || lower.contains("network is unreachable")
                || lower.contains("temporary failure in name resolution"))
            return "The connection was interrupted. Check your internet and tap Resume or try loading the link again.";
        if (lower.contains("http error 403") || lower.contains("http error 429"))
            return "YouTube temporarily refused this request. Wait a little, try another network, or update the download engine from Settings.";
        if (lower.contains("drm")) return "This video is protected and cannot be downloaded by StreamSave.";
        int error = message.lastIndexOf("ERROR: ");
        if (error >= 0) message = message.substring(error + 7);
        return message.length() > 700 ? message.substring(0, 700) + "…" : message;
    }
}
