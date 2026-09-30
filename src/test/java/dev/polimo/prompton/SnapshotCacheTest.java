package dev.polimo.prompton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.polimo.prompton.internal.Json;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
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

/** Demand config fetch: per-key TTL, attempt gate, stale fallback and single-flight. */
class SnapshotCacheTest {

    @TempDir
    Path tempDir;

    private StubServer server;
    private AtomicReference<Instant> clock;

    @BeforeEach
    void startServer() {
        server = new StubServer();
        clock = new AtomicReference<>(Instant.parse("2026-09-04T09:00:00Z"));
        SnapshotStore.useClockForTests(clock::get);
    }

    @AfterEach
    void stopServer() {
        SnapshotStore.useClockForTests(null);
        server.close();
    }

    private void advanceMillis(long millis) {
        clock.updateAndGet(now -> now.plusMillis(millis));
    }

    private PromptOnConfig.Builder config() {
        return PromptOnConfig.builder()
                .apiKey("ptn_sdkfixture_test")
                .baseUrl(server.baseUrl())
                .environment("production")
                .project("sdkfixture")
                .diskCachePath(tempDir.resolve("snapshot.json"))
                .requestTimeout(Duration.ofSeconds(2));
    }

    @Test
    void startupAndIdleDoNotFetchUntilAKeyIsResolved() {
        server.handle(request -> StubServer.Reply.ok(Fixtures.production()).withHeader("etag", "\"v1\""));
        try (PromptOn prompton = PromptOn.create(config().build())) {
            assertTrue(server.requests().isEmpty(), "constructor must not contact PromptOn");
            assertEquals("openai/gpt-4o-mini", prompton.useCase("greeting").model());
            assertEquals(1, server.requests().size());
            assertEquals("/api/v1/prompts/greeting", server.requests().get(0).path());
            assertEquals("environment=production", server.requests().get(0).query());
        }
    }

    @Test
    void freshCacheHitDoesNotFetchAgainWithinTenSeconds() {
        server.handle(request -> StubServer.Reply.ok(Fixtures.production()).withHeader("etag", "\"v1\""));
        try (PromptOn prompton = PromptOn.create(config().cacheTtl(Duration.ofSeconds(10)).build())) {
            repeatUseCase(prompton, "greeting", 5);
            assertEquals(1, server.requests().size());
        }
    }

    @Test
    void expiredKeyFetchesWithItsOwnEtagAndOtherKeysAreSeparate() throws Exception {
        server.handle(request -> {
            if ("\"greeting-v1\"".equals(request.header("if-none-match"))) {
                return StubServer.Reply.status(304);
            }
            return StubServer.Reply.ok(Fixtures.production())
                    .withHeader("etag", request.path().endsWith("/summarize")
                            ? "\"summarize-v1\"" : "\"greeting-v1\"");
        });
        try (PromptOn prompton = PromptOn.create(config().cacheTtl(Duration.ofMillis(40)).build())) {
            prompton.useCase("greeting");
            prompton.useCase("summarize");
            advanceMillis(10_100);
            prompton.useCase("greeting");

            assertEquals(3, server.requests().size());
            assertEquals("/api/v1/prompts/greeting", server.requests().get(2).path());
            assertEquals("\"greeting-v1\"", server.requests().get(2).header("if-none-match"));
        }
    }

    @Test
    void fetchingAnotherKeyDoesNotMutateFreshCachedKey() {
        server.handle(request -> {
            if (request.path().endsWith("/summarize")) {
                return StubServer.Reply.ok(Fixtures.productionV2()).withHeader("etag", "\"summarize-v2\"");
            }
            return StubServer.Reply.ok(Fixtures.production()).withHeader("etag", "\"greeting-v1\"");
        });
        try (PromptOn prompton = PromptOn.create(config().cacheTtl(Duration.ofSeconds(10)).build())) {
            assertEquals("You are a friendly greeter.",
                    prompton.useCase("greeting").messages().get(0).content());
            assertEquals("Summarize:\n{% for item in items %}- {{ item }}\n{% endfor %}",
                    prompton.useCase("summarize").textTemplate());
            assertEquals("You are a friendly greeter.",
                    prompton.useCase("greeting").messages().get(0).content(),
                    "a summarize fetch must not aggregate-merge and mutate greeting's fresh cache");
            assertEquals(2, server.requests().size());
        }
    }

    @Test
    void failedFetchStartsTheTenSecondAttemptGateAndReturnsStale() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server.handle(request -> calls.incrementAndGet() == 1
                ? StubServer.Reply.ok(Fixtures.production()).withHeader("etag", "\"v1\"")
                : StubServer.Reply.of(503, "{\"error\":{\"code\":\"unavailable\"}}"));
        try (PromptOn prompton = PromptOn.create(config().cacheTtl(Duration.ofMillis(80)).build())) {
            prompton.useCase("greeting");
            advanceMillis(10_100);
            assertEquals("openai/gpt-4o-mini", prompton.useCase("greeting").model());
            assertEquals(2, calls.get());
            assertTrue(prompton.useCaseDocumentInfo().stale());

            repeatUseCase(prompton, "greeting", 5);
            assertEquals(2, calls.get(), "failed attempt gates the key until the TTL elapses");
        }
    }

    @Test
    void timeoutFallsBackToExpiredCacheWithinOneSecondAndIgnoresLateResponse() throws Exception {
        SnapshotStore.useClockForTests(null);
        Path bundle = tempDir.resolve("prompts.production.json");
        assertTrue(DiskCache.write(bundle, Fixtures.production(),
                Map.of("environment", "production", "project", "sdkfixture")));
        server.handle(request -> {
            sleep(1_500);
            return StubServer.Reply.ok(Fixtures.productionV2()).withHeader("etag", "\"late\"");
        });
        try (PromptOn prompton = PromptOn.create(config()
                .diskCacheEnabled(false)
                .bundlePath(bundle)
                .requestTimeout(Duration.ofSeconds(5))
                .build())) {
            long started = System.nanoTime();
            assertEquals("You are a friendly greeter.",
                    prompton.useCase("greeting").messages().get(0).content());
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            assertTrue(elapsedMs < 1_250, "stale fallback waited " + elapsedMs + "ms");
            Thread.sleep(700);
            assertEquals("You are a friendly greeter.",
                    prompton.useCase("greeting").messages().get(0).content());
        }
    }

    @Test
    void coldFailureWithoutAnyFallbackIsExplicit() {
        server.handle(request -> StubServer.Reply.of(503, "{\"error\":{\"code\":\"unavailable\"}}"));
        try (PromptOn prompton = PromptOn.create(config().diskCacheEnabled(false).build())) {
            UseCaseException error = assertThrows(UseCaseException.class, () -> prompton.useCase("greeting"));
            assertEquals(UseCaseException.Reason.NOT_READY, error.reason());
            assertEquals(1, server.requests().size());
            assertThrows(UseCaseException.class, () -> prompton.useCase("greeting"));
            assertEquals(1, server.requests().size(), "cold failure is also gated");
        }
    }

    @Test
    void sameKeyConcurrentCallersShareOneFetch() throws Exception {
        server.handle(request -> {
            sleep(150);
            return StubServer.Reply.ok(Fixtures.production()).withHeader("etag", "\"v1\"");
        });
        try (PromptOn prompton = PromptOn.create(config().diskCacheEnabled(false).build())) {
            runConcurrent(24, () -> assertEquals("openai/gpt-4o-mini",
                    prompton.useCase("greeting").model()));
            assertEquals(1, server.requests().size());
        }
    }

    @Test
    void differentKeysDoNotWaitBehindEachOther() throws Exception {
        server.handle(request -> {
            if (request.path().endsWith("/greeting")) {
                sleep(900);
            }
            return StubServer.Reply.ok(Fixtures.production()).withHeader("etag", "\"" + request.path() + "\"");
        });
        try (PromptOn prompton = PromptOn.create(config().diskCacheEnabled(false).build())) {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                var slow = pool.submit(() -> prompton.useCase("greeting").model());
                Thread.sleep(50);
                long started = System.nanoTime();
                assertEquals("openai/gpt-4o-mini", prompton.useCase("summarize").model());
                long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
                assertTrue(elapsedMs < 400, "summarize waited behind greeting for " + elapsedMs + "ms");
                assertEquals("openai/gpt-4o-mini", slow.get(3, TimeUnit.SECONDS));
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void scopeMismatchIsRejectedAndDiskFallbackKeepsServing() throws Exception {
        Path cache = tempDir.resolve("snapshot.json");
        Files.writeString(cache, Fixtures.production(), StandardCharsets.UTF_8);
        Files.writeString(DiskCache.metaPath(cache),
                "{\"etag\":\"\\\"disk\\\"\",\"environment\":\"production\","
                        + "\"project\":\"sdkfixture\"}", StandardCharsets.UTF_8);
        server.handle(request -> StubServer.Reply.ok(Fixtures.staging()).withHeader("etag", "\"bad\""));
        try (PromptOn prompton = PromptOn.create(config().diskCachePath(cache)
                .cacheTtl(Duration.ofMillis(50)).build())) {
            advanceMillis(10_100);
            assertEquals(Source.DISK, prompton.useCase("greeting").source());
            assertEquals("openai/gpt-4o-mini", prompton.useCase("greeting").model());
            assertTrue(prompton.useCaseDocumentInfo().stale());
        }
    }

    @Test
    void fetchedPromptIsMirroredToDiskWithSidecar() {
        server.handle(request -> StubServer.Reply.ok(Fixtures.production())
                .withHeader("etag", "\"sha256-v1\"")
                .withHeader("last-modified", "Fri, 04 Sep 2026 00:21:48 GMT"));
        Path cache = tempDir.resolve("snapshot.json");
        try (PromptOn prompton = PromptOn.create(config().diskCachePath(cache).build())) {
            prompton.useCase("greeting");
        }
        assertTrue(Files.exists(cache));
        Map<String, Object> root = readJson(cache);
        Map<String, Object> entries = Json.mapAt(root, "entries");
        assertTrue(entries.containsKey("greeting"));
        Map<String, Object> saved = Conformance.map(entries.get("greeting"));
        Map<String, Object> meta = Conformance.map(saved.get("meta"));
        assertEquals("\"sha256-v1\"", meta.get("etag"));
        assertEquals("production", meta.get("environment"));
        assertEquals("sdkfixture", meta.get("project"));
    }

    @Test
    void restartLoadsEachPersistedPromptFromItsOwnDiskEntryWhenRemoteFails() {
        Path cache = tempDir.resolve("snapshot.json");
        server.handle(request -> {
            if (request.path().endsWith("/summarize")) {
                return StubServer.Reply.ok(Fixtures.productionV2()).withHeader("etag", "\"summarize-v2\"");
            }
            return StubServer.Reply.ok(Fixtures.production()).withHeader("etag", "\"greeting-v1\"");
        });
        try (PromptOn prompton = PromptOn.create(config().diskCachePath(cache).build())) {
            assertEquals("You are a friendly greeter.",
                    prompton.useCase("greeting").messages().get(0).content());
            assertEquals("Summarize:\n{% for item in items %}- {{ item }}\n{% endfor %}",
                    prompton.useCase("summarize").textTemplate());
        }

        server.close();
        server = new StubServer();
        server.handle(request -> StubServer.Reply.of(503, "{\"error\":{\"code\":\"unavailable\"}}"));

        try (PromptOn prompton = PromptOn.create(config().diskCachePath(cache).build())) {
            assertEquals("You are a friendly greeter.",
                    prompton.useCase("greeting").messages().get(0).content());
            assertEquals("Summarize:\n{% for item in items %}- {{ item }}\n{% endfor %}",
                    prompton.useCase("summarize").textTemplate());
            assertEquals(2, Json.mapAt(readJson(cache), "entries").size());
        }
    }

    @Test
    void refreshDoesNotRestoreBulkPolling() {
        server.handle(request -> StubServer.Reply.ok(Fixtures.production()).withHeader("etag", "\"v1\""));
        try (PromptOn prompton = PromptOn.create(config().build())) {
            assertEquals(RefreshResult.SKIPPED, prompton.refresh());
            assertTrue(server.requests().isEmpty());
        }
    }

    private static void repeatUseCase(PromptOn prompton, String key, int count) {
        for (int i = 0; i < count; i++) {
            prompton.useCase(key);
        }
    }

    private static void runConcurrent(int threads, ThrowingRunnable runnable) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        runnable.run();
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Map<String, Object> readJson(Path path) {
        try {
            return Json.parseObject(Files.readString(path));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
