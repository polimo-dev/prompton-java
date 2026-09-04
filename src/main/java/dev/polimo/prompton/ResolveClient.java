package dev.polimo.prompton;

import dev.polimo.prompton.http.HttpRequest;
import dev.polimo.prompton.http.HttpResponse;
import dev.polimo.prompton.internal.Json;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * The {@code POST /resolve} client: the simple path, and the smoke test that proves a pin resolves.
 *
 * <p>It is not for a hot loop — that is what the snapshot is for. Called without variables it
 * returns the raw templates and the answer is cached for the same TTL as the snapshot, per use
 * case, prompt name and environment, so the app renders locally; called with variables the server
 * renders and the answer is not cached. When PromptOn rate-limits, fails or is unreachable, a
 * cached answer is served rather than an error.
 */
final class ResolveClient {

    private static final Logger LOG = Logger.getLogger(ResolveClient.class.getName());

    private record Cached(Resolution resolution, Instant expiresAt) {}

    private final PromptOnConfig config;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    ResolveClient(PromptOnConfig config) {
        this.config = config;
    }

    Resolution resolve(String useCase, String promptName, Map<String, Object> variables) {
        boolean cacheable = variables == null;
        String key = useCase + " " + (promptName == null ? "" : promptName)
                + " " + config.environment();
        Cached cached = cache.get(key);
        if (cacheable && cached != null && Instant.now().isBefore(cached.expiresAt())) {
            return cached.resolution();
        }
        if (!config.remoteEnabled()) {
            if (cached != null) {
                return cached.resolution();
            }
            throw new PromptOnException(
                    "POST /resolve needs an API key; configure one or resolve from the snapshot");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("use_case", useCase);
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
            response = config.httpClient().send(new HttpRequest(
                    "POST", config.baseUrl() + "/resolve", headers, Json.write(body),
                    config.requestTimeout()));
        } catch (IOException | RuntimeException e) {
            if (cached != null) {
                LOG.warning("[PromptOn] /resolve is unreachable (" + e + "); serving the cached answer");
                return cached.resolution();
            }
            throw new PromptOnException("could not reach PromptOn: " + e, e);
        }

        int status = response.status();
        if ((status == 429 || status >= 500) && cached != null) {
            LOG.warning("[PromptOn] /resolve answered HTTP " + status + "; serving the cached answer");
            return cached.resolution();
        }
        if (status != 200) {
            throw error(response);
        }

        Resolution resolution = parse(Json.parseObject(response.body()));
        if (cacheable) {
            cache.put(key, new Cached(resolution, Instant.now().plus(config.cacheTtl())));
        }
        return resolution;
    }

    private Resolution parse(Map<String, Object> body) {
        Map<String, Object> deployment = Json.mapAt(body, "deployment");
        Map<String, Object> version = Json.mapAt(body, "prompt_version");
        List<String> prompts = new ArrayList<>();
        List<Object> rawPrompts = Json.listAt(body, "prompts");
        if (rawPrompts != null) {
            rawPrompts.forEach(name -> prompts.add(String.valueOf(name)));
        }
        List<String> warnings = new ArrayList<>();
        List<Object> rawWarnings = Json.listAt(body, "warnings");
        if (rawWarnings != null) {
            rawWarnings.forEach(warning -> warnings.add(String.valueOf(warning)));
        }
        Resolution.Builder builder = new Resolution.Builder()
                .useCase(Json.stringAt(body, "use_case"))
                .kind(UseCaseKind.from(Json.stringAt(body, "kind")))
                .deploymentId(deployment == null ? null : Json.stringAt(deployment, "id"))
                .deploymentRevision(deployment == null ? null : Json.intAt(deployment, "revision", null))
                .prompt(Json.stringAt(body, "prompt"))
                .availablePrompts(prompts)
                .model(Json.stringAt(body, "model"))
                .modelId(Json.stringAt(body, "model_id"))
                .provider(Json.stringAt(body, "provider"))
                .effectiveParams(Json.mapAt(body, "effective_params"))
                .effectiveProviderOptions(Json.mapAt(body, "effective_provider_options"))
                .promptVersionId(version == null ? null : Json.stringAt(version, "id"))
                .promptVersionNumber(version == null ? null : Json.intAt(version, "number", null))
                .source(ResolutionSource.REMOTE)
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

    private RuntimeException error(HttpResponse response) {
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
            return ResolutionException.of(
                    ResolutionException.Reason.UNRESOLVED, Json.stringAt(safeDetails, "use_case"));
        }
        if ("unknown_prompt".equals(reason)) {
            List<String> available = new ArrayList<>();
            List<Object> raw = Json.listAt(safeDetails, "available_prompts");
            if (raw != null) {
                raw.forEach(name -> available.add(String.valueOf(name)));
            }
            return ResolutionException.unknownPrompt(
                    Json.stringAt(safeDetails, "use_case"),
                    Json.stringAt(safeDetails, "prompt"),
                    available);
        }
        if ("not_found".equals(code) && safeDetails.containsKey("use_case")) {
            return ResolutionException.of(
                    ResolutionException.Reason.UNKNOWN_USE_CASE, Json.stringAt(safeDetails, "use_case"));
        }
        String missing = Json.stringAt(safeDetails, "missing_variable");
        if (missing != null) {
            return TemplateException.missingVariable(missing);
        }
        return new ApiException(response.status(), code, message, safeDetails);
    }
}
