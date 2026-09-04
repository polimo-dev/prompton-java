package dev.polimo.prompton;

/**
 * What your provider call returned, told apart into success and failure so the SDK can log it.
 *
 * <p>{@link #error(Object, GenerationError, GenerationOutcome)} keeps the usage and the output on a
 * failure, which is what you want when the call succeeded but the answer failed to parse: the
 * tokens were still spent, and the text is the evidence.
 *
 * @param <T> whatever your own code wants back
 */
public final class ProviderResult<T> {

    private final T value;
    private final GenerationOutcome outcome;
    private final GenerationError error;

    private ProviderResult(T value, GenerationOutcome outcome, GenerationError error) {
        this.value = value;
        this.outcome = outcome;
        this.error = error;
    }

    /** A success carrying what the provider produced. */
    public static <T> ProviderResult<T> ok(T value, GenerationOutcome outcome) {
        return new ProviderResult<>(value, outcome, null);
    }

    /** A success with nothing to record beyond the fact that it worked. */
    public static <T> ProviderResult<T> ok(T value) {
        return new ProviderResult<>(value, null, null);
    }

    /** A failure. */
    public static <T> ProviderResult<T> error(T value, GenerationError error) {
        return new ProviderResult<>(value, null, error);
    }

    /** A failure that still has usage and output worth keeping — a parse failure, typically. */
    public static <T> ProviderResult<T> error(
            T value, GenerationError error, GenerationOutcome outcome) {
        return new ProviderResult<>(value, outcome, error);
    }

    /** What your code gets back from the wrapper. */
    public T value() {
        return value;
    }

    /** What the provider produced, when there is anything. */
    public GenerationOutcome outcome() {
        return outcome;
    }

    /** The failure, or {@code null} on success. */
    public GenerationError error() {
        return error;
    }

    /** Whether this is a failure. */
    public boolean failed() {
        return error != null;
    }
}
