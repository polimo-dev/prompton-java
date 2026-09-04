# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[semantic versioning](https://semver.org/spec/v2.0.0.html).

## 0.2.0

Breaking vocabulary rename release. The SDK now matches the public schema-v4 use-case document,
monitoring-log and server-filled prompt vocabulary without compatibility aliases.

### Changed

- Renamed the public local-loading API from resolution vocabulary to use-case vocabulary:
  `resolve` / `resolveRemote` / `Resolution` / `ResolutionException` / `ResolutionSource` are now
  `useCase` / `useCaseRemote` / `UseCase` / `UseCaseException` / `Source`.
- Renamed the public use-case accessors from `useCase()` and `availablePrompts()` to `key()` and
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
- Updated the server-filled prompt route docs and client errors to `POST /use-cases/{key}/prompt`;
  unknown-prompt errors and conformance fixtures now use `prompt_names`.
- Updated the bundled file name examples to `use-cases.<environment>.json`.

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
