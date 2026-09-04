package dev.polimo.prompton;

import java.util.List;

/**
 * UseCase failed: the use case, its deployment or the requested prompt name is not in the
 * use-case document — or there is no use-case document at all.
 *
 * <p>None of these is a signal to fall back to a hard-coded prompt. {@link Reason#UNRESOLVED} and
 * {@link Reason#UNKNOWN_PROMPT} are bugs in the deployment or in the call; fail the provider call
 * loudly instead.
 */
public class UseCaseException extends PromptOnException {

    /** Why use-case loading failed. */
    public enum Reason {
        /** No use-case document at all: PromptOn is unreachable and nothing is cached on disk or bundled. */
        NOT_READY("not_ready"),
        /** The use-case document has no use case with that key. */
        UNKNOWN_USE_CASE("unknown_use_case"),
        /** The use case exists but has no live deployment in this environment. */
        UNRESOLVED("unresolved"),
        /** The live deployment pins no prompt version under the requested name. */
        UNKNOWN_PROMPT("unknown_prompt");

        private final String wireName;

        Reason(String wireName) {
            this.wireName = wireName;
        }

        /** The name PromptOn uses for this reason in {@code error.details.reason}. */
        public String wireName() {
            return wireName;
        }
    }

    private final transient Reason reason;
    private final transient String key;
    private final transient String prompt;
    private final transient List<String> promptNames;

    UseCaseException(
            Reason reason, String key, String prompt, List<String> promptNames, String message) {
        super(message);
        this.reason = reason;
        this.key = key;
        this.prompt = prompt;
        this.promptNames = promptNames == null ? List.of() : List.copyOf(promptNames);
    }

    /** Creates an exception for {@code reason} on {@code useCase}. */
    public static UseCaseException of(Reason reason, String key) {
        return new UseCaseException(reason, key, null, List.of(), switch (reason) {
            case NOT_READY -> "PromptOn is unreachable and no use-case document is cached in memory, "
                    + "on disk or in a bundle; no use case can be loaded yet";
            case UNKNOWN_USE_CASE -> "unknown use case: " + key;
            case UNRESOLVED -> "use case " + key + " has no live deployment in this environment";
            case UNKNOWN_PROMPT -> "unknown prompt for use case " + key;
        });
    }

    /** Creates an {@link Reason#UNKNOWN_PROMPT} exception listing what the deployment does pin. */
    public static UseCaseException unknownPrompt(
            String key, String prompt, List<String> promptNames) {
        return new UseCaseException(
                Reason.UNKNOWN_PROMPT,
                key,
                prompt,
                promptNames,
                "the live deployment of " + key + " pins no prompt named \"" + prompt
                        + "\" — prompt names: " + String.join(", ", promptNames));
    }

    /** Why use-case loading failed. */
    public Reason reason() {
        return reason;
    }

    /** The use case key that was resolved. */
    public String key() {
        return key;
    }

    /** The prompt name that was asked for, when the failure was about a prompt name. */
    public String prompt() {
        return prompt;
    }

    /** The prompt names the live deployment does pin. Empty unless {@link Reason#UNKNOWN_PROMPT}. */
    public List<String> promptNames() {
        return promptNames;
    }
}
