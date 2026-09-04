package dev.polimo.prompton;

/**
 * What your provider call returned, told apart into success and failure so the SDK can log it.
 *
 * <p>{@link #error(Object, LogError, Result)} keeps the usage and the output on a
 * failure, which is what you want when the call succeeded but the answer failed to parse: the
 * tokens were still spent, and the text is the evidence.
 *
 * @param <T> whatever your own code wants back
 */
public final class ProviderResult<T> {

    private final T value;
    private final Result result;
    private final LogError error;

    private ProviderResult(T value, Result result, LogError error) {
        this.value = value;
        this.result = result;
        this.error = error;
    }

    /** A success carrying what the provider produced. */
    public static <T> ProviderResult<T> ok(T value, Result result) {
        return new ProviderResult<>(value, result, null);
    }

    /** A success with nothing to record beyond the fact that it worked. */
    public static <T> ProviderResult<T> ok(T value) {
        return new ProviderResult<>(value, null, null);
    }

    /** A failure. */
    public static <T> ProviderResult<T> error(T value, LogError error) {
        return new ProviderResult<>(value, null, error);
    }

    /** A failure that still has usage and output worth keeping — a parse failure, typically. */
    public static <T> ProviderResult<T> error(
            T value, LogError error, Result result) {
        return new ProviderResult<>(value, result, error);
    }

    /** What your code gets back from the wrapper. */
    public T value() {
        return value;
    }

    /** What the provider produced, when there is anything. */
    public Result result() {
        return result;
    }

    /** The failure, or {@code null} on success. */
    public LogError error() {
        return error;
    }

    /** Whether this is a failure. */
    public boolean failed() {
        return error != null;
    }
}
