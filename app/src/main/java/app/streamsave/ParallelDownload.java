package app.streamsave;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
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
        fetch(url, size, target, allowed, progress, () -> false);
    }

    /** As above; {@code cancelled} is checked between reads and stops the download with an IOException. */
    static void fetch(URL url, long size, File target, Predicate<URL> allowed, LongConsumer progress,
                      BooleanSupplier cancelled) throws IOException {
        fetch(url, size, target, null, allowed, progress, cancelled);
    }

    /**
     * Like {@link #fetch}, but a download that was stopped (screen closed, app or phone restarted)
     * continues with the missing pieces only. {@code journal} lists each piece once its bytes are
     * safely on disk. The caller must still check the whole file's SHA-256 afterwards: the journal
     * only saves time and never replaces that check.
     */
    static void fetchResumable(URL url, long size, File target, File journal, Predicate<URL> allowed,
                               LongConsumer progress, BooleanSupplier cancelled) throws IOException {
        if (journal == null) throw new IllegalArgumentException("journal");
        fetch(url, size, target, journal, allowed, progress, cancelled);
    }

    private static void fetch(URL url, long size, File target, File journal, Predicate<URL> allowed,
                              LongConsumer progress, BooleanSupplier cancelled) throws IOException {
        if (size <= 0) throw new IOException("The download size is unknown.");
        if (!allowed.test(url)) throw new IOException("Unexpected update download host.");
        AtomicLong done = new AtomicLong();
        Set<Long> finished = journal != null && target.isFile() && target.length() == size
                ? readJournal(journal, size) : new HashSet<>();
        if (finished.isEmpty()) {
            if (journal != null && journal.exists() && !journal.delete()) throw new IOException("Cannot restart the download.");
            try (RandomAccessFile out = new RandomAccessFile(target, "rw")) {
                out.setLength(size);
            }
        }
        if (size <= PIECE_BYTES || !supportsRanges(url, size)) {
            if (journal != null && journal.exists() && !journal.delete()) throw new IOException("Cannot restart the download.");
            single(url, size, target, done, progress, cancelled);
            return;
        }
        if (journal != null && finished.isEmpty()) startJournal(journal, size);
        List<long[]> pieces = new ArrayList<>();
        for (long start = 0; start < size; start += PIECE_BYTES) {
            long last = Math.min(size, start + PIECE_BYTES) - 1;
            if (finished.contains(start)) done.addAndGet(last - start + 1);
            else pieces.add(new long[]{start, last});
        }
        if (done.get() > 0) progress.accept(done.get());
        ExecutorService pool = Executors.newFixedThreadPool(CONNECTIONS);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (long[] piece : pieces) {
                futures.add(pool.submit(() -> {
                    rangeWithRetry(url, size, piece[0], piece[1], target, done, progress, cancelled, journal != null);
                    if (journal != null) recordPiece(journal, piece[0]);
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

    // ---------- resume journal: "pieces <piece size> <file size>" then one finished piece start per line ----------

    private static String journalHeader(long size) { return "pieces " + PIECE_BYTES + " " + size + "\n"; }

    private static void startJournal(File journal, long size) throws IOException {
        try (FileOutputStream out = new FileOutputStream(journal, false)) {
            out.write(journalHeader(size).getBytes(StandardCharsets.US_ASCII));
            out.getFD().sync();
        }
    }

    private static synchronized void recordPiece(File journal, long start) throws IOException {
        try (FileOutputStream out = new FileOutputStream(journal, true)) {
            out.write((start + "\n").getBytes(StandardCharsets.US_ASCII));
            out.getFD().sync();
        }
    }

    /** Finished piece starts; empty when the journal is missing or belongs to another file or piece size. */
    static Set<Long> readJournal(File journal, long size) {
        Set<Long> finished = new HashSet<>();
        if (!journal.isFile() || journal.length() > 1024 * 1024) return finished;
        String text;
        try {
            text = new String(Files.readAllBytes(journal.toPath()), StandardCharsets.US_ASCII);
        } catch (IOException unreadable) {
            return finished;
        }
        if (!text.startsWith(journalHeader(size))) return finished;
        // Only whole lines count: a line cut short by a crash is ignored.
        String[] lines = text.substring(journalHeader(size).length()).split("\n", -1);
        for (int i = 0; i < lines.length - 1; i++) {
            try {
                long start = Long.parseLong(lines[i]);
                if (start >= 0 && start < size && start % PIECE_BYTES == 0) finished.add(start);
            } catch (NumberFormatException ignored) { }
        }
        return finished;
    }

    private static void rangeWithRetry(URL url, long size, long first, long last, File target,
                                       AtomicLong done, LongConsumer progress, BooleanSupplier cancelled,
                                       boolean durable) throws IOException {
        IOException lastError = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                range(url, size, first, last, target, done, progress, cancelled, durable);
                return;
            } catch (IOException error) {
                if (cancelled.getAsBoolean()) throw error;
                lastError = error; // range() already took back its bytes; retry the piece from its start
            }
        }
        throw lastError;
    }

    /** Downloads one piece; on failure it removes its bytes from {@code done} before rethrowing. */
    private static void range(URL url, long size, long first, long last, File target, AtomicLong done,
                              LongConsumer progress, BooleanSupplier cancelled, boolean durable) throws IOException {
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
                    if (cancelled.getAsBoolean()) throw new IOException("Download cancelled.");
                    out.write(buffer, 0, n);
                    received += n;
                    counted += n;
                    progress.accept(done.addAndGet(n));
                }
                if (received != expected) throw new IOException("A part of the update was cut short.");
                if (durable) out.getFD().sync(); // on disk before the journal says this piece is done
            }
        } catch (IOException error) {
            done.addAndGet(-counted);
            throw error;
        } finally {
            connection.disconnect();
        }
    }

    private static void single(URL url, long size, File target, AtomicLong done, LongConsumer progress,
                               BooleanSupplier cancelled) throws IOException {
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
                    if (cancelled.getAsBoolean()) throw new IOException("Download cancelled.");
                    if (received + n > size) throw new IOException("The download is larger than expected.");
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

    /**
     * Follows redirects (absolute or relative) from {@code start} to the URL
     * that serves the file, checking every hop with {@code allowed}.
     */
    static URL resolve(URL start, Predicate<URL> allowed) throws IOException {
        URL current = start;
        for (int hop = 0; hop < 8; hop++) {
            if (!allowed.test(current)) throw new IOException("Unexpected download host: " + current.getHost());
            HttpURLConnection connection = open(current, 0, 0);
            try {
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("A download redirect is missing its target.");
                    current = new URL(current, location);
                    continue;
                }
                if (status == 200 || status == 206) return current;
                throw new IOException("The download server answered HTTP " + status + ".");
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Too many download redirects.");
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
