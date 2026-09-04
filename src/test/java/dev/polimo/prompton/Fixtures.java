package dev.polimo.prompton;

/** Snapshot documents the unit tests resolve against. */
final class Fixtures {

    private Fixtures() {}

    /** A production snapshot with a chat, a text and an embedding use case. */
    static String snapshot(String environment, String project, String greetingSystemPrompt) {
        return """
            {
              "schema_version": 3,
              "project": "__PROJECT__",
              "environment": "__ENVIRONMENT__",
              "use_cases": {
                "greeting": {
                  "id": "0198f2a1-0000-7000-8000-00000000c001",
                  "kind": "chat",
                  "input_schema": [{"name": "name", "type": "string", "required": true}],
                  "default_params": {"max_tokens": 512},
                  "payload_policy": {"mode": "full", "sample_rate": 1.0, "max_bytes": 262144,
                                     "retention_days": 30, "encrypt": true}
                },
                "summarize": {
                  "id": "0198f2a1-0000-7000-8000-00000000c002",
                  "kind": "text",
                  "input_schema": [{"name": "items", "type": "list", "required": true}],
                  "default_params": {},
                  "payload_policy": {"mode": "full", "sample_rate": 1.0, "max_bytes": 262144}
                },
                "embed": {
                  "id": "0198f2a1-0000-7000-8000-00000000c003",
                  "kind": "embedding",
                  "input_schema": [{"name": "text", "type": "string", "required": true}],
                  "default_params": {"dimensions": 256},
                  "payload_policy": {"mode": "full", "sample_rate": 1.0, "max_bytes": 262144}
                },
                "draft": {
                  "id": "0198f2a1-0000-7000-8000-00000000c004",
                  "kind": "chat",
                  "input_schema": [],
                  "default_params": {}
                }
              },
              "deployments": {
                "greeting": {
                  "id": "0198f2a1-0000-7000-8000-00000000d001",
                  "revision": 3,
                  "model_id": "0198f2a1-0000-7000-8000-00000000e001",
                  "params": {"temperature": 0.2},
                  "provider_options": {"allow_fallbacks": true},
                  "prompt_pins": {
                    "default": "0198f2a1-0000-7000-8000-00000000a001",
                    "ko": "0198f2a1-0000-7000-8000-00000000a002"
                  }
                },
                "summarize": {
                  "id": "0198f2a1-0000-7000-8000-00000000d002",
                  "revision": 1,
                  "model_id": "0198f2a1-0000-7000-8000-00000000e001",
                  "params": {},
                  "provider_options": {},
                  "prompt_pins": {"default": "0198f2a1-0000-7000-8000-00000000a003"}
                },
                "embed": {
                  "id": "0198f2a1-0000-7000-8000-00000000d003",
                  "revision": 2,
                  "model_id": "0198f2a1-0000-7000-8000-00000000e002",
                  "params": {},
                  "provider_options": {},
                  "prompt_pins": {}
                }
              },
              "prompt_versions": {
                "0198f2a1-0000-7000-8000-00000000a001": {
                  "id": "0198f2a1-0000-7000-8000-00000000a001",
                  "prompt_id": "0198f2a1-0000-7000-8000-00000000b001",
                  "number": 2,
                  "engine": "liquid",
                  "messages": [
                    {"role": "system", "content": "__GREETING__"},
                    {"role": "user", "content": "Say hello to {{ name }}."}
                  ],
                  "text_template": null
                },
                "0198f2a1-0000-7000-8000-00000000a002": {
                  "id": "0198f2a1-0000-7000-8000-00000000a002",
                  "prompt_id": "0198f2a1-0000-7000-8000-00000000b002",
                  "number": 1,
                  "engine": "liquid",
                  "messages": [
                    {"role": "system", "content": "Korean greeter."},
                    {"role": "user", "content": "{{ name }}, hello."}
                  ],
                  "text_template": null
                },
                "0198f2a1-0000-7000-8000-00000000a003": {
                  "id": "0198f2a1-0000-7000-8000-00000000a003",
                  "prompt_id": "0198f2a1-0000-7000-8000-00000000b003",
                  "number": 4,
                  "engine": "liquid",
                  "messages": [],
                  "text_template": "Summarize:\\n{% for item in items %}- {{ item }}\\n{% endfor %}"
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
                },
                "0198f2a1-0000-7000-8000-00000000e002": {
                  "id": "0198f2a1-0000-7000-8000-00000000e002",
                  "provider": "openrouter",
                  "model_id": "openai/text-embedding-3-small",
                  "display_name": "text-embedding-3-small",
                  "metadata": {},
                  "provider_options": {},
                  "capabilities": [],
                  "status": "active"
                }
              }
            }
            """
            .replace("__PROJECT__", project)
            .replace("__ENVIRONMENT__", environment)
            .replace("__GREETING__", greetingSystemPrompt);
    }

    /** The production snapshot with the default greeting. */
    static String production() {
        return snapshot("production", "sdkfixture", "You are a friendly greeter.");
    }

    /** The production snapshot with a different greeting, so a refresh is observable. */
    static String productionV2() {
        return snapshot("production", "sdkfixture", "You are a very friendly greeter.");
    }

    /** The same document, but for staging. */
    static String staging() {
        return snapshot("staging", "sdkfixture", "You are a greeter (staging build).");
    }
}
