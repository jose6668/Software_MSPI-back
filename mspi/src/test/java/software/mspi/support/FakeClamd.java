package software.mspi.support;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** Test peer for exercising the same TCP client used in the running service. */
public final class FakeClamd implements AutoCloseable {
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy", Locale.ENGLISH).withZone(ZoneOffset.UTC);
    private final ServerSocket listener = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
    private final java.util.concurrent.ExecutorService workers = Executors.newFixedThreadPool(4, task -> {
        var thread = new Thread(task, "fake-clamd");
        thread.setDaemon(true);
        return thread;
    });
    public final AtomicInteger scans = new AtomicInteger();
    public volatile String version;
    public volatile Function<byte[], String> verdict = bytes -> "stream: OK";

    public FakeClamd() throws IOException {
        var accept = new Thread(() -> {
            while (!listener.isClosed()) {
                try {
                    var connection = listener.accept();
                    workers.execute(() -> respond(connection));
                } catch (IOException closed) { return; }
            }
        }, "fake-clamd-listener");
        accept.setDaemon(true);
        accept.start();
    }

    public int port() { return listener.getLocalPort(); }

    public static String catalog(Instant date) { return "ClamAV 1.4.6/12345/" + DATE.format(date); }

    private void respond(Socket connection) {
        try (connection) {
            connection.setSoTimeout(4000);
            var input = new DataInputStream(connection.getInputStream());
            byte[] header = input.readNBytes(9);
            String command = new String(header, StandardCharsets.US_ASCII);
            String response;
            if ("zVERSION\0".equals(command)) {
                response = version == null ? catalog(Instant.now()) : version;
            } else if ("zINSTREAM".equals(command) && input.readUnsignedByte() == 0) {
                var bytes = new ByteArrayOutputStream();
                for (int length; (length = input.readInt()) != 0;) {
                    if (length < 0 || length > 8192 || bytes.size() + length > 10_000_000) return;
                    bytes.write(input.readNBytes(length));
                }
                scans.incrementAndGet();
                response = verdict.apply(bytes.toByteArray());
            } else {
                return;
            }
            if (response != null) connection.getOutputStream().write((response + "\0").getBytes(StandardCharsets.US_ASCII));
        } catch (IOException closed) { /* The client may time out and close. */ }
    }

    @Override public void close() throws IOException {
        listener.close();
        workers.shutdownNow();
    }
}
