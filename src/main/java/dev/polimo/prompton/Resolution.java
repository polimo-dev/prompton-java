package dev.polimo.prompton;

import java.util.List;
import java.util.Map;

/**
 * What to use for one call: the pin the snapshot holds for a use case, prompt name and environment.
 *
 * <p>The app sends {@link #model()} with {@link #effectiveParams()} and
 * {@link #effectiveProviderOptions()} to {@link #provider()} using <em>its own</em> key and HTTP
 * client, and records {@link #deploymentId()}, {@link #deploymentRevision()}, {@link #prompt()} and
 * {@link #promptVersionId()} in the monitoring log so a change in behaviour can be traced back to a
 * revision.
 *
 * <p>{@link #messages()} and {@link #textTemplate()} are the raw templates. Rendering is a separate
 * step ({@link PromptOn#renderMessages}, {@link PromptOn#renderText}) because a resolution is
 * reusable across calls while the variables are not.
 */
public final class Resolution {

    private final String useCase;
    private final UseCaseKind kind;
    private final String deploymentId;
    private final Integer deploymentRevision;
    private final String prompt;
    private final List<String> availablePrompts;
    private final String model;
    private final String modelId;
    private final String provider;
    private final Map<String, Object> effectiveParams;
    private final Map<String, Object> effectiveProviderOptions;
    private final String promptVersionId;
    private final Integer promptVersionNumber;
    private final Template.Engine engine;
    private final List<Message> messages;
    private final String textTemplate;
    private final List<Map<String, Object>> inputSchema;
    private final PayloadPolicy payloadPolicy;
    private final ResolutionSource source;
    private final String etag;
    private final List<String> warnings;

    Resolution(Builder builder) {
        this.useCase = builder.useCase;
        this.kind = builder.kind;
        this.deploymentId = builder.deploymentId;
        this.deploymentRevision = builder.deploymentRevision;
        this.prompt = builder.prompt;
        this.availablePrompts = List.copyOf(builder.availablePrompts);
        this.model = builder.model;
        this.modelId = builder.modelId;
        this.provider = builder.provider;
        this.effectiveParams = frozen(builder.effectiveParams);
        this.effectiveProviderOptions = frozen(builder.effectiveProviderOptions);
        this.promptVersionId = builder.promptVersionId;
        this.promptVersionNumber = builder.promptVersionNumber;
        this.engine = builder.engine;
        this.messages = builder.messages == null ? null : List.copyOf(builder.messages);
        this.textTemplate = builder.textTemplate;
        this.inputSchema = List.copyOf(builder.inputSchema);
        this.payloadPolicy = builder.payloadPolicy;
        this.source = builder.source;
        this.etag = builder.etag;
        this.warnings = List.copyOf(builder.warnings);
    }

    /**
     * {@code Map.copyOf} rejects null values, and an explicit {@code null} is a meaningful override
     * — apps send {@code "only": null} to clear a provider restriction.
     */
    private static Map<String, Object> frozen(Map<String, Object> map) {
        return map == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(map));
    }

    /** The use case key. */
    public String useCase() {
        return useCase;
    }

    /** Whether this is a chat, text or embedding call. */
    public UseCaseKind kind() {
        return kind;
    }

    /** The id of the live deployment revision this came from. */
    public String deploymentId() {
        return deploymentId;
    }

    /** The revision number of that deployment. */
    public Integer deploymentRevision() {
        return deploymentRevision;
    }

    /** The prompt name that was selected, or {@code null} for an embedding use case. */
    public String prompt() {
        return prompt;
    }

    /** Every prompt name the live deployment pins, sorted. */
    public List<String> availablePrompts() {
        return availablePrompts;
    }

    /** The provider model string to send to the provider, for example {@code openai/gpt-4o-mini}. */
    public String model() {
        return model;
    }

    /** The catalog UUID of the model, for the monitoring log. */
    public String modelId() {
        return modelId;
    }

    /** Which provider to call: {@code openrouter}, {@code openai}, {@code anthropic}, … */
    public String provider() {
        return provider;
    }

    /** {@code use_case.default_params} with {@code deployment.params} layered on top. */
    public Map<String, Object> effectiveParams() {
        return effectiveParams;
    }

    /** {@code model.provider_options} with {@code deployment.provider_options} layered on top. */
    public Map<String, Object> effectiveProviderOptions() {
        return effectiveProviderOptions;
    }

    /** The id of the pinned prompt version, or {@code null} for an embedding use case. */
    public String promptVersionId() {
        return promptVersionId;
    }

    /** The number of the pinned prompt version, or {@code null}. */
    public Integer promptVersionNumber() {
        return promptVersionNumber;
    }

    /** Which template engine the pinned version uses. */
    public Template.Engine engine() {
        return engine;
    }

    /** The raw chat template, or {@code null} unless this is a chat use case. */
    public List<Message> messages() {
        return messages;
    }

    /** The raw text template, or {@code null} unless this is a text use case. */
    public String textTemplate() {
        return textTemplate;
    }

    /** The use case's declared input variables. */
    public List<Map<String, Object>> inputSchema() {
        return inputSchema;
    }

    /** How much of this call's text a monitoring log may carry. */
    public PayloadPolicy payloadPolicy() {
        return payloadPolicy;
    }

    /** Where the snapshot behind this resolution came from. */
    public ResolutionSource source() {
        return source;
    }

    /** The ETag of that snapshot, when there is one. */
    public String etag() {
        return etag;
    }

    /**
     * Anything the snapshot referenced but did not contain. A healthy server never emits such a
     * document; resolution still succeeds, with the corresponding fields {@code null}.
     */
    public List<String> warnings() {
        return warnings;
    }

    @Override
    public String toString() {
        return "Resolution[" + useCase + " " + kind.wireName() + " prompt=" + prompt + " model="
                + model + " revision=" + deploymentRevision + " source=" + source.wireName() + "]";
    }

    /** Assembles a {@link Resolution}. Used by {@link Resolver} and by the {@code /resolve} client. */
    public static final class Builder {
        private String useCase;
        private UseCaseKind kind = UseCaseKind.CHAT;
        private String deploymentId;
        private Integer deploymentRevision;
        private String prompt;
        private List<String> availablePrompts = List.of();
        private String model;
        private String modelId;
        private String provider;
        private Map<String, Object> effectiveParams = Map.of();
        private Map<String, Object> effectiveProviderOptions = Map.of();
        private String promptVersionId;
        private Integer promptVersionNumber;
        private Template.Engine engine = Template.Engine.LIQUID;
        private List<Message> messages;
        private String textTemplate;
        private List<Map<String, Object>> inputSchema = List.of();
        private PayloadPolicy payloadPolicy = PayloadPolicy.DEFAULT;
        private ResolutionSource source = ResolutionSource.REMOTE;
        private String etag;
        private List<String> warnings = List.of();

        /** @param value the use case key */
        public Builder useCase(String value) {
            this.useCase = value;
            return this;
        }

        /** @param value chat, text or embedding */
        public Builder kind(UseCaseKind value) {
            this.kind = value;
            return this;
        }

        /** @param value the deployment id */
        public Builder deploymentId(String value) {
            this.deploymentId = value;
            return this;
        }

        /** @param value the deployment revision */
        public Builder deploymentRevision(Integer value) {
            this.deploymentRevision = value;
            return this;
        }

        /** @param value the selected prompt name */
        public Builder prompt(String value) {
            this.prompt = value;
            return this;
        }

        /** @param value every prompt name the deployment pins */
        public Builder availablePrompts(List<String> value) {
            this.availablePrompts = value == null ? List.of() : value;
            return this;
        }

        /** @param value the provider model string */
        public Builder model(String value) {
            this.model = value;
            return this;
        }

        /** @param value the catalog model UUID */
        public Builder modelId(String value) {
            this.modelId = value;
            return this;
        }

        /** @param value the provider name */
        public Builder provider(String value) {
            this.provider = value;
            return this;
        }

        /** @param value the layered params */
        public Builder effectiveParams(Map<String, Object> value) {
            this.effectiveParams = value == null ? Map.of() : value;
            return this;
        }

        /** @param value the layered provider options */
        public Builder effectiveProviderOptions(Map<String, Object> value) {
            this.effectiveProviderOptions = value == null ? Map.of() : value;
            return this;
        }

        /** @param value the pinned prompt version id */
        public Builder promptVersionId(String value) {
            this.promptVersionId = value;
            return this;
        }

        /** @param value the pinned prompt version number */
        public Builder promptVersionNumber(Integer value) {
            this.promptVersionNumber = value;
            return this;
        }

        /** @param value the template engine */
        public Builder engine(Template.Engine value) {
            this.engine = value == null ? Template.Engine.LIQUID : value;
            return this;
        }

        /** @param value the raw chat template */
        public Builder messages(List<Message> value) {
            this.messages = value;
            return this;
        }

        /** @param value the raw text template */
        public Builder textTemplate(String value) {
            this.textTemplate = value;
            return this;
        }

        /** @param value the use case's input schema */
        public Builder inputSchema(List<Map<String, Object>> value) {
            this.inputSchema = value == null ? List.of() : value;
            return this;
        }

        /** @param value the payload policy */
        public Builder payloadPolicy(PayloadPolicy value) {
            this.payloadPolicy = value == null ? PayloadPolicy.DEFAULT : value;
            return this;
        }

        /** @param value where the snapshot came from */
        public Builder source(ResolutionSource value) {
            this.source = value == null ? ResolutionSource.MANUAL : value;
            return this;
        }

        /** @param value the snapshot ETag */
        public Builder etag(String value) {
            this.etag = value;
            return this;
        }

        /** @param value decoding or resolution warnings */
        public Builder warnings(List<String> value) {
            this.warnings = value == null ? List.of() : value;
            return this;
        }

        /** Builds the resolution. */
        public Resolution build() {
            return new Resolution(this);
        }
    }
}
