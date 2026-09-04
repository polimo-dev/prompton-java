package dev.polimo.prompton.http;

import java.io.IOException;

/**
 * The one seam between the SDK and the network.
 *
 * <p>Implement it to route PromptOn's own calls through your HTTP stack — a proxy, a shared
 * connection pool, your metrics. It is never used for provider calls: those stay in your code with
 * your own key and client.
 */
public interface PromptOnHttpClient extends AutoCloseable {

    /**
     * Performs one request.
     *
     * @param request what to send
     * @return the response, whatever its status — a non-2xx status is a value, not an exception
     * @throws IOException on a transport failure or a timeout
     */
    HttpResponse send(HttpRequest request) throws IOException;

    /** Releases whatever the implementation holds. The default does nothing. */
    @Override
    default void close() {}
}
