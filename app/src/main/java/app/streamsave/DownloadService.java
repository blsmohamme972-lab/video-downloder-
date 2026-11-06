package app.streamsave;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** One durable queue, one foreground transfer, up to eight parallel media fragments. */
public final class DownloadService extends Service {
    public static final String ACTION_RUN = "app.streamsave.RUN";
    private static final String ACTION_PAUSE = "app.streamsave.PAUSE";
    private static final String CHANNEL = "downloads";
    private static final int NOTIFICATION_ID = 40;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean draining = new AtomicBoolean();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean stopping;
    private volatile int latestStartId;
    private volatile String runningId = "";
    private App app;
    private PowerManager.WakeLock wakeLock;
    private long lastNotification;

    @Override public void onCreate() {
        super.onCreate();
        app = (App) getApplication();
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Download progress", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Progress and pause controls for active downloads");
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        PowerManager power = getSystemService(PowerManager.class);
        if (power != null) {
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StreamSave:download");
            wakeLock.setReferenceCounted(false);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        stopping = false;
        try {
            Notification notification = notification(app.job(app.activeId));
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(NOTIFICATION_ID, notification);
        } catch (RuntimeException e) {
            pauseUnfinished("Android stopped the background download. Open the app and tap Resume.");
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_PAUSE.equals(intent.getAction())) {
            String id = intent.getStringExtra("id");
            if (id != null) app.pause(id);
        }
        pump();
        return START_NOT_STICKY;
    }

    private void pump() {
        if (stopping || !draining.compareAndSet(false, true)) return;
        worker.execute(() -> {
            boolean ownsQueue = false;
            try {
                while (!stopping && !(ownsQueue = app.transferGate.tryLock(250, TimeUnit.MILLISECONDS))) {
                    // A previous Service instance is still stopping its native process.
                }
                if (!ownsQueue || stopping) return;
                if (wakeLock != null) wakeLock.acquire(6 * 60 * 60 * 1000L);
                Job next;
                while (!stopping && (next = app.nextQueued()) != null) runJob(next);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                releaseWakeLock();
                if (ownsQueue) app.transferGate.unlock();
                draining.set(false);
                main.post(() -> {
                    // A newer onStartCommand may already have claimed the next job.
                    if (stopping || draining.get()) return;
                    if (!stopping && app.nextQueued() != null) pump();
                    else { stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(latestStartId); }
                });
            }
        });
    }

    private void runJob(Job requested) {
        final String id = requested.id;
        boolean locked = false;
        runningId = id;
        app.activeId = id;
        AtomicReference<IOException> persistenceError = new AtomicReference<>();
        try {
            if (!app.transition(id, j -> j.state == State.QUEUED, j -> {
                j.state = State.DOWNLOADING; j.statusLine = "Connecting to YouTube…"; j.message = "";
            })) return;
            showProgress(true);
            app.engineGate.readLock().lock();
            locked = true;
            if (!app.engine().isReady()) throw new IOException("The download engine is not ready. Open Settings and retry its initialization.");
            if (shouldStop(id)) throw new CancellationException("Paused");
            Publisher.recover(app);
            Job job = app.job(id);
            if (job == null || job.state == State.COMPLETED) return;
            File work = app.workDir(id);
            File completed = existingComplete(job, work);
            if (completed == null) {
                long[] lastWrite = {0};
                completed = app.engine().download(job, work, (progress, eta, line) -> {
                    if (shouldStop(id)) return;
                    String raw = line == null ? "" : line;
                    boolean merging = raw.contains("[Merger]") || raw.contains("[VideoRemuxer]")
                            || raw.contains("[Fixup") || raw.contains("[ExtractAudio]");
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastWrite[0] < 650 && !merging) return;
                    lastWrite[0] = now;
                    try {
                        app.transition(id, j -> j.state == State.DOWNLOADING || j.state == State.MERGING, j -> {
                            if (merging) {
                                j.state = State.MERGING;
                                j.statusLine = "Combining original video + audio…";
                            } else if (j.state == State.DOWNLOADING) {
                                if (Float.isFinite(progress) && progress >= 0) j.progress = Math.min(100, progress);
                                j.statusLine = progressLine(raw, eta);
                            }
                        });
                    } catch (IOException e) {
                        persistenceError.compareAndSet(null, e);
                        app.requestCancel(id);
                    }
                    showProgress(false);
                }, () -> shouldStop(id));
            }
            if (persistenceError.get() != null) throw persistenceError.get();
            if (shouldStop(id)) throw new CancellationException("Paused");
            final File resultFile = completed;
            if (!app.transition(id, j -> j.state == State.DOWNLOADING || j.state == State.MERGING, j -> {
                j.state = State.SAVING; j.progress = 100;
                j.statusLine = "Saving your finished file…";
                j.localFile = resultFile.getAbsolutePath();
            })) return;
            showProgress(true);
            Publisher.save(app, app.job(id), completed, () -> stopping);
            showProgress(true);
        } catch (Exception e) {
            Job current = app.job(id);
            if (current != null && current.state != State.COMPLETED && current.state != State.PAUSED
                    && current.state != State.QUEUED) {
                boolean paused = stopping || e instanceof CancellationException;
                try {
                    app.transition(id,
                            j -> j.state != State.COMPLETED && j.state != State.PAUSED && j.state != State.QUEUED, j -> {
                        j.state = paused ? State.PAUSED : State.FAILED;
                        j.statusLine = paused ? "Paused · progress saved" : "Download stopped · tap Retry";
                        j.message = paused ? "You can continue this download when you are ready." : App.friendly(e);
                    });
                } catch (IOException persistence) { app.toast(persistence.getMessage()); }
            }
        } finally {
            if (locked) app.engineGate.readLock().unlock();
            app.finishedAttempt(id);
            runningId = "";
            app.changed();
        }
    }

    private File existingComplete(Job job, File work) throws IOException {
        if (job.localFile.isEmpty()) return null;
        File file = new File(job.localFile);
        if (!file.getCanonicalPath().startsWith(work.getCanonicalPath() + File.separator)) return null;
        return file.isFile() && file.length() > 0 ? file : null;
    }

    private boolean shouldStop(String id) { return stopping || app.canceled(id); }

    static String progressLine(String raw, long eta) {
        String line = raw.replaceAll("\\u001B\\[[;\\d]*m", "").trim();
        int lastNewline = Math.max(line.lastIndexOf('\n'), line.lastIndexOf('\r'));
        if (lastNewline >= 0) line = line.substring(lastNewline + 1);
        if (line.startsWith("[download]")) line = line.substring(10).trim();
        if (line.contains("Destination:") || line.contains("/data/") || line.contains("/storage/"))
            return "Downloading selected stream…";
        if (line.contains("%") && line.length() <= 180) return line;
        if (line.toLowerCase(java.util.Locale.ROOT).contains("retry")) return "Reconnecting · keeping your progress…";
        if (eta > 0) return "Downloading · about " + (eta < 60 ? eta + "s" : (eta + 59) / 60 + " min") + " remaining";
        return "Downloading selected stream…";
    }

    private void showProgress(boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastNotification < 1000) return;
        lastNotification = now;
        try { getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(app.job(app.activeId))); }
        catch (RuntimeException ignored) { }
    }

    private Notification notification(Job job) {
        Intent launch = new Intent(this, MainActivity.class).putExtra("show_library", true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(this, 0, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = job == null ? "Preparing your download…" : job.statusLine;
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(job == null ? "StreamSave" : job.title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS).setShowWhen(false)
                .setColor(0xff7bf1c9);
        if (job != null && job.state == State.DOWNLOADING) {
            builder.setProgress(100, Math.round(job.progress), job.progress <= 0);
            Intent pause = new Intent(this, DownloadService.class).setAction(ACTION_PAUSE).putExtra("id", job.id);
            PendingIntent action = PendingIntent.getService(this, 1, pause,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(null, "Pause", action).build());
        } else builder.setProgress(0, 0, job != null && job.state != State.COMPLETED && job.state != State.PAUSED);
        return builder.build();
    }

    private void pauseUnfinished(String message) {
        String ownedId = runningId;
        for (Job job : app.jobs()) {
            try { app.transition(job.id,
                    j -> j.active() && (j.id.equals(ownedId) || j.state == State.QUEUED), j -> {
                j.state = State.PAUSED; j.statusLine = "Interrupted · tap Resume"; j.message = message;
            }); } catch (IOException e) { app.toast(e.getMessage()); }
        }
    }

    private void releaseWakeLock() {
        if (wakeLock == null) return;
        synchronized (wakeLock) {
            try { if (wakeLock.isHeld()) wakeLock.release(); }
            catch (RuntimeException ignored) { /* Never strand the queue lock during cleanup. */ }
        }
    }

    @Override public void onTimeout(int startId, int foregroundServiceType) {
        // Android 15 bounds data-sync foreground-service time. Checkpoint instead of crashing.
        stopping = true;
        String ownedId = runningId;
        if (!ownedId.isEmpty()) app.requestCancel(ownedId);
        pauseUnfinished("Android paused this long-running download. Open StreamSave and tap Resume to continue.");
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf(startId);
    }

    @Override public void onDestroy() {
        stopping = true;
        String ownedId = runningId;
        if (!ownedId.isEmpty()) {
            app.requestCancel(ownedId);
            pauseUnfinished("The background service stopped. Tap Resume to continue from saved progress.");
        }
        worker.shutdown();
        releaseWakeLock();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
