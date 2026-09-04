package dev.polimo.prompton.http;

import java.util.Locale;
import java.util.Map;

/**
 * One response.
 *
 * @param status the HTTP status
 * @param body the response body, or an empty string (a {@code 304} has none)
 * @param headers response headers with lowercase names
 */
public record HttpResponse(int status, String body, Map<String, String> headers) {

    /** A header value by case-insensitive name, or {@code null}. */
    public String header(String name) {
        return headers == null ? null : headers.get(name.toLowerCase(Locale.ROOT));
    }
}
