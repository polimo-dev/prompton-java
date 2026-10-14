package dev.polimo.prompton;

import dev.polimo.prompton.internal.Json;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A parsed PromptOn prompt document — either a bundle or one demand-fetched prompt key.
 *
 * <p>The SDK reads schema version 1. The {@code schema_version} field must be a JSON
 * integer 1; missing, non-integral, and unsupported version values are refused ({@link #parse}
 * throws). A deployment revision is a <em>pin</em>, not a router: one model plus one pinned prompt
 * version per prompt name.
 */
public final class UseCaseDocument {

    /** The schema version this SDK reads. */
    public static final int SCHEMA_VERSION = 1;

    /**
     * One prompt: what shape of call it is and what its logs may carry.
     *
     * @param id reserved; always {@code null}
     * @param key the stable key one call site is known by
     * @param kind chat, decision, text or embedding
     * @param variables the variables the prompt declares
     * @param defaultParams the params a deployment layers its own on top of
     * @param payloadPolicy how much of a call's text its monitoring logs may carry
     */
    public record UseCase(
            String id,
            String key,
            UseCaseKind kind,
            List<Map<String, Object>> variables,
            Map<String, Object> defaultParams,
            PayloadPolicy payloadPolicy) {}

    /**
     * One live deployment revision: the pin.
     *
     * @param id reserved; always {@code null}
     * @param useCaseKey the prompt it pins
     * @param revision the UTC-date revision label, for example {@code v2026.09.30-1}
     * @param modelId the model lookup key it pins
     * @param api provider API family
     * @param requestPath origin-relative provider request path
     * @param params the params layered over the prompt's defaults
     * @param providerOptions the provider options layered over the model's
     * @param promptPins one pinned prompt version id per prompt name; empty for an embedding
     */
    public record Deployment(
            String id,
            String useCaseKey,
            String revision,
            String modelId,
            String api,
            String requestPath,
            Map<String, Object> params,
            Map<String, Object> providerOptions,
            Map<String, String> promptPins) {}

    /**
     * One immutable prompt version.
     *
     * @param id the version lookup key
     * @param promptId the prompt this is a version of, when supplied by the document
     * @param number the version number, counting up from 1
     * @param kind immutable version kind
     * @param engine liquid or raw
     * @param messages the chat template, or {@code null} for a text version
     * @param decision the Decision template, or {@code null} for another kind
     * @param textTemplate the text template, or {@code null} for a chat version
     */
    public record PromptVersion(
            String id,
            String promptId,
            Integer number,
            UseCaseKind kind,
            Template.Engine engine,
            List<Message> messages,
            Decision decision,
            Map<String, Object> tools,
            String textTemplate) {}

    /**
     * One catalog model.
     *
     * @param id the model lookup key
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

    /** Parses a prompt document JSON body. */
    public static UseCaseDocument parse(String json) {
        return fromMap(Json.parseObject(json));
    }

    /** Builds a prompt document from an already-decoded body. */
    @SuppressWarnings("unchecked")
    public static UseCaseDocument fromMap(Map<String, Object> doc) {
        List<String> warnings = new ArrayList<>();
        int version = readSchemaVersion(doc);
        Map<String, Object> normalized = hasSingleResource(doc) ? normalizeSingleDocument(doc) : doc;

        Map<String, Object> rawUseCases = Json.mapAt(normalized, "prompts");
        if (rawUseCases == null) {
            throw new PromptOnException("prompt document requires keyed prompts; a discovery index is not configuration");
        }
        requiredMap(normalized, "deployments", "deployments");
        requiredMap(normalized, "prompt_versions", "prompt versions");
        requiredMap(normalized, "models", "models");

        Map<String, UseCase> useCases = new LinkedHashMap<>();
        rawUseCases.forEach((key, value) -> {
            if (value instanceof Map<?, ?> raw) {
                useCases.put(key, readUseCase(key, (Map<String, Object>) raw));
            } else {
                warnings.add("invalid_use_case: " + key);
            }
        });

        Map<String, Deployment> deployments = new LinkedHashMap<>();
        Map<String, Object> rawDeployments = Json.mapAt(normalized, "deployments");
        if (rawDeployments != null) {
            rawDeployments.forEach((key, value) -> {
                if (value instanceof Map<?, ?> raw) {
                    deployments.put(key, readDeployment(
                            key, (Map<String, Object>) raw));
                } else {
                    warnings.add("invalid_deployment: " + key);
                }
            });
        }

        Map<String, PromptVersion> promptVersions = new LinkedHashMap<>();
        Map<String, Object> rawVersions = Json.mapAt(normalized, "prompt_versions");
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
        Map<String, Object> rawModels = Json.mapAt(normalized, "models");
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
                Json.stringAt(normalized, "project"),
                Json.stringAt(normalized, "environment"),
                useCases,
                deployments,
                promptVersions,
                models,
                warnings);
    }

    private static boolean hasSingleResource(Map<String, Object> doc) {
        return doc.containsKey("prompt") || doc.containsKey("deployment")
                || doc.containsKey("prompt_version") || doc.containsKey("model");
    }

    private static Map<String, Object> normalizeSingleDocument(Map<String, Object> doc) {
        if (doc.containsKey("prompts")
                || doc.containsKey("deployments")
                || doc.containsKey("prompt_versions")
                || doc.containsKey("models")) {
            throw new PromptOnException("single prompt document must use singular resources");
        }

        Map<String, Object> prompt = requiredMap(doc, "prompt", "prompt");
        Map<String, Object> deployment = requiredMap(doc, "deployment", "deployment");
        Map<String, Object> version = requiredMap(doc, "prompt_version", "prompt version");
        Map<String, Object> model = requiredMap(doc, "model", "model");

        String key = requiredString(prompt, "key", "prompt key");
        String versionPrompt = requiredString(version, "prompt", "prompt version prompt");
        if (!key.equals(versionPrompt)) {
            throw new PromptOnException(
                    "prompt version prompt " + versionPrompt + " must match prompt key " + key);
        }
        int versionNumber = strictPositiveInt(version.get("number"), "prompt version number");
        int deploymentVersion = strictPositiveInt(
                deployment.get("prompt_version"), "deployment prompt_version");
        if (deploymentVersion != versionNumber) {
            throw new PromptOnException(
                    "deployment prompt_version must match prompt version number " + versionNumber);
        }

        String deploymentProvider = requiredString(deployment, "provider", "deployment provider");
        String deploymentModel = requiredString(deployment, "model", "deployment model");
        String modelProvider = requiredString(model, "provider", "model provider");
        String modelName = requiredString(model, "model", "model name");
        if (!deploymentProvider.equals(modelProvider) || !deploymentModel.equals(modelName)) {
            throw new PromptOnException("deployment provider/model must match model provider/model");
        }

        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("schema_version", doc.get("schema_version"));
        normalized.put("project", doc.get("project"));
        normalized.put("environment", doc.get("environment"));
        normalized.put("prompts", Map.of(key, prompt));
        normalized.put("deployments", Map.of(key, deployment));
        normalized.put("prompt_versions", Map.of(key + "@" + versionNumber, version));
        normalized.put("models", Map.of(readableModelKey(modelProvider, modelName), model));
        return normalized;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requiredMap(Map<String, Object> doc, String key, String label) {
        Object value = doc.get(key);
        if (!(value instanceof Map<?, ?> map)) {
            throw new PromptOnException("prompt document " + label + " is required");
        }
        return (Map<String, Object>) map;
    }

    private static int readSchemaVersion(Map<String, Object> doc) {
        Object raw = doc.get("schema_version");
        if (raw == null) {
            throw new PromptOnException("prompt document is missing schema_version");
        }
        if (!(raw instanceof Byte
                || raw instanceof Short
                || raw instanceof Integer
                || raw instanceof Long)) {
            throw new PromptOnException(
                    "prompt document schema_version must be the JSON integer "
                            + SCHEMA_VERSION);
        }
        long version = ((Number) raw).longValue();
        if (version != SCHEMA_VERSION) {
            throw new PromptOnException(
                "unsupported prompt document schema_version " + version
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
        List<Object> rawSchema = Json.listAt(raw, "variables");
        if (rawSchema != null) {
            for (Object entry : rawSchema) {
                if (entry instanceof Map<?, ?> map) {
                    schema.add(frozen((Map<String, Object>) map));
                }
            }
        }
        Map<String, Object> defaults = Json.mapAt(raw, "default_params");
        return new UseCase(
                null,
                key,
                UseCaseKind.from(Json.stringAt(raw, "kind")),
                List.copyOf(schema),
                frozen(defaults),
                PayloadPolicy.fromMap(Json.mapAt(raw, "payload_policy")));
    }

    private static Deployment readDeployment(String key, Map<String, Object> raw) {
        int version = strictPositiveInt(raw.get("prompt_version"), "deployment prompt_version");
        return new Deployment(
                null,
                key,
                readRevision(raw, "revision"),
                readableModelKey(
                        requiredString(raw, "provider", "deployment provider"),
                        requiredString(raw, "model", "deployment model")),
                Json.stringAt(raw, "api"),
                Json.stringAt(raw, "request_path"),
                frozen(Json.mapAt(raw, "params")),
                frozen(Json.mapAt(raw, "provider_options")),
                Map.of("default", key + "@" + version));
    }

    private static String readRevision(Map<String, Object> raw, String key) {
        Object value = raw == null ? null : raw.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        throw new PromptOnException("deployment revision must be a string");
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
        String promptId = requiredString(raw, "prompt", "prompt version prompt");
        int number = strictPositiveInt(raw.get("number"), "prompt version number");
        String expectedId = promptId + "@" + number;
        if (!expectedId.equals(id)) {
            throw new PromptOnException(
                    "prompt version key " + id + " must match embedded prompt and number " + expectedId);
        }
        return new PromptVersion(
                id,
                promptId,
                number,
                Json.stringAt(raw, "kind") != null
                        ? UseCaseKind.fromRequired(Json.stringAt(raw, "kind"))
                        : null,
                Template.Engine.from(Json.stringAt(raw, "engine")),
                messages,
                Decision.fromMap(Json.mapAt(raw, "decision")),
                frozen(Json.mapAt(raw, "tools")),
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
        String provider = requiredString(raw, "provider", "model provider");
        String providerModel = requiredString(raw, "model", "model name");
        String expectedId = provider + ":" + providerModel;
        if (!expectedId.equals(id)) {
            throw new PromptOnException(
                    "model key " + id + " must match embedded provider and model " + expectedId);
        }
        return new Model(
                id,
                provider,
                providerModel,
                Json.stringAt(raw, "display_name"),
                frozen(metadata),
                frozen(providerOptions),
                List.copyOf(capabilities),
                Json.stringAt(raw, "status"));
    }

    private static String requiredString(Map<String, Object> raw, String key, String label) {
        String value = Json.stringAt(raw, key);
        if (value == null) {
            throw new PromptOnException(label + " is required");
        }
        return value;
    }

    private static int strictPositiveInt(Object value, String label) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            long number = ((Number) value).longValue();
            if (number > 0 && number <= Integer.MAX_VALUE) {
                return (int) number;
            }
        } else if (value instanceof BigInteger number) {
            if (number.signum() > 0 && number.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) <= 0) {
                return number.intValue();
            }
        }
        throw new PromptOnException(label + " must be a positive JSON integer");
    }

    private static String readableModelKey(String provider, String model) {
        if (provider == null || model == null) {
            return null;
        }
        return provider + ":" + model;
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

    /** Every prompt, keyed by prompt key. */
    public Map<String, UseCase> useCases() {
        return useCases;
    }

    /** The live deployment per prompt key. A prompt with none is simply absent. */
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
