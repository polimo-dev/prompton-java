package dev.polimo.prompton;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The local resolution algorithm: prompt document + prompt key (+ template name) → {@link Prompt}.
 *
 * <p>A pure function, and the same two lookups the server runs for {@code POST /prompts/{key}/render}:
 *
 * <pre>
 * deployment       = document.deployments[prompt_key]                     # absent -&gt; unresolved
 * version          = document.prompt_versions[key + "@" + deployment.prompt_version]
 * kind             = version.kind || prompt.kind
 * model            = document.models[deployment.provider + ":" + deployment.model]
 * params           = prompt.default_params  &lt;- deployment.params
 * provider_options = model.provider_options   &lt;- deployment.provider_options
 * </pre>
 *
 * <p>A template name the deployment does not pin is {@link PromptException.Reason#UNKNOWN_TEMPLATE}
 * — never a silent fall back to {@code default}, because shipping English to a request that asked
 * for {@code ko} is worse than an error.
 */
final class Resolver {

    /** The template name used when a call names none. */
    static final String DEFAULT_TEMPLATE = "default";

    private Resolver() {}

    /** Resolves {@code promptKey} with the default template name. */
    static Prompt resolve(PromptDocument document, String promptKey) {
        return resolve(document, promptKey, null, PromptSource.MANUAL, null);
    }

    /** Resolves {@code promptKey} with an explicit template name ({@code null} means default). */
    static Prompt resolve(PromptDocument document, String promptKey, String templateName) {
        return resolve(document, promptKey, templateName, PromptSource.MANUAL, null);
    }

    /**
     * Resolves, recording where the prompt document came from.
     *
     * @param document the document to resolve against
     * @param promptKey the prompt key
     * @param templateName the template name, or {@code null} for {@value #DEFAULT_TEMPLATE}
     * @param source where the prompt document came from
     * @param etag the prompt document's ETag, or {@code null}
     * @return the pin for this call
     */
    static Prompt resolve(
            PromptDocument document,
            String promptKey,
            String templateName,
            PromptSource source,
            String etag) {
        PromptDocument.Prompt prompt = document.prompts().get(promptKey);
        if (prompt == null) {
            throw PromptException.of(PromptException.Reason.UNKNOWN_PROMPT, promptKey);
        }
        PromptDocument.Deployment deployment = document.deployments().get(promptKey);
        if (deployment == null) {
            throw PromptException.of(PromptException.Reason.UNRESOLVED, promptKey);
        }

        List<String> templateNames = prompt.kind() == PromptKind.EMBEDDING
                ? List.of() : List.copyOf(deployment.templatePins().keySet());
        String selected = null;
        String versionId = null;
        if (prompt.kind() != PromptKind.EMBEDDING) {
            selected = templateName == null || templateName.isEmpty() ? DEFAULT_TEMPLATE : templateName;
            versionId = deployment.templatePins().get(selected);
            if (versionId == null) {
                throw PromptException.unknownTemplate(promptKey, selected, templateNames);
            }
        }

        List<String> warnings = new ArrayList<>();
        PromptDocument.PromptVersion version = null;
        if (versionId != null) {
            version = document.promptVersions().get(versionId);
            if (version == null) {
                warnings.add("missing_prompt_version: " + versionId);
            }
        }
        PromptDocument.Model model = null;
        if (deployment.modelId() != null) {
            model = document.models().get(deployment.modelId());
            if (model == null) {
                warnings.add("missing_model: " + deployment.modelId());
            }
        }

        Map<String, Object> params = Params.merge(prompt.defaultParams(), deployment.params());
        if (version != null) {
            params = Params.mergeTools(params, version.tools());
        }
        Map<String, Object> providerOptions = Params.merge(
                model == null ? null : model.providerOptions(), deployment.providerOptions());
        PromptKind runtimeKind = version == null || version.kind() == null
                ? prompt.kind()
                : version.kind();

        Prompt.Builder builder = new Prompt.Builder()
                .key(promptKey)
                .kind(runtimeKind)
                .deploymentId(deployment.id())
                .deploymentRevision(deployment.revision())
                .template(selected)
                .templateNames(templateNames)
                .api(deployment.api())
                .requestPath(deployment.requestPath())
                .pinnedKind(version == null ? null : version.kind())
                .params(params)
                .providerOptions(providerOptions)
                .variables(prompt.variables())
                .payloadPolicy(prompt.payloadPolicy())
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
            if (runtimeKind == PromptKind.CHAT) {
                builder.messages(version.messages());
            } else if (runtimeKind == PromptKind.DECISION) {
                builder.decision(version.decision());
            } else if (runtimeKind == PromptKind.TEXT) {
                builder.textTemplate(version.textTemplate());
            }
        }
        return builder.build();
    }
}
