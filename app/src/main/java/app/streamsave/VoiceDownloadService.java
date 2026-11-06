package app.streamsave;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
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
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Downloads the voice model in a foreground service, so it keeps going when the
 * Study player is closed, another app is opened or the screen turns off. If
 * Android or the user stops it anyway, nothing is lost: VoiceModel keeps every
 * finished file and piece, and the next start continues from there.
 */
public final class VoiceDownloadService extends Service {
    private static final String CHANNEL = "voice_download";
    private static final int NOTIFICATION_ID = 41;
    private static final String ACTION_STOP = "app.streamsave.VOICE_STOP";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<Runnable> LISTENERS = new CopyOnWriteArraySet<>();
    // Shared with the Study player. Written by the main thread, except bytes (download thread).
    private static volatile boolean running;
    private static volatile boolean stopRequested;
    private static volatile long bytes;
    private static volatile String failure = "";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private PowerManager.WakeLock wakeLock;
    private boolean started, finished;
    private long lastNotification;
    private int lastPercent = -1;

    /** Starts (or continues) the voice download; does nothing while one is running. Main thread. */
    static void start(Context context) {
        if (running) return;
        running = true;
        stopRequested = false;
        failure = "";
        bytes = 0;
        try {
            context.startForegroundService(new Intent(context, VoiceDownloadService.class));
        } catch (RuntimeException refused) {
            running = false;
            failure = "Android did not let the voice download start (" + refused.getClass().getSimpleName() + ").";
        }
        changed();
    }

    /** Stops the download; what was downloaded is kept for next time. */
    static void stop() { stopRequested = true; }

    static boolean running() { return running; }

    static int percent() {
        return (int) Math.max(0, Math.min(100, bytes * 100 / VoiceModel.totalBytes()));
    }

    /** Why the last download stopped ("" when it finished, or was stopped on purpose); cleared once read. */
    static String takeFailure() {
        String result = failure;
        failure = "";
        return result;
    }

    /** Called on the main thread whenever the download starts, progresses by 1% or ends. */
    static void listen(Runnable listener) { LISTENERS.add(listener); }

    static void unlisten(Runnable listener) { LISTENERS.remove(listener); }

    private static void changed() {
        for (Runnable listener : LISTENERS) listener.run();
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Voice download", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Progress of the one-time voice-over model download");
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        PowerManager power = getSystemService(PowerManager.class);
        if (power != null) {
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StreamSave:voice");
            wakeLock.setReferenceCounted(false);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stop();
            if (!started) stopSelf(startId);
            return START_NOT_STICKY;
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, notification());
            }
        } catch (RuntimeException refused) {
            if (!started) {
                finish("Android stopped the voice download. Open the voice-over again to continue where it stopped.");
            }
            return START_NOT_STICKY;
        }
        if (!started) {
            started = true;
            worker.execute(this::download);
        }
        return START_NOT_STICKY;
    }

    private void download() {
        if (wakeLock != null) wakeLock.acquire(60 * 60 * 1000L);
        String problem = "";
        try {
            new VoiceModel(new File(getFilesDir(), "voice")).download(value -> {
                bytes = value;
                int percent = percent();
                long now = SystemClock.elapsedRealtime();
                if (percent != lastPercent) {
                    lastPercent = percent;
                    MAIN.post(VoiceDownloadService::changed);
                    if (now - lastNotification >= 1000) {
                        lastNotification = now;
                        try {
                            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
                        } catch (RuntimeException ignored) { /* progress display only */ }
                    }
                }
            }, () -> stopRequested);
        } catch (IOException | RuntimeException error) {
            if (!stopRequested) {
                problem = error.getMessage() == null ? "The voice could not be downloaded." : error.getMessage();
            }
        } finally {
            if (wakeLock != null) {
                try { if (wakeLock.isHeld()) wakeLock.release(); } catch (RuntimeException ignored) { }
            }
        }
        final String result = problem;
        MAIN.post(() -> finish(result));
    }

    private void finish(String problem) {
        if (finished) return;
        finished = true;
        running = false;
        failure = problem == null ? "" : problem;
        changed();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private Notification notification() {
        Intent player = new Intent(this, PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(this, 0, player,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, VoiceDownloadService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long total = VoiceModel.totalBytes();
        int percent = percent();
        String text = String.format(Locale.US, "%d%% · %d of %d MB · continues where it stopped if interrupted",
                percent, bytes / (1024 * 1024), total / (1024 * 1024));
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle("Downloading the voice-over voice")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setProgress(100, percent, bytes == 0)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .setOngoing(true).setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS).setShowWhen(false)
                .build();
    }

    @Override public void onTimeout(int startId, int foregroundServiceType) {
        // Android 15 limits data-sync services; stop cleanly, the download resumes next time.
        stop();
    }

    @Override public void onDestroy() {
        // Only this instance's own unfinished download: never a newer one started after it finished.
        if (started && !finished) stop();
        worker.shutdown();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
