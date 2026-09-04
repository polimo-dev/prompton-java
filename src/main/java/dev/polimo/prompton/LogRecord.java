package dev.polimo.prompton;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One monitoring log: what your app asked for, what came back, and how long it took.
 *
 * <p>{@code use_case}, {@code model}, {@code status} and {@code started_at} are required;
 * {@link PromptOn#log(LogRecord)} fills in {@code id} (a UUIDv7, the idempotency key),
 * {@code sdk} and — when a {@link UseCase} is attached — {@code deployment_id},
 * {@code deployment_revision}, {@code prompt}, {@code prompt_version_id}, {@code model_id},
 * {@code provider}, {@code kind}, {@code params} and {@code source}.
 *
 * <p>Keep secrets out of {@code input}, {@code output}, {@code context} and {@code metadata}: no
 * provider keys, no {@code PTN_API_KEY}, no user PII beyond {@code end_user_ref}.
 */
public final class LogRecord {

    /** Whether the log worked. */
    public enum Status {
        /** The provider answered and the application accepted the answer. */
        OK("ok"),
        /** Anything else. Send these too: an error rate without them means nothing. */
        ERROR("error");

        private final String wireName;

        Status(String wireName) {
            this.wireName = wireName;
        }

        /** The value sent in {@code status}. */
        public String wireName() {
            return wireName;
        }
    }

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private final Map<String, Object> fields;
    private final String environment;
    private final PayloadPolicy payloadPolicy;

    private LogRecord(Map<String, Object> fields, String environment, PayloadPolicy policy) {
        this.fields = fields;
        this.environment = environment;
        this.payloadPolicy = policy;
    }

    /** A new builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Wraps an already-assembled record map, for an application that builds its own. */
    public static LogRecord fromMap(Map<String, Object> map) {
        return new LogRecord(new LinkedHashMap<>(map), null, null);
    }

    /** The record's id, or {@code null} when the SDK still has to issue one. */
    public String id() {
        Object id = fields.get("id");
        return id == null ? null : String.valueOf(id);
    }

    /** The use case key. */
    public String key() {
        Object useCase = fields.get("use_case");
        return useCase == null ? null : String.valueOf(useCase);
    }

    /** Which environment's batch this record belongs in, or {@code null} for the configured one. */
    public String environment() {
        return environment;
    }

    /** The payload policy to apply, or {@code null} to take the use case's from the use-case document. */
    public PayloadPolicy payloadPolicy() {
        return payloadPolicy;
    }

    /** A mutable copy of the wire form, with top-level nulls omitted. */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        fields.forEach((key, value) -> {
            if (value != null) {
                map.put(key, value);
            }
        });
        return map;
    }

    /**
     * Checks the four fields PromptOn requires.
     *
     * @throws PromptOnException when one is missing
     */
    public void validate() {
        for (String required : List.of("use_case", "model", "status", "started_at")) {
            Object value = fields.get(required);
            if (value == null || (value instanceof String s && s.isBlank())) {
                throw new PromptOnException(
                        "monitoring log is missing the required field " + required);
            }
        }
    }

    /** Assembles a {@link LogRecord}. */
    public static final class Builder {
        private final Map<String, Object> fields = new LinkedHashMap<>();
        private String environment;
        private PayloadPolicy payloadPolicy;

        private Builder() {}

        /** @param value the record id; the SDK issues a UUIDv7 when this is left unset */
        public Builder id(String value) {
            fields.put("id", value);
            return this;
        }

        /** @param value the use case key */
        public Builder key(String value) {
            fields.put("use_case", value);
            return this;
        }

        /** @param value chat, text or embedding */
        public Builder kind(UseCaseKind value) {
            fields.put("kind", value == null ? null : value.wireName());
            return this;
        }

        /** @param value the provider model string that was requested */
        public Builder model(String value) {
            fields.put("model", value);
            return this;
        }

        /** @param value the catalog model UUID */
        public Builder modelId(String value) {
            fields.put("model_id", value);
            return this;
        }

        /** @param value the provider that was called */
        public Builder provider(String value) {
            fields.put("provider", value);
            return this;
        }

        /** @param value whether the log worked */
        public Builder status(Status value) {
            fields.put("status", value == null ? null : value.wireName());
            return this;
        }

        /** @param value when the provider call started */
        public Builder startedAt(Instant value) {
            fields.put("started_at", value == null ? null : ISO.format(value));
            return this;
        }

        /** @param value how long the provider call took */
        public Builder latencyMs(Long value) {
            fields.put("latency_ms", value);
            return this;
        }

        /** @param value the provider's raw finish reason */
        public Builder finishReason(String value) {
            fields.put("finish_reason", value);
            return this;
        }

        /** @param value the normalised stop kind */
        public Builder stopKind(StopKind value) {
            fields.put("stop_kind", value == null ? null : value.wireName());
            return this;
        }

        /** @param value the failure, on a record whose status is {@link Status#ERROR} */
        public Builder error(LogError value) {
            fields.put("error", value == null ? null : value.toMap());
            return this;
        }

        /** @param value tokens and cost */
        public Builder usage(Usage value) {
            fields.put("usage", value == null ? null : value.toMap());
            return this;
        }

        /** @param value what went in: {@code messages}, {@code text} and/or {@code variables} */
        public Builder input(Map<String, Object> value) {
            fields.put("input", value);
            return this;
        }

        /** @param value what came out: {@code content} and/or {@code tool_calls} */
        public Builder output(Map<String, Object> value) {
            fields.put("output", value);
            return this;
        }

        /** @param value the params actually sent to the provider */
        public Builder params(Map<String, Object> value) {
            fields.put("params", value);
            return this;
        }

        /** @param value free-form tags to slice the logs by */
        public Builder context(Map<String, Object> value) {
            fields.put("context", value);
            return this;
        }

        /** @param value free-form application data */
        public Builder metadata(Map<String, Object> value) {
            fields.put("metadata", value);
            return this;
        }

        /** @param value whatever ties this call to a job, request or trace */
        public Builder traceId(String value) {
            fields.put("trace_id", value);
            return this;
        }

        /** @param value the position of this call within that trace */
        public Builder sequence(Integer value) {
            fields.put("sequence", value);
            return this;
        }

        /** @param value a stable reference to the end user */
        public Builder endUserRef(String value) {
            fields.put("end_user_ref", value);
            return this;
        }

        /** @param value where the configuration behind this call came from */
        public Builder source(Source value) {
            fields.put("source", value == null ? null : value.wireName());
            return this;
        }

        /** @param value the deployment revision's id */
        public Builder deploymentId(String value) {
            fields.put("deployment_id", value);
            return this;
        }

        /** @param value the deployment revision number */
        public Builder deploymentRevision(Integer value) {
            fields.put("deployment_revision", value);
            return this;
        }

        /** @param value the prompt name that was used */
        public Builder prompt(String value) {
            fields.put("prompt", value);
            return this;
        }

        /** @param value the pinned prompt version's id */
        public Builder promptVersionId(String value) {
            fields.put("prompt_version_id", value);
            return this;
        }

        /** @param value the model the provider actually served */
        public Builder modelUsed(String value) {
            fields.put("model_used", value);
            return this;
        }

        /** @param value the upstream provider a router picked */
        public Builder upstreamProvider(String value) {
            fields.put("upstream_provider", value);
            return this;
        }

        /** @param value the SDK identity; the SDK fills its own when this is left unset */
        public Builder sdk(Map<String, Object> value) {
            fields.put("sdk", value);
            return this;
        }

        /**
         * @param key the field name
         * @param value any field the contract adds that this SDK does not model yet
         */
        public Builder field(String key, Object value) {
            fields.put(key, value);
            return this;
        }

        /**
         * Copies the use-case evidence out of a {@link UseCase}: the deployment, the prompt, the
         * model and provider, the kind, the params, and where the use-case document came from.
         *
         * @param useCase the use case this call used
         * @return this builder
         */
        public Builder useCase(UseCase useCase) {
            if (useCase == null) {
                return this;
            }
            key(useCase.key());
            kind(useCase.kind());
            deploymentId(useCase.deploymentId());
            deploymentRevision(useCase.deploymentRevision());
            prompt(useCase.prompt());
            promptVersionId(useCase.promptVersionId());
            model(useCase.model());
            modelId(useCase.modelId());
            provider(useCase.provider());
            source(useCase.source());
            if (!fields.containsKey("params")) {
                params(useCase.params());
            }
            this.payloadPolicy = useCase.payloadPolicy();
            return this;
        }

        /** @param value which environment's batch this record belongs in */
        public Builder environment(String value) {
            this.environment = value;
            return this;
        }

        /** @param value the payload policy to apply before sending */
        public Builder payloadPolicy(PayloadPolicy value) {
            this.payloadPolicy = value;
            return this;
        }

        /** Builds the record. */
        public LogRecord build() {
            return new LogRecord(new LinkedHashMap<>(fields), environment, payloadPolicy);
        }
    }
}
