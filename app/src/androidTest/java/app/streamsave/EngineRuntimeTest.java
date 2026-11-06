package app.streamsave;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.test.InstrumentationTestCase;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.json.JSONObject;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises packaged native binaries on Android without contacting any website. */
@SuppressWarnings("deprecation")
public final class EngineRuntimeTest extends InstrumentationTestCase {
    public void testBundledEngineAndJavaScriptRuntimeStart() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        App app = (App) context.getApplicationContext();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (!app.ready && app.initializationError.isEmpty() && System.nanoTime() < deadline) Thread.sleep(100);
        assertTrue("Engine initialization exceeded 120 seconds", app.ready || !app.initializationError.isEmpty());
        assertTrue("Engine failed: " + app.initializationError, app.ready);
        // Engine.initialize imports/registers the adapter and executes --version
        // through the packaged native CPython supervisor and worker.
        assertEquals("2026.10.26", app.engine().version());

        String ffmpeg = runNative(context, "libffmpeg.so", true, "-version");
        assertTrue("Packaged FFmpeg did not run: " + ffmpeg, ffmpeg.contains("ffmpeg version"));

        String quickJsHelp = runNative(context, "libqjs.so", false, "--help");
        assertTrue("Unexpected QuickJS runtime: " + quickJsHelp,
                quickJsHelp.contains("QuickJS version 2025-04-26"));
        String javascript = runNative(context, "libqjs.so", true, "--eval", "console.log(1 + 1)");
        assertEquals("2", javascript.trim());
    }

    public void testSupervisorStopsDescendantsBeforeReturning() throws Exception {
        assertSupervisorCancellation("--streamsave-supervision-test");
    }

    public void testWatchdogStopsStalledSupervisorAndDescendants() throws Exception {
        assertSupervisorCancellation("--streamsave-supervision-stall-test");
    }

    public void testUnexpectedSupervisorExitStopsRemainingDescendants() throws Exception {
        assertSupervisorCancellation("--streamsave-supervision-crash-test");
    }

    private void assertSupervisorCancellation(String mode) throws Exception {
        Context context = getInstrumentation().getTargetContext();
        App app = (App) context.getApplicationContext();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (!app.ready && app.initializationError.isEmpty() && System.nanoTime() < deadline) Thread.sleep(100);
        assertTrue("Engine failed: " + app.initializationError, app.ready);
        NativeTransfer transfer = new NativeTransfer(context);
        AtomicBoolean canceled = new AtomicBoolean();
        AtomicInteger childPid = new AtomicInteger();
        AtomicReference<Throwable> result = new AtomicReference<>();
        CountDownLatch childStarted = new CountDownLatch(1);
        Thread thread = new Thread(() -> {
            try {
                transfer.execute("runtime-supervision-test", Arrays.asList(mode), line -> {
                    if (line.startsWith("STREAMSAVE_TEST_CHILD:")) {
                        childPid.set(Integer.parseInt(line.substring("STREAMSAVE_TEST_CHILD:".length())));
                        childStarted.countDown();
                    }
                }, canceled::get);
                result.set(new AssertionError("Canceled supervisor returned success"));
            } catch (Throwable error) {
                result.set(error);
            }
        }, "streamsave-runtime-test");
        thread.start();
        boolean unexpectedExit = mode.endsWith("-crash-test");
        try {
            assertTrue("Native descendant did not start", childStarted.await(40, TimeUnit.SECONDS));
            if (unexpectedExit) thread.join(20_000);
        } finally {
            if (!unexpectedExit || thread.isAlive()) {
                canceled.set(true);
                transfer.cancel("runtime-supervision-test");
            }
            thread.join(20_000);
        }
        assertFalse("Canceled transfer did not stop", thread.isAlive());
        assertTrue("Wrong cancellation result: " + result.get(), unexpectedExit
                ? result.get() instanceof IOException : result.get() instanceof CancellationException);
        assertTrue("Missing descendant PID", childPid.get() > 0);
        try {
            Os.kill(childPid.get(), 0);
            fail("Native descendant survived after execute returned");
        } catch (ErrnoException error) {
            assertEquals("Unexpected child check failure", OsConstants.ESRCH, error.errno);
        }
    }

    public void testNativeParallelHttpResumesLegacyPrefixAndMatchesHash() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        App app = (App) context.getApplicationContext();
        long startupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (!app.ready && app.initializationError.isEmpty() && System.nanoTime() < startupDeadline) Thread.sleep(100);
        assertTrue("Engine failed: " + app.initializationError, app.ready);
        byte[] source = new byte[16 * 1024 * 1024 + 179];
        new Random(20261106).nextBytes(source);
        File directory = new File(context.getCacheDir(), "native-transfer-qa");
        deleteTree(directory);
        assertTrue(directory.mkdirs());
        File output = new File(directory, "media.mp4");
        File partial = new File(directory, "media.mp4.part");
        int legacyPrefix = 1_048_777;
        try (FileOutputStream stream = new FileOutputStream(partial)) {
            stream.write(source, 0, legacyPrefix);
        }
        try (LocalTransferServer server = new LocalTransferServer(source)) {
            JSONObject info = new JSONObject();
            info.put("id", "native-offline-fixture");
            info.put("title", "Generated native transfer fixture");
            info.put("extractor", "youtube");
            info.put("extractor_key", "Youtube");
            info.put("url", server.url());
            info.put("protocol", "http");
            info.put("format_id", "137");
            info.put("filesize", source.length);
            info.put("ext", "mp4");
            info.put("vcodec", "avc1");
            info.put("acodec", "none");
            info.put("http_headers", new JSONObject().put("X-StreamSave-QA", "preserved"));
            File metadata = new File(directory, "fixture.json");
            try (FileOutputStream stream = new FileOutputStream(metadata)) {
                stream.write(info.toString().getBytes(StandardCharsets.UTF_8));
            }
            NativeTransfer transfer = new NativeTransfer(context);
            AtomicBoolean parallel = new AtomicBoolean();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            transfer.execute("native-http-fixture", Arrays.asList(
                    "--ignore-config", "--load-info-json", metadata.getAbsolutePath(),
                    "--continue", "--no-mtime", "--no-simulate", "--no-progress",
                    "--socket-timeout", "10", "--retries", "1", "--fixup", "never",
                    "-o", output.getAbsolutePath()), line -> {
                if (line.contains("[streamsave] Parallel transfer")) parallel.set(true);
            }, () -> System.nanoTime() > deadline);
            assertTrue("The packaged parallel path did not run", parallel.get());
            assertTrue("Native download did not produce a file", output.isFile());
            assertEquals(source.length, output.length());
            MessageDigest expected = MessageDigest.getInstance("SHA-256");
            MessageDigest actual = MessageDigest.getInstance("SHA-256");
            try (FileInputStream input = new FileInputStream(output)) {
                byte[] buffer = new byte[65_536];
                int count;
                while ((count = input.read(buffer)) != -1) actual.update(buffer, 0, count);
            }
            assertTrue("Downloaded source bytes changed", Arrays.equals(expected.digest(source), actual.digest()));
            assertTrue("Legacy prefix was not resumed at its exact byte offset", server.starts.contains((long) legacyPrefix));
            assertTrue("Native requests exceeded the8MiB bound", server.largestRange.get() <= 8 * 1024 * 1024);
            assertTrue("Native HTTP transfer was not concurrent", server.peak.get() >= 2);
            assertFalse("Completed partial file was left behind", partial.exists());
        } finally {
            deleteTree(directory);
        }
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }

    private String runNative(Context context, String binary, boolean requireZero,
                             String... arguments) throws Exception {
        File executable = new File(context.getApplicationInfo().nativeLibraryDir, binary);
        assertTrue("Missing native binary: " + binary, executable.isFile());
        List<String> command = new ArrayList<>();
        command.add(executable.getAbsolutePath());
        command.addAll(Arrays.asList(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        File packages = new File(context.getNoBackupFilesDir(), "youtubedl-android/packages");
        builder.environment().put("LD_LIBRARY_PATH",
                new File(packages, "python/usr/lib").getAbsolutePath() + ":"
                        + new File(packages, "ffmpeg/usr/lib").getAbsolutePath());
        builder.environment().put("TMPDIR", context.getCacheDir().getAbsolutePath());
        Process process = builder.start();
        boolean terminated = process.waitFor(30, TimeUnit.SECONDS);
        if (!terminated) process.destroy();
        assertTrue(binary + " exceeded its 30 second runtime test limit", terminated);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream input = process.getInputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
        String text = new String(output.toByteArray(), StandardCharsets.UTF_8);
        if (requireZero) assertEquals(binary + " failed: " + text, 0, process.exitValue());
        return text;
    }
}
