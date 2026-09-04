package dev.polimo.prompton.examples;

import dev.polimo.prompton.FlushResult;
import dev.polimo.prompton.TrackMeta;
import dev.polimo.prompton.Result;
import dev.polimo.prompton.Usage;
import dev.polimo.prompton.Message;
import dev.polimo.prompton.Mode;
import dev.polimo.prompton.PromptOn;
import dev.polimo.prompton.PromptOnConfig;
import dev.polimo.prompton.ProviderResult;
import dev.polimo.prompton.UseCase;
import java.util.List;
import java.util.Map;

/**
 * Load a use case, render its prompt, "call a provider", and log the call.
 *
 * <p>Run it against the local fixture server:
 *
 * <pre>
 * PTN_HOST=http://localhost:4000 PTN_API_KEY=ptn_sdkfixture_... ./gradlew example
 * </pre>
 *
 * <p>With no {@code PTN_API_KEY} it runs in test mode against a use-case document built into this file, so
 * it works with no server at all — which is also how you would test your own call sites.
 */
public final class QuickStart {

    private QuickStart() {}

    /**
     * Runs the example.
     *
     * @param args ignored
     * @throws Exception whatever the provider call throws
     */
    public static void main(String[] args) throws Exception {
        boolean live = System.getenv("PTN_API_KEY") != null;

        PromptOnConfig config = PromptOnConfig.builder()
                .mode(live ? Mode.LIVE : Mode.TEST)
                .diskCacheEnabled(live)
                .build();

        try (PromptOn prompton = PromptOn.create(config)) {
            if (!live) {
                System.out.println("No PTN_API_KEY: running in test mode on a built-in use-case document.\n");
                prompton.putUseCaseDocument(BUILT_IN_SNAPSHOT);
            }

            // 1. Resolve: which prompt version, which model, which params.
            UseCase useCase = prompton.useCase("greeting");
            System.out.println("use case   : " + useCase.key() + " ("
                    + useCase.kind().wireName() + ")");
            System.out.println("deployment : revision " + useCase.deploymentRevision()
                    + " from " + useCase.source().wireName());
            System.out.println("model      : " + useCase.model() + " via " + useCase.provider());
            System.out.println("params     : " + useCase.params());
            System.out.println("prompts    : " + useCase.promptNames());

            // 2. Render this call's variables into the pinned prompt.
            Map<String, Object> variables = Map.of("name", "Ada");
            List<Message> messages = useCase.messages(variables);
            System.out.println("\nrendered messages:");
            messages.forEach(message ->
                    System.out.println("  [" + message.role() + "] " + message.content()));

            // 3. Call the provider yourself, with your own key and HTTP client, inside the wrapper
            //    that times it and logs the result.
            String answer = useCase.track(TrackMeta.builder()
                            .inputMessages(messages)
                            .variables(variables)
                            .endUserRef("user-42")
                            .traceId("example:1")
                            .context(Map.of("language", "en"))
                            .build(),
                    () -> {
                        FakeProviderReply reply = callYourProvider(useCase.model(), messages);
                        return ProviderResult.ok(reply.text(), Result.builder()
                                .content(reply.text())
                                .finishReason(reply.finishReason())
                                .modelUsed(useCase.model())
                                .usage(new Usage(
                                        reply.inputTokens(), reply.outputTokens(),
                                        null, "unknown", null))
                                .build());
                    });

            System.out.println("\nprovider answered: " + answer);

            // 4. Send the queued monitoring logs. In a server you would let the buffer do this on
            //    its own and only flush on shutdown.
            FlushResult flushed = prompton.flush();
            System.out.println("flushed: " + flushed);
            if (!live) {
                System.out.println("captured log: " + prompton.lastCapturedLogAsJson());
            }
        }
    }

    /** Stands in for your provider SDK — PromptOn never makes this call for you. */
    private static FakeProviderReply callYourProvider(String model, List<Message> messages) {
        String name = messages.get(messages.size() - 1).content().replaceAll(".*hello to ", "")
                .replace(".", "");
        return new FakeProviderReply("Hello, " + name + "! (" + model + ")", "stop", 38, 9);
    }

    private record FakeProviderReply(
            String text, String finishReason, int inputTokens, int outputTokens) {}

    private static final String BUILT_IN_SNAPSHOT = """
        {
          "schema_version": 4,
          "project": "example",
          "environment": "production",
          "use_cases": {
            "greeting": {
              "id": "0198f2a1-0000-7000-8000-00000000c001",
              "kind": "chat",
              "input_schema": [{"name": "name", "type": "string", "required": true}],
              "default_params": {"max_tokens": 256},
              "payload_policy": {"mode": "full", "sample_rate": 1.0, "max_bytes": 262144}
            }
          },
          "deployments": {
            "greeting": {
              "id": "0198f2a1-0000-7000-8000-00000000d001",
              "revision": 1,
              "model_id": "0198f2a1-0000-7000-8000-00000000e001",
              "params": {"temperature": 0.2},
              "provider_options": {},
              "prompt_pins": {"default": "0198f2a1-0000-7000-8000-00000000a001"}
            }
          },
          "prompt_versions": {
            "0198f2a1-0000-7000-8000-00000000a001": {
              "id": "0198f2a1-0000-7000-8000-00000000a001",
              "number": 1,
              "engine": "liquid",
              "messages": [
                {"role": "system", "content": "You are a friendly greeter. Answer in one short line."},
                {"role": "user", "content": "Say hello to {{ name }}."}
              ],
              "text_template": null
            }
          },
          "models": {
            "0198f2a1-0000-7000-8000-00000000e001": {
              "id": "0198f2a1-0000-7000-8000-00000000e001",
              "provider": "openrouter",
              "model_id": "openai/gpt-4o-mini",
              "display_name": "GPT-4o mini",
              "metadata": {},
              "provider_options": {"only": ["OpenAI"]},
              "capabilities": ["tools"],
              "status": "active"
            }
          }
        }
        """;
}
