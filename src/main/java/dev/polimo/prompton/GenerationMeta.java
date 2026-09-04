package dev.polimo.prompton;

import java.util.List;
import java.util.Map;

/**
 * The application-side context of one generation: what went in, and how to find it again later.
 *
 * <p>Rendering is a pure function that keeps no state, so pass the same {@code variables} here if
 * you want them logged. {@code context} and {@code metadata} are free-form passthroughs — keep them
 * small (the server rejects a record whose {@code context} is over 2 KB or whose {@code metadata} is
 * over 4 KB) and keep secrets out of both.
 */
public final class GenerationMeta {

    private final String id;
    private final Map<String, Object> variables;
    private final List<Message> inputMessages;
    private final String inputText;
    private final String endUserRef;
    private final String traceId;
    private final Integer sequence;
    private final Map<String, Object> context;
    private final Map<String, Object> metadata;
    private final Map<String, Object> params;

    private GenerationMeta(Builder b) {
        this.id = b.id;
        this.variables = b.variables;
        this.inputMessages = b.inputMessages;
        this.inputText = b.inputText;
        this.endUserRef = b.endUserRef;
        this.traceId = b.traceId;
        this.sequence = b.sequence;
        this.context = b.context;
        this.metadata = b.metadata;
        this.params = b.params;
    }

    /** A new builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Meta with nothing set. */
    public static GenerationMeta empty() {
        return builder().build();
    }

    /** The record id to use, when the application pre-issued one. */
    public String id() {
        return id;
    }

    /** The variables the prompt was rendered with. */
    public Map<String, Object> variables() {
        return variables;
    }

    /** The final messages sent to the provider, after any history the app attached. */
    public List<Message> inputMessages() {
        return inputMessages;
    }

    /** The final prompt text, for a text use case. */
    public String inputText() {
        return inputText;
    }

    /** A stable reference to the end user — never their email or name. */
    public String endUserRef() {
        return endUserRef;
    }

    /** Whatever ties this call to a job, request or trace on your side. */
    public String traceId() {
        return traceId;
    }

    /** The position of this call within that trace. */
    public Integer sequence() {
        return sequence;
    }

    /** Free-form tags to slice the logs by, for example language or plan. */
    public Map<String, Object> context() {
        return context;
    }

    /** Free-form application data. */
    public Map<String, Object> metadata() {
        return metadata;
    }

    /** The params actually sent, when they differ from the resolution's. */
    public Map<String, Object> params() {
        return params;
    }

    /** Assembles a {@link GenerationMeta}. */
    public static final class Builder {
        private String id;
        private Map<String, Object> variables;
        private List<Message> inputMessages;
        private String inputText;
        private String endUserRef;
        private String traceId;
        private Integer sequence;
        private Map<String, Object> context;
        private Map<String, Object> metadata;
        private Map<String, Object> params;

        private Builder() {}

        /** @param value a pre-issued record id */
        public Builder id(String value) {
            this.id = value;
            return this;
        }

        /** @param value the variables the prompt was rendered with */
        public Builder variables(Map<String, Object> value) {
            this.variables = value;
            return this;
        }

        /** @param value the final messages sent to the provider */
        public Builder inputMessages(List<Message> value) {
            this.inputMessages = value;
            return this;
        }

        /** @param value the final prompt text, for a text use case */
        public Builder inputText(String value) {
            this.inputText = value;
            return this;
        }

        /** @param value a stable reference to the end user */
        public Builder endUserRef(String value) {
            this.endUserRef = value;
            return this;
        }

        /** @param value whatever ties this call to a job or request */
        public Builder traceId(String value) {
            this.traceId = value;
            return this;
        }

        /** @param value the position of this call within that trace */
        public Builder sequence(Integer value) {
            this.sequence = value;
            return this;
        }

        /** @param value free-form tags to slice the logs by */
        public Builder context(Map<String, Object> value) {
            this.context = value;
            return this;
        }

        /** @param value free-form application data */
        public Builder metadata(Map<String, Object> value) {
            this.metadata = value;
            return this;
        }

        /** @param value the params actually sent */
        public Builder params(Map<String, Object> value) {
            this.params = value;
            return this;
        }

        /** Builds the meta. */
        public GenerationMeta build() {
            return new GenerationMeta(this);
        }
    }
}
