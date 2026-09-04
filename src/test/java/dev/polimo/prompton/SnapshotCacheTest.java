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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
                assertEquals("openai/gpt-4o-mini", prompton.useCase("greeting").model());
            }
            assertEquals(1, server.requests("/use-cases").size(),
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
            prompton.useCase("greeting");
            Thread.sleep(120);
            prompton.useCase("greeting");
            waitUntil(() -> notModified.get() >= 1);
            assertTrue(notModified.get() >= 1, "the refresh sent the ETag back");
            assertEquals(Source.REMOTE, prompton.useCaseDocumentInfo().source());
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
                    prompton.useCase("greeting").messages().get(0).content());
            body.set(Fixtures.productionV2());
            etag.set("\"sha256-v2\"");
            assertEquals(RefreshResult.UPDATED, prompton.refresh());
            assertEquals("You are a very friendly greeter.",
                    prompton.useCase("greeting").messages().get(0).content());
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
            prompton.useCase("greeting");
            assertEquals(RefreshResult.FAILED, prompton.refresh());
            int after429 = calls.get();
            for (int i = 0; i < 10; i++) {
                assertEquals("openai/gpt-4o-mini", prompton.useCase("greeting").model());
                Thread.sleep(5);
            }
            Thread.sleep(60);
            assertEquals(after429, calls.get(),
                    "no request is made before Retry-After has elapsed");
            assertTrue(prompton.useCaseDocumentInfo().stale(), "the document is flagged stale");
        }
    }

    @Test
    void aBurstOfResolvesOnAnExpiredTtlTriggersOneRefreshNotOnePerCaller() throws Exception {
        server.handle(request -> StubServer.Reply.ok(Fixtures.production())
                .withHeader("etag", "\"v1\""));
        try (PromptOn prompton = PromptOn.create(config().cacheTtl(Duration.ofMillis(200)).build())) {
            prompton.useCase("greeting");
            assertEquals(1, server.requests("/use-cases").size());
            Thread.sleep(250);

            int threads = 32;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                for (int t = 0; t < threads; t++) {
                    pool.execute(() -> {
                        try {
                            start.await();
                            for (int i = 0; i < 100; i++) {
                                prompton.useCase("greeting");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                assertTrue(done.await(30, TimeUnit.SECONDS));
            } finally {
                pool.shutdownNow();
            }
            Thread.sleep(50);
            assertEquals(2, server.requests("/use-cases").size(),
                    "3200 resolves on one expired TTL are one refresh, not one fetch per caller");
        }
    }

    @Test
    void afterARateLimitNotOneOfAThousandResolvesContactsTheServer() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server.handle(request -> calls.incrementAndGet() == 1
                ? StubServer.Reply.ok(Fixtures.production()).withHeader("etag", "\"v1\"")
                : StubServer.Reply.of(429, "{\"error\":{\"code\":\"rate_limited\"}}")
                        .withHeader("retry-after", "120"));
        try (PromptOn prompton = PromptOn.create(config().cacheTtl(Duration.ofMillis(20)).build())) {
            prompton.useCase("greeting");
            Thread.sleep(40);
            prompton.useCase("greeting");
            waitUntil(() -> calls.get() >= 2);
            assertEquals(2, calls.get(), "the refresh after the TTL was the one that got the 429");

            int threads = 16;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                for (int t = 0; t < threads; t++) {
                    pool.execute(() -> {
                        try {
                            start.await();
                            for (int i = 0; i < 100; i++) {
                                assertEquals("openai/gpt-4o-mini",
                                        prompton.useCase("greeting").model());
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                assertTrue(done.await(30, TimeUnit.SECONDS));
            } finally {
                pool.shutdownNow();
            }
            Thread.sleep(50);
            assertEquals(2, calls.get(),
                    "Retry-After: 120 was read, so nothing may reach the server before it elapses");
        }
    }

    @Test
    void aColdStartWhilePromptOnIsDownRecoversWhenItComesBack() throws Exception {
        int port = server.port();
        server.close();

        try (PromptOn prompton = PromptOn.create(config()
                .diskCacheEnabled(false)
                .pollingEnabled(false)
                .cacheTtl(Duration.ofMillis(20))
                .initialFetchTimeout(Duration.ofMillis(500))
                .build())) {
            UseCaseException e = assertThrows(
                    UseCaseException.class, () -> prompton.useCase("greeting"));
            assertEquals(UseCaseException.Reason.NOT_READY, e.reason());

            server = new StubServer(port);
            server.handle(request -> StubServer.Reply.ok(Fixtures.production())
                    .withHeader("etag", "\"v1\""));
            Thread.sleep(60);

            assertEquals("openai/gpt-4o-mini", prompton.useCase("greeting").model(),
                    "a cold start during an outage must recover, not fail for the process's life");
            assertEquals(Source.REMOTE, prompton.useCaseDocumentInfo().source());
        }
    }

    @Test
    void aServerErrorKeepsServingThePreviousDocument() {
        AtomicInteger calls = new AtomicInteger();
        server.handle(request -> calls.incrementAndGet() == 1
                ? StubServer.Reply.ok(Fixtures.production()).withHeader("etag", "\"v1\"")
                : StubServer.Reply.of(503, "{\"error\":{\"code\":\"unavailable\"}}"));
        try (PromptOn prompton = PromptOn.create(config().cacheTtl(Duration.ofMillis(10)).build())) {
            prompton.useCase("greeting");
            assertEquals(RefreshResult.FAILED, prompton.refresh());
            assertEquals("openai/gpt-4o-mini", prompton.useCase("greeting").model());
            assertTrue(prompton.useCaseDocumentInfo().stale());
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
            prompton.useCase("greeting");
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
            UseCase pin = prompton.useCase("greeting");
            assertEquals(Source.DISK, pin.source());
            assertEquals("openai/gpt-4o-mini", pin.model());
            assertTrue(prompton.useCaseDocumentInfo().stale());
        }
    }

    @Test
    void withNoDiskCacheItResolvesFromTheBundle() {
        Path bundle = tempDir.resolve("bundle").resolve("use-cases.production.json");
        assertTrue(DiskCache.write(bundle, Fixtures.production(),
                Map.of("environment", "production", "project", "sdkfixture")));
        server.close();

        try (PromptOn prompton = PromptOn.create(config()
                .diskCachePath(tempDir.resolve("missing").resolve("snapshot.json"))
                .bundlePath(bundle)
                .initialFetchTimeout(Duration.ofMillis(300))
                .build())) {
            assertEquals(Source.BUNDLE, prompton.useCase("greeting").source());
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
            UseCaseException e = assertThrows(
                    UseCaseException.class, () -> prompton.useCase("greeting"));
            assertEquals(UseCaseException.Reason.NOT_READY, e.reason());
            assertTrue(e.getMessage().contains("unreachable"));
        }
    }

    @Test
    void aDocumentForAnotherProjectIsNeverUsed() {
        Path bundle = tempDir.resolve("other.json");
        assertTrue(DiskCache.write(bundle,
                Fixtures.useCaseDocument("production", "someone-else", "hi"), Map.of()));
        server.close();

        try (PromptOn prompton = PromptOn.create(config()
                .diskCacheEnabled(false)
                .bundlePath(bundle)
                .project("sdkfixture")
                .initialFetchTimeout(Duration.ofMillis(200))
                .build())) {
            assertThrows(UseCaseException.class, () -> prompton.useCase("greeting"));
        }
    }

    @Test
    void aCorruptDiskCacheIsIgnoredRatherThanFatal() throws Exception {
        Path cache = tempDir.resolve("snapshot.json");
        Files.writeString(cache, "{\"schema_version\": 3, \"use_ca", StandardCharsets.UTF_8);
        server.handle(request -> StubServer.Reply.ok(Fixtures.production()).withHeader("etag", "\"v1\""));

        try (PromptOn prompton = PromptOn.create(config().diskCachePath(cache).build())) {
            assertEquals(Source.REMOTE, prompton.useCase("greeting").source());
        }
    }

    @Test
    void aMissingSchemaVersionIsRefused() {
        PromptOnException e = assertThrows(PromptOnException.class, () ->
                UseCaseDocument.parse("{\"use_cases\": {}, \"deployments\": {}}"));
        assertTrue(e.getMessage().contains("missing schema_version"));
    }

    @Test
    void aLegacyVersionOnlyDocumentIsRefused() {
        PromptOnException e = assertThrows(PromptOnException.class, () ->
                UseCaseDocument.parse("{\"version\": 4, \"use_cases\": {}, \"deployments\": {}}"));
        assertTrue(e.getMessage().contains("missing schema_version"));
    }

    @Test
    void aStringSchemaVersionIsRefused() {
        PromptOnException e = assertThrows(PromptOnException.class, () ->
                UseCaseDocument.parse("{\"schema_version\": \"4\", \"use_cases\": {}}"));
        assertTrue(e.getMessage().contains("schema_version must be the JSON integer 4"));
    }

    @Test
    void aFractionalSchemaVersionIsRefused() {
        PromptOnException e = assertThrows(PromptOnException.class, () ->
                UseCaseDocument.parse("{\"schema_version\": 4.0, \"use_cases\": {}}"));
        assertTrue(e.getMessage().contains("schema_version must be the JSON integer 4"));
    }

    @Test
    void schemaVersionThreeIsRefused() {
        PromptOnException e = assertThrows(PromptOnException.class, () ->
                UseCaseDocument.parse("{\"schema_version\": 3, \"use_cases\": {}}"));
        assertTrue(e.getMessage().contains("schema_version 3"));
    }

    @Test
    void aNewerSchemaVersionIsRefused() {
        PromptOnException e = assertThrows(PromptOnException.class, () ->
                UseCaseDocument.parse("{\"schema_version\": 5, \"use_cases\": {}}"));
        assertTrue(e.getMessage().contains("schema_version 5"));
    }

    @Test
    void withNothingCachedAndPromptOnDownUseCaseFailsWithAClearError() {
        server.close();
        try (PromptOn prompton = PromptOn.create(config()
                .diskCacheEnabled(false)
                .initialFetchTimeout(Duration.ofMillis(200))
                .build())) {
            UseCaseException e = assertThrows(
                    UseCaseException.class, () -> prompton.useCase("greeting"));
            assertEquals(UseCaseException.Reason.NOT_READY, e.reason());
            assertTrue(e.getMessage().contains("no use-case document is cached"));
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
            assertEquals(Source.BUNDLE, prompton.useCase("greeting").source());
            assertEquals(RefreshResult.SKIPPED, prompton.refresh());
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
            assertEquals(Source.BUNDLE, prompton.useCase("greeting").source());
            assertEquals(RefreshResult.SKIPPED, prompton.refresh());
            assertEquals(0, server.requests().size());
        }
    }

    @Test
    void exportWritesTheDocumentAndItsSidecarForBundling() {
        server.handle(request -> StubServer.Reply.ok(Fixtures.production())
                .withHeader("etag", "\"sha256-v1\""));
        Path out = tempDir.resolve("dist").resolve("use-cases.production.json");
        try (PromptOn prompton = PromptOn.create(config().build())) {
            prompton.useCase("greeting");
            prompton.exportUseCaseDocument(out);
        }
        assertTrue(Files.exists(out));
        assertEquals("production", readJson(DiskCache.metaPath(out)).get("environment"));
        assertNotNull(UseCaseDocument.parse(readString(out)).useCases().get("greeting"));
    }

    @Test
    void useCaseDocumentInfoReportsSourceAgeAndIdentity() {
        server.handle(request -> StubServer.Reply.ok(Fixtures.production())
                .withHeader("etag", "\"sha256-v1\""));
        try (PromptOn prompton = PromptOn.create(config().build())) {
            prompton.useCase("greeting");
            UseCaseDocumentInfo info = prompton.useCaseDocumentInfo();
            assertTrue(info.loaded());
            assertEquals(Source.REMOTE, info.source());
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
