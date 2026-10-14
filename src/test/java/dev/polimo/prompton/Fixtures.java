package dev.polimo.prompton;

/** Use-case documents the unit tests read against. */
final class Fixtures {

    private Fixtures() {}

    /** A production prompt document with a chat, a text and an embedding prompt. */
    static String useCaseDocument(String environment, String project, String greetingSystemPrompt) {
        return """
            {
              "schema_version": 4,
              "project": "__PROJECT__",
              "environment": "__ENVIRONMENT__",
              "prompts": {
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
                  "revision": "v2026.09.30-3",
                  "model_id": "0198f2a1-0000-7000-8000-00000000e001",
                  "params": {"temperature": 0.2},
                  "provider_options": {"allow_fallbacks": true},
                  "template_pins": {
                    "default": "0198f2a1-0000-7000-8000-00000000a001",
                    "ko": "0198f2a1-0000-7000-8000-00000000a002"
                  }
                },
                "summarize": {
                  "id": "0198f2a1-0000-7000-8000-00000000d002",
                  "revision": "v2026.09.30-1",
                  "model_id": "0198f2a1-0000-7000-8000-00000000e001",
                  "params": {},
                  "provider_options": {},
                  "template_pins": {"default": "0198f2a1-0000-7000-8000-00000000a003"}
                },
                "embed": {
                  "id": "0198f2a1-0000-7000-8000-00000000d003",
                  "revision": "v2026.09.30-2",
                  "model_id": "0198f2a1-0000-7000-8000-00000000e002",
                  "params": {},
                  "provider_options": {},
                  "template_pins": {}
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

    /** The production prompt document with the default greeting. */
    static String production() {
        return useCaseDocument("production", "sdkfixture", "You are a friendly greeter.");
    }

    /** The production prompt document with a different greeting, so a refresh is observable. */
    static String productionV2() {
        return useCaseDocument("production", "sdkfixture", "You are a very friendly greeter.");
    }

    /** The same document, but for staging. */
    static String staging() {
        return useCaseDocument("staging", "sdkfixture", "You are a greeter (staging build).");
    }

    /** A schema-v7 document with one native Decision prompt. */
    static String decisionDocument() {
        return """
            {
              "schema_version": 7,
              "project": "sdkfixture",
              "environment": "production",
              "prompts": {
                "sentiment": {
                  "id": "0198f2a1-0000-7000-8000-00000000c005",
                  "kind": "decision",
                  "input_schema": [{"name": "diary", "type": "string", "required": true}],
                  "default_params": {"session_id": "default-session"},
                  "payload_policy": {"mode": "full", "sample_rate": 1.0, "max_bytes": 262144}
                }
              },
              "deployments": {
                "sentiment": {
                  "id": "0198f2a1-0000-7000-8000-00000000d004",
                  "revision": "v2026.10.14-1",
                  "model_id": "0198f2a1-0000-7000-8000-00000000e003",
                  "api": "decisions",
                  "request_path": "/api/v1/systemone",
                  "params": {"user": "default-user"},
                  "provider_options": {"order": ["Fireworks"]},
                  "template_pins": {"default": "0198f2a1-0000-7000-8000-00000000a004"}
                }
              },
              "prompt_versions": {
                "0198f2a1-0000-7000-8000-00000000a004": {
                  "id": "0198f2a1-0000-7000-8000-00000000a004",
                  "prompt_id": "0198f2a1-0000-7000-8000-00000000b004",
                  "number": 1,
                  "kind": "decision",
                  "engine": "liquid",
                  "messages": [],
                  "text_template": null,
                  "decision": {
                    "state": {"diary": "{{ diary }}", "literal": "kept"},
                    "questions": {
                      "mood": {
                        "type": "choice",
                        "instructions": "Classify {{ diary }}.",
                        "criteria": {
                          "positive": "The diary feels upbeat.",
                          "negative": null
                        }
                      },
                      "urgent": {
                        "type": "noul",
                        "instructions": {"goal": "Flag {{ diary }}."},
                        "criteria": {"true": "urgent", "false": "not urgent"}
                      },
                      "intensity": {
                        "type": "score",
                        "instructions": ["Rate", "{{ diary }}"],
                        "criteria": ["calm", "strong"]
                      }
                    }
                  }
                }
              },
              "models": {
                "0198f2a1-0000-7000-8000-00000000e003": {
                  "id": "0198f2a1-0000-7000-8000-00000000e003",
                  "provider": "openrouter",
                  "model_id": "typesafe/jev-1.13",
                  "display_name": "Jev",
                  "metadata": {"output_modalities": ["decisions"]},
                  "provider_options": {"allow_fallbacks": false},
                  "capabilities": ["decisions"],
                  "status": "active"
                }
              }
            }
            """;
    }
}
