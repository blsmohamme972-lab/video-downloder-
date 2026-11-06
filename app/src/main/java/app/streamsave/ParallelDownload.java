package app.streamsave;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Downloads one file over several HTTP range requests at once, like the
 * video engine does, because a single connection to GitHub's file servers
 * can be much slower than the phone's internet line. Each range is checked
 * (206 status, exact Content-Range and length) and written at its offset.
 * Falls back to one plain request when the server does not honour ranges.
 *
 * Plain Java (java.net only) so it is unit-tested on the JVM.
 */
final class ParallelDownload {
    static final int CONNECTIONS = 6;
    static final long PIECE_BYTES = 4L * 1024 * 1024;
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");

    private ParallelDownload() { }

    /** App updates may only be downloaded from GitHub over HTTPS (checked for every request). */
    static boolean allowedUpdateHost(URL url) {
        if (!"https".equalsIgnoreCase(url.getProtocol())) return false;
        String host = url.getHost().toLowerCase(java.util.Locale.ROOT);
        return host.equals("api.github.com") || host.equals("github.com") || host.endsWith(".githubusercontent.com");
    }

    /**
     * Writes exactly {@code size} bytes from {@code url} into {@code target}.
     * {@code allowed} vets every URL that is opened; {@code progress} receives
     * the running byte count from worker threads.
     */
    static void fetch(URL url, long size, File target, Predicate<URL> allowed, LongConsumer progress)
            throws IOException {
        if (size <= 0) throw new IOException("The update size is unknown.");
        if (!allowed.test(url)) throw new IOException("Unexpected update download host.");
        AtomicLong done = new AtomicLong();
        try (RandomAccessFile out = new RandomAccessFile(target, "rw")) {
            out.setLength(size);
        }
        if (size <= PIECE_BYTES || !supportsRanges(url, size)) {
            single(url, size, target, done, progress);
            return;
        }
        List<long[]> pieces = new ArrayList<>();
        for (long start = 0; start < size; start += PIECE_BYTES) {
            pieces.add(new long[]{start, Math.min(size, start + PIECE_BYTES) - 1});
        }
        ExecutorService pool = Executors.newFixedThreadPool(CONNECTIONS);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (long[] piece : pieces) {
                futures.add(pool.submit(() -> {
                    rangeWithRetry(url, size, piece[0], piece[1], target, done, progress);
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (java.util.concurrent.ExecutionException failure) {
                    Throwable cause = failure.getCause();
                    if (cause instanceof IOException) throw (IOException) cause;
                    throw new IOException("The update download failed.", cause);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("The update download was interrupted.");
                }
            }
        } finally {
            pool.shutdownNow();
        }
        if (done.get() != size) throw new IOException("The APK download was incomplete.");
    }

    /** One small range request tells whether the server returns exact byte ranges. */
    private static boolean supportsRanges(URL url, long size) {
        HttpURLConnection connection = null;
        try {
            connection = open(url, 0, 0);
            return connection.getResponseCode() == 206 && matches(connection, 0, 0, size);
        } catch (IOException error) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void rangeWithRetry(URL url, long size, long first, long last, File target,
                                       AtomicLong done, LongConsumer progress) throws IOException {
        IOException lastError = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                range(url, size, first, last, target, done, progress);
                return;
            } catch (IOException error) {
                lastError = error; // range() already took back its bytes; retry the piece from its start
            }
        }
        throw lastError;
    }

    /** Downloads one piece; on failure it removes its bytes from {@code done} before rethrowing. */
    private static void range(URL url, long size, long first, long last, File target,
                              AtomicLong done, LongConsumer progress) throws IOException {
        HttpURLConnection connection = open(url, first, last);
        long counted = 0;
        try {
            if (connection.getResponseCode() != 206 || !matches(connection, first, last, size)) {
                throw new IOException("The server returned an unexpected part of the update.");
            }
            long expected = last - first + 1;
            try (InputStream in = connection.getInputStream();
                 RandomAccessFile out = new RandomAccessFile(target, "rw")) {
                out.seek(first);
                byte[] buffer = new byte[64 * 1024];
                long received = 0;
                int n;
                while (received < expected && (n = in.read(buffer, 0, (int) Math.min(buffer.length, expected - received))) != -1) {
                    out.write(buffer, 0, n);
                    received += n;
                    counted += n;
                    progress.accept(done.addAndGet(n));
                }
                if (received != expected) throw new IOException("A part of the update was cut short.");
            }
        } catch (IOException error) {
            done.addAndGet(-counted);
            throw error;
        } finally {
            connection.disconnect();
        }
    }

    private static void single(URL url, long size, File target, AtomicLong done, LongConsumer progress)
            throws IOException {
        HttpURLConnection connection = open(url, -1, -1);
        try {
            if (connection.getResponseCode() != 200) {
                throw new IOException("Update download failed (HTTP " + connection.getResponseCode() + ").");
            }
            try (InputStream in = connection.getInputStream();
                 RandomAccessFile out = new RandomAccessFile(target, "rw")) {
                byte[] buffer = new byte[64 * 1024];
                long received = 0;
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (received + n > size) throw new IOException("Update APK exceeds the expected size.");
                    out.write(buffer, 0, n);
                    received += n;
                    progress.accept(done.addAndGet(n));
                }
                if (received != size) throw new IOException("The APK download was incomplete.");
            }
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(URL url, long first, long last) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(45000);
        connection.setRequestProperty("User-Agent", "StreamSave-Android-Updater");
        connection.setRequestProperty("Accept-Encoding", "identity");
        if (first >= 0) connection.setRequestProperty("Range", "bytes=" + first + "-" + last);
        return connection;
    }

    private static boolean matches(HttpURLConnection connection, long first, long last, long size) {
        String header = connection.getHeaderField("Content-Range");
        if (header == null) return false;
        Matcher match = CONTENT_RANGE.matcher(header.trim());
        return match.matches() && Long.parseLong(match.group(1)) == first
                && Long.parseLong(match.group(2)) == last && Long.parseLong(match.group(3)) == size;
    }
}
