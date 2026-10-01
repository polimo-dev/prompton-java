# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[semantic versioning](https://semver.org/spec/v2.0.0.html).

## Unreleased

### Changed

- Message slots are no longer expanded by the SDK. A prompt document containing a `type: "slot"`
  chat message now fails during rendering and applications should compose conversation history and
  the current user message in app code before calling the provider.
- Documentation and examples now show logging the final app-composed `inputMessages` while keeping
  ordinary variables such as `history` available as normal template variables.


## 0.5.0

Demand-driven config fetch release.

### Changed

- Removed startup and idle config polling. `useCase(key)` now fetches only that prompt key when its cached value is stale or missing.
- Added per-project/environment/prompt in-memory cache entries with a 10-second freshness TTL and a separate 10-second attempt gate.
- Added same-key single-flight config fetches, one-second total config-fetch deadline, no SDK retry, stale fallback after failures, and explicit cold-cache failure.
- Switched runtime config lookup to `GET /api/v1/prompts/{key}?environment=...` with per-key ETag validation. Bulk `GET /prompts` remains outside the normal runtime path.
- Bumped the SDK version to 0.5.0 for the behavior change.

## 0.4.2

Patch release aligning native tool prompts and monitoring events with the verified preview runtime contract.

### Fixed

- Preserve native chat messages, including explicit `null` content, tool-result messages, and empty `tool_calls` arrays, through local and remote rendering.
- Send monitoring-log identity as `prompt_key` and selected template evidence as `template`.
- Return `EventLogResult` from `logEvents`, parsing nested event acceptance, duplicate, and rejection counts from `/logs`.
- Preserve the server-rendered provider request on remote prompts with `providerPreparedRequest()`.

## 0.4.1

Patch release correcting the SDK wire contract to the current PromptOn runtime API.

### Fixed

- Fetch prompt documents from `GET /api/v1/prompts` and render through `POST /api/v1/prompts/{key}/render`.
- Decode canonical prompt documents with `prompts` and `template_pins`, and exercise `conformance/prompt.json` in the resolver tests.
- Send the render request field as `template` and read `template` / `template_names` from render responses while keeping legacy public `UseCase` method names.

## 0.2.0

Breaking vocabulary rename release. The SDK now matches the public schema-v4 prompt document,
monitoring-log and server-filled prompt vocabulary without compatibility aliases.

### Changed

- Renamed the public local-loading API from resolution vocabulary to prompt vocabulary:
  `resolve` / `resolveRemote` / `Resolution` / `ResolutionException` / `ResolutionSource` are now
  `useCase` / `useCaseRemote` / `UseCase` / `UseCaseException` / `Source`.
- Renamed the public prompt accessors from `useCase()` and `availablePrompts()` to `key()` and
  `promptNames()`.
- Renamed high-level rendering entrypoints to methods on `UseCase`: `messages(variables)` and
  `text(variables)`.
- Renamed generation logging vocabulary to monitoring-log result vocabulary:
  `GenerationRecord` / `GenerationMeta` / `GenerationOutcome` / `GenerationUsage` /
  `GenerationError` are now `LogRecord` / `TrackMeta` / `Result` / `Usage` / `LogError`, and
  `withGeneration` is now `track`.
- Renamed snapshot public document lifecycle vocabulary:
  `Snapshot` / `SnapshotInfo` / `snapshotInfo` / `exportSnapshot` / `putSnapshot` / `snapshot` are
  now `UseCaseDocument` / `UseCaseDocumentInfo` / `useCaseDocumentInfo` /
  `exportUseCaseDocument` / `putUseCaseDocument` / `useCaseDocument`.
- Renamed `RefreshOutcome` to `RefreshResult`.
- Updated the server-filled prompt route docs and client errors to `POST /prompts/{key}/render`;
  unknown-prompt errors and conformance fixtures now use `prompt_names`.
- Updated the bundled file name examples to `prompts.<environment>.json`.

## 0.1.0

Initial release. The PromptOn SDK for Java 17+, reading snapshot schema version 3.

### Added

- `PromptOn`, the client: `resolve`, `renderMessages`, `renderText`, `promptNames`,
  `resolveRemote`, `log`, `flush`, `withGeneration`, `snapshotInfo`, `refresh`, `exportSnapshot`,
  `putSnapshot`, `logStats`, `capturedLogs`.
- Local resolution (`Resolver`, `Resolution`, `Snapshot`) exactly as the runtime contract defines
  it: deployment to version to model, params and provider options layered shallowly, and
  `unknown_use_case` / `unresolved` / `unknown_prompt` errors with no silent fall back to the
  `default` prompt.
- `Template`, the Liquid subset PromptOn allows — `for`, `if`/`elsif`/`else`, `unless`, `assign`,
  `break`, `continue`, the filters `size`, `join` and `default`, and a `raw` passthrough — plus
  `lint` and `variables`.
- A snapshot store with a ten-second memory cache, ETag polling, an atomic disk cache with a
  sidecar, an optional bundled snapshot, environment and project guards, `Retry-After` handling and
  exponential backoff. Refreshes are coalesced, so a burst of resolves on an expired TTL is one
  fetch and a pause read from a `429` is respected by every caller and by any refresh already
  queued; a cold start whose first fetch failed retries on a later `resolve` rather than failing
  for the life of the process.
- A monitoring-log buffer: size, byte and time flush triggers, batches of at most 200 records and
  under 5 MB, one batch per environment however the records interleave, app-generated UUIDv7 ids,
  partial-acceptance handling, retries on `429` and 5xx, `413` splitting, dropping on other 4xx, a
  bounded queue that drops the oldest, and a best-effort drain on shutdown.
- Payload policy: sampling on a hash of the record id, UTF-8-safe truncation to the contract's
  arithmetic, `hash` and `none` modes, `hashEndUser`, and a redact hook applied last.
- A `POST /resolve` client that caches for the same TTL and follows the snapshot store's rules for
  a server that has pushed back: after a `429`, a 5xx or an unreachable host it serves the cached
  answer and makes no request until `Retry-After`, or the backoff, has elapsed.
- `StopKind` normalisation, `UuidV7` generation, and `Payload` as public helpers.
- Test mode (no HTTP, records captured) and offline mode (disk and bundle only; a monitoring log
  there is counted in `logStats().droppedFailed()` and dropped, never queued forever).
- A pluggable `PromptOnHttpClient`, with `JdkHttpClient` as the default. Jackson is an internal
  implementation detail and is not on a consumer's compile classpath.
- Builder-style configuration whose `build()` leaves the builder untouched, so one builder can
  produce a production and a staging configuration without them sharing a disk cache or a client.
- The cross-language conformance suite, executed case by case, and an environment-gated live
  contract test.
