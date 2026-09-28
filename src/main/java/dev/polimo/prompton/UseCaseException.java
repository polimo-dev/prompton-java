package dev.polimo.prompton;

import java.util.List;

/**
 * UseCase failed: the prompt, its deployment or the requested prompt name is not in the
 * prompt document — or there is no prompt document at all.
 *
 * <p>None of these is a signal to fall back to a hard-coded prompt. {@link Reason#UNRESOLVED} and
 * {@link Reason#UNKNOWN_PROMPT} are bugs in the deployment or in the call; fail the provider call
 * loudly instead.
 */
public class UseCaseException extends PromptOnException {

    /** Why prompt loading failed. */
    public enum Reason {
        /** No prompt document at all: PromptOn is unreachable and nothing is cached on disk or bundled. */
        NOT_READY("not_ready"),
        /** The prompt document has no prompt with that key. */
        UNKNOWN_USE_CASE("unknown_prompt"),
        /** The prompt exists but has no live deployment in this environment. */
        UNRESOLVED("unresolved"),
        /** The live deployment pins no prompt version under the requested name. */
        UNKNOWN_PROMPT("unknown_template");

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
            case NOT_READY -> "PromptOn is unreachable and no prompt document is cached in memory, "
                    + "on disk or in a bundle; no prompt can be loaded yet";
            case UNKNOWN_USE_CASE -> "unknown prompt: " + key;
            case UNRESOLVED -> "prompt " + key + " has no live deployment in this environment";
            case UNKNOWN_PROMPT -> "unknown prompt for prompt " + key;
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

    /** Why prompt loading failed. */
    public Reason reason() {
        return reason;
    }

    /** The prompt key that was resolved. */
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
