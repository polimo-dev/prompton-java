package dev.polimo.prompton;

/** Prompt documents the unit tests read against. */
final class Fixtures {

    private Fixtures() {}

    /** A production prompt document with a chat, a text and an embedding prompt. */
    static String promptDocument(String environment, String project, String greetingSystemPrompt) {
        return """
            {
              "schema_version": 1,
              "project": "__PROJECT__",
              "environment": "__ENVIRONMENT__",
              "prompts": {
                "greeting": {
                  "kind": "chat",
                  "default_params": {
                    "max_tokens": 512
                  },
                  "payload_policy": {
                    "mode": "full",
                    "sample_rate": 1.0,
                    "max_bytes": 262144,
                    "retention_days": 30,
                    "encrypt": true
                  },
                  "variables": [
                    {
                      "name": "name",
                      "type": "string",
                      "required": true
                    }
                  ],
                  "key": "greeting"
                },
                "summarize": {
                  "kind": "text",
                  "default_params": {},
                  "payload_policy": {
                    "mode": "full",
                    "sample_rate": 1.0,
                    "max_bytes": 262144
                  },
                  "variables": [
                    {
                      "name": "items",
                      "type": "list",
                      "required": true
                    }
                  ],
                  "key": "summarize"
                },
                "embed": {
                  "kind": "embedding",
                  "default_params": {
                    "dimensions": 256
                  },
                  "payload_policy": {
                    "mode": "full",
                    "sample_rate": 1.0,
                    "max_bytes": 262144
                  },
                  "variables": [
                    {
                      "name": "text",
                      "type": "string",
                      "required": true
                    }
                  ],
                  "key": "embed"
                },
                "draft": {
                  "kind": "chat",
                  "default_params": {},
                  "variables": [],
                  "key": "draft"
                }
              },
              "deployments": {
                "greeting": {
                  "revision": "v2026.09.30-3",
                  "params": {
                    "temperature": 0.2
                  },
                  "provider_options": {
                    "allow_fallbacks": true
                  },
                  "provider": "openrouter",
                  "model": "openai/gpt-4o-mini",
                  "prompt_version": 2
                },
                "summarize": {
                  "revision": "v2026.09.30-1",
                  "params": {},
                  "provider_options": {},
                  "provider": "openrouter",
                  "model": "openai/gpt-4o-mini",
                  "prompt_version": 4
                },
                "embed": {
                  "revision": "v2026.09.30-2",
                  "params": {},
                  "provider_options": {},
                  "provider": "openrouter",
                  "model": "openai/text-embedding-3-small",
                  "prompt_version": 1
                }
              },
              "prompt_versions": {
                "greeting@2": {
                  "number": 2,
                  "engine": "liquid",
                  "messages": [
                    {
                      "role": "system",
                      "content": "__GREETING__"
                    },
                    {
                      "role": "user",
                      "content": "Say hello to {{ name }}."
                    }
                  ],
                  "text_template": null,
                  "prompt": "greeting",
                  "kind": "chat"
                },
                "summarize@4": {
                  "number": 4,
                  "engine": "liquid",
                  "messages": [],
                  "text_template": "Summarize:\\n{% for item in items %}- {{ item }}\\n{% endfor %}",
                  "prompt": "summarize",
                  "kind": "text"
                }
              },
              "models": {
                "openrouter:openai/gpt-4o-mini": {
                  "provider": "openrouter",
                  "display_name": "GPT-4o mini",
                  "metadata": {},
                  "provider_options": {
                    "only": [
                      "OpenAI"
                    ]
                  },
                  "capabilities": [
                    "tools"
                  ],
                  "status": "active",
                  "model": "openai/gpt-4o-mini"
                },
                "openrouter:openai/text-embedding-3-small": {
                  "provider": "openrouter",
                  "display_name": "text-embedding-3-small",
                  "metadata": {},
                  "provider_options": {},
                  "capabilities": [],
                  "status": "active",
                  "model": "openai/text-embedding-3-small"
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
        return promptDocument("production", "sdkfixture", "You are a friendly greeter.");
    }

    /** The production prompt document with a different greeting, so a refresh is observable. */
    static String productionV2() {
        return promptDocument("production", "sdkfixture", "You are a very friendly greeter.");
    }

    /** The same document, but for staging. */
    static String staging() {
        return promptDocument("staging", "sdkfixture", "You are a greeter (staging build).");
    }

    /** A schema-v7 document with one native Decision prompt. */
    static String decisionDocument() {
        return """
            {
              "schema_version": 1,
              "project": "sdkfixture",
              "environment": "production",
              "prompts": {
                "sentiment": {
                  "kind": "decision",
                  "default_params": {
                    "session_id": "default-session"
                  },
                  "payload_policy": {
                    "mode": "full",
                    "sample_rate": 1.0,
                    "max_bytes": 262144
                  },
                  "variables": [
                    {
                      "name": "diary",
                      "type": "string",
                      "required": true
                    }
                  ],
                  "key": "sentiment"
                }
              },
              "deployments": {
                "sentiment": {
                  "revision": "v2026.10.14-1",
                  "api": "decisions",
                  "request_path": "/api/v1/systemone",
                  "params": {
                    "user": "default-user"
                  },
                  "provider_options": {
                    "order": [
                      "Fireworks"
                    ]
                  },
                  "provider": "openrouter",
                  "model": "typesafe/jev-1.13",
                  "prompt_version": 1
                }
              },
              "prompt_versions": {
                "sentiment@1": {
                  "number": 1,
                  "kind": "decision",
                  "engine": "liquid",
                  "messages": [],
                  "text_template": null,
                  "decision": {
                    "state": {
                      "diary": "{{ diary }}",
                      "literal": "kept"
                    },
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
                        "instructions": {
                          "goal": "Flag {{ diary }}."
                        },
                        "criteria": {
                          "true": "urgent",
                          "false": "not urgent"
                        }
                      },
                      "intensity": {
                        "type": "score",
                        "instructions": [
                          "Rate",
                          "{{ diary }}"
                        ],
                        "criteria": [
                          "calm",
                          "strong"
                        ]
                      }
                    }
                  },
                  "prompt": "sentiment"
                }
              },
              "models": {
                "openrouter:typesafe/jev-1.13": {
                  "provider": "openrouter",
                  "display_name": "Jev",
                  "metadata": {
                    "output_modalities": [
                      "decisions"
                    ]
                  },
                  "provider_options": {
                    "allow_fallbacks": false
                  },
                  "capabilities": [
                    "decisions"
                  ],
                  "status": "active",
                  "model": "typesafe/jev-1.13"
                }
              }
            }
            """;
    }
}
