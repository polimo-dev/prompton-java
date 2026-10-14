package dev.polimo.prompton;

import java.util.List;

/**
 * Prompt failed: the prompt, its deployment or the requested template name is not in the
 * prompt document — or there is no prompt document at all.
 *
 * <p>None of these is a signal to fall back to a hard-coded prompt. {@link Reason#UNRESOLVED} and
 * {@link Reason#UNKNOWN_TEMPLATE} are bugs in the deployment or in the call; fail the provider call
 * loudly instead.
 */
public class PromptException extends PromptOnException {

    /** Why prompt loading failed. */
    public enum Reason {
        /** No prompt document at all: PromptOn is unreachable and nothing is cached on disk or bundled. */
        NOT_READY("not_ready"),
        /** The prompt document has no prompt with that key. */
        UNKNOWN_PROMPT("unknown_prompt"),
        /** The prompt exists but has no live deployment in this environment. */
        UNRESOLVED("unresolved"),
        /** The live deployment pins no prompt version under the requested name. */
        UNKNOWN_TEMPLATE("unknown_template");

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
    private final transient String template;
    private final transient List<String> templateNames;

    PromptException(
            Reason reason, String key, String template, List<String> templateNames, String message) {
        super(message);
        this.reason = reason;
        this.key = key;
        this.template = template;
        this.templateNames = templateNames == null ? List.of() : List.copyOf(templateNames);
    }

    /** Creates an exception for {@code reason} on {@code prompt}. */
    public static PromptException of(Reason reason, String key) {
        return new PromptException(reason, key, null, List.of(), switch (reason) {
            case NOT_READY -> "PromptOn is unreachable and no prompt document is cached in memory, "
                    + "on disk or in a bundle; no prompt can be loaded yet";
            case UNKNOWN_PROMPT -> "unknown prompt: " + key;
            case UNRESOLVED -> "prompt " + key + " has no live deployment in this environment";
            case UNKNOWN_TEMPLATE -> "unknown prompt for prompt " + key;
        });
    }

    /** Creates an {@link Reason#UNKNOWN_TEMPLATE} exception listing what the deployment does pin. */
    public static PromptException unknownTemplate(
            String key, String template, List<String> templateNames) {
        return new PromptException(
                Reason.UNKNOWN_TEMPLATE,
                key,
                template,
                templateNames,
                "the live deployment of " + key + " pins no template named \"" + template
                        + "\" — template names: " + String.join(", ", templateNames));
    }

    /** Why prompt loading failed. */
    public Reason reason() {
        return reason;
    }

    /** The prompt key that was resolved. */
    public String key() {
        return key;
    }

    /** The template name that was asked for, when the failure was about a template name. */
    public String template() {
        return template;
    }

    /** The template names the live deployment does pin. Empty unless {@link Reason#UNKNOWN_TEMPLATE}. */
    public List<String> templateNames() {
        return templateNames;
    }
}
