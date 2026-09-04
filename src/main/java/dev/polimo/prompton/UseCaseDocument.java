package dev.polimo.prompton;

import dev.polimo.prompton.internal.Json;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A parsed {@code GET /use-cases} document — everything live in one environment.
 *
 * <p>The SDK reads schema version 4 only. The {@code schema_version} field must be the JSON
 * integer {@code 4}; missing, non-integral, and non-4 version values are refused ({@link #parse}
 * throws). A deployment revision is a <em>pin</em>, not a router: one model plus one pinned prompt
 * version per prompt name.
 */
public final class UseCaseDocument {

    /** The schema version this SDK reads. */
    public static final int SCHEMA_VERSION = 4;

    /**
     * One use case: what shape of call it is and what its logs may carry.
     *
     * @param id the use case's UUID
     * @param key the stable key one call site is known by
     * @param kind chat, text or embedding
     * @param inputSchema the variables the prompt declares
     * @param defaultParams the params a deployment layers its own on top of
     * @param payloadPolicy how much of a call's text its monitoring logs may carry
     */
    public record UseCase(
            String id,
            String key,
            UseCaseKind kind,
            List<Map<String, Object>> inputSchema,
            Map<String, Object> defaultParams,
            PayloadPolicy payloadPolicy) {}

    /**
     * One live deployment revision: the pin.
     *
     * @param id the revision's UUID
     * @param useCaseKey the use case it pins
     * @param revision the revision number, which increases on every deploy and rollback
     * @param modelId the catalog UUID of the model it pins
     * @param params the params layered over the use case's defaults
     * @param providerOptions the provider options layered over the model's
     * @param promptPins one pinned prompt version id per prompt name; empty for an embedding
     */
    public record Deployment(
            String id,
            String useCaseKey,
            Integer revision,
            String modelId,
            Map<String, Object> params,
            Map<String, Object> providerOptions,
            Map<String, String> promptPins) {}

    /**
     * One immutable prompt version.
     *
     * @param id the version's UUID, which a deployment pins
     * @param promptId the prompt this is a version of
     * @param number the version number, counting up from 1
     * @param engine liquid or raw
     * @param messages the chat template, or {@code null} for a text version
     * @param textTemplate the text template, or {@code null} for a chat version
     */
    public record PromptVersion(
            String id,
            String promptId,
            Integer number,
            Template.Engine engine,
            List<Message> messages,
            String textTemplate) {}

    /**
     * One catalog model.
     *
     * @param id the catalog UUID a deployment pins
     * @param provider which provider to call
     * @param modelId the provider model string the app sends to the provider, byte for byte
     * @param displayName a human-readable name
     * @param metadata free-form catalog data
     * @param providerOptions the provider options a deployment layers its own on top of
     * @param capabilities what the model supports, for example {@code tools}
     * @param status whether the catalog entry is active
     */
    public record Model(
            String id,
            String provider,
            String modelId,
            String displayName,
            Map<String, Object> metadata,
            Map<String, Object> providerOptions,
            List<String> capabilities,
            String status) {}

    private final int schemaVersion;
    private final String project;
    private final String environment;
    private final Map<String, UseCase> useCases;
    private final Map<String, Deployment> deployments;
    private final Map<String, PromptVersion> promptVersions;
    private final Map<String, Model> models;
    private final List<String> warnings;

    private UseCaseDocument(
            int schemaVersion,
            String project,
            String environment,
            Map<String, UseCase> useCases,
            Map<String, Deployment> deployments,
            Map<String, PromptVersion> promptVersions,
            Map<String, Model> models,
            List<String> warnings) {
        this.schemaVersion = schemaVersion;
        this.project = project;
        this.environment = environment;
        this.useCases = Collections.unmodifiableMap(useCases);
        this.deployments = Collections.unmodifiableMap(deployments);
        this.promptVersions = Collections.unmodifiableMap(promptVersions);
        this.models = Collections.unmodifiableMap(models);
        this.warnings = List.copyOf(warnings);
    }

    /** Parses a use-case document JSON body. */
    public static UseCaseDocument parse(String json) {
        return fromMap(Json.parseObject(json));
    }

    /** Builds a use-case document from an already-decoded body. */
    @SuppressWarnings("unchecked")
    public static UseCaseDocument fromMap(Map<String, Object> doc) {
        List<String> warnings = new ArrayList<>();
        int version = readSchemaVersion(doc);

        Map<String, Object> rawUseCases = Json.mapAt(doc, "use_cases");
        if (rawUseCases == null) {
            throw new PromptOnException("use-case document is missing use_cases");
        }

        Map<String, UseCase> useCases = new LinkedHashMap<>();
        rawUseCases.forEach((key, value) -> {
            if (value instanceof Map<?, ?> raw) {
                useCases.put(key, readUseCase(key, (Map<String, Object>) raw));
            } else {
                warnings.add("invalid_use_case: " + key);
            }
        });

        Map<String, Deployment> deployments = new LinkedHashMap<>();
        Map<String, Object> rawDeployments = Json.mapAt(doc, "deployments");
        if (rawDeployments != null) {
            rawDeployments.forEach((key, value) -> {
                if (value instanceof Map<?, ?> raw) {
                    deployments.put(key, readDeployment(key, (Map<String, Object>) raw, warnings));
                } else {
                    warnings.add("invalid_deployment: " + key);
                }
            });
        }

        Map<String, PromptVersion> promptVersions = new LinkedHashMap<>();
        Map<String, Object> rawVersions = Json.mapAt(doc, "prompt_versions");
        if (rawVersions != null) {
            rawVersions.forEach((id, value) -> {
                if (value instanceof Map<?, ?> raw) {
                    PromptVersion parsed = readPromptVersion(id, (Map<String, Object>) raw);
                    promptVersions.put(parsed.id(), parsed);
                } else {
                    warnings.add("invalid_prompt_version: " + id);
                }
            });
        }

        Map<String, Model> models = new LinkedHashMap<>();
        Map<String, Object> rawModels = Json.mapAt(doc, "models");
        if (rawModels != null) {
            rawModels.forEach((id, value) -> {
                if (value instanceof Map<?, ?> raw) {
                    Model parsed = readModel(id, (Map<String, Object>) raw);
                    models.put(parsed.id(), parsed);
                } else {
                    warnings.add("invalid_model: " + id);
                }
            });
        }

        return new UseCaseDocument(
                version,
                Json.stringAt(doc, "project"),
                Json.stringAt(doc, "environment"),
                useCases,
                deployments,
                promptVersions,
                models,
                warnings);
    }

    private static int readSchemaVersion(Map<String, Object> doc) {
        Object raw = doc.get("schema_version");
        if (raw == null) {
            throw new PromptOnException("use-case document is missing schema_version");
        }
        if (!(raw instanceof Byte
                || raw instanceof Short
                || raw instanceof Integer
                || raw instanceof Long)) {
            throw new PromptOnException(
                    "use-case document schema_version must be the JSON integer "
                            + SCHEMA_VERSION);
        }
        long version = ((Number) raw).longValue();
        if (version != SCHEMA_VERSION) {
            throw new PromptOnException(
                    "unsupported use-case document schema_version " + version
                            + "; this SDK reads version "
                            + SCHEMA_VERSION);
        }
        return (int) version;
    }

    /** {@code Map.copyOf} rejects null values, and an explicit null is a meaningful override. */
    private static Map<String, Object> frozen(Map<String, Object> map) {
        return map == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    @SuppressWarnings("unchecked")
    private static UseCase readUseCase(String key, Map<String, Object> raw) {
        List<Map<String, Object>> schema = new ArrayList<>();
        List<Object> rawSchema = Json.listAt(raw, "input_schema");
        if (rawSchema != null) {
            for (Object entry : rawSchema) {
                if (entry instanceof Map<?, ?> map) {
                    schema.add(frozen((Map<String, Object>) map));
                }
            }
        }
        Map<String, Object> defaults = Json.mapAt(raw, "default_params");
        return new UseCase(
                Json.stringAt(raw, "id"),
                key,
                UseCaseKind.from(Json.stringAt(raw, "kind")),
                List.copyOf(schema),
                frozen(defaults),
                PayloadPolicy.fromMap(Json.mapAt(raw, "payload_policy")));
    }

    private static Deployment readDeployment(
            String key, Map<String, Object> raw, List<String> warnings) {
        Map<String, String> pins = new TreeMap<>();
        Map<String, Object> rawPins = Json.mapAt(raw, "prompt_pins");
        if (rawPins != null) {
            rawPins.forEach((name, id) -> {
                if (id instanceof String versionId) {
                    pins.put(name, versionId);
                } else {
                    warnings.add("invalid_prompt_pin: " + key + "/" + name);
                }
            });
        }
        Map<String, Object> params = Json.mapAt(raw, "params");
        Map<String, Object> providerOptions = Json.mapAt(raw, "provider_options");
        String useCaseKey = Json.stringAt(raw, "use_case_key");
        return new Deployment(
                Json.stringAt(raw, "id"),
                useCaseKey == null ? key : useCaseKey,
                Json.intAt(raw, "revision", null),
                Json.stringAt(raw, "model_id"),
                frozen(params),
                frozen(providerOptions),
                Collections.unmodifiableMap(pins));
    }

    @SuppressWarnings("unchecked")
    private static PromptVersion readPromptVersion(String id, Map<String, Object> raw) {
        List<Message> messages = null;
        List<Object> rawMessages = Json.listAt(raw, "messages");
        if (rawMessages != null) {
            messages = new ArrayList<>();
            for (Object entry : rawMessages) {
                if (entry instanceof Map<?, ?> map) {
                    messages.add(Message.fromMap((Map<String, Object>) map));
                }
            }
            messages = List.copyOf(messages);
        }
        String versionId = Json.stringAt(raw, "id");
        return new PromptVersion(
                versionId == null ? id : versionId,
                Json.stringAt(raw, "prompt_id"),
                Json.intAt(raw, "number", null),
                Template.Engine.from(Json.stringAt(raw, "engine")),
                messages,
                Json.stringAt(raw, "text_template"));
    }

    private static Model readModel(String id, Map<String, Object> raw) {
        List<String> capabilities = new ArrayList<>();
        List<Object> rawCapabilities = Json.listAt(raw, "capabilities");
        if (rawCapabilities != null) {
            for (Object entry : rawCapabilities) {
                if (entry != null) {
                    capabilities.add(String.valueOf(entry));
                }
            }
        }
        Map<String, Object> metadata = Json.mapAt(raw, "metadata");
        Map<String, Object> providerOptions = Json.mapAt(raw, "provider_options");
        String modelUuid = Json.stringAt(raw, "id");
        return new Model(
                modelUuid == null ? id : modelUuid,
                Json.stringAt(raw, "provider"),
                Json.stringAt(raw, "model_id"),
                Json.stringAt(raw, "display_name"),
                frozen(metadata),
                frozen(providerOptions),
                List.copyOf(capabilities),
                Json.stringAt(raw, "status"));
    }

    /** The schema version of the document that was parsed. */
    public int schemaVersion() {
        return schemaVersion;
    }

    /** The project slug this document belongs to. */
    public String project() {
        return project;
    }

    /** The environment this document describes. */
    public String environment() {
        return environment;
    }

    /** Every use case, keyed by use case key. */
    public Map<String, UseCase> useCases() {
        return useCases;
    }

    /** The live deployment per use case key. A use case with none is simply absent. */
    public Map<String, Deployment> deployments() {
        return deployments;
    }

    /** Every pinned prompt version, keyed by version id. */
    public Map<String, PromptVersion> promptVersions() {
        return promptVersions;
    }

    /** Every pinned model, keyed by catalog id. */
    public Map<String, Model> models() {
        return models;
    }

    /** Anything odd noticed while decoding — an unknown schema version, a malformed entry. */
    public List<String> warnings() {
        return warnings;
    }

    /** The sorted prompt names the live deployment of {@code useCaseKey} pins. */
    public List<String> promptNames(String useCaseKey) {
        if (!useCases.containsKey(useCaseKey)) {
            throw UseCaseException.of(UseCaseException.Reason.UNKNOWN_USE_CASE, useCaseKey);
        }
        Deployment deployment = deployments.get(useCaseKey);
        return deployment == null ? List.of() : List.copyOf(deployment.promptPins().keySet());
    }
}
