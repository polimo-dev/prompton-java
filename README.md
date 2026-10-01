# prompton-sdk for Java

The official [PromptOn](https://app.prompton.ai) SDK for Java 17+.

PromptOn is a control plane for the prompts and models your app uses. Every place your code calls an
LLM becomes a **prompt**, and for each prompt and environment PromptOn holds one configuration: a
prompt version, one model, and its parameters. Your app fetches that configuration and then calls the
provider itself, with your own provider key and your own HTTP client — PromptOn is **config-fetch,
not a proxy**, so it is never in the request path and never sees your key. After each call your app
sends back a **monitoring log**, and those logs are how you see cost, latency, error rate and stop
reasons per prompt.

This SDK does four things and nothing else:

```
useCase("support_reply")       -> UseCase: model, params, provider options, prompt template
useCase.messages(variables)    -> the messages PromptOn manages
useCase.track(meta, call)      -> times your provider call and queues a monitoring log
flush()                        -> sends what is queued
```

It has no runtime dependency beyond Jackson, needs no database, Redis or any other shared store, and
keeps working when PromptOn does not.

## Install

Not published to Maven Central yet. Until it is, build it and install it into your local repository:

```sh
git clone https://github.com/polimo-dev/prompton-java.git
cd prompton-java
./gradlew publishToMavenLocal
```

Then depend on it, with `mavenLocal()` in your repositories:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("dev.polimo:prompton-sdk:0.5.0")
}
```

```xml
<dependency>
  <groupId>dev.polimo</groupId>
  <artifactId>prompton-sdk</artifactId>
  <version>0.5.0</version>
</dependency>
```

Once it is on Maven Central the same coordinates will resolve without the local install. Check the
registry rather than trusting this line: a `dev.polimo:prompton-sdk` published by anyone else is not
this SDK.

## Quick start

```java
try (PromptOn prompton = PromptOn.create()) {                      // reads PTN_HOST and PTN_API_KEY
    UseCase useCase = prompton.useCase("support_reply");        // from memory: no HTTP call
    Map<String, Object> variables = Map.of("question", question);
    List<Message> managedMessages = useCase.messages(variables);
    List<Message> finalMessages = new ArrayList<>(managedMessages);
    finalMessages.addAll(conversationHistory);
    finalMessages.add(Message.of("user", question));

    String answer = useCase.track(TrackMeta.builder()
                    .inputMessages(finalMessages).variables(variables).build(),
            () -> {                                                // your provider, your key
                MyReply reply = myProvider.chat(useCase.model(), finalMessages, useCase.params());
                return ProviderResult.ok(reply.text(), Result.builder()
                        .content(reply.text()).finishReason(reply.finishReason()).build());
            });
}
```

Build one `PromptOn` per process — it owns the prompt config cache and a log-sender thread — share it across
threads, and `close()` it on shutdown so the last logs are flushed. `examples/` has a runnable
version that works with or without a server:

```sh
./gradlew example
PTN_HOST=http://localhost:4000 PTN_API_KEY=ptn_yourproject_... ./gradlew example
```

## Configuration

Precedence is **explicit option > environment variable > default**.

| Option | Environment variable | Default | What it does |
|---|---|---|---|
| `apiKey` | `PTN_API_KEY` | none | The runtime key, `ptn_<project>_…`. Without it the SDK makes no remote call at all: it loads from disk or the bundle, and monitoring logs are counted in `logStats().droppedFailed()` and dropped rather than stored |
| `host` | `PTN_HOST` | `https://app.prompton.ai` | The SDK appends `/api/v1` |
| `baseUrl` | — | `host + "/api/v1"` | Set it when your API base is not under the host root |
| `environment` | `PTN_ENVIRONMENT` | `production` | Sent as `?environment=`, and the guard that stops a staging process booting on a production document |
| `project` | `PTN_PROJECT` | read out of the API key | Names the disk cache, and guards against another project's document |
| `cacheTtl` | — | 10 s | TTL/backoff base for `/prompts/{key}/render` cached server-render answers; runtime config fetch always uses the SDK fixed 10 s freshness and attempt gate |
| `requestTimeout` | — | 5 s | Read timeout for PromptOn's own calls |
| `connectTimeout` | — | 5 s | Connect timeout of the default HTTP client |
| `initialFetchTimeout` | — | 1 s | Deprecated compatibility option; config fetches use a one-second total budget |
| `maxBackoff` | — | 5 min | Ceiling of every exponential backoff |
| `diskCachePath` | — | OS cache dir, `prompton/prompts-<project>-<environment>.json` | Where the prompt document is mirrored |
| `diskCacheEnabled` | — | `true` | `false` keeps the SDK entirely in memory |
| `bundlePath` | — | none | A prompt document shipped inside your application, used when memory and disk are empty |
| `mode` | — | `LIVE` | `TEST` captures logs and makes no HTTP call; `OFFLINE` loads from disk or bundle only and, like a missing key, counts and drops monitoring logs |
| `hashEndUser` | — | `false` | Send `sha256(end_user_ref)` instead of the raw reference |
| `redact` | — | none | `UnaryOperator<Map<String, Object>>` applied to every record last |
| `logFlushSize` | — | 100 | Flush once this many records are queued |
| `logFlushBytes` | — | 1 MB | Flush once this many bytes are queued |
| `logFlushInterval` | — | 2 s | Flush at least this often |
| `logMaxBuffer` | — | 10 000 | Queue cap; over it the oldest are dropped and counted |
| `logMaxAttempts` | — | 8 | How many times one batch is retried before it is dropped and counted |
| `shutdownFlushTimeout` | — | 5 s | How long `close()` spends draining |
| `pollingEnabled` | — | `false` | Deprecated compatibility option; config is always fetched on demand per prompt key |
| `httpClient` | — | `JdkHttpClient` | Route PromptOn's own calls through your own HTTP stack |
| `payloadDefaults` | — | full, no sampling, 256 KiB | The payload policy used when the prompt document declares none |

```java
PromptOn prompton = PromptOn.create(PromptOnConfig.builder()
        .apiKey(System.getenv("PTN_API_KEY"))
        .environment("staging")
        .bundlePath(Path.of("/app/resources/prompts.staging.json"))
        .redact(record -> { record.remove("end_user_ref"); return record; })
        .build());
```

## Resolving and rendering

`useCase` reads the key's cached configuration. Inside the 10-second TTL it is a memory lookup. When
the key is stale or missing, the SDK makes one `GET /prompts/{key}?environment=...` attempt for that
key and waits up to one second; if that fails or times out, it returns the last valid value, even if
expired. With no cached value at all, it raises a normal `UseCaseException`.

```java
UseCase useCase = prompton.useCase("support_reply");      // the "default" prompt
UseCase korean = prompton.useCase("support_reply", "ko"); // a named prompt
List<String> names = prompton.promptNames("support_reply");  // ["default", "ko"]
```

A `UseCase` carries everything one call needs: `key()`, `kind()`, `deploymentId()`,
`deploymentRevision()`, `prompt()`, `promptNames()`, `model()` (the provider string to send),
`modelId()` (the catalog UUID), `provider()`, `params()`, `providerOptions()`,
`promptVersionId()`, `promptVersionNumber()`, and the raw template as `messages()` (chat) or
`textTemplate()` (text). An embedding prompt has neither.

Rendering is a separate step, because a prompt is reusable across calls and the variables are
not:

```java
List<Message> messages = useCase.messages(Map.of("question", "why is the sky blue?"));
String prompt = summarizeUseCase.text(Map.of("items", List.of("alpha", "beta")));
```

For chat calls, PromptOn returns only the messages managed in the editor, usually the system and
developer instructions. Conversation history and the current user message belong to your app, so
compose the final provider request explicitly:

```java
Map<String, Object> variables = Map.of("locale", "ko-KR");
List<Message> managedMessages = useCase.messages(variables);
List<Message> finalMessages = new ArrayList<>(managedMessages);
finalMessages.addAll(loadConversationHistory(conversationId));
finalMessages.add(Message.of("user", userText));

MyReply reply = myProvider.chat(useCase.model(), finalMessages, useCase.params());
useCase.track(TrackMeta.builder()
        .variables(variables)
        .inputMessages(finalMessages)
        .build(), () -> ProviderResult.ok(reply.text(), Result.ofContent(reply.text())));
```

Prompts are Liquid, restricted to the subset PromptOn allows: `{{ var }}`, `for` (with `else`,
`break`, `continue` and `forloop.*`), `if`/`elsif`/`else`, `unless`, `assign`, and the filters
`size`, `join` and `default`. Anything else is a parse error. A variable is missing when its key is
absent — a key present with a `null` value renders as the empty string and `default` replaces it —
and a missing variable throws `TemplateException` naming it rather than silently rendering a hole.
`Template.lint` and `Template.variables` are public if you want to check a template yourself.

There is also the server-side path, for a smoke test or a cold, low-traffic call site:

```java
UseCase useCase = prompton.useCaseRemote("support_reply", null);        // cached for cacheTtl
UseCase rendered = prompton.useCaseRemote("support_reply", null, vars); // server renders
```

Never call `useCaseRemote` once per request in a hot loop; that is what the prompt document cache is for. It
follows the same rules as the prompt document store when PromptOn pushes back: after a `429` the endpoint is
left alone until `Retry-After` has elapsed, a 5xx or an unreachable server backs off the same way,
and while the pause is in force the cached answer is served instead of a request.

## Monitoring logs

Three entry points, and the buffer behind them.

```java
useCase.track(meta, call);   // times your call and builds the record
prompton.log(record);                       // queue a record you built yourself
prompton.flush();                           // send what is queued, and wait
```

`track` returns whatever your call returned, unchanged. An exception thrown inside it is
logged as `status: "error"` with `error.kind: "app"` and then rethrown unchanged: the wrapper never
swallows a failure or changes control flow. Tell success from failure with `ProviderResult`:

```java
ProviderResult.ok(value, result);             // status ok
ProviderResult.error(value, error);           // status error
ProviderResult.error(value, error, result);   // status error, keeping the usage and the output
```

The third form is what you want when the provider answered but the answer failed to parse: the
tokens were still spent, and the text is the evidence.

`log` is for the cases the wrapper does not fit — a streaming response you finish accounting for
later, or a record your own framework assembles:

```java
prompton.log(LogRecord.builder()
        .useCase(useCase)                         // fills the deployment, prompt and model fields
        .status(LogRecord.Status.OK)
        .startedAt(startedAt)
        .latencyMs(latency)
        .output(Map.of("content", answer))
        .usage(Usage.ofTokens(inputTokens, outputTokens))
        .build());
```

### What a record carries

| Field | Notes |
|---|---|
| `id` | UUIDv7, the idempotency key. The SDK issues one; a resend is counted as a duplicate, never stored twice |
| `prompt_key`, `model`, `status`, `started_at` | Required. `status` is `ok` or `error`; `started_at` must be within 5 minutes ahead and 7 days behind |
| `kind` | `chat`, `text` or `embedding` |
| `deployment_id`, `deployment_revision`, `template`, `prompt_version_id`, `model_id` | The prompt evidence, filled from the `UseCase` |
| `source` | `remote`, `disk`, `bundle` or `manual` — which tier answered |
| `provider`, `model_used`, `upstream_provider` | Who actually served the call |
| `params` | What was sent. The server blanks it over 4 KB rather than rejecting the record |
| `input` | `{variables, messages}` or `{text}` |
| `output` | `{content, tool_calls}` |
| `finish_reason`, `stop_kind` | `stop_kind` is `stop`, `length`, `tool_call`, `content_filter` or `other`, derived from the finish reason. Only `length` counts as truncated |
| `error` | `{kind, status, message}` on a failure. `kind` is one of `http_4xx`, `http_5xx`, `rate_limited`, `timeout`, `transport`, `parse`, `app` |
| `usage` | `{input_tokens, output_tokens, cost_usd, cost_source, raw}` |
| `latency_ms`, `trace_id`, `sequence`, `end_user_ref` | How to find this call again |
| `context`, `metadata` | Free-form. Keep `context` under 2 KB and `metadata` under 4 KB, or the record is rejected |
| `sdk` | `{"name": "prompton-java", "version": "0.5.0"}` |

Do not log secrets: no provider keys, no `PTN_API_KEY`, no user PII beyond `end_user_ref`.

### What the buffer does

Records are queued and sent in batches on a size, byte or time trigger — never one HTTP call per
log, and never blocking your provider call. A batch carries at most 200 records and under
5 MB, and one batch covers one environment because `?environment=` applies to the whole request.
Before a record is queued the SDK applies the prompt's payload policy from the prompt document: sampling
(errors and length truncations are always kept), truncation to the caps the server re-checks,
`hash`/`none` modes, then `hashEndUser`, then your `redact` hook last.

Records for different environments are queued separately and each batch drains one environment, so
logging into production and staging from one process is two requests per flush, not two per record.

`logStats()` reports what is queued and what has been dropped, so you can alarm on it. Mind the two
counters that sound alike: `FlushResult.records()` is what one flush put on the wire, while
`LogStats.sent()` is the running total PromptOn actually *accepted* — they differ by duplicates and
rejections.

## Resilience

This is the part that matters when PromptOn has a bad day.

**Three tiers, consulted in this order.** Memory always; then the disk cache, which is on by default
and written atomically (temporary file, then rename) with a sidecar holding the ETag and
`Last-Modified`; then an optional prompt document bundled into your application. Whichever answered is
reported as `source`.

**A fixed ten-second per-key config cache.** Within the TTL every `useCase("key")` call is served from memory with
no HTTP call. Once it has passed, the next caller for that key attempts
`GET /prompts/{key}?environment=...` with that key's `If-None-Match`. A `304` marks the cached value
fresh. Other keys are independent and are never serialized behind a slow key.

**One-second budget and stale fallback.** Config fetch has a one-second total deadline, including
response body reading, and the SDK does not retry it. If a request fails, times out, returns an
invalid document, or returns a document for the wrong project/environment/key, the last valid value
keeps serving even when expired. The failed attempt still starts the 10-second per-key attempt gate.
If there is no cached value, the call fails explicitly.

**Same-key single-flight.** Concurrent callers for the same prompt key share the same in-flight fetch
and original deadline. The SDK may still perform separate LLM/provider calls afterwards; only the
PromptOn config lookup is shared.

**Never the wrong document.** A prompt document whose `environment` or `project` does not match this
process is ignored, wherever it came from — so a staging build cannot boot on a production bundle.
A schema version older than 4 is refused. A corrupt
or half-written file is ignored rather than fatal, which is what makes it safe for several processes
on one host to share the disk cache.

**No external services, ever.** Memory, one local file, and the bundled file are the only tiers.
Instances never coordinate; each keeps its own per-key copy.

### Building the bundle

Fetch once and write the file, in CI, on every build, and commit both files it writes — one per
environment:

```java
try (PromptOn prompton = PromptOn.create(PromptOnConfig.builder()
        .apiKey(System.getenv("PTN_API_KEY")).environment("production").build())) {
    prompton.useCase("support_reply");
    prompton.exportUseCaseDocument(Path.of("src/main/resources/prompts.production.json"));
}
```

Then point the SDK at it with `bundlePath(...)`. The `.meta.json` sidecar carries the ETag,
`Last-Modified`, project and environment, so the first demand fetch can seed `If-None-Match` and
`useCaseDocumentInfo()` can report how old the bundle really is.

### Serverless and short-lived processes

There is no config polling timer. On a runtime with no writable disk set `diskCacheEnabled(false)`
and rely on `bundlePath` — there the bundle is the primary fallback, not a nicety.

### Prove it

Before you call a migration done, run your app with PromptOn unreachable — a wrong host, or the
network cut — and confirm provider calls still happen. `useCaseDocumentInfo()` tells you which tier answered:

```java
UseCaseDocumentInfo info = prompton.useCaseDocumentInfo();
log.info("prompton prompt document: source={} age={}s stale={} etag={}",
        info.source(), info.ageSeconds(), info.stale(), info.etag());
```

## How it fails

| What happens | What the SDK does | What your app sees |
|---|---|---|
| PromptOn is slow, down, or answers 5xx while a document is cached | Keeps serving it, marks it stale, and gates the next config attempt for the fixed 10 s window | Nothing. Config is stale at worst |
| PromptOn answers `429` | Waits out `Retry-After` before contacting the server again | Nothing |
| PromptOn is unreachable and memory, disk and bundle are all empty | — | `UseCaseException` with reason `NOT_READY` and a message saying PromptOn is unreachable and nothing is cached |
| The disk cache or the bundle is corrupt, truncated, or for another environment or project | Ignores it and falls through to the next tier | Nothing, unless no tier is left |
| The prompt document has no such prompt | — | `UseCaseException`, reason `UNKNOWN_USE_CASE` |
| The prompt has no live deployment in this environment | — | `UseCaseException`, reason `UNRESOLVED`. A bug in the deployment — never a reason to fall back to a hard-coded prompt |
| The live deployment pins no prompt of that name | — | `UseCaseException`, reason `UNKNOWN_PROMPT`, listing `promptNames()`. There is no silent fall back to `default` |
| A template needs a variable the call did not supply | — | `TemplateException`, kind `MISSING_VARIABLE`, naming the variable |
| A monitoring-log batch gets `429` or any 5xx | Resends the same batch with the same ids, honouring `Retry-After`, else doubling from 1 s to 5 min, up to `logMaxAttempts` | Nothing. `logStats().droppedFailed()` counts what was finally given up on |
| A monitoring-log batch gets `413` | Splits it in half and resends both halves | Nothing |
| A monitoring-log batch gets any other 4xx | Drops it, counts it, logs once | Nothing. Retrying what PromptOn has refused only loses the next batch |
| Some records in a batch are rejected | Reads `rejected`, counts them, never resends the accepted ones | Nothing |
| The log queue is full | Drops the oldest and counts them, warning at most once a minute | Nothing |
| Your provider call throws | Logs `status: "error"`, `error.kind: "app"`, then rethrows | Your own exception, unchanged |
| The process is shutting down | `close()` drains the buffer for `shutdownFlushTimeout` | Nothing |
| No API key, or `Mode.OFFLINE`, and something is logged | Counts every record in `logStats().droppedFailed()` and logs one line saying so | Nothing. Monitoring logs are not queued forever and not stored anywhere |

The rule behind the table: **a provider call must never fail because PromptOn did.**

## Testing your call sites

`Mode.TEST` makes no HTTP call at all and captures every record for assertions:

```java
PromptOn prompton = PromptOn.create(PromptOnConfig.builder().mode(Mode.TEST).build());
prompton.putUseCaseDocument(Files.readString(Path.of("src/test/resources/prompts.production.json")));

myService.reply("why is the sky blue?");

Map<String, Object> logged = prompton.capturedLogs().get(0);
assertEquals("support_reply", logged.get("prompt_key"));
assertEquals("ok", logged.get("status"));
```

`Mode.OFFLINE` is the other half: real behaviour, disk and bundle only, no network — useful in CI and
on a developer laptop with no key. It cannot send monitoring logs, and it does not hoard them either:
each record is counted in `logStats().droppedFailed()` and dropped, with one log line saying so. When
the records are what you are asserting on, use `Mode.TEST`.

## Conformance

`src/test/resources/conformance/` is a copy of the cross-language contract every PromptOn SDK
reproduces: template rendering, prompt loading, monitoring-log truncation, `stop_kind` normalisation and
golden records. The test suite executes every case in those files, so this SDK renders a prompt,
loads a prompt and truncates a payload byte for byte the way the Elixir reference implementation and
the PromptOn server do.

```sh
./gradlew clean build
PTN_HOST=http://localhost:4000 PTN_API_KEY=ptn_... ./gradlew test   # adds the live contract test
```

The live test (`LiveFixtureIT`) is skipped unless `PTN_API_KEY` is set. It checks the prompt document fetch
and the `304` on repoll, that local prompt rendering matches the server's `POST /prompts/{key}/render` field for field,
the error cases, and that a `/logs` batch is accepted once and counted as duplicates on
resend.

## License

Copyright 2026 Polimo

Licensed under the Apache License, Version 2.0 — see [LICENSE](LICENSE).

PromptOn is a trademark of Polimo. The license does not grant permission to use the PromptOn name or
logo; forks and derived services must use a different name.

## Prompt tools and trace events

Schema 7 prompt versions may include a `tools` block with OpenAI-compatible function tool definitions plus optional `tool_choice` and `parallel_tool_calls`. The SDK merges those into the provider params it returns and strips authoring-only `output_schema` / `output_examples` before the provider request body is built. The SDK never calls tools itself.

Use `logEvents` to submit observed tool attempts and completion events to the same monitoring endpoint when your application has executed or rejected tool calls. Events require `trace_id`, `event_kind`, and `status`; the SDK fills `event_id`, `observed_at`, SDK identity, and `metadata.sdk.version` when they are absent.
