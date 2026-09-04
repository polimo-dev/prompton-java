package dev.polimo.prompton.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** The default {@link PromptOnHttpClient}, on {@code java.net.http.HttpClient}. */
public final class JdkHttpClient implements PromptOnHttpClient {

    private final HttpClient client;

    /** A client with a five-second connect timeout. */
    public JdkHttpClient() {
        this(Duration.ofSeconds(5));
    }

    /**
     * A client with an explicit connect timeout.
     *
     * @param connectTimeout how long to wait for the TCP/TLS handshake
     */
    public JdkHttpClient(Duration connectTimeout) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public HttpResponse send(HttpRequest request) throws IOException {
        java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(request.url()))
                .timeout(request.timeout());
        request.headers().forEach(builder::header);
        if ("POST".equalsIgnoreCase(request.method())) {
            builder.POST(BodyPublishers.ofString(
                    request.body() == null ? "" : request.body(), java.nio.charset.StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }
        try {
            java.net.http.HttpResponse<String> response =
                    client.send(builder.build(), BodyHandlers.ofString());
            Map<String, String> headers = new LinkedHashMap<>();
            response.headers().map().forEach((name, values) -> {
                if (!values.isEmpty()) {
                    headers.put(name.toLowerCase(Locale.ROOT), values.get(0));
                }
            });
            return new HttpResponse(response.statusCode(), response.body(), headers);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while calling " + request.url(), e);
        }
    }

    @Override
    public void close() {
        // java.net.http.HttpClient has no close() on Java 17; its selector thread is released
        // once the client becomes unreachable.
    }
}
