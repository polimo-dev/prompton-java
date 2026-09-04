package dev.polimo.prompton;

/** Base class for every exception the SDK throws on purpose. */
public class PromptOnException extends RuntimeException {

    /** @param message what went wrong */
    public PromptOnException(String message) {
        super(message);
    }

    /**
     * @param message what went wrong
     * @param cause the underlying failure
     */
    public PromptOnException(String message, Throwable cause) {
        super(message, cause);
    }
}
