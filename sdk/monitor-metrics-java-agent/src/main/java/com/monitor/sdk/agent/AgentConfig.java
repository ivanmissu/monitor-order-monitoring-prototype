package com.monitor.sdk.agent;

import com.monitor.sdk.ReporterConfig;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Java Agent 启动参数与系统属性的解析器。 */
final class AgentConfig {

    private final boolean enabled;
    private final ReporterConfig reporterConfig;
    private final String defaultBizLine;

    private AgentConfig(boolean enabled, ReporterConfig reporterConfig, String defaultBizLine) {
        this.enabled = enabled;
        this.reporterConfig = reporterConfig;
        this.defaultBizLine = defaultBizLine;
    }

    static AgentConfig from(String agentArgs) {
        Map<String, String> args = parse(agentArgs);
        boolean enabled = bool(value(args, "enabled"), true);
        if (!enabled) {
            return new AgentConfig(false, null, null);
        }

        String endpoint = required(value(args, "endpoint"), "endpoint");
        String token = required(value(args, "token"), "token");
        ReporterConfig config = ReporterConfig.builder()
                .endpoint(endpoint)
                .token(token)
                .batchSize(integer(value(args, "batchSize"), 500, "batchSize"))
                .maxBatchBytes(integer(value(args, "maxBatchBytes"), 1_800_000, "maxBatchBytes"))
                .flushInterval(Duration.ofMillis(integer(value(args, "flushIntervalMs"), 2_000, "flushIntervalMs")))
                .queueCapacity(integer(value(args, "queueCapacity"), 10_000, "queueCapacity"))
                .maxRetries(integer(value(args, "maxRetries"), 3, "maxRetries"))
                .connectTimeout(Duration.ofMillis(integer(value(args, "connectTimeoutMs"), 2_000, "connectTimeoutMs")))
                .requestTimeout(Duration.ofMillis(integer(value(args, "requestTimeoutMs"), 5_000, "requestTimeoutMs")))
                .userAgent(defaulted(value(args, "userAgent"), "monitor-metrics-java-agent/1.0"))
                .build();
        return new AgentConfig(true, config, blankToNull(value(args, "defaultBizLine")));
    }

    boolean isEnabled() {
        return enabled;
    }

    ReporterConfig getReporterConfig() {
        return reporterConfig;
    }

    String getDefaultBizLine() {
        return defaultBizLine;
    }

    private static Map<String, String> parse(String source) {
        if (source == null || source.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> values = new LinkedHashMap<String, String>();
        String[] pairs = source.split(",");
        for (String pair : pairs) {
            int index = pair.indexOf('=');
            if (index <= 0 || index == pair.length() - 1) {
                throw new IllegalArgumentException("agent argument must be key=value: " + pair);
            }
            values.put(pair.substring(0, index).trim(), pair.substring(index + 1).trim());
        }
        return values;
    }

    /** 参数优先；其次 JVM 属性；最后环境变量。令牌建议经环境变量或 JVM 密钥注入。 */
    private static String value(Map<String, String> args, String key) {
        String configured = args.get(key);
        if (!isBlank(configured)) {
            return configured;
        }
        configured = System.getProperty("monitor.sdk." + key);
        if (!isBlank(configured)) {
            return configured;
        }
        return System.getenv(toEnvironmentKey(key));
    }

    private static String toEnvironmentKey(String key) {
        StringBuilder env = new StringBuilder("MONITOR_SDK_");
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (Character.isUpperCase(c)) {
                env.append('_').append(c);
            } else {
                env.append(Character.toUpperCase(c));
            }
        }
        return env.toString();
    }

    private static boolean bool(String value, boolean fallback) {
        return isBlank(value) ? fallback : Boolean.parseBoolean(value);
    }

    private static int integer(String value, int fallback, String key) {
        if (isBlank(value)) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(key + " must be an integer", ex);
        }
    }

    private static String required(String value, String key) {
        if (isBlank(value)) {
            throw new IllegalArgumentException("monitor.sdk." + key + " is required when agent is enabled");
        }
        return value;
    }

    private static String defaulted(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
