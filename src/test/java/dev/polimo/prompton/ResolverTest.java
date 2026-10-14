package dev.polimo.prompton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.polimo.prompton.internal.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The prompt loading rules that decide what a call sends, and what they refuse to guess. */
class ResolverTest {

    private static final PromptDocument SNAPSHOT = PromptDocument.parse(Fixtures.production());

    @Test
    void paramsAndProviderOptionsAreLayeredShallowlyWithTheDeploymentOnTop() {
        Prompt pin = Resolver.resolve(SNAPSHOT, "greeting");
        assertEquals(Map.of("max_tokens", 512, "temperature", 0.2), pin.params());
        assertEquals(Map.of("only", List.of("OpenAI"), "allow_fallbacks", true),
                pin.providerOptions());
    }

    @Test
    void anUnpinnedTemplateNameIsAnErrorAndNeverFallsBackToDefault() {
        PromptException e = assertThrows(PromptException.class,
                () -> Resolver.resolve(SNAPSHOT, "greeting", "fr"));
        assertEquals(PromptException.Reason.UNKNOWN_TEMPLATE, e.reason());
        assertEquals("fr", e.template());
        assertEquals(List.of("default"), e.templateNames());
    }

    @Test
    void aPromptWithNoLiveDeploymentIsUnresolvedRatherThanUnknown() {
        assertEquals(PromptException.Reason.UNRESOLVED,
                assertThrows(PromptException.class,
                        () -> Resolver.resolve(SNAPSHOT, "draft")).reason());
        assertEquals(PromptException.Reason.UNKNOWN_PROMPT,
                assertThrows(PromptException.class,
                        () -> Resolver.resolve(SNAPSHOT, "nope")).reason());
    }

    @Test
    void anEmbeddingPromptIgnoresATemplateName() {
        Prompt pin = Resolver.resolve(SNAPSHOT, "embed", "ko");
        assertEquals(PromptKind.EMBEDDING, pin.kind());
        assertNull(pin.template());
        assertNull(pin.promptVersionId());
        assertNull(pin.messages());
        assertNull(pin.textTemplate());
        assertEquals("openai/text-embedding-3-small", pin.model());
    }

    @Test
    void anExplicitNullOverrideIsKeptRatherThanRemovingTheKey() {
        PromptDocument snapshot = PromptDocument.parse("""
            {
              "schema_version": 1,
              "project": "p",
              "environment": "production",
              "prompts": {
                "greeting": {
                  "kind": "chat",
                  "default_params": {
                    "temperature": 0.5,
                    "seed": 7
                  },
                  "key": "greeting"
                }
              },
              "deployments": {
                "greeting": {
                  "revision": "v2026.09.30-1",
                  "params": {
                    "seed": null
                  },
                  "provider_options": {
                    "only": null
                  },
                  "provider": "openrouter",
                  "model": "openai/gpt-4o-mini",
                  "prompt_version": 1
                }
              },
              "prompt_versions": {
                "greeting@1": {
                  "number": 1,
                  "engine": "liquid",
                  "messages": [
                    {
                      "role": "user",
                      "content": "hi"
                    }
                  ],
                  "prompt": "greeting",
                  "kind": "chat"
                }
              },
              "models": {
                "openrouter:openai/gpt-4o-mini": {
                  "provider": "openrouter",
                  "provider_options": {
                    "only": [
                      "OpenAI"
                    ],
                    "sort": "price"
                  },
                  "model": "openai/gpt-4o-mini"
                }
              }
            }
            """);
        Prompt pin = Resolver.resolve(snapshot, "greeting");

        assertTrue(pin.params().containsKey("seed"));
        assertNull(pin.params().get("seed"));
        assertEquals(0.5, pin.params().get("temperature"));
        assertTrue(pin.providerOptions().containsKey("only"));
        assertNull(pin.providerOptions().get("only"));
        assertEquals("price", pin.providerOptions().get("sort"));
    }

    @Test
    void deploymentRevisionMustBeAString() {
        PromptOnException e = assertThrows(PromptOnException.class, () -> PromptDocument.parse("""
            {
              "schema_version": 1,
              "project": "p",
              "environment": "production",
              "prompts": {
                "greeting": {
                  "kind": "chat",
                  "default_params": {},
                  "key": "greeting"
                }
              },
              "deployments": {
                "greeting": {
                  "revision": 1,
                  "params": {},
                  "provider_options": {},
                  "provider": "openrouter",
                  "model": "m1",
                  "prompt_version": 1
                }
              },
              "models": {},
              "prompt_versions": {}
            }
            """));
        assertTrue(e.getMessage().contains("deployment revision must be a string"));
    }

    @Test
    void nativeToolMessagesSurviveRenderingAsWholeProviderMaps() {
        PromptDocument snapshot = PromptDocument.parse("""
            {
              "schema_version": 1,
              "project": "p",
              "environment": "production",
              "prompts": {
                "tool_chat": {
                  "kind": "chat",
                  "default_params": {},
                  "key": "tool_chat"
                }
              },
              "deployments": {
                "tool_chat": {
                  "revision": "v2026.09.30-1",
                  "params": {},
                  "provider_options": {},
                  "provider": "openrouter",
                  "model": "openai/gpt-4o-mini",
                  "prompt_version": 1
                }
              },
              "prompt_versions": {
                "tool_chat@1": {
                  "number": 1,
                  "engine": "liquid",
                  "messages": [
                    {
                      "role": "system",
                      "content": "Continue with {{ input }}."
                    },
                    {
                      "role": "assistant",
                      "tool_calls": [
                        {
                          "id": "call_search",
                          "type": "function",
                          "function": {
                            "name": "search",
                            "arguments": "{\\"q\\":\\"diary\\"}"
                          }
                        }
                      ],
                      "content": null
                    },
                    {
                      "role": "tool",
                      "tool_call_id": "call_search",
                      "content": [
                        {
                          "type": "text",
                          "text": "found"
                        }
                      ]
                    },
                    {
                      "role": "user",
                      "content": "Next: {{ input }}"
                    }
                  ],
                  "prompt": "tool_chat",
                  "kind": "chat"
                }
              },
              "models": {
                "openrouter:openai/gpt-4o-mini": {
                  "provider": "openrouter",
                  "provider_options": {},
                  "capabilities": [
                    "tools"
                  ],
                  "status": "active",
                  "model": "openai/gpt-4o-mini"
                }
              }
            }
            """);
        Prompt pin = Resolver.resolve(snapshot, "tool_chat");
        List<?> actual = pin.messages(Map.of("input", "continue")).stream()
                .map(Message::toMap)
                .toList();
        List<Object> expected = Json.listAt(Json.parseObject("""
            {"messages":[
              {"role":"system","content":"Continue with continue."},
              {"role":"assistant","tool_calls":[{"id":"call_search","type":"function","function":{"name":"search","arguments":"{\\\"q\\\":\\\"diary\\\"}"}}],"content":null},
              {"role":"tool","tool_call_id":"call_search","content":[{"type":"text","text":"found"}]},
              {"role":"user","content":"Next: continue"}
            ]}
            """), "messages");
        assertEquals(Json.canonical(expected), Json.canonical(actual));
    }

    @Test
    void promptToolsBecomeProviderParamsAndStripAuthoringMetadata() {
        PromptDocument snapshot = PromptDocument.parse("""
            {
              "schema_version": 1,
              "project": "p",
              "environment": "production",
              "prompts": {
                "tool_chat": {
                  "kind": "chat",
                  "default_params": {},
                  "key": "tool_chat"
                }
              },
              "deployments": {
                "tool_chat": {
                  "revision": "v2026.09.30-1",
                  "params": {},
                  "provider_options": {},
                  "provider": "openrouter",
                  "model": "openai/gpt-4o-mini",
                  "prompt_version": 1
                }
              },
              "prompt_versions": {
                "tool_chat@1": {
                  "number": 1,
                  "engine": "liquid",
                  "messages": [
                    {
                      "role": "user",
                      "content": "hi"
                    }
                  ],
                  "tools": {
                    "definitions": [
                      {
                        "type": "function",
                        "function": {
                          "name": "search"
                        },
                        "output_schema": {
                          "type": "object"
                        },
                        "output_examples": [
                          {
                            "ok": true
                          }
                        ]
                      }
                    ],
                    "tool_choice": {
                      "type": "function",
                      "function": {
                        "name": "search"
                      }
                    },
                    "parallel_tool_calls": false
                  },
                  "prompt": "tool_chat",
                  "kind": "chat"
                }
              },
              "models": {
                "openrouter:openai/gpt-4o-mini": {
                  "provider": "openrouter",
                  "provider_options": {},
                  "capabilities": [
                    "tools"
                  ],
                  "status": "active",
                  "model": "openai/gpt-4o-mini"
                }
              }
            }
            """);
        Prompt pin = Resolver.resolve(snapshot, "tool_chat");
        Map<String, Object> params = pin.params();
        List<?> tools = (List<?>) params.get("tools");
        Map<String, Object> tool = Conformance.map(tools.get(0));
        assertFalse(tool.containsKey("output_schema"));
        assertFalse(tool.containsKey("output_examples"));
        assertEquals(Map.of("type", "function", "function", Map.of("name", "search")), tool);
        assertEquals(false, params.get("parallel_tool_calls"));
    }

    @Test
    void promptToolsConflictWithDifferentLegacyProviderParams() {
        PromptDocument snapshot = PromptDocument.parse("""
            {
              "schema_version": 1,
              "project": "p",
              "environment": "production",
              "prompts": {
                "tool_chat": {
                  "kind": "chat",
                  "default_params": {},
                  "key": "tool_chat"
                }
              },
              "deployments": {
                "tool_chat": {
                  "revision": "v2026.09.30-1",
                  "params": {
                    "parallel_tool_calls": true
                  },
                  "provider_options": {},
                  "provider": "openrouter",
                  "model": "openai/gpt-4o-mini",
                  "prompt_version": 1
                }
              },
              "prompt_versions": {
                "tool_chat@1": {
                  "number": 1,
                  "engine": "liquid",
                  "messages": [
                    {
                      "role": "user",
                      "content": "hi"
                    }
                  ],
                  "tools": {
                    "parallel_tool_calls": false
                  },
                  "prompt": "tool_chat",
                  "kind": "chat"
                }
              },
              "models": {
                "openrouter:openai/gpt-4o-mini": {
                  "provider": "openrouter",
                  "provider_options": {},
                  "capabilities": [
                    "tools"
                  ],
                  "status": "active",
                  "model": "openai/gpt-4o-mini"
                }
              }
            }
            """);
        assertThrows(PromptOnException.class, () -> Resolver.resolve(snapshot, "tool_chat"));
    }

    @Test
    void templateNamesListsWhatTheLiveDeploymentPins() {
        assertEquals(List.of("default"), SNAPSHOT.templateNames("greeting"));
        assertEquals(List.of(), SNAPSHOT.templateNames("draft"));
        assertThrows(PromptException.class, () -> SNAPSHOT.templateNames("nope"));
    }

    @Test
    void aSnapshotThatReferencesWhatItDoesNotContainStillResolvesWithWarnings() {
        PromptDocument degraded = PromptDocument.parse("""
            {
              "schema_version": 1,
              "project": "p",
              "environment": "production",
              "prompts": {
                "greeting": {
                  "kind": "chat",
                  "default_params": {},
                  "key": "greeting"
                }
              },
              "deployments": {
                "greeting": {
                  "revision": "v2026.09.30-1",
                  "params": {},
                  "provider_options": {},
                  "provider": "openrouter",
                  "model": "gone",
                  "prompt_version": 1
                }
              },
              "prompt_versions": {},
              "models": {}
            }
            """);
        Prompt pin = Resolver.resolve(degraded, "greeting");
        assertNull(pin.model());
        assertNull(pin.promptVersionId());
        assertEquals(List.of("missing_prompt_version: greeting@1", "missing_model: openrouter:gone"),
                pin.warnings());
    }

    @Test
    void offlineBundleUsesReadableResourceReferencesWithoutPublicIds() {
        PromptDocument snapshot = PromptDocument.parse("""
            {"schema_version": 1, "project": "p", "environment": "production",
             "prompts": {
               "greeting": {"kind": "chat", "default_params": {"temperature": 0.5}},
               "classify": {"kind": "decision", "default_params": {}}
             },
             "deployments": {
               "greeting": {"revision": "v2026.10.14-1", "provider": "openrouter",
                            "model": "shared/model", "prompt_version": 2,
                            "params": {"temperature": 0.2}, "provider_options": {"allow_fallbacks": false},
                            "api": "chat_completions", "request_path": "/api/v1/chat/completions"},
               "classify": {"revision": "v2026.10.14-2", "provider": "anthropic",
                            "model": "shared/model", "prompt_version": 2,
                            "params": {}, "provider_options": {},
                            "api": "decisions", "request_path": "/api/v1/decisions"}
             },
             "prompt_versions": {
               "greeting@2": {"prompt": "greeting", "number": 2, "kind": "chat", "engine": "liquid",
                              "messages": [{"role": "user", "content": "Hello {{ name }}"}]},
               "classify@2": {"prompt": "classify", "number": 2, "kind": "decision", "engine": "liquid",
                               "decision": {"state": {"text": "{{ text }}"},
                                            "questions": {"ok": {"type": "noul", "instructions": "Accept?"}}}}
             },
             "models": {
               "openrouter:shared/model": {"provider": "openrouter", "model": "shared/model",
                                           "display_name": "OpenRouter shared", "provider_options": {"only": ["OpenAI"]},
                                           "metadata": {}, "capabilities": ["tools"], "status": "active"},
               "anthropic:shared/model": {"provider": "anthropic", "model": "shared/model",
                                          "display_name": "Anthropic shared", "provider_options": {},
                                          "metadata": {}, "capabilities": [], "status": "active"}
             }}
            """);

        Prompt greeting = Resolver.resolve(snapshot, "greeting");
        assertEquals(1, snapshot.schemaVersion());
        assertNull(greeting.deploymentId());
        assertNull(greeting.modelId());
        assertNull(greeting.promptVersionId());
        assertEquals(2, greeting.promptVersionNumber());
        assertEquals("openrouter", greeting.provider());
        assertEquals("shared/model", greeting.model());
        assertEquals("Hello Ada", greeting.messages(Map.of("name", "Ada")).get(0).content());

        Prompt classify = Resolver.resolve(snapshot, "classify");
        assertNull(classify.modelId());
        assertNull(classify.promptVersionId());
        assertEquals(2, classify.promptVersionNumber());
        assertEquals("anthropic", classify.provider());
        assertEquals(PromptKind.DECISION, classify.kind());
        assertEquals(Map.of("text", "great"), classify.decision(Map.of("text", "great")).state());
    }

    @Test
    void offlineBundleWarningsUseReadableMissingReferences() {
        PromptDocument degraded = PromptDocument.parse("""
            {"schema_version": 1, "project": "p", "environment": "production",
             "prompts": {"greeting": {"kind": "chat", "default_params": {}}},
             "deployments": {"greeting": {"revision": "v2026.10.14-1", "provider": "openrouter",
                                          "model": "missing:model", "prompt_version": 3,
                                          "params": {}, "provider_options": {}}},
             "prompt_versions": {}, "models": {}}
            """);
        Prompt pin = Resolver.resolve(degraded, "greeting");
        assertEquals(List.of("missing_prompt_version: greeting@3", "missing_model: openrouter:missing:model"),
                pin.warnings());
        assertNull(pin.modelId());
        assertNull(pin.promptVersionId());
    }

    @Test
    void offlineBundleRejectsPromptVersionKeysThatDoNotMatchEmbeddedPromptAndNumber() {
        assertThrows(PromptOnException.class, () -> PromptDocument.parse("""
            {"schema_version": 1, "project": "p", "environment": "production",
             "prompts": {"greeting": {"kind": "chat", "default_params": {}}},
             "deployments": {"greeting": {"revision": "v2026.10.14-1", "provider": "openrouter",
                                          "model": "m", "prompt_version": 2,
                                          "params": {}, "provider_options": {}}},
             "prompt_versions": {"greeting@2": {"prompt": "other", "number": 2,
                                                   "kind": "chat", "engine": "liquid",
                                                   "messages": [{"role": "user", "content": "Hi"}]}},
             "models": {"openrouter:m": {"provider": "openrouter", "model": "m",
                                           "metadata": {}, "provider_options": {},
                                           "capabilities": [], "status": "active"}}}
            """));
    }

    @Test
    void offlineBundleRejectsModelKeysThatDoNotMatchEmbeddedProviderAndModel() {
        assertThrows(PromptOnException.class, () -> PromptDocument.parse("""
            {"schema_version": 1, "project": "p", "environment": "production",
             "prompts": {"greeting": {"kind": "chat", "default_params": {}}},
             "deployments": {"greeting": {"revision": "v2026.10.14-1", "provider": "openrouter",
                                          "model": "m", "prompt_version": 2,
                                          "params": {}, "provider_options": {}}},
             "prompt_versions": {"greeting@2": {"prompt": "greeting", "number": 2,
                                                   "kind": "chat", "engine": "liquid",
                                                   "messages": [{"role": "user", "content": "Hi"}]}},
             "models": {"openrouter:m": {"provider": "other", "model": "m",
                                           "metadata": {}, "provider_options": {},
                                           "capabilities": [], "status": "active"}}}
            """));
    }

    @Test
    void offlineBundlePreservesModelNamesContainingColons() {
        PromptDocument snapshot = PromptDocument.parse("""
            {"schema_version": 1, "project": "p", "environment": "production",
             "prompts": {"greeting": {"kind": "chat", "default_params": {}}},
             "deployments": {"greeting": {"revision": "v2026.10.14-1", "provider": "openrouter",
                                          "model": "vendor:model:with:colon", "prompt_version": 2,
                                          "params": {}, "provider_options": {}}},
             "prompt_versions": {"greeting@2": {"prompt": "greeting", "number": 2,
                                                   "kind": "chat", "engine": "liquid",
                                                   "messages": [{"role": "user", "content": "Hi"}]}},
             "models": {"openrouter:vendor:model:with:colon": {"provider": "openrouter",
                                                                  "model": "vendor:model:with:colon",
                                                                  "metadata": {}, "provider_options": {},
                                                                  "capabilities": [], "status": "active"}}}
            """);

        assertEquals("vendor:model:with:colon", Resolver.resolve(snapshot, "greeting").model());
    }

    @Test
    void offlineBundleRejectsInvalidDeploymentPromptVersionValues() {
        List<String> invalidFragments = List.of(
                "",
                ", \"prompt_version\": true",
                ", \"prompt_version\": 2.5",
                ", \"prompt_version\": \"2\"",
                ", \"prompt_version\": 0",
                ", \"prompt_version\": -1");
        for (String fragment : invalidFragments) {
            assertThrows(PromptOnException.class, () -> PromptDocument.parse("""
                {"schema_version": 1, "project": "p", "environment": "production",
                 "prompts": {"greeting": {"kind": "chat", "default_params": {}}},
                 "deployments": {"greeting": {"revision": "v2026.10.14-1", "provider": "openrouter",
                                              "model": "m"%s,
                                              "params": {}, "provider_options": {}}},
                 "prompt_versions": {"greeting@2": {"prompt": "greeting", "number": 2,
                                                       "kind": "chat", "engine": "liquid",
                                                       "messages": [{"role": "user", "content": "Hi"}]}},
                 "models": {"openrouter:m": {"provider": "openrouter", "model": "m",
                                               "metadata": {}, "provider_options": {},
                                               "capabilities": [], "status": "active"}}}
                """.formatted(fragment)));
        }
    }

    @Test
    void offlineBundleDoesNotAcceptLegacyDeploymentAliases() {
        assertThrows(PromptOnException.class, () -> PromptDocument.parse("""
            {"schema_version": 1, "project": "p", "environment": "production",
             "prompts": {"greeting": {"kind": "chat", "default_params": {}}},
             "deployments": {"greeting": {"revision": "v2026.10.14-1", "model_id": "openrouter:m",
                                          "template_pins": {"default": "greeting@2"},
                                          "params": {}, "provider_options": {}}},
             "prompt_versions": {"greeting@2": {"prompt": "greeting", "number": 2,
                                                   "kind": "chat", "engine": "liquid",
                                                   "messages": [{"role": "user", "content": "Hi"}]}},
             "models": {"openrouter:m": {"provider": "openrouter", "model": "m",
                                           "metadata": {}, "provider_options": {},
                                           "capabilities": [], "status": "active"}}}
            """));
    }

    @Test
    void singleDetailSinglePromptChatDocumentsResolveFromSingularResources() {
        PromptDocument document = PromptDocument.parse(singleDetailChat());

        Prompt prompt = Resolver.resolve(document, "support_reply");
        PreparedRequest request = prompt.request(Map.of("question", "pricing", "plan", "pro"));

        assertEquals(1, document.schemaVersion());
        assertEquals("support_reply", prompt.key());
        assertEquals("openrouter", prompt.provider());
        assertEquals("anthropic/claude-sonnet-4", prompt.model());
        assertEquals(1, prompt.promptVersionNumber());
        assertNull(prompt.deploymentId());
        assertNull(prompt.modelId());
        assertNull(prompt.promptVersionId());
        assertEquals("chat_completions", request.api());
        assertEquals("/api/v1/chat/completions", request.path());
        assertEquals("anthropic/claude-sonnet-4", request.body().get("model"));
        assertTrue(request.body().containsKey("usage"));
        assertEquals("Reply to pricing for pro.", prompt.messages(Map.of(
                "question", "pricing", "plan", "pro")).get(1).content());
    }

    @Test
    void singleDetailSinglePromptDecisionDocumentsPrepareDecisionRequests() {
        PromptDocument document = PromptDocument.parse(singleDetailDecision());

        Prompt prompt = Resolver.resolve(document, "decision_triage");
        PreparedRequest request = prompt.request(Map.of(
                        "ticket", "T-1", "plan", "team", "region", "eu"),
                RequestOptions.builder().sessionId("session-1").user("user-1").build());

        assertEquals(PromptKind.DECISION, prompt.kind());
        assertEquals("decisions", request.api());
        assertEquals("/api/v1/systemone", request.path());
        assertEquals("typesafe/jev-1.13", request.body().get("model"));
        Map<String, Object> expectedState = new LinkedHashMap<>();
        expectedState.put("ticket", "T-1");
        expectedState.put("plan", "team");
        expectedState.put("region", "eu");
        List<Object> facts = new ArrayList<>();
        facts.add(0);
        facts.add(false);
        facts.add(null);
        facts.add(Map.of("literal", "kept"));
        expectedState.put("facts", facts);
        assertEquals(expectedState, request.body().get("state"));
        assertEquals("session-1", request.body().get("session_id"));
        assertEquals("user-1", request.body().get("user"));
        assertFalse(request.body().containsKey("messages"));
        assertFalse(request.body().containsKey("usage"));
    }

    @Test
    void singleDetailRejectsIncompleteMixedOrMismatchedSinglePromptDocuments() {
        assertThrows(PromptOnException.class, () -> PromptDocument.parse("""
            {"schema_version": 1, "project": "p", "environment": "production", "prompts": {}}
            """));
        assertThrows(PromptOnException.class, () -> PromptDocument.parse("""
            {"schema_version": 1, "project": "p", "environment": "production",
             "prompt": {"key": "greeting", "kind": "chat"},
             "deployment": {"provider": "openrouter", "model": "m", "prompt_version": 2},
             "prompt_version": {"prompt": "other", "number": 2, "kind": "chat", "engine": "liquid",
                                "messages": [{"role": "user", "content": "Hi"}]},
             "model": {"provider": "openrouter", "model": "m"}}
            """));
        assertThrows(PromptOnException.class, () -> PromptDocument.parse("""
            {"schema_version": 1, "project": "p", "environment": "production",
             "prompt": {"key": "greeting", "kind": "chat"},
             "deployment": {"provider": "openrouter", "model": "m", "prompt_version": 3},
             "prompt_version": {"prompt": "greeting", "number": 2, "kind": "chat", "engine": "liquid",
                                "messages": [{"role": "user", "content": "Hi"}]},
             "model": {"provider": "openrouter", "model": "m"}}
            """));
        assertThrows(PromptOnException.class, () -> PromptDocument.parse("""
            {"schema_version": 1, "project": "p", "environment": "production",
             "prompt": {"key": "greeting", "kind": "chat"},
             "deployment": {"provider": "openrouter", "model": "m", "prompt_version": 2},
             "prompt_version": {"prompt": "greeting", "number": 2, "kind": "chat", "engine": "liquid",
                                "messages": [{"role": "user", "content": "Hi"}]},
             "model": {"provider": "other", "model": "m"}}
            """));
    }

    private static String singleDetailChat() {
        return """
            {"schema_version":1,"project":"heydiary-10242","environment":"production",
             "prompt":{"key":"support_reply","kind":"chat",
                       "variables":[{"name":"plan","required":false,"type":"string"},
                                       {"name":"question","required":false,"type":"string"}],
                       "default_params":{},"payload_policy":{"mode":"full","sample_rate":1.0,
                       "max_bytes":262144,"retention_days":30,"encrypt":true}},
             "deployment":{"revision":"v2026.10.14-1","provider":"openrouter",
                           "model":"anthropic/claude-sonnet-4","prompt_version":1,
                           "params":{},"provider_options":{"only":["Anthropic"]},
                           "api":"chat_completions","request_path":"/api/v1/chat/completions"},
             "prompt_version":{"prompt":"support_reply","number":1,"kind":"chat","engine":"liquid",
                               "messages":[{"role":"system","content":"Keep opaque 019916f4-12b0-7e6c-a1d3-8c9f2b4e6a57."},
                                           {"role":"user","content":"Reply to {{ question }} for {{ plan }}."}],
                               "decision":null,"text_template":null},
             "model":{"provider":"openrouter","model":"anthropic/claude-sonnet-4",
                      "display_name":"Claude Sonnet 4","metadata":{},
                      "provider_options":{},"capabilities":["tools","streaming"],"status":"active"}}
            """;
    }

    private static String singleDetailDecision() {
        return """
            {"schema_version":1,"project":"heydiary-10242","environment":"production",
             "prompt":{"key":"decision_triage","kind":"decision",
                       "variables":[{"name":"plan","required":false,"type":"string"},
                                       {"name":"region","required":false,"type":"string"},
                                       {"name":"ticket","required":false,"type":"string"}],
                       "default_params":{},"payload_policy":{"mode":"full","sample_rate":1.0,
                       "max_bytes":262144,"retention_days":30,"encrypt":true}},
             "deployment":{"revision":"v2026.10.14-1","provider":"openrouter",
                           "model":"typesafe/jev-1.13","prompt_version":1,
                           "params":{},"provider_options":{"only":["Anthropic"]},
                           "api":"decisions","request_path":"/api/v1/systemone"},
             "prompt_version":{"prompt":"decision_triage","number":1,"kind":"decision","engine":"liquid",
                               "messages":[],"text_template":null,
                               "decision":{"state":{"ticket":"{{ ticket }}","plan":"{{ plan }}",
                                                    "region":"{{ region }}",
                                                    "facts":[0,false,null,{"literal":"kept"}]},
                                           "questions":{"eligible":{"type":"noul",
                                             "instructions":"Is this eligible?",
                                             "criteria":{"true":"Eligible","false":"Not eligible"}}}}},
             "model":{"provider":"openrouter","model":"typesafe/jev-1.13",
                      "display_name":"Jev","metadata":{},"provider_options":{},
                      "capabilities":["decisions"],"status":"active"}}
            """;
    }
}
