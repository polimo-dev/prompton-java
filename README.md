# prompton-sdk for Java

The official [PromptOn](https://app.prompton.ai) SDK for Java 17+.

PromptOn is a control plane for the prompts and models your app uses. Every place your code calls an
LLM becomes a **use case**, and for each use case and environment PromptOn holds one **pin**: a
prompt version, one model, and its parameters. Your app fetches that configuration and then calls the
provider itself, with your own provider key and your own HTTP client — PromptOn is **config-fetch,
not a proxy**, so it is never in the request path and never sees your key. After each call your app
sends back a **monitoring log**, and those logs are how you see cost, latency, error rate and stop
reasons per use case.

This SDK does four things and nothing else:

```
resolve("support_reply")            -> Resolution: model, params, provider options, prompt template
renderMessages(pin, variables)      -> the messages to send
withGeneration(pin, meta, call)     -> times your provider call and queues a monitoring log
flush()                             -> sends what is queued
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
    implementation("dev.polimo:prompton-sdk:0.1.0")
}
```

```xml
<dependency>
  <groupId>dev.polimo</groupId>
  <artifactId>prompton-sdk</artifactId>
  <version>0.1.0</version>
</dependency>
```

Once it is on Maven Central the same coordinates will resolve without the local install. Check the
registry rather than trusting this line: a `dev.polimo:prompton-sdk` published by anyone else is not
this SDK.

## Quick start

```java
try (PromptOn prompton = PromptOn.create()) {                      // reads PTN_HOST and PTN_API_KEY
    Resolution pin = prompton.resolve("support_reply");            // from memory: no HTTP call
    List<Message> messages = prompton.renderMessages(pin, Map.of("question", question));
    String answer = prompton.withGeneration(pin, GenerationMeta.builder()
                    .inputMessages(messages).variables(Map.of("question", question)).build(),
            () -> {                                                // your provider, your key
                MyReply reply = myProvider.chat(pin.model(), messages, pin.effectiveParams());
                return ProviderResult.ok(reply.text(), GenerationOutcome.builder()
                        .content(reply.text()).finishReason(reply.finishReason()).build());
            });
}
```

Build one `PromptOn` per process — it owns a poll loop and a log-sender thread — share it across
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
| `apiKey` | `PTN_API_KEY` | none | The runtime key, `ptn_<project>_…`. Without it the SDK makes no remote call at all and resolves from disk or the bundle |
| `host` | `PTN_HOST` | `https://app.prompton.ai` | The SDK appends `/api/v1` |
| `baseUrl` | — | `host + "/api/v1"` | Set it when your API base is not under the host root |
| `environment` | `PTN_ENVIRONMENT` | `production` | Sent as `?environment=`, and the guard that stops a staging process booting on a production document |
| `project` | `PTN_PROJECT` | read out of the API key | Names the disk cache, and guards against another project's document |
| `cacheTtl` | — | 10 s | How long a snapshot is served from memory before a refresh is due; also the base of the poll backoff |
| `requestTimeout` | — | 5 s | Read timeout for PromptOn's own calls |
| `connectTimeout` | — | 5 s | Connect timeout of the default HTTP client |
| `initialFetchTimeout` | — | 3 s | How long the very first `resolve` waits when nothing is cached yet |
| `maxBackoff` | — | 5 min | Ceiling of every exponential backoff |
| `diskCachePath` | — | OS cache dir, `prompton/snapshot-<project>-<environment>.json` | Where the snapshot is mirrored |
| `diskCacheEnabled` | — | `true` | `false` keeps the SDK entirely in memory |
| `bundlePath` | — | none | A snapshot file shipped inside your application, used when memory and disk are empty |
| `mode` | — | `LIVE` | `TEST` captures logs and makes no HTTP call; `OFFLINE` resolves from disk or bundle only |
| `hashEndUser` | — | `false` | Send `sha256(end_user_ref)` instead of the raw reference |
| `redact` | — | none | `UnaryOperator<Map<String, Object>>` applied to every record last |
| `logFlushSize` | — | 100 | Flush once this many records are queued |
| `logFlushBytes` | — | 1 MB | Flush once this many bytes are queued |
| `logFlushInterval` | — | 2 s | Flush at least this often |
| `logMaxBuffer` | — | 10 000 | Queue cap; over it the oldest are dropped and counted |
| `logMaxAttempts` | — | 8 | How many times one batch is retried before it is dropped and counted |
| `shutdownFlushTimeout` | — | 5 s | How long `close()` spends draining |
| `pollingEnabled` | — | `true` | `false` refreshes on the next `resolve` instead of on a timer — for serverless |
| `httpClient` | — | `JdkHttpClient` | Route PromptOn's own calls through your own HTTP stack |
| `payloadDefaults` | — | full, no sampling, 256 KiB | The payload policy used when the snapshot declares none |

```java
PromptOn prompton = PromptOn.create(PromptOnConfig.builder()
        .apiKey(System.getenv("PTN_API_KEY"))
        .environment("staging")
        .bundlePath(Path.of("/app/resources/prompton-snapshot.staging.json"))
        .redact(record -> { record.remove("end_user_ref"); return record; })
        .build());
```

## Resolving and rendering

`resolve` reads the snapshot in memory: no HTTP call, no lock, no blocking.

```java
Resolution pin = prompton.resolve("support_reply");          // the "default" prompt
Resolution korean = prompton.resolve("support_reply", "ko"); // a named prompt
List<String> names = prompton.promptNames("support_reply");  // ["default", "ko"]
```

A `Resolution` carries everything one call needs: `useCase()`, `kind()`, `deploymentId()`,
`deploymentRevision()`, `prompt()`, `availablePrompts()`, `model()` (the provider string to send),
`modelId()` (the catalog UUID), `provider()`, `effectiveParams()`, `effectiveProviderOptions()`,
`promptVersionId()`, `promptVersionNumber()`, and the raw template as `messages()` (chat) or
`textTemplate()` (text). An embedding use case has neither.

Rendering is a separate step, because a resolution is reusable across calls and the variables are
not:

```java
List<Message> messages = prompton.renderMessages(pin, Map.of("question", "why is the sky blue?"));
String prompt = prompton.renderText(summarizePin, Map.of("items", List.of("alpha", "beta")));
```

Prompts are Liquid, restricted to the subset PromptOn allows: `{{ var }}`, `for` (with `else`,
`break`, `continue` and `forloop.*`), `if`/`elsif`/`else`, `unless`, `assign`, and the filters
`size`, `join` and `default`. Anything else is a parse error. A variable is missing when its key is
absent — a key present with a `null` value renders as the empty string and `default` replaces it —
and a missing variable throws `TemplateException` naming it rather than silently rendering a hole.
`Template.lint` and `Template.variables` are public if you want to check a template yourself.

There is also the server-side path, for a smoke test or a cold, low-traffic call site:

```java
Resolution pin = prompton.resolveRemote("support_reply", null);            // cached for cacheTtl
Resolution rendered = prompton.resolveRemote("support_reply", null, vars); // server renders
```

Never call `resolveRemote` once per request in a hot loop; that is what the snapshot is for.

## Monitoring logs

Three entry points, and the buffer behind them.

```java
prompton.withGeneration(pin, meta, call);   // times your call and builds the record
prompton.log(record);                       // queue a record you built yourself
prompton.flush();                           // send what is queued, and wait
```

`withGeneration` returns whatever your call returned, unchanged. An exception thrown inside it is
logged as `status: "error"` with `error.kind: "app"` and then rethrown unchanged: the wrapper never
swallows a failure or changes control flow. Tell success from failure with `ProviderResult`:

```java
ProviderResult.ok(value, outcome);            // status ok
ProviderResult.error(value, error);           // status error
ProviderResult.error(value, error, outcome);  // status error, keeping the usage and the output
```

The third form is what you want when the provider answered but the answer failed to parse: the
tokens were still spent, and the text is the evidence.

`log` is for the cases the wrapper does not fit — a streaming response you finish accounting for
later, or a record your own framework assembles:

```java
prompton.log(GenerationRecord.builder()
        .resolution(pin)                             // fills the deployment, prompt and model fields
        .status(GenerationRecord.Status.OK)
        .startedAt(startedAt)
        .latencyMs(latency)
        .output(Map.of("content", answer))
        .usage(GenerationUsage.ofTokens(inputTokens, outputTokens))
        .build());
```

### What a record carries

| Field | Notes |
|---|---|
| `id` | UUIDv7, the idempotency key. The SDK issues one; a resend is counted as a duplicate, never stored twice |
| `use_case`, `model`, `status`, `started_at` | Required. `status` is `ok` or `error`; `started_at` must be within 5 minutes ahead and 7 days behind |
| `kind` | `chat`, `text` or `embedding` |
| `deployment_id`, `deployment_revision`, `prompt`, `prompt_version_id`, `model_id` | The resolution evidence, filled from the `Resolution` |
| `resolution_source` | `remote`, `disk`, `bundle` or `manual` — which tier answered |
| `provider`, `model_used`, `upstream_provider` | Who actually served the call |
| `params` | What was sent. The server blanks it over 4 KB rather than rejecting the record |
| `input` | `{variables, messages}` or `{text}` |
| `output` | `{content, tool_calls}` |
| `finish_reason`, `stop_kind` | `stop_kind` is `stop`, `length`, `tool_call`, `content_filter` or `other`, derived from the finish reason. Only `length` counts as truncated |
| `error` | `{kind, status, message}` on a failure. `kind` is one of `http_4xx`, `http_5xx`, `rate_limited`, `timeout`, `transport`, `parse`, `app` |
| `usage` | `{input_tokens, output_tokens, cost_usd, cost_source, raw}` |
| `latency_ms`, `trace_id`, `sequence`, `end_user_ref` | How to find this call again |
| `context`, `metadata` | Free-form. Keep `context` under 2 KB and `metadata` under 4 KB, or the record is rejected |
| `sdk` | `{"name": "prompton-java", "version": "0.1.0"}` |

Do not log secrets: no provider keys, no `PTN_API_KEY`, no user PII beyond `end_user_ref`.

### What the buffer does

Records are queued and sent in batches on a size, byte or time trigger — never one HTTP call per
generation, and never blocking your provider call. A batch carries at most 200 records and under
5 MB, and one batch covers one environment because `?environment=` applies to the whole request.
Before a record is queued the SDK applies the use case's payload policy from the snapshot: sampling
(errors and length truncations are always kept), truncation to the caps the server re-checks,
`hash`/`none` modes, then `hashEndUser`, then your `redact` hook last.

`logStats()` reports what is queued and what has been dropped, so you can alarm on it.

## Resilience

This is the part that matters when PromptOn has a bad day.

**Three tiers, consulted in this order.** Memory always; then the disk cache, which is on by default
and written atomically (temporary file, then rename) with a sidecar holding the ETag and
`Last-Modified`; then an optional snapshot bundled into your application. Whichever answered is
reported as `resolution_source`.

**A ten-second cache.** Within the TTL every `resolve` is served from memory with no HTTP call at
all. Once it has passed the SDK refreshes in the background with `GET /snapshot` and
`If-None-Match`, where a `304` means there is nothing to parse. A refresh never blocks a generation
and never fails one: while it is in flight, and if it fails, the previous document keeps serving.

**Rate limits and failures.** A `429` is honoured to the second from `Retry-After` (falling back to
`error.details.retry_after`, then to backoff) and no request is made before it has elapsed. A 5xx, a
timeout or a transport failure backs off by doubling from the TTL up to five minutes. In every case
the caller sees the previous configuration, not an error.

**Never the wrong document.** A snapshot whose `environment` or `project` does not match this
process is ignored, wherever it came from — so a staging build cannot boot on a production bundle.
A schema version older than 3 is refused and the SDK keeps polling for one it understands. A corrupt
or half-written file is ignored rather than fatal, which is what makes it safe for several processes
on one host to share the disk cache.

**No external services, ever.** Memory, one local file, and the bundled file are the only tiers.
Instances never coordinate; each keeps its own copy, which ETag polling makes cheap.

### Building the bundle

Fetch once and write the file, in CI, on every build, and commit both files it writes — one per
environment:

```java
try (PromptOn prompton = PromptOn.create(PromptOnConfig.builder()
        .apiKey(System.getenv("PTN_API_KEY")).environment("production").build())) {
    prompton.refresh();
    prompton.exportSnapshot(Path.of("src/main/resources/prompton-snapshot.production.json"));
}
```

Then point the SDK at it with `bundlePath(...)`. The `.meta.json` sidecar carries the ETag,
`Last-Modified`, project and environment, so the first poll can seed `If-None-Match` and
`snapshotInfo()` can report how old the bundle really is.

### Serverless and short-lived processes

Set `pollingEnabled(false)`: there is no timer, and the refresh happens on the next `resolve` once
the TTL has passed. On a runtime with no writable disk also set `diskCacheEnabled(false)` and rely on
`bundlePath` — there the bundle is the primary fallback, not a nicety.

### Prove it

Before you call a migration done, run your app with PromptOn unreachable — a wrong host, or the
network cut — and confirm generations still happen. `snapshotInfo()` tells you which tier answered:

```java
SnapshotInfo info = prompton.snapshotInfo();
log.info("prompton snapshot: source={} age={}s stale={} etag={}",
        info.source(), info.ageSeconds(), info.stale(), info.etag());
```

## How it fails

| What happens | What the SDK does | What your app sees |
|---|---|---|
| PromptOn is slow, down, or answers 5xx while a document is cached | Keeps serving it, marks it stale, backs off ×2 from the TTL to 5 min | Nothing. Config is stale at worst |
| PromptOn answers `429` | Waits out `Retry-After` before contacting the server again | Nothing |
| PromptOn is unreachable and memory, disk and bundle are all empty | — | `ResolutionException` with reason `NOT_READY` and a message saying PromptOn is unreachable and nothing is cached |
| The disk cache or the bundle is corrupt, truncated, or for another environment or project | Ignores it and falls through to the next tier | Nothing, unless no tier is left |
| The snapshot has no such use case | — | `ResolutionException`, reason `UNKNOWN_USE_CASE` |
| The use case has no live deployment in this environment | — | `ResolutionException`, reason `UNRESOLVED`. A bug in the deployment — never a reason to fall back to a hard-coded prompt |
| The live deployment pins no prompt of that name | — | `ResolutionException`, reason `UNKNOWN_PROMPT`, listing `availablePrompts()`. There is no silent fall back to `default` |
| A template needs a variable the call did not supply | — | `TemplateException`, kind `MISSING_VARIABLE`, naming the variable |
| A monitoring-log batch gets `429` or any 5xx | Resends the same batch with the same ids, honouring `Retry-After`, else doubling from 1 s to 5 min, up to `logMaxAttempts` | Nothing. `logStats().droppedFailed()` counts what was finally given up on |
| A monitoring-log batch gets `413` | Splits it in half and resends both halves | Nothing |
| A monitoring-log batch gets any other 4xx | Drops it, counts it, logs once | Nothing. Retrying what PromptOn has refused only loses the next batch |
| Some records in a batch are rejected | Reads `rejected`, counts them, never resends the accepted ones | Nothing |
| The log queue is full | Drops the oldest and counts them, warning at most once a minute | Nothing |
| Your provider call throws | Logs `status: "error"`, `error.kind: "app"`, then rethrows | Your own exception, unchanged |
| The process is shutting down | `close()` drains the buffer for `shutdownFlushTimeout` | Nothing |

The rule behind the table: **a generation must never fail because PromptOn did.**

## Testing your call sites

`Mode.TEST` makes no HTTP call at all and captures every record for assertions:

```java
PromptOn prompton = PromptOn.create(PromptOnConfig.builder().mode(Mode.TEST).build());
prompton.putSnapshot(Files.readString(Path.of("src/test/resources/prompton-snapshot.json")));

myService.reply("why is the sky blue?");

Map<String, Object> logged = prompton.capturedLogs().get(0);
assertEquals("support_reply", logged.get("use_case"));
assertEquals("ok", logged.get("status"));
```

`Mode.OFFLINE` is the other half: real behaviour, disk and bundle only, no network — useful in CI and
on a developer laptop with no key.

## Conformance

`src/test/resources/conformance/` is a copy of the cross-language contract every PromptOn SDK
reproduces: template rendering, resolution, monitoring-log truncation, `stop_kind` normalisation and
golden records. The test suite executes every case in those files, so this SDK renders a prompt,
resolves a pin and truncates a payload byte for byte the way the Elixir reference implementation and
the PromptOn server do.

```sh
./gradlew clean build
PTN_HOST=http://localhost:4000 PTN_API_KEY=ptn_... ./gradlew test   # adds the live contract test
```

The live test (`LiveFixtureIT`) is skipped unless `PTN_API_KEY` is set. It checks the snapshot fetch
and the `304` on repoll, that local resolution matches the server's `POST /resolve` field for field,
the error cases, and that a `/generations` batch is accepted once and counted as duplicates on
resend.

## License

Copyright 2026 Polimo

Licensed under the Apache License, Version 2.0 — see [LICENSE](LICENSE).

PromptOn is a trademark of Polimo. The license does not grant permission to use the PromptOn name or
logo; forks and derived services must use a different name.
