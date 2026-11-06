package app.streamsave;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Generated-byte loopback fixture; no website or external network is used. */
final class LocalTransferServer implements AutoCloseable {
    private final byte[] data;
    private final ServerSocket server;
    private final ExecutorService handlers = Executors.newFixedThreadPool(8);
    private final Thread acceptor;
    private volatile boolean closed;
    final AtomicInteger active = new AtomicInteger();
    final AtomicInteger peak = new AtomicInteger();
    final AtomicLong largestRange = new AtomicLong();
    final Set<Long> starts = Collections.synchronizedSet(new HashSet<>());

    LocalTransferServer(byte[] data) throws Exception {
        this.data = data;
        server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        acceptor = new Thread(() -> {
            while (!closed) {
                try {
                    Socket socket = server.accept();
                    handlers.execute(() -> serve(socket));
                } catch (Exception error) {
                    if (!closed) throw new RuntimeException(error);
                }
            }
        }, "streamsave-http-fixture");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    String url() { return "http://127.0.0.1:" + server.getLocalPort() + "/generated.bin"; }

    private void serve(Socket socket) {
        boolean counted = false;
        try (Socket connection = socket) {
            connection.setSoTimeout(10_000);
            BufferedReader input = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.US_ASCII));
            if (input.readLine() == null) return;
            String range = "";
            boolean headerPreserved = false;
            String line;
            while ((line = input.readLine()) != null && !line.isEmpty()) {
                if (line.regionMatches(true, 0, "Range:", 0, 6)) range = line.substring(6).trim();
                if (line.equalsIgnoreCase("X-StreamSave-QA: preserved")) headerPreserved = true;
            }
            OutputStream output = connection.getOutputStream();
            if (!headerPreserved) {
                output.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                return;
            }
            Matcher match = Pattern.compile("bytes=([0-9]+)-([0-9]*)").matcher(range);
            boolean partial = match.matches();
            long first = partial ? Long.parseLong(match.group(1)) : 0;
            long last = partial && !match.group(2).isEmpty() ? Long.parseLong(match.group(2)) : data.length - 1;
            last = Math.min(data.length - 1, last);
            long length = last - first + 1;
            starts.add(first);
            largestRange.accumulateAndGet(length, Math::max);
            if (length > 65_536) {
                counted = true;
                peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            }
            String response = "HTTP/1.1 " + (partial ? "206 Partial Content" : "200 OK") + "\r\n"
                    + "Content-Length: " + length + "\r\n"
                    + (partial ? "Content-Range: bytes " + first + "-" + last + "/" + data.length + "\r\n" : "")
                    + "ETag: \"android-fixture\"\r\nConnection: close\r\n\r\n";
            output.write(response.getBytes(StandardCharsets.US_ASCII));
            for (long offset = first; offset <= last; offset += 65_536) {
                int lengthNow = (int) Math.min(65_536, last - offset + 1);
                if (length > 65_536) Thread.sleep(2);
                output.write(data, (int) offset, lengthNow);
                output.flush();
            }
        } catch (Exception ignored) {
            // Cancellation intentionally closes live fixture connections.
        } finally {
            if (counted) active.decrementAndGet();
        }
    }

    @Override public void close() throws Exception {
        closed = true;
        server.close();
        handlers.shutdownNow();
        handlers.awaitTermination(10, TimeUnit.SECONDS);
        acceptor.join(2_000);
    }
}
