package dev.polimo.prompton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Configuration: precedence, derived values and the defaults the contract names. */
class PromptOnConfigTest {

    @Test
    void anExplicitOptionBeatsTheEnvironmentAndTheDefault() {
        PromptOnConfig config = PromptOnConfig.builder()
                .host("https://prompton.example")
                .environment("staging")
                .apiKey("ptn_myproject_secret")
                .build();
        assertEquals("https://prompton.example/api/v1", config.baseUrl());
        assertEquals("staging", config.environment());
        assertEquals("ptn_myproject_secret", config.apiKey());
    }

    @Test
    void theSdkAppendsApiV1AndTrimsTrailingSlashes() {
        assertEquals("https://app.prompton.ai/api/v1",
                PromptOnConfig.builder().host("https://app.prompton.ai/").build().baseUrl());
        assertEquals("https://app.prompton.ai/api/v1",
                PromptOnConfig.builder().host("https://app.prompton.ai/api/v1").build().baseUrl());
        assertEquals("http://localhost:4000/api/v1",
                PromptOnConfig.builder().baseUrl("http://localhost:4000/api/v1/").build().baseUrl());
    }

    @Test
    void theDefaultsAreTheOnesTheContractNames() {
        PromptOnConfig config = PromptOnConfig.builder().apiKey(null).build();
        assertEquals(Duration.ofSeconds(10), config.cacheTtl());
        assertEquals(Duration.ofMinutes(5), config.maxBackoff());
        assertEquals(200, Math.min(200, config.logFlushSize() * 2));
        assertEquals(10_000, config.logMaxBuffer());
        assertEquals(Mode.LIVE, config.mode());
        assertEquals(PayloadPolicy.DEFAULT, config.payloadDefaults());
        assertEquals("prompton-java/0.1.0", config.userAgent());
        if (System.getenv("PTN_HOST") == null) {
            assertEquals("https://app.prompton.ai/api/v1", config.baseUrl());
        }
        if (System.getenv("PTN_ENVIRONMENT") == null) {
            assertEquals("production", config.environment());
        }
    }

    @Test
    void theProjectIsReadOutOfTheApiKeyWhenItIsNotGiven() {
        assertEquals("heydiary", PromptOnConfig.projectFromApiKey("ptn_heydiary_abc123"));
        assertNull(PromptOnConfig.projectFromApiKey("not-a-prompton-key"));
        assertNull(PromptOnConfig.projectFromApiKey(null));
        assertEquals("heydiary", PromptOnConfig.builder()
                .apiKey("ptn_heydiary_abc123")
                .project(null)
                .build()
                .project());
    }

    @Test
    void theDiskCacheIsOnByDefaultAndNamedByProjectAndEnvironment() {
        PromptOnConfig config = PromptOnConfig.builder()
                .apiKey("ptn_heydiary_abc123")
                .project(null)
                .environment("staging")
                .build();
        Path path = config.diskCachePath();
        assertTrue(path.toString().endsWith("snapshot-heydiary-staging.json"), path.toString());
        assertTrue(path.getParent().toString().endsWith("prompton"), path.toString());
    }

    @Test
    void theDiskCacheCanBeTurnedOff() {
        assertNull(PromptOnConfig.builder().diskCacheEnabled(false).build().diskCachePath());
    }

    @Test
    void noApiKeyMeansNoRemoteCalls() {
        assertFalse(PromptOnConfig.builder().apiKey(null).build().remoteEnabled());
        assertFalse(PromptOnConfig.builder().apiKey("k").mode(Mode.OFFLINE).build().remoteEnabled());
        assertFalse(PromptOnConfig.builder().apiKey("k").mode(Mode.TEST).build().remoteEnabled());
        assertTrue(PromptOnConfig.builder().apiKey("k").build().remoteEnabled());
    }
}
