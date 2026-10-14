package dev.polimo.prompton;

import dev.polimo.prompton.internal.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Provider request preparation for Chat Completions and Decisions. */
final class ProviderRequests {

    private static final Set<String> PROTECTED = Set.of(
            "model", "messages", "state", "questions", "provider", "usage", "api",
            "request_path", "method", "path", "body");
    private static final Set<String> DECISION_PARAMS = Set.of("session_id", "trace", "user");

    private ProviderRequests() {}

    static PreparedRequest prepare(
            Prompt prompt, Map<String, Object> variables, RequestOptions options) {
        String api = expectApi(prompt);
        String path = expectPath(prompt);
        if ("chat_completions".equals(api)) {
            return prepareChat(prompt, variables, options, api, path);
        }
        if ("decisions".equals(api)) {
            return prepareDecision(prompt, variables, options, api, path);
        }
        throw new PromptOnException(
                "prompt " + prompt.key() + " uses unsupported deployment api " + prompt.api());
    }

    static Decision renderDecision(
            Decision decision, Map<String, Object> variables, Template.Engine engine) {
        validateDecision(decision);
        Object state = renderDecisionValue(decision.state(), variables == null ? Map.of() : variables, engine);
        Map<String, Object> questions = renderQuestions(
                decision.questions(), variables == null ? Map.of() : variables, engine);
        Decision rendered = new Decision(state, questions);
        validateDecision(rendered);
        return rendered;
    }

    private static PreparedRequest prepareChat(
            Prompt prompt,
            Map<String, Object> variables,
            RequestOptions options,
            String api,
            String path) {
        if (options.sessionId() != null || options.trace() != null || options.user() != null) {
            throw new PromptOnException("Decisions metadata options require the Decisions API");
        }
        if (prompt.kind() != PromptKind.CHAT || prompt.messages() == null) {
            throw new PromptOnException("prompt " + prompt.key() + " has no chat template");
        }
        List<Message> rendered = prompt.messages(variables);
        Map<String, Object> canonical = toolFields(prompt.params());
        Map<String, Object> params = requestParams(prompt.params(), options.params(), "chat", canonical);
        Map<String, Object> provider = providerOptions(
                Params.merge(prompt.providerOptions(), options.providerOptions()));
        rejectProviderOptionsForNonOpenRouter(prompt, provider);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", expectModel(prompt));
        List<Object> messages = new ArrayList<>(rendered.size());
        for (Message message : rendered) {
            messages.add(message.toMap());
        }
        body.put("messages", messages);
        body.putAll(params);
        body.putAll(canonical);
        if ("openrouter".equals(prompt.provider())) {
            body.put("usage", Map.of("include", true));
        }
        if (!provider.isEmpty()) {
            body.put("provider", provider);
        }
        return new PreparedRequest(api, "POST", path, body);
    }

    private static PreparedRequest prepareDecision(
            Prompt prompt,
            Map<String, Object> variables,
            RequestOptions options,
            String api,
            String path) {
        if (prompt.kind() != PromptKind.DECISION || prompt.decision() == null) {
            throw new PromptOnException("prompt " + prompt.key() + " has no Decision template");
        }
        Map<String, Object> params = decisionParams(prompt.params(), options.params());
        Decision decision = renderDecision(prompt.decision(), variables, prompt.engine());
        Map<String, Object> provider = providerOptions(
                Params.merge(prompt.providerOptions(), options.providerOptions()));
        rejectProviderOptionsForNonOpenRouter(prompt, provider);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", expectModel(prompt));
        body.put("state", decision.state());
        body.put("questions", decision.questions());
        body.putAll(params);
        if (!provider.isEmpty()) {
            body.put("provider", provider);
        }
        putDecisionOption(body, "session_id", options.sessionId());
        putDecisionOption(body, "trace", options.trace());
        putDecisionOption(body, "user", options.user());
        return new PreparedRequest(api, "POST", path, body);
    }

    private static String expectApi(Prompt prompt) {
        if (prompt.pinnedKind() == null) {
            throw new PromptOnException("prompt " + prompt.key()
                    + " cannot prepare a provider request because its pinned prompt version"
                    + " has no kind metadata");
        }
        if (!"chat_completions".equals(prompt.api()) && !"decisions".equals(prompt.api())) {
            throw new PromptOnException("prompt " + prompt.key()
                    + " cannot prepare a provider request because its deployment has no"
                    + " supported api metadata");
        }
        if ("chat_completions".equals(prompt.api()) && prompt.pinnedKind() != PromptKind.CHAT) {
            throw new PromptOnException("prompt " + prompt.key()
                    + " has deployment api chat_completions but pinned version kind "
                    + prompt.pinnedKind().wireName());
        }
        if ("decisions".equals(prompt.api()) && prompt.pinnedKind() != PromptKind.DECISION) {
            throw new PromptOnException("prompt " + prompt.key()
                    + " has deployment api decisions but pinned version kind "
                    + prompt.pinnedKind().wireName());
        }
        return prompt.api();
    }

    private static String expectPath(Prompt prompt) {
        String path = prompt.requestPath();
        if (path == null || path.isBlank()) {
            throw new PromptOnException("prompt " + prompt.key()
                    + " cannot prepare a provider request because its deployment has no"
                    + " request_path metadata");
        }
        if (!path.startsWith("/") || path.startsWith("//") || path.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) {
            throw new PromptOnException("prompt " + prompt.key() + " has invalid request_path "
                    + path + "; expected an origin-relative path");
        }
        List<String> expected = expectedPaths(prompt.provider(), prompt.api());
        if (expected.isEmpty()) {
            throw new PromptOnException("prompt " + prompt.key()
                    + " cannot prepare a provider request for provider " + prompt.provider()
                    + " and api " + prompt.api());
        }
        if (!expected.contains(path)) {
            throw new PromptOnException("prompt " + prompt.key() + " has request_path " + path
                    + ", expected " + String.join(" or ", expected) + " for provider "
                    + prompt.provider() + " and api " + prompt.api());
        }
        return path;
    }

    private static List<String> expectedPaths(String provider, String api) {
        if ("openrouter".equals(provider) && "chat_completions".equals(api)) {
            return List.of("/api/v1/chat/completions");
        }
        if ("openrouter".equals(provider) && "decisions".equals(api)) {
            return List.of("/api/v1/systemone", "/api/alpha/decisions");
        }
        if ("openai".equals(provider) && "chat_completions".equals(api)) {
            return List.of("/v1/chat/completions");
        }
        if ("groq".equals(provider) && "chat_completions".equals(api)) {
            return List.of("/openai/v1/chat/completions");
        }
        return List.of();
    }

    private static String expectModel(Prompt prompt) {
        if (prompt.model() == null || prompt.model().isBlank()) {
            throw new PromptOnException("prompt " + prompt.key()
                    + " cannot prepare a provider request because its deployment model is missing");
        }
        return prompt.model();
    }

    private static Map<String, Object> requestParams(
            Map<String, Object> base,
            Map<String, Object> override,
            String api,
            Map<String, Object> canonical) {
        Map<String, Object> merged = Params.merge(base, override);
        for (String key : merged.keySet()) {
            if (PROTECTED.contains(key)
                    && !(canonical.containsKey(key) && sameJson(merged.get(key), canonical.get(key)))) {
                throw new PromptOnException(api + " request params cannot override protected field " + key);
            }
        }
        Map<String, Object> cleaned = cleanRecord(merged);
        canonical.keySet().forEach(cleaned::remove);
        return cleaned;
    }

    private static Map<String, Object> toolFields(Map<String, Object> params) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : List.of("tools", "tool_choice", "parallel_tool_calls")) {
            if (params.containsKey(key)) {
                out.put(key, params.get(key));
            }
        }
        return out;
    }

    private static Map<String, Object> decisionParams(
            Map<String, Object> base, Map<String, Object> override) {
        Map<String, Object> merged = providerOptions(Params.merge(base, override));
        List<String> unsupported = merged.keySet().stream()
                .filter(key -> !DECISION_PARAMS.contains(key))
                .sorted()
                .toList();
        if (!unsupported.isEmpty()) {
            throw new PromptOnException("Decisions requests support only session_id, trace, user"
                    + " and providerOptions; unsupported parameter(s): "
                    + String.join(", ", unsupported));
        }
        validateDecisionMetadata(merged);
        return merged;
    }

    private static void putDecisionOption(
            Map<String, Object> body, String key, Object value) {
        if (value == null) {
            return;
        }
        validateDecisionMetadata(Map.of(key, value));
        body.put(key, value);
    }

    private static void validateDecisionMetadata(Map<String, Object> params) {
        Object sessionId = params.get("session_id");
        if (params.containsKey("session_id")
                && (!(sessionId instanceof String s) || s.length() > 256)) {
            throw new PromptOnException("Decisions session_id must be a string of 256 characters or fewer");
        }
        Object user = params.get("user");
        if (params.containsKey("user") && (!(user instanceof String s) || s.length() > 256)) {
            throw new PromptOnException("Decisions user must be a string of 256 characters or fewer");
        }
        Object trace = params.get("trace");
        if (params.containsKey("trace") && !(trace instanceof Map<?, ?>)) {
            throw new PromptOnException("Decisions trace must be an object");
        }
    }

    private static Map<String, Object> providerOptions(Map<String, Object> record) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (record != null) {
            record.forEach((key, value) -> {
                if (value != null || record.containsKey(key)) {
                    out.put(key, value);
                }
            });
        }
        return out;
    }

    private static Map<String, Object> cleanRecord(Map<String, Object> record) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (record != null) {
            record.forEach((key, value) -> {
                if (value != null) {
                    out.put(key, value);
                }
            });
        }
        return out;
    }

    private static void rejectProviderOptionsForNonOpenRouter(
            Prompt prompt, Map<String, Object> provider) {
        if ("openrouter".equals(prompt.provider()) || provider.isEmpty()) {
            return;
        }
        throw new PromptOnException("prompt " + prompt.key()
                + " has provider options, but provider options are only supported"
                + " for OpenRouter requests");
    }

    private static Object renderDecisionValue(
            Object value, Map<String, Object> variables, Template.Engine engine) {
        if (value instanceof String text) {
            return Template.render(text, variables, engine);
        }
        if (value instanceof List<?> list) {
            List<Object> rendered = new ArrayList<>(list.size());
            for (Object item : list) {
                rendered.add(renderDecisionValue(item, variables, engine));
            }
            return rendered;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> rendered = new LinkedHashMap<>();
            map.forEach((key, item) ->
                    rendered.put(String.valueOf(key), renderDecisionValue(item, variables, engine)));
            return rendered;
        }
        return value;
    }

    private static Map<String, Object> renderQuestions(
            Map<String, Object> questions, Map<String, Object> variables, Template.Engine engine) {
        Map<String, Object> rendered = new LinkedHashMap<>();
        questions.forEach((name, question) -> {
            if (!(question instanceof Map<?, ?> raw)) {
                throw new PromptOnException("Decisions question " + name + " must be an object");
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            raw.forEach((key, value) -> copy.put(String.valueOf(key), value));
            if (copy.containsKey("instructions")) {
                copy.put("instructions", renderDecisionValue(copy.get("instructions"), variables, engine));
            }
            if (copy.containsKey("criteria")) {
                copy.put("criteria", renderDecisionValue(copy.get("criteria"), variables, engine));
            }
            rendered.put(name, copy);
        });
        return rendered;
    }

    private static void validateDecision(Decision decision) {
        validateState(decision.state());
        validateQuestions(decision.questions());
    }

    private static void validateState(Object state) {
        if (state instanceof String || state instanceof Map<?, ?> || state instanceof List<?>) {
            return;
        }
        throw new PromptOnException("Decisions state must be a string, JSON object, or JSON array");
    }

    private static void validateQuestions(Map<String, Object> questions) {
        if (questions == null || questions.isEmpty()) {
            throw new PromptOnException("Decisions questions must be a non-empty object");
        }
        questions.forEach((name, question) -> {
            if (name == null || name.trim().isEmpty()) {
                throw new PromptOnException("Decisions question names must be non-empty strings");
            }
            validateQuestion(name, question);
        });
    }

    private static void validateQuestion(String name, Object question) {
        if (!(question instanceof Map<?, ?> raw)) {
            throw new PromptOnException("Decisions question " + name + " must be an object");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        raw.forEach((key, value) -> map.put(String.valueOf(key), value));
        for (String key : map.keySet()) {
            if (!List.of("type", "instructions", "criteria").contains(key)) {
                throw new PromptOnException("Decisions question " + name
                        + " accepts only type, instructions, and criteria");
            }
        }
        Object type = map.get("type");
        if ("choice".equals(type)) {
            validateInstructions(name, map);
            validateChoiceCriteria(name, map.get("criteria"));
            return;
        }
        if ("noul".equals(type)) {
            validateInstructions(name, map);
            validateNoulCriteria(name, map.get("criteria"));
            return;
        }
        if ("score".equals(type)) {
            validateInstructions(name, map);
            validateScoreCriteria(name, map.get("criteria"));
            return;
        }
        throw new PromptOnException("Decisions question " + name + " has an unsupported type");
    }

    private static void validateInstructions(String name, Map<String, Object> question) {
        if (!isGuidance(question.get("instructions"))) {
            throw new PromptOnException("Decisions question " + name + " must include instructions");
        }
    }

    private static void validateChoiceCriteria(String name, Object criteria) {
        if (!(criteria instanceof Map<?, ?> map) || map.isEmpty()) {
            throw new PromptOnException("Decisions choice question " + name
                    + " criteria must be a non-empty object");
        }
        if (map.size() > 255) {
            throw new PromptOnException("Decisions choice question " + name
                    + " criteria must contain at most 255 choices");
        }
        map.forEach((choice, guidance) -> {
            if (String.valueOf(choice).trim().isEmpty()
                    || (!(isGuidance(guidance) || guidance == null))) {
                throw new PromptOnException("Decisions choice question " + name
                        + " criteria must map choices to guidance");
            }
        });
    }

    private static void validateNoulCriteria(String name, Object criteria) {
        if (criteria == null) {
            return;
        }
        if (!(criteria instanceof Map<?, ?> map)
                || map.size() != 2
                || !map.containsKey("true")
                || !map.containsKey("false")
                || !isGuidance(map.get("true"))
                || !isGuidance(map.get("false"))) {
            throw new PromptOnException("Decisions noul question " + name
                    + " criteria must include true and false guidance");
        }
    }

    private static void validateScoreCriteria(String name, Object criteria) {
        if (!(criteria instanceof List<?> list)
                || list.size() < 2
                || list.size() > 10
                || !list.stream().allMatch(ProviderRequests::isGuidance)) {
            throw new PromptOnException("Decisions score question " + name
                    + " criteria must be a list of 2 to 10 guidance values");
        }
    }

    private static boolean isGuidance(Object value) {
        return value instanceof String || value instanceof Map<?, ?> || value instanceof List<?>;
    }

    private static boolean sameJson(Object left, Object right) {
        return Objects.equals(Json.canonical(left), Json.canonical(right));
    }
}
