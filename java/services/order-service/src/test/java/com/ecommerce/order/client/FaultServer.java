package com.ecommerce.order.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A loopback HTTP server that misbehaves on purpose, for fault-injection tests of the HTTP clients.
 *
 * <p>A real {@code HttpServer} cannot reset a connection, cut a body short or send garbage, which are exactly the
 * failures a provider crash or a middlebox produces. This one speaks just enough HTTP to read a request and then does
 * what the current {@link Fault} says. Set {@link #fault} between calls to script a sequence.
 */
public final class FaultServer implements AutoCloseable {

    public enum Fault {
        /** Reads the request and never answers. */
        HANG,
        /** Reads the request and closes the connection with a TCP reset. */
        RESET,
        /** Answers with bytes that are not HTTP. */
        GARBAGE,
        /** Declares a long JSON body, sends a little, and closes. */
        TRUNCATED_BODY,
        /** Answers {@code 200} with a body that is not JSON. */
        MALFORMED_JSON,
        /** Sends the headers at once and the body a byte at a time, every two seconds: slower than any read timeout a test sets. */
        SLOW_BODY,
        /** Answers with {@link #status}, {@link #headers} and {@link #body}. */
        STATUS
    }

    private final ServerSocket socket;
    private final ExecutorService pool = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "fault-server");
        thread.setDaemon(true);
        return thread;
    });
    private final List<Socket> open = new ArrayList<>();
    private final AtomicInteger requests = new AtomicInteger();

    public volatile Fault fault = Fault.STATUS;
    public volatile int status = 200;
    public volatile String body = "{}";
    public volatile String[] headers = {};

    public FaultServer() throws IOException {
        socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        pool.submit(this::acceptLoop);
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + socket.getLocalPort();
    }

    public int port() {
        return socket.getLocalPort();
    }

    /** Requests whose headers were read, however they were answered. */
    public int requests() {
        return requests.get();
    }

    private void acceptLoop() {
        while (!socket.isClosed()) {
            try {
                Socket client = socket.accept();
                synchronized (open) {
                    open.add(client);
                }
                pool.submit(() -> handle(client));
            } catch (IOException closed) {
                return;
            }
        }
    }

    private void handle(Socket client) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1));
            int contentLength = 0;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                }
            }
            for (int i = 0; i < contentLength; i++) {
                in.read();
            }
            requests.incrementAndGet();
            OutputStream out = client.getOutputStream();
            switch (fault) {
                case HANG -> Thread.sleep(60_000);
                case RESET -> {
                    client.setSoLinger(true, 0);
                    client.close();
                }
                case GARBAGE -> {
                    out.write("\u0000\u0001 this is not http \r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                    client.close();
                }
                case TRUNCATED_BODY -> {
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 500\r\n\r\n{\"outcome\":\"OK\",")
                            .getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                    client.close();
                }
                case MALFORMED_JSON -> write(out, 200, new String[0], "<html>proxy error</html>");
                case SLOW_BODY -> {
                    byte[] payload = "{\"outcome\":\"OK\",\"padding\":\"xxxxxxxxxxxxxxxx\"}".getBytes(StandardCharsets.ISO_8859_1);
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + payload.length
                            + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                    for (byte b : payload) {
                        out.write(b);
                        out.flush();
                        Thread.sleep(2_000); // far above any read timeout a test uses, so the outcome never depends on timing
                    }
                }
                case STATUS -> write(out, status, headers, body);
                default -> throw new IllegalStateException(fault.name());
            }
            if (fault == Fault.STATUS || fault == Fault.MALFORMED_JSON) {
                client.close();
            }
        } catch (IOException | InterruptedException ignored) {
            // the client gave up first, which is what several faults are meant to provoke
        }
    }

    private static void write(OutputStream out, int status, String[] headers, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder("HTTP/1.1 ").append(status).append(" X\r\n");
        boolean hasContentType = false;
        for (int i = 0; i + 1 < headers.length; i += 2) {
            head.append(headers[i]).append(": ").append(headers[i + 1]).append("\r\n");
            hasContentType |= headers[i].equalsIgnoreCase("Content-Type");
        }
        if (!hasContentType) {
            head.append("Content-Type: application/json\r\n");
        }
        head.append("Content-Length: ").append(payload.length).append("\r\nConnection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(payload);
        out.flush();
    }

    @Override
    public void close() throws IOException {
        socket.close();
        synchronized (open) {
            for (Socket client : open) {
                try {
                    client.close();
                } catch (IOException ignored) {
                    // already closed
                }
            }
        }
        pool.shutdownNow();
    }
}
