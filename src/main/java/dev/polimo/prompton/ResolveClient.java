package dev.polimo.prompton;

import dev.polimo.prompton.http.HttpRequest;
import dev.polimo.prompton.http.HttpResponse;
import dev.polimo.prompton.internal.Json;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * The {@code POST /use-cases/{key}/prompt} client: the simple path, and the smoke test that proves
 * a use case resolves.
 *
 * <p>It is not for a hot loop — that is what the use-case document cache is for. Called without variables it
 * returns the raw templates and the answer is cached for the same TTL as the use-case document, per use
 * case, prompt name and environment, so the app renders locally; called with variables the server
 * renders and the answer is not cached. When PromptOn rate-limits, fails or is unreachable, a
 * cached answer is served rather than an error.
 *
 * <p>It follows the use-case document store's rules for a server that has asked to be left alone: after a
 * {@code 429} the endpoint is not contacted again before {@code Retry-After} — falling back to
 * {@code error.details.retry_after}, then to a backoff doubling from the TTL up to
 * {@code maxBackoff} — has elapsed, and a 5xx, a timeout or a transport failure backs off the same
 * way. While the pause is in force every call is answered from the cache.
 */
final class ResolveClient {

    private static final Logger LOG = Logger.getLogger(ResolveClient.class.getName());

    private record Cached(UseCase value, Instant expiresAt) {}

    private final PromptOnConfig config;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private volatile Instant pausedUntil = Instant.EPOCH;
    private volatile int failures;

    ResolveClient(PromptOnConfig config) {
        this.config = config;
    }

    UseCase resolve(String useCase, String promptName, Map<String, Object> variables) {
        boolean cacheable = variables == null;
        String key = useCase + " " + (promptName == null ? "" : promptName)
                + " " + config.environment();
        Cached cached = cache.get(key);
        if (cacheable && cached != null && Instant.now().isBefore(cached.expiresAt())) {
            return cached.value();
        }
        if (!config.remoteEnabled()) {
            if (cached != null) {
                return cached.value();
            }
            throw new PromptOnException(config.mode() == Mode.LIVE
                    ? "POST /use-cases/{key}/prompt needs an API key; configure one or load a use case "
                            + "from the cached document"
                    : "POST /use-cases/{key}/prompt is not available in "
                            + config.mode().name().toLowerCase(java.util.Locale.ROOT)
                            + " mode; load a use case from the cached document instead");
        }
        Instant pause = pausedUntil;
        if (Instant.now().isBefore(pause)) {
            if (cached != null) {
                return cached.value();
            }
            throw new PromptOnException("PromptOn asked /use-cases/{key}/prompt to wait until " + pause
                    + " and nothing is cached for " + key
                    + "; load a use case from the cached document instead");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("environment", config.environment());
        if (promptName != null) {
            body.put("prompt", promptName);
        }
        if (variables != null) {
            body.put("variables", variables);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("accept", "application/json");
        headers.put("content-type", "application/json");
        headers.put("user-agent", config.userAgent());
        headers.put("authorization", "Bearer " + config.apiKey());

        HttpResponse response;
        try {
            String path = "/use-cases/" + URLEncoder.encode(useCase, StandardCharsets.UTF_8)
                    .replace("+", "%20") + "/prompt";
            response = config.httpClient().send(new HttpRequest(
                    "POST", config.baseUrl() + path, headers, Json.write(body),
                    config.requestTimeout()));
        } catch (IOException | RuntimeException e) {
            Duration wait = backOff(null);
            if (cached != null) {
                LOG.warning("[PromptOn] /use-cases/{key}/prompt is unreachable (" + e
                        + "); serving the cached answer and not calling again for "
                        + wait.toSeconds() + "s");
                return cached.value();
            }
            throw new PromptOnException("could not reach PromptOn: " + e, e);
        }

        int status = response.status();
        if (status == 429 || status >= 500) {
            Duration wait = backOff(Backoff.retryAfterFrom(response));
            if (cached != null) {
                LOG.warning("[PromptOn] /use-cases/{key}/prompt answered HTTP " + status
                        + "; serving the cached answer and not calling again for "
                        + wait.toSeconds() + "s");
                return cached.value();
            }
        }
        if (status != 200) {
            throw error(response, useCase);
        }

        failures = 0;
        pausedUntil = Instant.EPOCH;
        UseCase loaded = parse(Json.parseObject(response.body()));
        if (cacheable) {
            cache.put(key, new Cached(loaded, Instant.now().plus(config.cacheTtl())));
        }
        return loaded;
    }

    /** Records a failure and returns how long the endpoint is now left alone for. */
    private Duration backOff(Duration retryAfter) {
        int attempt = failures + 1;
        failures = attempt;
        Duration wait = retryAfter != null
                ? retryAfter
                : Backoff.exponential(config.cacheTtl(), attempt, config.maxBackoff());
        pausedUntil = Instant.now().plus(wait);
        return wait;
    }

    private UseCase parse(Map<String, Object> body) {
        Map<String, Object> deployment = Json.mapAt(body, "deployment");
        Map<String, Object> version = Json.mapAt(body, "prompt_version");
        List<String> prompts = new ArrayList<>();
        List<Object> rawPrompts = Json.listAt(body, "prompt_names");
        if (rawPrompts != null) {
            rawPrompts.forEach(name -> prompts.add(String.valueOf(name)));
        }
        List<String> warnings = new ArrayList<>();
        List<Object> rawWarnings = Json.listAt(body, "warnings");
        if (rawWarnings != null) {
            rawWarnings.forEach(warning -> warnings.add(String.valueOf(warning)));
        }
        String source = Json.stringAt(body, "source");
        UseCase.Builder builder = new UseCase.Builder()
                .key(Json.stringAt(body, "key"))
                .kind(UseCaseKind.from(Json.stringAt(body, "kind")))
                .deploymentId(deployment == null ? null : Json.stringAt(deployment, "id"))
                .deploymentRevision(deployment == null ? null : Json.intAt(deployment, "revision", null))
                .prompt(Json.stringAt(body, "prompt"))
                .promptNames(prompts)
                .model(Json.stringAt(body, "model"))
                .modelId(Json.stringAt(body, "model_id"))
                .provider(Json.stringAt(body, "provider"))
                .params(Json.mapAt(body, "params"))
                .providerOptions(Json.mapAt(body, "provider_options"))
                .promptVersionId(version == null ? null : Json.stringAt(version, "id"))
                .promptVersionNumber(version == null ? null : Json.intAt(version, "number", null))
                .source(source == null ? Source.REMOTE : Source.from(source))
                .etag(Json.stringAt(body, "etag"))
                .warnings(warnings);

        List<Object> messages = Json.listAt(body, "messages");
        if (messages != null) {
            List<Message> parsed = new ArrayList<>(messages.size());
            for (Object entry : messages) {
                if (entry instanceof Map<?, ?>) {
                    parsed.add(Message.fromMap(Json.mapAt(Map.of("m", entry), "m")));
                }
            }
            builder.messages(parsed);
        }
        String text = Json.stringAt(body, "text");
        if (text != null) {
            builder.textTemplate(text);
        }
        return builder.build();
    }

    private RuntimeException error(HttpResponse response, String requestedKey) {
        Map<String, Object> error;
        try {
            error = Json.mapAt(Json.parseObject(response.body()), "error");
        } catch (RuntimeException e) {
            error = null;
        }
        if (error == null) {
            return new ApiException(response.status(), null, response.body(), Map.of());
        }
        String code = Json.stringAt(error, "code");
        String message = Json.stringAt(error, "message");
        Map<String, Object> details = Json.mapAt(error, "details");
        Map<String, Object> safeDetails = details == null ? Map.of() : details;

        String reason = Json.stringAt(safeDetails, "reason");
        if ("unresolved".equals(reason)) {
            String key = Json.stringAt(safeDetails, "key");
            return UseCaseException.of(
                    UseCaseException.Reason.UNRESOLVED, key == null ? requestedKey : key);
        }
        if ("unknown_prompt".equals(reason)) {
            List<String> available = new ArrayList<>();
            List<Object> raw = Json.listAt(safeDetails, "prompt_names");
            if (raw != null) {
                raw.forEach(name -> available.add(String.valueOf(name)));
            }
            String key = Json.stringAt(safeDetails, "key");
            return UseCaseException.unknownPrompt(
                    key == null ? requestedKey : key,
                    Json.stringAt(safeDetails, "prompt"),
                    available);
        }
        if ("not_found".equals(code) && safeDetails.containsKey("key")) {
            return UseCaseException.of(
                    UseCaseException.Reason.UNKNOWN_USE_CASE, Json.stringAt(safeDetails, "key"));
        }
        String missing = Json.stringAt(safeDetails, "missing_variable");
        if (missing != null) {
            return TemplateException.missingVariable(missing);
        }
        return new ApiException(response.status(), code, message, safeDetails);
    }
}
