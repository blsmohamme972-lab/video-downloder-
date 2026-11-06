package app.streamsave;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/** The updater's parallel downloader against a real local HTTP server. */
public final class ParallelDownloadTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private static final Pattern RANGE = Pattern.compile("bytes=(\\d+)-(\\d+)");
    private final byte[] data = new byte[20 * 1024 * 1024 + 321];
    private ServerSocket server;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger peak = new AtomicInteger();
    private final AtomicInteger requests = new AtomicInteger();
    private volatile boolean ignoreRanges;
    private final AtomicBoolean cutOnce = new AtomicBoolean();
    /** Pieces from this offset send half their bytes, then wait for {@link #release}. */
    private volatile long stallFrom = Long.MAX_VALUE;
    private final CountDownLatch release = new CountDownLatch(1);
    private final Set<Long> rangeStarts = ConcurrentHashMap.newKeySet();

    /** A tiny HTTP/1.1 server (one request per connection) that honours byte ranges. */
    private URL serve() throws IOException {
        new Random(7).nextBytes(data);
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        workers.execute(() -> {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    workers.execute(() -> handle(socket));
                } catch (IOException closed) {
                    return;
                }
            }
        });
        return new URL("http://127.0.0.1:" + server.getLocalPort() + "/app.apk");
    }

    private void handle(Socket socket) {
        requests.incrementAndGet();
        peak.accumulateAndGet(active.incrementAndGet(), Math::max);
        try (Socket s = socket; OutputStream out = s.getOutputStream()) {
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
            String line = in.readLine();
            String range = null;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase(java.util.Locale.ROOT).startsWith("range:")) range = line.substring(6).trim();
            }
            Matcher match = range == null || ignoreRanges ? null : RANGE.matcher(range);
            if (match == null || !match.matches()) {
                out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + data.length + "\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.ISO_8859_1));
                out.write(data);
                return;
            }
            int first = Integer.parseInt(match.group(1));
            int last = Integer.parseInt(match.group(2));
            int length = last - first + 1;
            out.write(("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes " + first + "-" + last + "/" + data.length
                    + "\r\nContent-Length: " + length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            if (length > 1) rangeStarts.add((long) first);
            if (first >= stallFrom) {
                out.write(data, first, length / 2);
                out.flush();
                release.await(10, TimeUnit.SECONDS);
                out.write(data, first + length / 2, length - length / 2);
                return;
            }
            if (length > 1 && first > 0 && cutOnce.compareAndSet(false, true)) {
                out.write(data, first, length / 2); // the connection drops halfway once
                return;
            }
            Thread.sleep(30); // keeps several requests in flight together
            out.write(data, first, length);
        } catch (IOException | InterruptedException ignored) {
            // the client closed the connection or the test ended
        } finally {
            active.decrementAndGet();
        }
    }

    @After public void stop() throws IOException {
        if (server != null) server.close();
        workers.shutdownNow();
    }

    @Test public void downloadsInParallelPiecesWithIdenticalBytes() throws IOException {
        URL url = serve();
        File target = folder.newFile("update.apk");
        AtomicLong last = new AtomicLong();
        ParallelDownload.fetch(url, data.length, target, u -> true, last::set);
        assertArrayEquals(data, Files.readAllBytes(target.toPath()));
        assertEquals(data.length, last.get());
        assertTrue("expected several connections at once, saw " + peak.get(), peak.get() > 1);
        assertTrue(peak.get() <= ParallelDownload.CONNECTIONS + 1); // + the range probe
        assertTrue("the dropped piece was retried", cutOnce.get());
    }

    @Test public void fallsBackToOneRequestWhenRangesAreIgnored() throws IOException {
        URL url = serve();
        ignoreRanges = true;
        File target = folder.newFile("update.apk");
        ParallelDownload.fetch(url, data.length, target, u -> true, bytes -> { });
        assertArrayEquals(data, Files.readAllBytes(target.toPath()));
    }

    /** The voice model download: stopping keeps finished pieces, and the next attempt fetches only the rest. */
    @Test public void resumesWithOnlyTheMissingPieces() throws IOException {
        URL url = serve();
        cutOnce.set(true); // no dropped connection in this test
        long piece = ParallelDownload.PIECE_BYTES;
        stallFrom = 3 * piece; // pieces 0, 1 and 2 finish; 3, 4 and 5 hang halfway
        File target = folder.newFile("voice.onnx.part");
        File journal = new File(folder.getRoot(), "voice.onnx.pieces");
        long lastPiece = data.length - 5 * piece;
        long reachable = 3 * piece + 2 * (piece / 2) + lastPiece / 2;
        AtomicBoolean stop = new AtomicBoolean();
        try {
            ParallelDownload.fetchResumable(url, data.length, target, journal, u -> true, bytes -> {
                if (bytes >= reachable && stop.compareAndSet(false, true)) release.countDown();
            }, stop::get);
            fail("The first attempt should have been stopped");
        } catch (IOException expected) {
            assertTrue(stop.get());
        }
        assertEquals(new HashSet<>(Arrays.asList(0L, piece, 2 * piece)), ParallelDownload.readJournal(journal, data.length));

        stallFrom = Long.MAX_VALUE;
        rangeStarts.clear();
        AtomicLong firstReport = new AtomicLong(-1);
        ParallelDownload.fetchResumable(url, data.length, target, journal, u -> true,
                bytes -> firstReport.compareAndSet(-1, bytes), () -> false);
        assertArrayEquals(data, Files.readAllBytes(target.toPath()));
        assertEquals("progress starts from the kept pieces", 3 * piece, firstReport.get());
        assertEquals("only the missing pieces were requested",
                new HashSet<>(Arrays.asList(3 * piece, 4 * piece, 5 * piece)), rangeStarts);
    }

    @Test public void journalsForAnotherFileOrCutShortLinesAreIgnored() throws IOException {
        long piece = ParallelDownload.PIECE_BYTES;
        long size = 3 * piece;
        File journal = folder.newFile("x.pieces");
        String header = "pieces " + piece + " " + size + "\n";
        Files.write(journal.toPath(), (header + "0\n" + piece + "\n" + (2 * piece)).getBytes(StandardCharsets.US_ASCII));
        assertEquals("the last line was cut short", new HashSet<>(Arrays.asList(0L, piece)),
                ParallelDownload.readJournal(journal, size));
        assertTrue("another file size", ParallelDownload.readJournal(journal, size + 1).isEmpty());
        Files.write(journal.toPath(), (header + "5\n" + (7 * piece) + "\nx\n").getBytes(StandardCharsets.US_ASCII));
        assertTrue("not piece starts", ParallelDownload.readJournal(journal, size).isEmpty());
        assertTrue("missing journal", ParallelDownload.readJournal(new File(folder.getRoot(), "none"), size).isEmpty());
    }

    @Test public void refusesHostsTheUpdaterDoesNotAllow() throws IOException {
        URL url = serve();
        try {
            ParallelDownload.fetch(url, data.length, folder.newFile("update.apk"), u -> false, bytes -> { });
            fail("Downloaded from a refused host");
        } catch (IOException expected) {
            assertEquals(0, requests.get());
        }
    }

    @Test public void onlyGithubOverHttpsIsAllowedForUpdates() throws IOException {
        assertTrue(ParallelDownload.allowedUpdateHost(new URL("https://github.com/x/y/releases/download/v1/a.apk")));
        assertTrue(ParallelDownload.allowedUpdateHost(new URL("https://release-assets.githubusercontent.com/a")));
        assertFalse(ParallelDownload.allowedUpdateHost(new URL("http://github.com/a")));
        assertFalse(ParallelDownload.allowedUpdateHost(new URL("https://github.com.evil.example/a")));
        assertFalse(ParallelDownload.allowedUpdateHost(new URL("https://example.com/a.apk")));
    }
}
