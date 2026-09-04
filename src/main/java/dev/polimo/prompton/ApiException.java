package dev.polimo.prompton;

import java.util.Map;

/** PromptOn answered a direct (non-cached) API call with an error. */
public class ApiException extends PromptOnException {

    private final transient int status;
    private final transient String code;
    private final transient Map<String, Object> details;

    /**
     * @param status the HTTP status
     * @param code the {@code error.code} PromptOn returned
     * @param message the {@code error.message} PromptOn returned
     * @param details the {@code error.details} object, never {@code null}
     */
    public ApiException(int status, String code, String message, Map<String, Object> details) {
        super("PromptOn " + status + " " + (code == null ? "error" : code) + ": " + message);
        this.status = status;
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    /** The HTTP status. */
    public int status() {
        return status;
    }

    /** The {@code error.code} PromptOn returned, for example {@code not_found}. */
    public String code() {
        return code;
    }

    /** The {@code error.details} object; the discriminator for 404s lives here, not in the code. */
    public Map<String, Object> details() {
        return details;
    }
}
