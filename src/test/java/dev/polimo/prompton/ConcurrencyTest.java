package dev.polimo.prompton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.polimo.prompton.internal.Json;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
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

/** One client, many threads — and one disk file, many processes. */
class ConcurrencyTest {

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

    @Test
    void manyThreadsResolveRenderAndLogAgainstOneClient() throws Exception {
        server.handle(request -> {
            if (request.path().endsWith("/use-cases")) {
                return StubServer.Reply.ok(Fixtures.production()).withHeader("etag", "\"v1\"");
            }
            int count = Json.listAt(Json.parseObject(request.body()), "logs").size();
            return StubServer.Reply.of(202,
                    "{\"accepted\":" + count + ",\"duplicates\":0,\"rejected\":[]}");
        });

        int threads = 16;
        int perThread = 50;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        try (PromptOn prompton = PromptOn.create(PromptOnConfig.builder()
                .apiKey("ptn_sdkfixture_test")
                .baseUrl(server.baseUrl())
                .environment("production")
                .project("sdkfixture")
                .diskCacheEnabled(false)
                .pollingEnabled(true)
                .cacheTtl(Duration.ofMillis(20))
                .logFlushInterval(Duration.ofMillis(20))
                .build())) {
            prompton.useCase("greeting");
            for (int t = 0; t < threads; t++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            UseCase pin = prompton.useCase("greeting", i % 2 == 0 ? null : "ko");
                            List<Message> messages =
                                    pin.messages(Map.of("name", "Ada"));
                            assertEquals(2, messages.size());
                            prompton.log(LogRecord.builder()
                                    .useCase(pin)
                                    .status(LogRecord.Status.OK)
                                    .startedAt(Instant.now())
                                    .latencyMs(1L)
                                    .build());
                        }
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(60, TimeUnit.SECONDS), "the workers did not finish");
            assertEquals(null, failure.get(), String.valueOf(failure.get()));

            prompton.flush(Duration.ofSeconds(20));
            LogStats stats = prompton.logStats();
            assertEquals(0, stats.queued());
            assertEquals(0, stats.droppedFull() + stats.droppedFailed() + stats.droppedRejected());
            assertEquals((long) threads * perThread, stats.sent());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aSlowRefreshNeverBlocksOrFailsAGeneration() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server.handle(request -> {
            if (calls.incrementAndGet() > 1) {
                sleep(400);
            }
            return StubServer.Reply.ok(Fixtures.production()).withHeader("etag", "\"v" + calls.get() + "\"");
        });
        try (PromptOn prompton = PromptOn.create(PromptOnConfig.builder()
                .apiKey("k").baseUrl(server.baseUrl()).environment("production")
                .project("sdkfixture").diskCacheEnabled(false)
                .pollingEnabled(false).cacheTtl(Duration.ofMillis(10))
                .requestTimeout(Duration.ofSeconds(5)).build())) {
            prompton.useCase("greeting");
            Thread.sleep(30);

            long startedAt = System.nanoTime();
            for (int i = 0; i < 200; i++) {
                assertNotNull(prompton.useCase("greeting").model());
            }
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
            assertTrue(elapsedMillis < 300,
                    "resolving while a refresh is in flight took " + elapsedMillis + "ms");
        }
    }

    @Test
    void severalWritersShareOneDiskFileWithoutAReaderEverSeeingAHalfWrite() throws Exception {
        Path path = tempDir.resolve("shared").resolve("snapshot.json");
        assertTrue(DiskCache.write(path, Fixtures.production(), Map.of("etag", "\"v1\"")));

        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger reads = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(6);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            for (int w = 0; w < 3; w++) {
                pool.execute(() -> {
                    try {
                        for (int i = 0; i < 100; i++) {
                            DiskCache.write(path, Fixtures.productionV2(),
                                    Map.of("etag", "\"v2\"", "environment", "production"));
                        }
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            for (int r = 0; r < 3; r++) {
                pool.execute(() -> {
                    try {
                        for (int i = 0; i < 100; i++) {
                            DiskCache.Stored stored = DiskCache.read(path);
                            if (stored != null) {
                                assertEquals("production", UseCaseDocument.parse(stored.body()).environment());
                                reads.incrementAndGet();
                            }
                        }
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(60, TimeUnit.SECONDS));
            assertEquals(null, failure.get(), String.valueOf(failure.get()));
            assertTrue(reads.get() > 0, "the readers never managed to read the file");
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
}
