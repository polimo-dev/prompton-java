package dev.polimo.prompton;

import java.util.Map;

/** Per-call overrides for {@link UseCase#request(Map, RequestOptions)}. */
public final class RequestOptions {

    private final Map<String, Object> params;
    private final Map<String, Object> providerOptions;
    private final String sessionId;
    private final Map<String, Object> trace;
    private final String user;

    private RequestOptions(Builder builder) {
        this.params = builder.params;
        this.providerOptions = builder.providerOptions;
        this.sessionId = builder.sessionId;
        this.trace = builder.trace;
        this.user = builder.user;
    }

    /** Empty request options. */
    public static RequestOptions empty() {
        return builder().build();
    }

    /** A new builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Parameters layered over deployment params. */
    public Map<String, Object> params() {
        return params;
    }

    /** Provider routing/options layered over deployment provider options. */
    public Map<String, Object> providerOptions() {
        return providerOptions;
    }

    /** Decisions-only session id. */
    public String sessionId() {
        return sessionId;
    }

    /** Decisions-only trace metadata. */
    public Map<String, Object> trace() {
        return trace;
    }

    /** Decisions-only user id. */
    public String user() {
        return user;
    }

    /** Assembles request options. */
    public static final class Builder {
        private Map<String, Object> params;
        private Map<String, Object> providerOptions;
        private String sessionId;
        private Map<String, Object> trace;
        private String user;

        private Builder() {}

        /** @param value parameters layered over deployment params */
        public Builder params(Map<String, Object> value) {
            this.params = value;
            return this;
        }

        /** @param value provider routing/options layered over deployment provider options */
        public Builder providerOptions(Map<String, Object> value) {
            this.providerOptions = value;
            return this;
        }

        /** @param value Decisions-only session id */
        public Builder sessionId(String value) {
            this.sessionId = value;
            return this;
        }

        /** @param value Decisions-only trace metadata */
        public Builder trace(Map<String, Object> value) {
            this.trace = value;
            return this;
        }

        /** @param value Decisions-only user id */
        public Builder user(String value) {
            this.user = value;
            return this;
        }

        /** Builds the options. */
        public RequestOptions build() {
            return new RequestOptions(this);
        }
    }
}
