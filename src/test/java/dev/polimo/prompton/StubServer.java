package dev.polimo.prompton;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * A local HTTP server the tests point the SDK at, so the retry, rate-limit and outage behaviour is
 * exercised against real sockets rather than a mocked client.
 */
final class StubServer implements AutoCloseable {

    /** One request the server saw. */
    record Request(String method, String path, String query, Map<String, String> headers, String body) {

        /** A header by lowercase name. */
        String header(String name) {
            return headers.get(name);
        }
    }

    /** What to answer with. */
    record Reply(int status, String body, Map<String, String> headers) {

        /** A 200 with a JSON body. */
        static Reply ok(String body) {
            return new Reply(200, body, Map.of("content-type", "application/json"));
        }

        /** A status with no body. */
        static Reply status(int status) {
            return new Reply(status, "", Map.of());
        }

        /** A status with a JSON body. */
        static Reply of(int status, String body) {
            return new Reply(status, body, Map.of("content-type", "application/json"));
        }

        /** This reply plus one header. */
        Reply withHeader(String name, String value) {
            Map<String, String> merged = new LinkedHashMap<>(headers);
            merged.put(name, value);
            return new Reply(status, body, merged);
        }
    }

    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile Function<Request, Reply> handler = request -> Reply.status(404);

    StubServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("could not start the stub server", e);
        }
        server.createContext("/", this::dispatch);
        server.setExecutor(null);
        server.start();
    }

    /** Replaces the handler. */
    void handle(Function<Request, Reply> newHandler) {
        this.handler = newHandler;
    }

    /** The base URL the SDK should use, already including {@code /api/v1}. */
    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1";
    }

    /** Every request the server has seen, oldest first. */
    List<Request> requests() {
        return List.copyOf(requests);
    }

    /** Requests whose path ends with {@code suffix}. */
    List<Request> requests(String suffix) {
        return requests.stream().filter(r -> r.path().endsWith(suffix)).toList();
    }

    /** Forgets the recorded requests. */
    void reset() {
        requests.clear();
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headers.put(name.toLowerCase(java.util.Locale.ROOT), values.get(0));
            }
        });
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Request request = new Request(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getQuery(),
                headers,
                body);
        requests.add(request);

        Reply reply;
        try {
            reply = handler.apply(request);
        } catch (RuntimeException e) {
            reply = Reply.of(500, "{\"error\":{\"code\":\"internal_error\",\"message\":\"" + e + "\"}}");
        }
        reply.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
        byte[] bytes = reply.body() == null ? new byte[0] : reply.body().getBytes(StandardCharsets.UTF_8);
        if (reply.status() == 304 || bytes.length == 0) {
            exchange.sendResponseHeaders(reply.status(), -1);
        } else {
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
