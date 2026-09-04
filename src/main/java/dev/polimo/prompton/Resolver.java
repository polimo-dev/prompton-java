package dev.polimo.prompton;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The local resolution algorithm: snapshot + use case key (+ prompt name) → {@link Resolution}.
 *
 * <p>A pure function, and the same two lookups the server runs for {@code POST /resolve}:
 *
 * <pre>
 * deployment       = snapshot.deployments[use_case]                     # absent -&gt; unresolved
 * version          = snapshot.prompt_versions[deployment.prompt_pins[prompt or "default"]]
 * model            = snapshot.models[deployment.model_id]
 * params           = use_case.default_params  &lt;- deployment.params
 * provider_options = model.provider_options   &lt;- deployment.provider_options
 * </pre>
 *
 * <p>A prompt name the deployment does not pin is {@link ResolutionException.Reason#UNKNOWN_PROMPT}
 * — never a silent fall back to {@code default}, because shipping English to a request that asked
 * for {@code ko} is worse than an error.
 */
public final class Resolver {

    /** The prompt name used when a call names none. */
    public static final String DEFAULT_PROMPT = "default";

    private Resolver() {}

    /** Resolves {@code useCaseKey} with the default prompt name. */
    public static Resolution resolve(Snapshot snapshot, String useCaseKey) {
        return resolve(snapshot, useCaseKey, null, ResolutionSource.MANUAL, null);
    }

    /** Resolves {@code useCaseKey} with an explicit prompt name ({@code null} means default). */
    public static Resolution resolve(Snapshot snapshot, String useCaseKey, String promptName) {
        return resolve(snapshot, useCaseKey, promptName, ResolutionSource.MANUAL, null);
    }

    /**
     * Resolves, recording where the snapshot came from.
     *
     * @param snapshot the document to resolve against
     * @param useCaseKey the use case key
     * @param promptName the prompt name, or {@code null} for {@value #DEFAULT_PROMPT}
     * @param source where the snapshot came from
     * @param etag the snapshot's ETag, or {@code null}
     * @return the pin for this call
     */
    public static Resolution resolve(
            Snapshot snapshot,
            String useCaseKey,
            String promptName,
            ResolutionSource source,
            String etag) {
        Snapshot.UseCase useCase = snapshot.useCases().get(useCaseKey);
        if (useCase == null) {
            throw ResolutionException.of(ResolutionException.Reason.UNKNOWN_USE_CASE, useCaseKey);
        }
        Snapshot.Deployment deployment = snapshot.deployments().get(useCaseKey);
        if (deployment == null) {
            throw ResolutionException.of(ResolutionException.Reason.UNRESOLVED, useCaseKey);
        }

        List<String> availablePrompts = List.copyOf(deployment.promptPins().keySet());
        String selected = null;
        String versionId = null;
        if (useCase.kind() != UseCaseKind.EMBEDDING) {
            selected = promptName == null || promptName.isEmpty() ? DEFAULT_PROMPT : promptName;
            versionId = deployment.promptPins().get(selected);
            if (versionId == null) {
                throw ResolutionException.unknownPrompt(useCaseKey, selected, availablePrompts);
            }
        }

        List<String> warnings = new ArrayList<>();
        Snapshot.PromptVersion version = null;
        if (versionId != null) {
            version = snapshot.promptVersions().get(versionId);
            if (version == null) {
                warnings.add("missing_prompt_version: " + versionId);
            }
        }
        Snapshot.Model model = null;
        if (deployment.modelId() != null) {
            model = snapshot.models().get(deployment.modelId());
            if (model == null) {
                warnings.add("missing_model: " + deployment.modelId());
            }
        }

        Map<String, Object> params = Params.merge(useCase.defaultParams(), deployment.params());
        Map<String, Object> providerOptions = Params.merge(
                model == null ? null : model.providerOptions(), deployment.providerOptions());

        Resolution.Builder builder = new Resolution.Builder()
                .useCase(useCaseKey)
                .kind(useCase.kind())
                .deploymentId(deployment.id())
                .deploymentRevision(deployment.revision())
                .prompt(selected)
                .availablePrompts(availablePrompts)
                .effectiveParams(params)
                .effectiveProviderOptions(providerOptions)
                .inputSchema(useCase.inputSchema())
                .payloadPolicy(useCase.payloadPolicy())
                .source(source)
                .etag(etag)
                .warnings(warnings);

        if (model != null) {
            builder.model(model.modelId()).modelId(model.id()).provider(model.provider());
        }
        if (version != null) {
            builder.promptVersionId(version.id())
                    .promptVersionNumber(version.number())
                    .engine(version.engine());
            if (useCase.kind() == UseCaseKind.CHAT) {
                builder.messages(version.messages());
            } else if (useCase.kind() == UseCaseKind.TEXT) {
                builder.textTemplate(version.textTemplate());
            }
        }
        return builder.build();
    }
}
