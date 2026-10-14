package dev.polimo.prompton;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The local resolution algorithm: prompt document + prompt key (+ prompt name) → {@link UseCase}.
 *
 * <p>A pure function, and the same two lookups the server runs for {@code POST /prompts/{key}/render}:
 *
 * <pre>
 * deployment       = document.deployments[use_case]                     # absent -&gt; unresolved
 * version          = document.prompt_versions[deployment.template_pins[prompt or "default"]]
 * kind             = version.kind || use_case.kind
 * model            = document.models[deployment.model_id]
 * params           = use_case.default_params  &lt;- deployment.params
 * provider_options = model.provider_options   &lt;- deployment.provider_options
 * </pre>
 *
 * <p>A prompt name the deployment does not pin is {@link UseCaseException.Reason#UNKNOWN_PROMPT}
 * — never a silent fall back to {@code default}, because shipping English to a request that asked
 * for {@code ko} is worse than an error.
 */
final class Resolver {

    /** The prompt name used when a call names none. */
    static final String DEFAULT_PROMPT = "default";

    private Resolver() {}

    /** Resolves {@code useCaseKey} with the default prompt name. */
    static UseCase resolve(UseCaseDocument document, String useCaseKey) {
        return resolve(document, useCaseKey, null, Source.MANUAL, null);
    }

    /** Resolves {@code useCaseKey} with an explicit prompt name ({@code null} means default). */
    static UseCase resolve(UseCaseDocument document, String useCaseKey, String promptName) {
        return resolve(document, useCaseKey, promptName, Source.MANUAL, null);
    }

    /**
     * Resolves, recording where the prompt document came from.
     *
     * @param document the document to resolve against
     * @param useCaseKey the prompt key
     * @param promptName the prompt name, or {@code null} for {@value #DEFAULT_PROMPT}
     * @param source where the prompt document came from
     * @param etag the prompt document's ETag, or {@code null}
     * @return the pin for this call
     */
    static UseCase resolve(
            UseCaseDocument document,
            String useCaseKey,
            String promptName,
            Source source,
            String etag) {
        UseCaseDocument.UseCase useCase = document.useCases().get(useCaseKey);
        if (useCase == null) {
            throw UseCaseException.of(UseCaseException.Reason.UNKNOWN_USE_CASE, useCaseKey);
        }
        UseCaseDocument.Deployment deployment = document.deployments().get(useCaseKey);
        if (deployment == null) {
            throw UseCaseException.of(UseCaseException.Reason.UNRESOLVED, useCaseKey);
        }

        List<String> promptNames = useCase.kind() == UseCaseKind.EMBEDDING
                ? List.of() : List.copyOf(deployment.promptPins().keySet());
        String selected = null;
        String versionId = null;
        if (useCase.kind() != UseCaseKind.EMBEDDING) {
            selected = promptName == null || promptName.isEmpty() ? DEFAULT_PROMPT : promptName;
            versionId = deployment.promptPins().get(selected);
            if (versionId == null) {
                throw UseCaseException.unknownPrompt(useCaseKey, selected, promptNames);
            }
        }

        List<String> warnings = new ArrayList<>();
        UseCaseDocument.PromptVersion version = null;
        if (versionId != null) {
            version = document.promptVersions().get(versionId);
            if (version == null) {
                warnings.add("missing_prompt_version: " + versionId);
            }
        }
        UseCaseDocument.Model model = null;
        if (deployment.modelId() != null) {
            model = document.models().get(deployment.modelId());
            if (model == null) {
                warnings.add("missing_model: " + deployment.modelId());
            }
        }

        Map<String, Object> params = Params.merge(useCase.defaultParams(), deployment.params());
        if (version != null) {
            params = Params.mergeTools(params, version.tools());
        }
        Map<String, Object> providerOptions = Params.merge(
                model == null ? null : model.providerOptions(), deployment.providerOptions());
        UseCaseKind runtimeKind = version == null || version.kind() == null
                ? useCase.kind()
                : version.kind();

        UseCase.Builder builder = new UseCase.Builder()
                .key(useCaseKey)
                .kind(runtimeKind)
                .deploymentId(deployment.id())
                .deploymentRevision(deployment.revision())
                .prompt(selected)
                .promptNames(promptNames)
                .api(deployment.api())
                .requestPath(deployment.requestPath())
                .pinnedKind(version == null ? null : version.kind())
                .params(params)
                .providerOptions(providerOptions)
                .variables(useCase.variables())
                .payloadPolicy(useCase.payloadPolicy())
                .source(source)
                .etag(etag)
                .warnings(warnings);

        if (model != null) {
            builder.model(model.modelId())
                    .modelId(null)
                    .provider(model.provider());
        }
        if (version != null) {
            builder.promptVersionId(null)
                    .promptVersionNumber(version.number())
                    .engine(version.engine());
            if (runtimeKind == UseCaseKind.CHAT) {
                builder.messages(version.messages());
            } else if (runtimeKind == UseCaseKind.DECISION) {
                builder.decision(version.decision());
            } else if (runtimeKind == UseCaseKind.TEXT) {
                builder.textTemplate(version.textTemplate());
            }
        }
        return builder.build();
    }
}
