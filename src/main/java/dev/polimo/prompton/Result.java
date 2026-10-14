package dev.polimo.prompton;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the provider call produced, in the shape the monitoring log wants.
 *
 * <p>Build one from your provider's response and hand it to
 * {@link ProviderResult#ok(Object, Result)}; the SDK turns it into {@code output},
 * {@code usage}, {@code stop_kind} and the provider-identity fields.
 */
public final class Result {

    private final String content;
    private final List<Object> toolCalls;
    private final String finishReason;
    private final StopKind stopKind;
    private final Usage usage;
    private final String modelUsed;
    private final String upstreamProvider;
    private final Boolean byok;
    private final Object rawResult;

    private Result(Builder b) {
        this.content = b.content;
        this.toolCalls = b.toolCalls;
        this.finishReason = b.finishReason;
        this.stopKind = b.stopKind;
        this.usage = b.usage;
        this.modelUsed = b.modelUsed;
        this.upstreamProvider = b.upstreamProvider;
        this.byok = b.byok;
        this.rawResult = b.rawResult;
    }

    /** A new builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** A result that carries only the completion text. */
    public static Result ofContent(String content) {
        return builder().content(content).build();
    }

    /**
     * Builds a result from an OpenAI-style chat completion object or decoded JSON map.
     *
     * <p>The SDK intentionally does not depend on the OpenAI Java package. It reads common bean
     * accessors and map keys instead: first choice message/content, finish reason, model, and token
     * usage.
     */
    public static Result fromOpenAI(Object answer) {
        Object choice = first(path(answer, "choices"));
        Object message = path(choice, "message");
        Object usage = path(answer, "usage");
        return builder()
                .content(string(path(message == null ? choice : message, "content")))
                .finishReason(string(firstPresent(path(choice, "finish_reason"), path(choice, "finishReason"))))
                .modelUsed(string(path(answer, "model")))
                .upstreamProvider("OpenAI")
                .usage(usage(usage, "prompt_tokens", "promptTokens",
                        "completion_tokens", "completionTokens"))
                .toolCalls(list(path(message == null ? choice : message, "tool_calls")))
                .build();
    }

    /**
     * Builds a result from an Anthropic-style message object or decoded JSON map.
     *
     * <p>Reads the first text block, stop reason, model, and input/output token usage without
     * depending on the Anthropic Java package.
     */
    public static Result fromAnthropic(Object answer) {
        Object contentBlock = first(path(answer, "content"));
        Object usage = path(answer, "usage");
        return builder()
                .content(string(firstPresent(path(contentBlock, "text"), path(answer, "text"))))
                .finishReason(string(firstPresent(path(answer, "stop_reason"), path(answer, "stopReason"))))
                .modelUsed(string(path(answer, "model")))
                .upstreamProvider("Anthropic")
                .usage(usage(usage, "input_tokens", "inputTokens", "output_tokens", "outputTokens"))
                .build();
    }

    /**
     * Builds a result from an OpenRouter Decisions response object or decoded JSON map.
     *
     * <p>The typed {@code answers} object is preserved as JSON content and the full provider
     * response remains available through {@link #rawResult()}.
     */
    public static Result fromDecisions(Object answer) {
        Object answers = path(answer, "answers");
        Object usage = path(answer, "usage");
        Object modelValue = path(answer, "model");
        validateDecisionResponse(modelValue, answers, usage);
        String model = ((String) modelValue).trim();
        Boolean byok = booleanValue(firstPresent(path(usage, "is_byok"), path(usage, "byok")));
        return builder()
                .content(answers == null ? null : dev.polimo.prompton.internal.Json.write(answers))
                .finishReason("stop")
                .stopKind(StopKind.STOP)
                .usage(decisionUsage(usage, byok))
                .modelUsed(model)
                .upstreamProvider(string(path(answer, "provider")))
                .byok(byok)
                .rawResult(answer)
                .build();
    }

    private static Usage usage(Object usage, String inputSnake, String inputCamel,
            String outputSnake, String outputCamel) {
        if (usage == null) {
            return null;
        }
        Integer input = integer(firstPresent(path(usage, inputSnake), path(usage, inputCamel)));
        Integer output = integer(firstPresent(path(usage, outputSnake), path(usage, outputCamel)));
        return input == null && output == null ? null : new Usage(input, output, null, "provider", map(usage));
    }

    private static Usage decisionUsage(Object usage, Boolean byok) {
        Integer input = integer(firstPresent(path(usage, "input_tokens"), path(usage, "inputTokens")));
        Integer output = integer(firstPresent(path(usage, "output_tokens"), path(usage, "outputTokens")));
        Object costDetails = path(usage, "cost_details");
        Double upstreamCost = number(path(costDetails, "upstream_inference_cost"));
        Double providerCost = number(path(usage, "cost"));
        Double cost = Boolean.TRUE.equals(byok) && upstreamCost != null ? upstreamCost : providerCost;
        return input == null && output == null && cost == null
                ? null
                : new Usage(input, output, cost, "provider", map(usage));
    }

    private static void validateDecisionResponse(Object model, Object answers, Object usage) {
        if (!(model instanceof String text) || text.isBlank()) {
            throw new PromptOnException("Decisions response model must be a non-empty string");
        }
        if (!(answers instanceof Map<?, ?> answerMap) || answerMap.isEmpty()) {
            throw new PromptOnException("Decisions response answers must be a non-empty object");
        }
        answerMap.forEach((name, answer) -> validateDecisionAnswer(String.valueOf(name), answer));
        if (!(usage instanceof Map<?, ?> usageMap)) {
            throw new PromptOnException("Decisions response usage must be an object");
        }
        validateNonNegativeInt(usageMap.get("input_tokens"), "input_tokens");
        validateNonNegativeInt(usageMap.get("output_tokens"), "output_tokens");
    }

    private static void validateDecisionAnswer(String name, Object answer) {
        if (!(answer instanceof Map<?, ?> map)) {
            throw new PromptOnException("Decisions answer " + name + " must be an object");
        }
        Object type = map.get("type");
        if ("choice".equals(type)) {
            Object choice = map.get("choice");
            if (!(choice instanceof String s) || s.isBlank()) {
                throw new PromptOnException("Decisions choice answer " + name
                        + " must include a non-empty choice");
            }
        } else if ("noul".equals(type)) {
            validateNumber(map.get("noul"), "Decisions noul answer " + name);
        } else if ("score".equals(type)) {
            validateNumber(map.get("score"), "Decisions score answer " + name);
        } else {
            throw new PromptOnException("Decisions answer " + name + " has an unsupported type");
        }
        Object confidence = map.get("confidence");
        if (confidence != null) {
            validateNumber(confidence, "Decisions answer " + name + " confidence");
        }
        Object probabilities = map.get("probabilities");
        if (probabilities != null) {
            if (!(probabilities instanceof Map<?, ?> probabilityMap)) {
                throw new PromptOnException("Decisions answer " + name
                        + " probabilities must be an object");
            }
            probabilityMap.forEach((key, value) -> validateNumber(
                    value, "Decisions answer " + name + " probabilities " + key));
        }
    }

    private static void validateNonNegativeInt(Object value, String name) {
        if (!(value instanceof Number number)
                || number.longValue() < 0
                || number.doubleValue() != number.longValue()) {
            throw new PromptOnException("Decisions response usage." + name
                    + " must be a non-negative integer");
        }
    }

    private static void validateNumber(Object value, String name) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new PromptOnException(name + " must be numeric");
        }
    }

    private static Object firstPresent(Object first, Object second) {
        return first != null ? first : second;
    }

    private static Object path(Object value, String key) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            return map.get(key);
        }
        String methodName = key.contains("_") ? snakeToGetter(key) : key;
        Object direct = invoke(value, methodName);
        if (direct != null) {
            return direct;
        }
        return invoke(value, "get" + Character.toUpperCase(methodName.charAt(0)) + methodName.substring(1));
    }

    private static String snakeToGetter(String key) {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char c : key.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else if (upper) {
                out.append(Character.toUpperCase(c));
                upper = false;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static Object invoke(Object value, String methodName) {
        try {
            Method method = value.getClass().getMethod(methodName);
            return method.invoke(value);
        } catch (ReflectiveOperationException | SecurityException ignored) {
            return null;
        }
    }

    private static Object first(Object value) {
        if (value instanceof List<?> list && !list.isEmpty()) {
            return list.get(0);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        if (value instanceof List<?> list) {
            return List.copyOf((List<Object>) list);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((key, item) -> out.put(String.valueOf(key), item));
            return Collections.unmodifiableMap(out);
        }
        return null;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Boolean booleanValue(Object value) {
        return value instanceof Boolean b ? b : null;
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
    public Usage usage() {
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

    /** The full provider response object, when a helper captured it. */
    public Object rawResult() {
        return rawResult;
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

    /** Assembles a {@link Result}. */
    public static final class Builder {
        private String content;
        private List<Object> toolCalls;
        private String finishReason;
        private StopKind stopKind;
        private Usage usage;
        private String modelUsed;
        private String upstreamProvider;
        private Boolean byok;
        private Object rawResult;

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
        public Builder usage(Usage value) {
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

        /** @param value the full provider response object */
        public Builder rawResult(Object value) {
            this.rawResult = value;
            return this;
        }

        /** Builds the result. */
        public Result build() {
            return new Result(this);
        }
    }
}
