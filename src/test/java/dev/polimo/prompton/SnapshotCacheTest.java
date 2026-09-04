package dev.polimo.prompton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.polimo.prompton.internal.Json;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The caching rules: the TTL, ETag polling, rate limits, backoff and the three fallback tiers. */
class SnapshotCacheTest {

    @TempDir
    Path tempDir;

    private StubServer server;

    @BeforeEach
    void startServer() {
        server = new StubServer();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    private PromptOnConfig.Builder config() {
        return PromptOnConfig.builder()
                .apiKey("ptn_sdkfixture_test")
                .baseUrl(server.baseUrl())
                .environment("production")
                .project("sdkfixture")
                .diskCachePath(tempDir.resolve("snapshot.json"))
                .pollingEnabled(false)
                .requestTimeout(Duration.ofSeconds(2));
    }

    @Test
    void servesEveryResolveFromMemoryWithinTheCacheTtl() {
        server.handle(request -> StubServer.Reply.ok(Fixtures.production())
                .withHeader("etag", "\"sha256-v1\""));
        try (PromptOn prompton = PromptOn.create(config().cacheTtl(Duration.ofMinutes(10)).build())) {
            for (int i = 0; i < 25; i++) {
                assertEquals("openai/gpt-4o-mini", prompton.resolve("greeting").model());
            }
            assertEquals(1, server.requests("/snapshot").size(),
                    "one fetch on start, and nothing else within the TTL");
        }
    }

    @Test
    void refreshesWithIfNoneMatchOnceTheTtlHasPassed() throws Exception {
        AtomicInteger notModified = new AtomicInteger();
        server.handle(request -> {
            if ("\"sha256-v1\"".equals(request.header("if-none-match"))) {
                notModified.incrementAndGet();
                return StubServer.Reply.status(304).withHeader("etag", "\"sha256-v1\"");
            }
            return StubServer.Reply.ok(Fixtures.production()).withHeader("etag", "\"sha256-v1\"");
        });
        try (PromptOn prompton = PromptOn.create(config().cacheTtl(Duration.ofMillis(50)).build())) {
            prompton.resolve("greeting");
            Thread.sleep(120);
            prompton.resolve("greeting");
            waitUntil(() -> notModified.get() >= 1);
            assertTrue(notModified.get() >= 1, "the refresh sent the ETag back");
            assertEquals(ResolutionSource.REMOTE, prompton.snapshotInfo().source());
        }
    }

    @Test
    void aRefreshThatFindsANewDocumentSwapsIt() {
        AtomicReference<String> body = new AtomicReference<>(Fixtures.production());
        AtomicReference<String> etag = new AtomicReference<>("\"sha256-v1\"");
        server.handle(request -> {
            if (etag.get().equals(request.header("if-none-match"))) {
                return StubServer.Reply.status(304).withHeader("etag", etag.get());
            }
            return StubServer.Reply.ok(body.get()).withHeader("etag", etag.get());
        });
        try (PromptOn prompton = PromptOn.create(config().cacheTtl(Duration.ofMillis(20)).build())) {
            assertEquals("You are a friendly greeter.",
                    prompton.resolve("greeting").messages().get(0).content());
            body.set(Fixtures.productionV2());
            etag.set("\"sha256-v2\"");
            assertEquals(RefreshOutcome.UPDATED, prompton.refresh());
            assertEquals("You are a very friendly greeter.",
                    prompton.resolve("greeting").messages().get(0).content());
        }
    }

    @Test
    void aRateLimitIsHonouredToTheSecondAndNeverReachesTheCaller() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server.handle(request -> {
            if (calls.incrementAndGet() == 1) {
                return StubServer.Reply.ok(Fixtures.production()).withHeader("etag", "\"v1\"");
            }
            return StubServer.Reply.of(429,
                            "{\"error\":{\"code\":\"rate_limited\",\"details\":{\"retry_after\":30}}}")
                    .withHeader("retry-after", "30");
        });
        try (PromptOn prompton = PromptOn.create(config().cacheTtl(Duration.ofMillis(10)).build())) {
            prompton.resolve("greeting");
            assertEquals(RefreshOutcome.FAILED, prompton.refresh());
            int after429 = calls.get();
            for (int i = 0; i < 10; i++) {
                assertEquals("openai/gpt-4o-mini", prompton.resolve("greeting").model());
                Thread.sleep(5);
            }
            Thread.sleep(60);
            assertEquals(after429, calls.get(),
                    "no request is made before Retry-After has elapsed");
            assertTrue(prompton.snapshotInfo().stale(), "the document is flagged stale");
        }
    }

    @Test
    void aServerErrorKeepsServingThePreviousDocument() {
        AtomicInteger calls = new AtomicInteger();
        server.handle(request -> calls.incrementAndGet() == 1
                ? StubServer.Reply.ok(Fixtures.production()).withHeader("etag", "\"v1\"")
                : StubServer.Reply.of(503, "{\"error\":{\"code\":\"unavailable\"}}"));
        try (PromptOn prompton = PromptOn.create(config().cacheTtl(Duration.ofMillis(10)).build())) {
            prompton.resolve("greeting");
            assertEquals(RefreshOutcome.FAILED, prompton.refresh());
            assertEquals("openai/gpt-4o-mini", prompton.resolve("greeting").model());
            assertTrue(prompton.snapshotInfo().stale());
        }
    }

    @Test
    void theBackoffDoublesFromTheTtlUpToTheCeiling() {
        assertEquals(Duration.ofSeconds(10), Backoff.exponential(
                Duration.ofSeconds(10), 1, Duration.ofMinutes(5)));
        assertEquals(Duration.ofSeconds(20), Backoff.exponential(
                Duration.ofSeconds(10), 2, Duration.ofMinutes(5)));
        assertEquals(Duration.ofSeconds(80), Backoff.exponential(
                Duration.ofSeconds(10), 4, Duration.ofMinutes(5)));
        assertEquals(Duration.ofMinutes(5), Backoff.exponential(
                Duration.ofSeconds(10), 20, Duration.ofMinutes(5)));
        assertEquals(Duration.ofSeconds(7), Backoff.retryAfter("7"));
        assertNull(Backoff.retryAfter(null));
    }

    @Test
    void aFetchedSnapshotIsMirroredToDiskAtomicallyWithASidecar() {
        server.handle(request -> StubServer.Reply.ok(Fixtures.production())
                .withHeader("etag", "\"sha256-v1\"")
                .withHeader("last-modified", "Fri, 04 Sep 2026 00:21:48 GMT"));
        Path cache = tempDir.resolve("snapshot.json");
        try (PromptOn prompton = PromptOn.create(config().diskCachePath(cache).build())) {
            prompton.resolve("greeting");
        }
        assertTrue(Files.exists(cache));
        Map<String, Object> meta = readJson(DiskCache.metaPath(cache));
        assertEquals("\"sha256-v1\"", meta.get("etag"));
        assertEquals("production", meta.get("environment"));
        assertEquals("sdkfixture", meta.get("project"));
    }

    @Test
    void withPromptOnDownItResolvesFromTheDiskCache() throws Exception {
        Path cache = tempDir.resolve("snapshot.json");
        Files.writeString(cache, Fixtures.production(), StandardCharsets.UTF_8);
        Files.writeString(DiskCache.metaPath(cache),
                "{\"etag\":\"\\\"sha256-v1\\\"\",\"environment\":\"production\","
                        + "\"project\":\"sdkfixture\"}", StandardCharsets.UTF_8);
        server.close();

        try (PromptOn prompton = PromptOn.create(config().diskCachePath(cache)
                .initialFetchTimeout(Duration.ofMillis(300)).build())) {
            Resolution pin = prompton.resolve("greeting");
            assertEquals(ResolutionSource.DISK, pin.source());
            assertEquals("openai/gpt-4o-mini", pin.model());
            assertTrue(prompton.snapshotInfo().stale());
        }
    }

    @Test
    void withNoDiskCacheItResolvesFromTheBundle() {
        Path bundle = tempDir.resolve("bundle").resolve("snapshot.production.json");
        assertTrue(DiskCache.write(bundle, Fixtures.production(),
                Map.of("environment", "production", "project", "sdkfixture")));
        server.close();

        try (PromptOn prompton = PromptOn.create(config()
                .diskCachePath(tempDir.resolve("missing").resolve("snapshot.json"))
                .bundlePath(bundle)
                .initialFetchTimeout(Duration.ofMillis(300))
                .build())) {
            assertEquals(ResolutionSource.BUNDLE, prompton.resolve("greeting").source());
        }
    }

    @Test
    void aDocumentForAnotherEnvironmentIsNeverUsed() {
        Path bundle = tempDir.resolve("staging.json");
        assertTrue(DiskCache.write(bundle, Fixtures.staging(), Map.of()));
        server.close();

        try (PromptOn prompton = PromptOn.create(config()
                .diskCacheEnabled(false)
                .bundlePath(bundle)
                .environment("production")
                .initialFetchTimeout(Duration.ofMillis(200))
                .build())) {
            ResolutionException e = assertThrows(
                    ResolutionException.class, () -> prompton.resolve("greeting"));
            assertEquals(ResolutionException.Reason.NOT_READY, e.reason());
            assertTrue(e.getMessage().contains("unreachable"));
        }
    }

    @Test
    void aDocumentForAnotherProjectIsNeverUsed() {
        Path bundle = tempDir.resolve("other.json");
        assertTrue(DiskCache.write(bundle,
                Fixtures.snapshot("production", "someone-else", "hi"), Map.of()));
        server.close();

        try (PromptOn prompton = PromptOn.create(config()
                .diskCacheEnabled(false)
                .bundlePath(bundle)
                .project("sdkfixture")
                .initialFetchTimeout(Duration.ofMillis(200))
                .build())) {
            assertThrows(ResolutionException.class, () -> prompton.resolve("greeting"));
        }
    }

    @Test
    void aCorruptDiskCacheIsIgnoredRatherThanFatal() throws Exception {
        Path cache = tempDir.resolve("snapshot.json");
        Files.writeString(cache, "{\"schema_version\": 3, \"use_ca", StandardCharsets.UTF_8);
        server.handle(request -> StubServer.Reply.ok(Fixtures.production()).withHeader("etag", "\"v1\""));

        try (PromptOn prompton = PromptOn.create(config().diskCachePath(cache).build())) {
            assertEquals(ResolutionSource.REMOTE, prompton.resolve("greeting").source());
        }
    }

    @Test
    void anOlderSchemaVersionIsRefused() {
        PromptOnException e = assertThrows(PromptOnException.class, () ->
                Snapshot.parse("{\"schema_version\": 2, \"use_cases\": {}}"));
        assertTrue(e.getMessage().contains("schema_version 2"));
    }

    @Test
    void aNewerSchemaVersionIsReadWithAWarning() {
        Snapshot snapshot = Snapshot.parse(
                "{\"schema_version\": 4, \"project\": \"p\", \"environment\": \"production\","
                        + " \"use_cases\": {}, \"deployments\": {}}");
        assertEquals(List.of("unknown_schema_version: 4"), snapshot.warnings());
    }

    @Test
    void withNothingCachedAndPromptOnDownResolutionFailsWithAClearError() {
        server.close();
        try (PromptOn prompton = PromptOn.create(config()
                .diskCacheEnabled(false)
                .initialFetchTimeout(Duration.ofMillis(200))
                .build())) {
            ResolutionException e = assertThrows(
                    ResolutionException.class, () -> prompton.resolve("greeting"));
            assertEquals(ResolutionException.Reason.NOT_READY, e.reason());
            assertTrue(e.getMessage().contains("no snapshot is cached"));
        }
    }

    @Test
    void withoutAnApiKeyNothingIsFetchedAndTheBundleStillResolves() {
        Path bundle = tempDir.resolve("bundle.json");
        assertTrue(DiskCache.write(bundle, Fixtures.production(), Map.of()));
        try (PromptOn prompton = PromptOn.create(PromptOnConfig.builder()
                .apiKey(null)
                .baseUrl(server.baseUrl())
                .environment("production")
                .project("sdkfixture")
                .diskCacheEnabled(false)
                .bundlePath(bundle)
                .build())) {
            assertEquals(ResolutionSource.BUNDLE, prompton.resolve("greeting").source());
            assertEquals(RefreshOutcome.SKIPPED, prompton.refresh());
            assertEquals(0, server.requests().size(), "no API key means no remote call at all");
        }
    }

    @Test
    void offlineModeNeverContactsTheServer() {
        Path bundle = tempDir.resolve("bundle.json");
        assertTrue(DiskCache.write(bundle, Fixtures.production(), Map.of()));
        try (PromptOn prompton = PromptOn.create(config()
                .mode(Mode.OFFLINE)
                .diskCacheEnabled(false)
                .bundlePath(bundle)
                .build())) {
            assertEquals(ResolutionSource.BUNDLE, prompton.resolve("greeting").source());
            assertEquals(RefreshOutcome.SKIPPED, prompton.refresh());
            assertEquals(0, server.requests().size());
        }
    }

    @Test
    void exportWritesTheDocumentAndItsSidecarForBundling() {
        server.handle(request -> StubServer.Reply.ok(Fixtures.production())
                .withHeader("etag", "\"sha256-v1\""));
        Path out = tempDir.resolve("dist").resolve("snapshot.production.json");
        try (PromptOn prompton = PromptOn.create(config().build())) {
            prompton.resolve("greeting");
            prompton.exportSnapshot(out);
        }
        assertTrue(Files.exists(out));
        assertEquals("production", readJson(DiskCache.metaPath(out)).get("environment"));
        assertNotNull(Snapshot.parse(readString(out)).useCases().get("greeting"));
    }

    @Test
    void snapshotInfoReportsSourceAgeAndIdentity() {
        server.handle(request -> StubServer.Reply.ok(Fixtures.production())
                .withHeader("etag", "\"sha256-v1\""));
        try (PromptOn prompton = PromptOn.create(config().build())) {
            prompton.resolve("greeting");
            SnapshotInfo info = prompton.snapshotInfo();
            assertTrue(info.loaded());
            assertEquals(ResolutionSource.REMOTE, info.source());
            assertEquals("\"sha256-v1\"", info.etag());
            assertEquals("production", info.environment());
            assertEquals("sdkfixture", info.project());
            assertFalse(info.stale());
            assertNotNull(info.ageSeconds());
        }
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws Exception {
        for (int i = 0; i < 100 && !condition.getAsBoolean(); i++) {
            Thread.sleep(20);
        }
    }

    private static Map<String, Object> readJson(Path path) {
        return Json.parseObject(readString(path));
    }

    private static String readString(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
