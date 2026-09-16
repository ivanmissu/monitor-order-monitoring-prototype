package com.monitor.sdk;

import java.net.URI;
import java.time.Duration;

/** 异步 HTTP 上报客户端配置。 */
public final class ReporterConfig {

    private final URI endpoint;
    private final String token;
    private final int batchSize;
    private final int maxBatchBytes;
    private final Duration flushInterval;
    private final int queueCapacity;
    private final int maxRetries;
    private final Duration initialRetryBackoff;
    private final Duration maxRetryBackoff;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final Duration shutdownTimeout;
    private final String userAgent;
    private final DeliveryListener deliveryListener;

    private ReporterConfig(Builder builder) {
        this.endpoint = requireEndpoint(builder.endpoint);
        this.token = requireText(builder.token, "token");
        this.batchSize = inRange(builder.batchSize, 1, 500, "batchSize");
        this.maxBatchBytes = inRange(builder.maxBatchBytes, 1024, 2_000_000, "maxBatchBytes");
        this.flushInterval = requirePositive(builder.flushInterval, "flushInterval");
        this.queueCapacity = inRange(builder.queueCapacity, 1, 1_000_000, "queueCapacity");
        this.maxRetries = inRange(builder.maxRetries, 0, 100, "maxRetries");
        this.initialRetryBackoff = requirePositive(builder.initialRetryBackoff, "initialRetryBackoff");
        this.maxRetryBackoff = requirePositive(builder.maxRetryBackoff, "maxRetryBackoff");
        if (maxRetryBackoff.compareTo(initialRetryBackoff) < 0) {
            throw new IllegalArgumentException("maxRetryBackoff must be greater than or equal to initialRetryBackoff");
        }
        this.connectTimeout = requirePositive(builder.connectTimeout, "connectTimeout");
        this.requestTimeout = requirePositive(builder.requestTimeout, "requestTimeout");
        this.shutdownTimeout = requirePositive(builder.shutdownTimeout, "shutdownTimeout");
        this.userAgent = requireText(builder.userAgent, "userAgent");
        this.deliveryListener = builder.deliveryListener == null
                ? DeliveryListener.NOOP : builder.deliveryListener;
    }

    public static Builder builder() {
        return new Builder();
    }

    public URI getEndpoint() {
        return endpoint;
    }

    public String getToken() {
        return token;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public int getMaxBatchBytes() {
        return maxBatchBytes;
    }

    public Duration getFlushInterval() {
        return flushInterval;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public Duration getInitialRetryBackoff() {
        return initialRetryBackoff;
    }

    public Duration getMaxRetryBackoff() {
        return maxRetryBackoff;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public Duration getShutdownTimeout() {
        return shutdownTimeout;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public DeliveryListener getDeliveryListener() {
        return deliveryListener;
    }

    private static URI requireEndpoint(URI value) {
        if (value == null || value.getScheme() == null
                || !("http".equalsIgnoreCase(value.getScheme()) || "https".equalsIgnoreCase(value.getScheme()))
                || value.getHost() == null) {
            throw new IllegalArgumentException("endpoint must be an absolute http(s) URI");
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static int inRange(int value, int min, int max, String field) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(field + " must be between " + min + " and " + max);
        }
        return value;
    }

    private static Duration requirePositive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    public static final class Builder {
        private URI endpoint;
        private String token;
        private int batchSize = 500;
        private int maxBatchBytes = 1_800_000;
        private Duration flushInterval = Duration.ofSeconds(2);
        private int queueCapacity = 10_000;
        private int maxRetries = 3;
        private Duration initialRetryBackoff = Duration.ofMillis(200);
        private Duration maxRetryBackoff = Duration.ofSeconds(5);
        private Duration connectTimeout = Duration.ofSeconds(2);
        private Duration requestTimeout = Duration.ofSeconds(5);
        private Duration shutdownTimeout = Duration.ofSeconds(10);
        private String userAgent = "monitor-metrics-sdk/1.0";
        private DeliveryListener deliveryListener;

        private Builder() {
        }

        public Builder endpoint(String value) {
            this.endpoint = value == null ? null : URI.create(value);
            return this;
        }

        public Builder endpoint(URI value) {
            this.endpoint = value;
            return this;
        }

        public Builder token(String value) {
            this.token = value;
            return this;
        }

        public Builder batchSize(int value) {
            this.batchSize = value;
            return this;
        }

        public Builder maxBatchBytes(int value) {
            this.maxBatchBytes = value;
            return this;
        }

        public Builder flushInterval(Duration value) {
            this.flushInterval = value;
            return this;
        }

        public Builder queueCapacity(int value) {
            this.queueCapacity = value;
            return this;
        }

        public Builder maxRetries(int value) {
            this.maxRetries = value;
            return this;
        }

        public Builder initialRetryBackoff(Duration value) {
            this.initialRetryBackoff = value;
            return this;
        }

        public Builder maxRetryBackoff(Duration value) {
            this.maxRetryBackoff = value;
            return this;
        }

        public Builder connectTimeout(Duration value) {
            this.connectTimeout = value;
            return this;
        }

        public Builder requestTimeout(Duration value) {
            this.requestTimeout = value;
            return this;
        }

        public Builder shutdownTimeout(Duration value) {
            this.shutdownTimeout = value;
            return this;
        }

        public Builder userAgent(String value) {
            this.userAgent = value;
            return this;
        }

        public Builder deliveryListener(DeliveryListener value) {
            this.deliveryListener = value;
            return this;
        }

        public ReporterConfig build() {
            return new ReporterConfig(this);
        }
    }
}
