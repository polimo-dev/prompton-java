package dev.polimo.prompton;

import java.util.List;
import java.util.Map;

/**
 * What the provider call produced, in the shape the monitoring log wants.
 *
 * <p>Build one from your provider's response and hand it to
 * {@link ProviderResult#ok(Object, GenerationOutcome)}; the SDK turns it into {@code output},
 * {@code usage}, {@code stop_kind} and the provider-identity fields.
 */
public final class GenerationOutcome {

    private final String content;
    private final List<Object> toolCalls;
    private final String finishReason;
    private final StopKind stopKind;
    private final GenerationUsage usage;
    private final String modelUsed;
    private final String upstreamProvider;
    private final Boolean byok;

    private GenerationOutcome(Builder b) {
        this.content = b.content;
        this.toolCalls = b.toolCalls;
        this.finishReason = b.finishReason;
        this.stopKind = b.stopKind;
        this.usage = b.usage;
        this.modelUsed = b.modelUsed;
        this.upstreamProvider = b.upstreamProvider;
        this.byok = b.byok;
    }

    /** A new builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** An outcome that carries only the completion text. */
    public static GenerationOutcome ofContent(String content) {
        return builder().content(content).build();
    }

    /** The completion text. */
    public String content() {
        return content;
    }

    /** The tool calls the model asked for, as the provider returned them. */
    public List<Object> toolCalls() {
        return toolCalls;
    }

    /** The provider's raw finish reason. */
    public String finishReason() {
        return finishReason;
    }

    /** The normalised stop kind; derived from {@link #finishReason()} when not set. */
    public StopKind stopKind() {
        return stopKind != null ? stopKind
                : (finishReason == null ? null : StopKind.normalize(finishReason));
    }

    /** Tokens and cost. */
    public GenerationUsage usage() {
        return usage;
    }

    /** The model the provider actually served, when it differs from the one requested. */
    public String modelUsed() {
        return modelUsed;
    }

    /** The upstream provider a router picked, for example {@code Anthropic} behind OpenRouter. */
    public String upstreamProvider() {
        return upstreamProvider;
    }

    /** Whether the call ran on a bring-your-own-key credential; recorded in {@code metadata}. */
    public Boolean byok() {
        return byok;
    }

    /** The {@code output} object, or {@code null} when there is nothing to record. */
    public Map<String, Object> outputMap() {
        java.util.LinkedHashMap<String, Object> output = new java.util.LinkedHashMap<>();
        if (content != null) {
            output.put("content", content);
        }
        if (toolCalls != null) {
            output.put("tool_calls", toolCalls);
        }
        return output.isEmpty() ? null : output;
    }

    /** Assembles a {@link GenerationOutcome}. */
    public static final class Builder {
        private String content;
        private List<Object> toolCalls;
        private String finishReason;
        private StopKind stopKind;
        private GenerationUsage usage;
        private String modelUsed;
        private String upstreamProvider;
        private Boolean byok;

        private Builder() {}

        /** @param value the completion text */
        public Builder content(String value) {
            this.content = value;
            return this;
        }

        /** @param value the tool calls the model asked for */
        public Builder toolCalls(List<Object> value) {
            this.toolCalls = value;
            return this;
        }

        /** @param value the provider's raw finish reason */
        public Builder finishReason(String value) {
            this.finishReason = value;
            return this;
        }

        /** @param value an explicit stop kind, overriding what the finish reason implies */
        public Builder stopKind(StopKind value) {
            this.stopKind = value;
            return this;
        }

        /** @param value tokens and cost */
        public Builder usage(GenerationUsage value) {
            this.usage = value;
            return this;
        }

        /** @param value the model the provider actually served */
        public Builder modelUsed(String value) {
            this.modelUsed = value;
            return this;
        }

        /** @param value the upstream provider a router picked */
        public Builder upstreamProvider(String value) {
            this.upstreamProvider = value;
            return this;
        }

        /** @param value whether the call ran on a bring-your-own-key credential */
        public Builder byok(Boolean value) {
            this.byok = value;
            return this;
        }

        /** Builds the outcome. */
        public GenerationOutcome build() {
            return new GenerationOutcome(this);
        }
    }
}
