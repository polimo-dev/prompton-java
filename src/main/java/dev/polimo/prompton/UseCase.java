package dev.polimo.prompton;

import java.util.List;
import java.util.Map;

/**
 * What to use for one call: the use-case document-backed configuration for a use case, prompt name and
 * environment.
 *
 * <p>The app sends {@link #model()} with {@link #params()} and
 * {@link #providerOptions()} to {@link #provider()} using <em>its own</em> key and HTTP
 * client, and records {@link #deploymentId()}, {@link #deploymentRevision()}, {@link #prompt()} and
 * {@link #promptVersionId()} in the monitoring log so a change in behaviour can be traced back to a
 * revision.
 *
 * <p>{@link #messages()} and {@link #textTemplate()} are the raw templates. Use
 * {@link #messages(Map)} or {@link #text(Map)} for the rendered prompt for one call.
 */
public final class UseCase {

    private final PromptOn client;
    private final String key;
    private final UseCaseKind kind;
    private final String deploymentId;
    private final Integer deploymentRevision;
    private final String prompt;
    private final List<String> promptNames;
    private final String model;
    private final String modelId;
    private final String provider;
    private final Map<String, Object> params;
    private final Map<String, Object> providerOptions;
    private final String promptVersionId;
    private final Integer promptVersionNumber;
    private final Template.Engine engine;
    private final List<Message> messages;
    private final String textTemplate;
    private final List<Map<String, Object>> inputSchema;
    private final PayloadPolicy payloadPolicy;
    private final Source source;
    private final String etag;
    private final List<String> warnings;

    UseCase(Builder builder) {
        this.client = builder.client;
        this.key = builder.key;
        this.kind = builder.kind;
        this.deploymentId = builder.deploymentId;
        this.deploymentRevision = builder.deploymentRevision;
        this.prompt = builder.prompt;
        this.promptNames = List.copyOf(builder.promptNames);
        this.model = builder.model;
        this.modelId = builder.modelId;
        this.provider = builder.provider;
        this.params = frozen(builder.params);
        this.providerOptions = frozen(builder.providerOptions);
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

    UseCase attachTo(PromptOn client) {
        return new Builder(this).client(client).build();
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
    public String key() {
        return key;
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
    public List<String> promptNames() {
        return promptNames;
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
    public Map<String, Object> params() {
        return params;
    }

    /** {@code model.provider_options} with {@code deployment.provider_options} layered on top. */
    public Map<String, Object> providerOptions() {
        return providerOptions;
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

    /**
     * Renders this chat use case's messages with the variables for one call.
     *
     * @throws PromptOnException when this is not a chat use case
     * @throws TemplateException when a required variable is missing
     */
    public List<Message> messages(Map<String, Object> variables) {
        if (kind != UseCaseKind.CHAT || messages == null) {
            throw new PromptOnException(
                    "use case " + key + " is of kind "
                            + kind.wireName() + " and has no chat template");
        }
        return Template.renderMessages(messages, variables, engine);
    }

    /** The raw text template, or {@code null} unless this is a text use case. */
    public String textTemplate() {
        return textTemplate;
    }

    /**
     * Renders this text use case's prompt with the variables for one call.
     *
     * @throws PromptOnException when this is not a text use case
     * @throws TemplateException when a required variable is missing
     */
    public String text(Map<String, Object> variables) {
        if (kind != UseCaseKind.TEXT || textTemplate == null) {
            throw new PromptOnException(
                    "use case " + key + " is of kind "
                            + kind.wireName() + " and has no text template");
        }
        return Template.render(textTemplate, variables, engine);
    }

    /** The use case's declared input variables. */
    public List<Map<String, Object>> inputSchema() {
        return inputSchema;
    }

    /** How much of this call's text a monitoring log may carry. */
    public PayloadPolicy payloadPolicy() {
        return payloadPolicy;
    }

    /** Where the use-case document behind this use case came from. */
    public Source source() {
        return source;
    }

    /** The ETag of that use-case document, when there is one. */
    public String etag() {
        return etag;
    }

    /**
     * Anything the use-case document referenced but did not contain. A healthy server never emits such a
     * document; loading still succeeds, with the corresponding fields {@code null}.
     */
    public List<String> warnings() {
        return warnings;
    }

    /**
     * Times a provider call for this use case, queues a monitoring log, and returns the provider
     * value.
     *
     * @throws Exception whatever the provider call threw
     */
    public <T> T track(TrackMeta meta, ProviderCall<T> supplier) throws Exception {
        requireClient();
        return client.track(this, meta, supplier);
    }

    /** {@link #track(TrackMeta, ProviderCall)} for a supplier that throws no checked exception. */
    public <T> T trackUnchecked(TrackMeta meta, PromptOn.UncheckedProviderCall<T> supplier) {
        requireClient();
        return client.trackUnchecked(this, meta, supplier);
    }

    private void requireClient() {
        if (client == null) {
            throw new PromptOnException(
                    "this use case is not attached to a PromptOn client; get it from PromptOn.useCase()");
        }
    }

    @Override
    public String toString() {
        return "UseCase[" + key + " " + kind.wireName() + " prompt=" + prompt + " model="
                + model + " revision=" + deploymentRevision + " source=" + source.wireName() + "]";
    }

    /** Assembles a {@link UseCase}. Used by {@link Resolver} and by the remote prompt client. */
    public static final class Builder {
        private PromptOn client;
        private String key;
        private UseCaseKind kind = UseCaseKind.CHAT;
        private String deploymentId;
        private Integer deploymentRevision;
        private String prompt;
        private List<String> promptNames = List.of();
        private String model;
        private String modelId;
        private String provider;
        private Map<String, Object> params = Map.of();
        private Map<String, Object> providerOptions = Map.of();
        private String promptVersionId;
        private Integer promptVersionNumber;
        private Template.Engine engine = Template.Engine.LIQUID;
        private List<Message> messages;
        private String textTemplate;
        private List<Map<String, Object>> inputSchema = List.of();
        private PayloadPolicy payloadPolicy = PayloadPolicy.DEFAULT;
        private Source source = Source.REMOTE;
        private String etag;
        private List<String> warnings = List.of();

        public Builder() {}

        private Builder(UseCase source) {
            this.client = source.client;
            this.key = source.key;
            this.kind = source.kind;
            this.deploymentId = source.deploymentId;
            this.deploymentRevision = source.deploymentRevision;
            this.prompt = source.prompt;
            this.promptNames = source.promptNames;
            this.model = source.model;
            this.modelId = source.modelId;
            this.provider = source.provider;
            this.params = source.params;
            this.providerOptions = source.providerOptions;
            this.promptVersionId = source.promptVersionId;
            this.promptVersionNumber = source.promptVersionNumber;
            this.engine = source.engine;
            this.messages = source.messages;
            this.textTemplate = source.textTemplate;
            this.inputSchema = source.inputSchema;
            this.payloadPolicy = source.payloadPolicy;
            this.source = source.source;
            this.etag = source.etag;
            this.warnings = source.warnings;
        }

        Builder client(PromptOn value) {
            this.client = value;
            return this;
        }

        /** @param value the use case key */
        public Builder key(String value) {
            this.key = value;
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
        public Builder promptNames(List<String> value) {
            this.promptNames = value == null ? List.of() : value;
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
        public Builder params(Map<String, Object> value) {
            this.params = value == null ? Map.of() : value;
            return this;
        }

        /** @param value the layered provider options */
        public Builder providerOptions(Map<String, Object> value) {
            this.providerOptions = value == null ? Map.of() : value;
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

        /** @param value where the use-case document came from */
        public Builder source(Source value) {
            this.source = value == null ? Source.MANUAL : value;
            return this;
        }

        /** @param value the use-case document ETag */
        public Builder etag(String value) {
            this.etag = value;
            return this;
        }

        /** @param value decoding or use-case loading warnings */
        public Builder warnings(List<String> value) {
            this.warnings = value == null ? List.of() : value;
            return this;
        }

        /** Builds the use case. */
        public UseCase build() {
            return new UseCase(this);
        }
    }
}
