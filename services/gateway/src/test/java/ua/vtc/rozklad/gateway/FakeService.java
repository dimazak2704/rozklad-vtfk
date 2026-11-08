package ua.vtc.rozklad.gateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal HTTP backend for gateway tests: answers JSON with its name and the headers it received;
 * {@code /api/events/stream} streams two SSE events {@value #STREAM_PAUSE_MILLIS} ms apart.
 */
final class FakeService implements AutoCloseable {

    static final long STREAM_PAUSE_MILLIS = 1_500;

    private final String name;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    FakeService(String name) {
        this.name = name;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (exchange.getRequestURI().getPath().equals("/api/events/stream")) {
                stream(exchange);
                return;
            }
            String body = """
                    {"service":"%s","path":"%s","requestId":"%s","host":"%s","authorization":"%s"}"""
                    .formatted(name, exchange.getRequestURI().getPath(), header(exchange, "X-Request-Id"),
                            header(exchange, "Host"), header(exchange, "Authorization"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            // як справжні сервіси: повертають отриманий X-Request-Id
            exchange.getResponseHeaders().set("X-Request-Id", header(exchange, "X-Request-Id"));
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private void stream(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        writeEvent(out, "event:ping\ndata:{\"n\":1}\n\n");
        try {
            Thread.sleep(STREAM_PAUSE_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        writeEvent(out, "event:change.updated\ndata:{\"n\":2}\n\n");
    }

    private static void writeEvent(OutputStream out, String event) throws IOException {
        out.write(event.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String header(HttpExchange exchange, String name) {
        return Objects.requireNonNullElse(exchange.getRequestHeaders().getFirst(name), "");
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
