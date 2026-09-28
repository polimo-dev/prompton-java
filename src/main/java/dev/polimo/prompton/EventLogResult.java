package dev.polimo.prompton;

import java.util.List;
import java.util.Map;

/**
 * What a synchronous {@link PromptOn#logEvents(List)} call stored.
 *
 * @param accepted how many trace events PromptOn stored
 * @param duplicates how many event ids PromptOn had already stored
 * @param rejected event rows PromptOn refused while accepting the rest of the batch
 */
public record EventLogResult(
        int accepted, int duplicates, List<Map<String, Object>> rejected) {

    /** No remote event submission happened. */
    public static final EventLogResult EMPTY = new EventLogResult(0, 0, List.of());
}
