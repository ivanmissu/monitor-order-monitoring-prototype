package com.monitor.sdk.spring;

import com.monitor.sdk.ReporterConfig;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code monitor.sdk.*} 配置绑定。 */
@ConfigurationProperties(prefix = "monitor.sdk")
public class MonitorSdkProperties {

    /** 显式开启，默认关闭，避免应用升级 Starter 后意外发出遥测数据。 */
    private boolean enabled = false;
    /** 完整上报地址，例如 https://monitor.example.com/api/v1/ingest/events。 */
    private String endpoint;
    /** 仅限 INGEST 权限的短期 token；推荐通过环境变量注入。 */
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
    private String userAgent = "monitor-metrics-spring-boot-starter/1.0";

    public ReporterConfig toReporterConfig() {
        return ReporterConfig.builder()
                .endpoint(endpoint)
                .token(token)
                .batchSize(batchSize)
                .maxBatchBytes(maxBatchBytes)
                .flushInterval(flushInterval)
                .queueCapacity(queueCapacity)
                .maxRetries(maxRetries)
                .initialRetryBackoff(initialRetryBackoff)
                .maxRetryBackoff(maxRetryBackoff)
                .connectTimeout(connectTimeout)
                .requestTimeout(requestTimeout)
                .shutdownTimeout(shutdownTimeout)
                .userAgent(userAgent)
                .build();
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public int getMaxBatchBytes() { return maxBatchBytes; }
    public void setMaxBatchBytes(int maxBatchBytes) { this.maxBatchBytes = maxBatchBytes; }
    public Duration getFlushInterval() { return flushInterval; }
    public void setFlushInterval(Duration flushInterval) { this.flushInterval = flushInterval; }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public Duration getInitialRetryBackoff() { return initialRetryBackoff; }
    public void setInitialRetryBackoff(Duration initialRetryBackoff) { this.initialRetryBackoff = initialRetryBackoff; }
    public Duration getMaxRetryBackoff() { return maxRetryBackoff; }
    public void setMaxRetryBackoff(Duration maxRetryBackoff) { this.maxRetryBackoff = maxRetryBackoff; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
    public Duration getShutdownTimeout() { return shutdownTimeout; }
    public void setShutdownTimeout(Duration shutdownTimeout) { this.shutdownTimeout = shutdownTimeout; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
}
