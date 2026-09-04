# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[semantic versioning](https://semver.org/spec/v2.0.0.html).

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
  exponential backoff.
- A monitoring-log buffer: size, byte and time flush triggers, batches of at most 200 records and
  under 5 MB, one batch per environment, app-generated UUIDv7 ids, partial-acceptance handling,
  retries on `429` and 5xx, `413` splitting, dropping on other 4xx, a bounded queue that drops the
  oldest, and a best-effort drain on shutdown.
- Payload policy: sampling on a hash of the record id, UTF-8-safe truncation to the contract's
  arithmetic, `hash` and `none` modes, `hashEndUser`, and a redact hook applied last.
- `StopKind` normalisation, `UuidV7` generation, and `Payload` as public helpers.
- Test mode (no HTTP, records captured) and offline mode (disk and bundle only).
- A pluggable `PromptOnHttpClient`, with `JdkHttpClient` as the default.
- The cross-language conformance suite, executed case by case, and an environment-gated live
  contract test.
