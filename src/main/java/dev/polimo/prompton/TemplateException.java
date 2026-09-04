package dev.polimo.prompton;

/** Rendering a prompt template failed. */
public class TemplateException extends PromptOnException {

    /** What kind of template failure this is. */
    public enum Kind {
        /**
         * A variable the template reads at an output position, as a {@code for} enumerable, in an
         * {@code unless} condition or as an {@code assign} source is absent from the variables map.
         * A key that is present with a {@code null} value is <em>not</em> missing.
         */
        MISSING_VARIABLE("missing_variable"),
        /** The template uses a construct outside the allowed subset, or is malformed. */
        PARSE_ERROR("parse_error"),
        /** The template parsed but rendering failed for another reason. */
        RENDER_ERROR("render_error");

        private final String wireName;

        Kind(String wireName) {
            this.wireName = wireName;
        }

        /** The name the conformance suite uses for this category. */
        public String wireName() {
            return wireName;
        }
    }

    private final transient Kind kind;
    private final transient String variable;

    TemplateException(Kind kind, String variable, String message) {
        super(message);
        this.kind = kind;
        this.variable = variable;
    }

    /** A {@link Kind#MISSING_VARIABLE} failure naming the variable. */
    public static TemplateException missingVariable(String name) {
        return new TemplateException(Kind.MISSING_VARIABLE, name, "missing variable: " + name);
    }

    /** A {@link Kind#PARSE_ERROR} failure. */
    public static TemplateException parseError(String message) {
        return new TemplateException(Kind.PARSE_ERROR, null, message);
    }

    /** A {@link Kind#RENDER_ERROR} failure. */
    public static TemplateException renderError(String message) {
        return new TemplateException(Kind.RENDER_ERROR, null, message);
    }

    /** What kind of failure this is. */
    public Kind kind() {
        return kind;
    }

    /** The missing variable's dotted name, or {@code null} for the other kinds. */
    public String variable() {
        return variable;
    }
}
