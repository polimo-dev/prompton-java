package dev.polimo.prompton;

import dev.polimo.prompton.http.PromptOnHttpClient;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Everything the SDK needs to know, resolved once at construction.
 *
 * <p>Precedence for every setting is <strong>explicit option &gt; environment variable &gt;
 * default</strong>. The environment variables are {@code PTN_API_KEY}, {@code PTN_HOST},
 * {@code PTN_ENVIRONMENT} and {@code PTN_PROJECT}.
 *
 * <p>Without an API key the SDK makes no remote calls at all: it resolves from the disk cache or
 * the bundled snapshot and says so once in a log line.
 */
public final class PromptOnConfig {

    /** The host used when neither an option nor {@code PTN_HOST} says otherwise. */
    public static final String DEFAULT_HOST = "https://app.prompton.ai";

    /** The environment used when nothing says otherwise. */
    public static final String DEFAULT_ENVIRONMENT = "production";

    /** The SDK's name, as sent in each record's {@code sdk} field and in the User-Agent. */
    public static final String SDK_NAME = "prompton-java";

    /** The SDK's version. */
    public static final String SDK_VERSION = "0.1.0";

    private final String apiKey;
    private final String baseUrl;
    private final String environment;
    private final String project;
    private final Duration cacheTtl;
    private final Duration requestTimeout;
    private final Duration connectTimeout;
    private final Duration initialFetchTimeout;
    private final Duration maxBackoff;
    private final Path diskCachePath;
    private final Path bundlePath;
    private final Mode mode;
    private final boolean hashEndUser;
    private final UnaryOperator<Map<String, Object>> redact;
    private final int logFlushSize;
    private final int logFlushBytes;
    private final Duration logFlushInterval;
    private final int logMaxBuffer;
    private final int logMaxAttempts;
    private final Duration shutdownFlushTimeout;
    private final boolean pollingEnabled;
    private final PromptOnHttpClient httpClient;
    private final PayloadPolicy payloadDefaults;
    private final boolean ownsHttpClient;

    private PromptOnConfig(Builder b, Derived derived) {
        this.apiKey = b.apiKey;
        this.baseUrl = derived.baseUrl;
        this.environment = derived.environment;
        this.project = derived.project;
        this.cacheTtl = b.cacheTtl;
        this.requestTimeout = b.requestTimeout;
        this.connectTimeout = b.connectTimeout;
        this.initialFetchTimeout = b.initialFetchTimeout;
        this.maxBackoff = b.maxBackoff;
        this.diskCachePath = derived.diskCachePath;
        this.bundlePath = b.bundlePath;
        this.mode = b.mode;
        this.hashEndUser = b.hashEndUser;
        this.redact = b.redact;
        this.logFlushSize = b.logFlushSize;
        this.logFlushBytes = b.logFlushBytes;
        this.logFlushInterval = b.logFlushInterval;
        this.logMaxBuffer = b.logMaxBuffer;
        this.logMaxAttempts = b.logMaxAttempts;
        this.shutdownFlushTimeout = b.shutdownFlushTimeout;
        this.pollingEnabled = b.pollingEnabled;
        this.httpClient = derived.httpClient;
        this.payloadDefaults = b.payloadDefaults;
        this.ownsHttpClient = derived.ownsHttpClient;
    }

    /**
     * What {@link Builder#build()} works out from the settings, kept out of the builder's own fields
     * so that a builder can be reused: two configurations built from one builder, differing only in
     * {@code environment}, must not share a base URL, a disk-cache path or an HTTP client.
     */
    private record Derived(
            String baseUrl,
            String environment,
            String project,
            Path diskCachePath,
            PromptOnHttpClient httpClient,
            boolean ownsHttpClient) {}

    /** A configuration builder pre-filled from the environment. */
    public static Builder builder() {
        return new Builder();
    }

    /** The runtime API key, or {@code null} when the SDK must work offline. */
    public String apiKey() {
        return apiKey;
    }

    /** The API base, host plus {@code /api/v1}, with no trailing slash. */
    public String baseUrl() {
        return baseUrl;
    }

    /** Which environment this process reads. */
    public String environment() {
        return environment;
    }

    /** The project slug, used to name the disk cache and to guard against a foreign snapshot. */
    public String project() {
        return project;
    }

    /** How long a snapshot is served from memory before a refresh is due. */
    public Duration cacheTtl() {
        return cacheTtl;
    }

    /** The per-request read timeout. */
    public Duration requestTimeout() {
        return requestTimeout;
    }

    /** The connect timeout of the default HTTP client. */
    public Duration connectTimeout() {
        return connectTimeout;
    }

    /** How long the first resolve waits for the very first snapshot fetch. */
    public Duration initialFetchTimeout() {
        return initialFetchTimeout;
    }

    /** The ceiling of the exponential backoff after a failed poll or a failed send. */
    public Duration maxBackoff() {
        return maxBackoff;
    }

    /** Where the snapshot is mirrored on disk, or {@code null} when the disk cache is off. */
    public Path diskCachePath() {
        return diskCachePath;
    }

    /** The snapshot shipped inside the application, or {@code null}. */
    public Path bundlePath() {
        return bundlePath;
    }

    /** Live, test or offline. */
    public Mode mode() {
        return mode;
    }

    /** Whether {@code end_user_ref} is sent as a sha256 digest. */
    public boolean hashEndUser() {
        return hashEndUser;
    }

    /** The application's redact hook, applied to every record last. */
    public UnaryOperator<Map<String, Object>> redact() {
        return redact;
    }

    /** Flush the log buffer once this many records are queued. */
    public int logFlushSize() {
        return logFlushSize;
    }

    /** Flush the log buffer once this many bytes are queued. */
    public int logFlushBytes() {
        return logFlushBytes;
    }

    /** Flush the log buffer at least this often. */
    public Duration logFlushInterval() {
        return logFlushInterval;
    }

    /** The queue cap; over it the oldest records are dropped and counted. */
    public int logMaxBuffer() {
        return logMaxBuffer;
    }

    /** How many times one batch is retried before it is dropped and counted. */
    public int logMaxAttempts() {
        return logMaxAttempts;
    }

    /** How long {@link PromptOn#close()} spends draining the log buffer. */
    public Duration shutdownFlushTimeout() {
        return shutdownFlushTimeout;
    }

    /** Whether the background poll loop runs. Off means refreshes happen on the next resolve. */
    public boolean pollingEnabled() {
        return pollingEnabled;
    }

    /** The HTTP client PromptOn's own calls go through. */
    public PromptOnHttpClient httpClient() {
        return httpClient;
    }

    /** The payload policy used when the snapshot declares none. */
    public PayloadPolicy payloadDefaults() {
        return payloadDefaults;
    }

    /** Whether the SDK created the HTTP client, and may therefore close it. */
    public boolean ownsHttpClient() {
        return ownsHttpClient;
    }

    /** Whether remote calls are possible: live mode, an API key and a base URL. */
    public boolean remoteEnabled() {
        return mode == Mode.LIVE && apiKey != null && !apiKey.isBlank();
    }

    /** The {@code User-Agent} the SDK sends. */
    public String userAgent() {
        return SDK_NAME + "/" + SDK_VERSION;
    }

    /** Assembles a {@link PromptOnConfig}. */
    public static final class Builder {
        private String apiKey = env("PTN_API_KEY");
        private String host = envOr("PTN_HOST", DEFAULT_HOST);
        private String baseUrl;
        private String environment = envOr("PTN_ENVIRONMENT", DEFAULT_ENVIRONMENT);
        private String project = env("PTN_PROJECT");
        private Duration cacheTtl = Duration.ofSeconds(10);
        private Duration requestTimeout = Duration.ofSeconds(5);
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration initialFetchTimeout = Duration.ofSeconds(3);
        private Duration maxBackoff = Duration.ofMinutes(5);
        private Path diskCachePath;
        private boolean diskCacheEnabled = true;
        private Path bundlePath;
        private Mode mode = Mode.LIVE;
        private boolean hashEndUser;
        private UnaryOperator<Map<String, Object>> redact;
        private int logFlushSize = 100;
        private int logFlushBytes = 1_000_000;
        private Duration logFlushInterval = Duration.ofSeconds(2);
        private int logMaxBuffer = 10_000;
        private int logMaxAttempts = 8;
        private Duration shutdownFlushTimeout = Duration.ofSeconds(5);
        private boolean pollingEnabled = true;
        private PromptOnHttpClient httpClient;
        private PayloadPolicy payloadDefaults = PayloadPolicy.DEFAULT;

        private Builder() {}

        /** @param value the runtime API key ({@code ptn_<project>_…}) */
        public Builder apiKey(String value) {
            this.apiKey = blankToNull(value);
            return this;
        }

        /** @param value the PromptOn host; the SDK appends {@code /api/v1} */
        public Builder host(String value) {
            this.host = blankToNull(value);
            return this;
        }

        /** @param value the full API base, when it is not {@code host + "/api/v1"} */
        public Builder baseUrl(String value) {
            this.baseUrl = blankToNull(value);
            return this;
        }

        /** @param value which environment this process reads */
        public Builder environment(String value) {
            this.environment = blankToNull(value);
            return this;
        }

        /** @param value the project slug */
        public Builder project(String value) {
            this.project = blankToNull(value);
            return this;
        }

        /** @param value how long a snapshot is served from memory before a refresh is due */
        public Builder cacheTtl(Duration value) {
            this.cacheTtl = value;
            return this;
        }

        /** @param value the per-request read timeout */
        public Builder requestTimeout(Duration value) {
            this.requestTimeout = value;
            return this;
        }

        /** @param value the connect timeout of the default HTTP client */
        public Builder connectTimeout(Duration value) {
            this.connectTimeout = value;
            return this;
        }

        /** @param value how long the first resolve waits for the first fetch */
        public Builder initialFetchTimeout(Duration value) {
            this.initialFetchTimeout = value;
            return this;
        }

        /** @param value the backoff ceiling */
        public Builder maxBackoff(Duration value) {
            this.maxBackoff = value;
            return this;
        }

        /** @param value where to mirror the snapshot on disk */
        public Builder diskCachePath(Path value) {
            this.diskCachePath = value;
            this.diskCacheEnabled = value != null;
            return this;
        }

        /** @param value {@code false} to keep the SDK entirely in memory */
        public Builder diskCacheEnabled(boolean value) {
            this.diskCacheEnabled = value;
            return this;
        }

        /** @param value a snapshot file shipped inside the application */
        public Builder bundlePath(Path value) {
            this.bundlePath = value;
            return this;
        }

        /** @param value live, test or offline */
        public Builder mode(Mode value) {
            this.mode = value == null ? Mode.LIVE : value;
            return this;
        }

        /** @param value {@code true} to send {@code sha256(end_user_ref)} instead of the raw ref */
        public Builder hashEndUser(boolean value) {
            this.hashEndUser = value;
            return this;
        }

        /** @param value a hook applied to each record last, after truncation */
        public Builder redact(UnaryOperator<Map<String, Object>> value) {
            this.redact = value;
            return this;
        }

        /** @param value flush once this many records are queued */
        public Builder logFlushSize(int value) {
            this.logFlushSize = Math.max(1, value);
            return this;
        }

        /** @param value flush once this many bytes are queued */
        public Builder logFlushBytes(int value) {
            this.logFlushBytes = Math.max(1, value);
            return this;
        }

        /** @param value flush at least this often */
        public Builder logFlushInterval(Duration value) {
            this.logFlushInterval = value;
            return this;
        }

        /** @param value the queue cap, over which the oldest records are dropped */
        public Builder logMaxBuffer(int value) {
            this.logMaxBuffer = Math.max(1, value);
            return this;
        }

        /** @param value how many times a batch is retried before being dropped */
        public Builder logMaxAttempts(int value) {
            this.logMaxAttempts = Math.max(1, value);
            return this;
        }

        /** @param value how long {@link PromptOn#close()} spends draining */
        public Builder shutdownFlushTimeout(Duration value) {
            this.shutdownFlushTimeout = value;
            return this;
        }

        /** @param value {@code false} to refresh on the next resolve instead of on a timer */
        public Builder pollingEnabled(boolean value) {
            this.pollingEnabled = value;
            return this;
        }

        /** @param value the HTTP client PromptOn's own calls go through */
        public Builder httpClient(PromptOnHttpClient value) {
            this.httpClient = value;
            return this;
        }

        /** @param value the payload policy used when the snapshot declares none */
        public Builder payloadDefaults(PayloadPolicy value) {
            this.payloadDefaults = value == null ? PayloadPolicy.DEFAULT : value;
            return this;
        }

        /**
         * Resolves defaults and builds the configuration.
         *
         * <p>The builder is left exactly as it was, so it can be reused: build once for production
         * and again with {@code .environment("staging")} and the second configuration gets its own
         * disk-cache path, not the first one's.
         *
         * @return the configuration
         */
        public PromptOnConfig build() {
            String resolvedEnvironment = environment == null ? DEFAULT_ENVIRONMENT : environment;
            String resolvedProject = project == null ? projectFromApiKey(apiKey) : project;
            String resolvedBaseUrl = resolveBaseUrl();
            Path resolvedDiskCachePath = null;
            if (diskCacheEnabled) {
                resolvedDiskCachePath = diskCachePath != null
                        ? diskCachePath
                        : defaultDiskCachePath(resolvedProject, resolvedEnvironment);
            }
            boolean owned = httpClient == null;
            PromptOnHttpClient resolvedHttpClient = owned
                    ? new dev.polimo.prompton.http.JdkHttpClient(connectTimeout)
                    : httpClient;
            return new PromptOnConfig(this, new Derived(resolvedBaseUrl, resolvedEnvironment,
                    resolvedProject, resolvedDiskCachePath, resolvedHttpClient, owned));
        }

        private String resolveBaseUrl() {
            String base = baseUrl;
            if (base == null) {
                base = trimTrailingSlashes(host == null ? DEFAULT_HOST : host);
                return base.endsWith("/api/v1") ? base : base + "/api/v1";
            }
            return trimTrailingSlashes(base);
        }

        private static String trimTrailingSlashes(String value) {
            String trimmed = value;
            while (trimmed.endsWith("/")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            return trimmed;
        }
    }

    /** The project slug embedded in a {@code ptn_<project>_…} key, or {@code null}. */
    static String projectFromApiKey(String apiKey) {
        if (apiKey == null || !apiKey.startsWith("ptn_")) {
            return null;
        }
        int end = apiKey.indexOf('_', 4);
        return end > 4 ? apiKey.substring(4, end) : null;
    }

    /** {@code <os cache dir>/prompton/snapshot-<project>-<environment>.json}. */
    static Path defaultDiskCachePath(String project, String environment) {
        String slug = sanitize(project == null ? "default" : project)
                + "-" + sanitize(environment == null ? DEFAULT_ENVIRONMENT : environment);
        return osCacheDirectory().resolve("prompton").resolve("snapshot-" + slug + ".json");
    }

    private static Path osCacheDirectory() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home");
        if (os.contains("mac") && home != null) {
            return Paths.get(home, "Library", "Caches");
        }
        if (os.contains("win")) {
            String local = System.getenv("LOCALAPPDATA");
            if (local != null && !local.isBlank()) {
                return Paths.get(local);
            }
        }
        String xdg = System.getenv("XDG_CACHE_HOME");
        if (xdg != null && !xdg.isBlank()) {
            return Paths.get(xdg);
        }
        if (home != null && !home.isBlank()) {
            return Paths.get(home, ".cache");
        }
        return Paths.get(System.getProperty("java.io.tmpdir", "."));
    }

    private static String sanitize(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            out.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' ? c : '_');
        }
        return out.toString();
    }

    private static String env(String name) {
        return blankToNull(System.getenv(name));
    }

    private static String envOr(String name, String fallback) {
        String value = env(name);
        return value == null ? fallback : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
