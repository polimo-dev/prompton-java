package dev.polimo.prompton.http;

import java.time.Duration;
import java.util.Map;

/**
 * One request the SDK wants made.
 *
 * @param method {@code GET} or {@code POST}
 * @param url the absolute URL
 * @param headers request headers, already including {@code Authorization} when there is a key
 * @param body the JSON body for a {@code POST}, or {@code null}
 * @param timeout how long to wait for the response
 */
public record HttpRequest(
        String method, String url, Map<String, String> headers, String body, Duration timeout) {}
