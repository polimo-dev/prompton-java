package dev.polimo.prompton;

import java.util.List;

/**
 * Resolution failed: the use case, its deployment or the requested prompt name is not in the
 * snapshot — or there is no snapshot at all.
 *
 * <p>None of these is a signal to fall back to a hard-coded prompt. {@link Reason#UNRESOLVED} and
 * {@link Reason#UNKNOWN_PROMPT} are bugs in the deployment or in the call; fail the generation
 * loudly instead.
 */
public class ResolutionException extends PromptOnException {

    /** Why resolution failed. */
    public enum Reason {
        /** No snapshot at all: PromptOn is unreachable and nothing is cached on disk or bundled. */
        NOT_READY("not_ready"),
        /** The snapshot has no use case with that key. */
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
    private final transient String useCase;
    private final transient String prompt;
    private final transient List<String> availablePrompts;

    ResolutionException(
            Reason reason, String useCase, String prompt, List<String> availablePrompts, String message) {
        super(message);
        this.reason = reason;
        this.useCase = useCase;
        this.prompt = prompt;
        this.availablePrompts = availablePrompts == null ? List.of() : List.copyOf(availablePrompts);
    }

    /** Creates an exception for {@code reason} on {@code useCase}. */
    public static ResolutionException of(Reason reason, String useCase) {
        return new ResolutionException(reason, useCase, null, List.of(), switch (reason) {
            case NOT_READY -> "PromptOn is unreachable and no snapshot is cached in memory, "
                    + "on disk or in a bundle; nothing can be resolved yet";
            case UNKNOWN_USE_CASE -> "unknown use case: " + useCase;
            case UNRESOLVED -> "use case " + useCase + " has no live deployment in this environment";
            case UNKNOWN_PROMPT -> "unknown prompt for use case " + useCase;
        });
    }

    /** Creates an {@link Reason#UNKNOWN_PROMPT} exception listing what the deployment does pin. */
    public static ResolutionException unknownPrompt(
            String useCase, String prompt, List<String> availablePrompts) {
        return new ResolutionException(
                Reason.UNKNOWN_PROMPT,
                useCase,
                prompt,
                availablePrompts,
                "the live deployment of " + useCase + " pins no prompt named \"" + prompt
                        + "\" — available prompts: " + String.join(", ", availablePrompts));
    }

    /** Why resolution failed. */
    public Reason reason() {
        return reason;
    }

    /** The use case key that was resolved. */
    public String useCase() {
        return useCase;
    }

    /** The prompt name that was asked for, when the failure was about a prompt name. */
    public String prompt() {
        return prompt;
    }

    /** The prompt names the live deployment does pin. Empty unless {@link Reason#UNKNOWN_PROMPT}. */
    public List<String> availablePrompts() {
        return availablePrompts;
    }
}
