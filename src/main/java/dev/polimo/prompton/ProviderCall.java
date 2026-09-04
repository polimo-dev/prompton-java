package dev.polimo.prompton;

/**
 * The provider call {@link UseCase#track(TrackMeta, ProviderCall) UseCase.track} times and logs.
 *
 * @param <T> whatever your own code wants back
 */
@FunctionalInterface
public interface ProviderCall<T> {

    /**
     * Calls the provider with your own key and HTTP client.
     *
     * @return the result, told apart into success and failure
     * @throws Exception anything your code throws; it is logged as {@code error.kind: "app"} and
     *     then rethrown unchanged
     */
    ProviderResult<T> call() throws Exception;
}
