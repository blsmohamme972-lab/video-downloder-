package app.streamsave;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Owns each supervisor until all native download descendants have stopped. */
final class NativeTransfer {
    private static final String WORKER_MARKER = "STREAMSAVE_WORKER:";
    private final Context context;
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final File runner;

    NativeTransfer(Context context) {
        this.context = context.getApplicationContext();
        runner = new File(context.getNoBackupFilesDir(), "streamsave/streamsave_runner.py");
    }

    void initialize() throws IOException {
        File directory = runner.getParentFile();
        if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) {
            throw new IOException("Cannot prepare the accelerated transfer engine.");
        }
        // The asset is part of the signed APK. Replace it on every app startup
        // so an installed update cannot accidentally keep an older adapter.
        File temporary = new File(directory, "streamsave_runner.py.new");
        try (InputStream input = context.getAssets().open("streamsave_runner.py");
             FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.getFD().sync();
        }
        if (!temporary.renameTo(runner)) {
            throw new IOException("Cannot install the accelerated transfer engine.");
        }
    }

    String execute(String id, List<String> arguments, Consumer<String> listener,
                   BooleanSupplier canceled) throws Exception {
        Task task = new Task();
        if (tasks.putIfAbsent(id, task) != null) {
            throw new IOException("The previous download is still stopping. Please try again shortly.");
        }
        ScheduledExecutorService watcher = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "streamsave-native-cancel");
            thread.setDaemon(true);
            return thread;
        });
        StringBuilder transcript = new StringBuilder();
        Process process = null;
        boolean exited = false;
        try {
            if (canceled.getAsBoolean()) throw new CancellationException("Paused");
            watcher.scheduleWithFixedDelay(() -> {
                if (canceled.getAsBoolean()) task.cancel();
                task.checkDeadline();
            }, 0L, 150L, TimeUnit.MILLISECONDS);
            process = processBuilder(arguments).start();
            task.started(process);
            try (BufferedReader input = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String firstLine = input.readLine();
                if (firstLine == null || !firstLine.matches("STREAMSAVE_WORKER:[1-9][0-9]{0,9}:[1-9][0-9]{0,9}")) {
                    throw new IOException("The transfer supervisor did not start: " + (firstLine == null ? "no response" : firstLine));
                }
                String[] ownership = firstLine.substring(WORKER_MARKER.length()).split(":");
                task.allowWorker(Integer.parseInt(ownership[0]), Integer.parseInt(ownership[1]));
                String line;
                while ((line = input.readLine()) != null) {
                    if (transcript.length() > 64 * 1024) transcript.delete(0, transcript.length() - 32 * 1024);
                    transcript.append(line).append('\n');
                    if (!task.canceled && listener != null) listener.accept(line);
                }
            } catch (IOException error) {
                if (!task.canceled && !canceled.getAsBoolean()) throw error;
            }
            int code = waitFor(process, task);
            exited = true;
            if (task.canceled || canceled.getAsBoolean()) throw new CancellationException("Paused");
            if (code != 0) {
                String detail = transcript.toString().trim();
                throw new IOException(detail.isEmpty() ? "The native transfer ended unexpectedly." : detail);
            }
            return transcript.toString();
        } finally {
            watcher.shutdownNow();
            if (process != null && !exited) {
                task.cancel();
                waitFor(process, task);
            }
            tasks.remove(id, task);
        }
    }

    void cancel(String id) {
        Task task = tasks.get(id);
        if (task != null) task.cancel();
    }

    private ProcessBuilder processBuilder(List<String> arguments) {
        File binaryDirectory = new File(context.getApplicationInfo().nativeLibraryDir);
        File base = new File(context.getNoBackupFilesDir(), "youtubedl-android");
        File pythonHome = new File(base, "packages/python/usr");
        List<String> command = new ArrayList<>(Arrays.asList(
                new File(binaryDirectory, "libpython.so").getAbsolutePath(), "-u", runner.getAbsolutePath(),
                new File(base, "yt-dlp/yt-dlp").getAbsolutePath()));
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("LD_LIBRARY_PATH", new File(pythonHome, "lib").getAbsolutePath()
                + ":" + new File(base, "packages/ffmpeg/usr/lib").getAbsolutePath());
        builder.environment().put("SSL_CERT_FILE", new File(pythonHome, "etc/tls/cert.pem").getAbsolutePath());
        builder.environment().put("PYTHONHOME", pythonHome.getAbsolutePath());
        builder.environment().put("HOME", pythonHome.getAbsolutePath());
        builder.environment().put("TMPDIR", context.getCacheDir().getAbsolutePath());
        builder.environment().put("PATH", System.getenv("PATH") + ":" + binaryDirectory.getAbsolutePath());
        return builder;
    }

    /** A canceled supervisor reaps the group before it exits. */
    private static int waitFor(Process process, Task task) throws InterruptedException {
        boolean interrupted = false;
        try {
            for (;;) {
                try {
                    if (process.waitFor(250, TimeUnit.MILLISECONDS)) {
                        task.supervisorExited();
                        task.awaitGroupExit();
                        return process.exitValue();
                    }
                    task.checkDeadline();
                } catch (InterruptedException error) {
                    interrupted = true;
                    task.cancel();
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static final class Task {
        volatile boolean canceled;
        private Process process;
        private int workerGroup;
        private int supervisorPid;
        private long cancelStarted;
        private boolean stopped;

        synchronized void started(Process process) {
            this.process = process;
            if (canceled) process.destroy();
        }

        synchronized void allowWorker(int group, int supervisor) throws IOException {
            workerGroup = group;
            supervisorPid = supervisor;
            if (canceled) {
                signalSupervisor(OsConstants.SIGTERM);
                return;
            }
            process.getOutputStream().write("READY\n".getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().flush();
            process.getOutputStream().close();
        }

        synchronized void cancel() {
            if (canceled || stopped) return;
            canceled = true;
            cancelStarted = System.nanoTime();
            if (supervisorPid > 0) signalSupervisor(OsConstants.SIGTERM);
            else if (process != null) process.destroy();
        }

        synchronized void checkDeadline() {
            if (stopped || process == null) return;
            if (!process.isAlive()) {
                // An unexpectedly terminated supervisor cannot clean up pipes
                // held by grandchildren. Stop its group even without a user
                // cancellation, allowing the blocked output reader to finish.
                killWorker();
                return;
            }
            if (!canceled) return;
            long elapsed = System.nanoTime() - cancelStarted;
            if (elapsed >= TimeUnit.SECONDS.toNanos(3)) killWorker();
            if (elapsed >= TimeUnit.SECONDS.toNanos(5) && supervisorPid > 0) {
                // Older Android Process.destroyForcibly can still send SIGTERM.
                // Explicitly signal the owned PID after stopping its group.
                signalSupervisor(OsConstants.SIGKILL);
            }
        }

        synchronized void supervisorExited() {
            // Stop the watchdog before PID reuse is possible; the owning
            // execute call now confirms group termination before returning.
            stopped = true;
            killWorker();
        }

        private void killWorker() {
            if (workerGroup <= 0) return;
            try { Os.kill(-workerGroup, OsConstants.SIGKILL); } catch (Exception ignored) { }
        }

        void awaitGroupExit() throws InterruptedException {
            int group;
            synchronized (this) { group = workerGroup; }
            if (group <= 0) return;
            // Normally the subreaper already did this. After a forced supervisor
            // kill, wait for Android's init to reap the stopped descendants too.
            // Queue ownership is retained until no process in the group remains.
            for (;;) {
                try { Os.kill(-group, 0); }
                catch (ErrnoException error) {
                    if (error.errno == OsConstants.ESRCH) return;
                    throw new IllegalStateException("Cannot confirm that the native transfer stopped", error);
                }
                Thread.sleep(25);
            }
        }

        private void signalSupervisor(int signal) {
            if (process == null) return;
            if (!process.isAlive()) { killWorker(); return; }
            try { Os.kill(supervisorPid, signal); } catch (Exception ignored) { }
        }
    }
}
