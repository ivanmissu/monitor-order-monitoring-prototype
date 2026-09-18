package com.monitor.sdk.agent;

import com.monitor.sdk.ReporterConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/** Java Agent 启动参数、配置文件与系统属性的解析器。 */
final class AgentConfig {

    private final boolean enabled;
    private final ReporterConfig reporterConfig;
    private final String bizLine;

    private AgentConfig(boolean enabled, ReporterConfig reporterConfig, String bizLine) {
        this.enabled = enabled;
        this.reporterConfig = reporterConfig;
        this.bizLine = bizLine;
    }

    static AgentConfig from(String agentArgs) {
        Map<String, String> args = parse(agentArgs);
        Map<String, String> fileProps = loadConfigFile(args);

        boolean enabled = bool(value(args, fileProps, "enabled"), true);
        if (!enabled) {
            return new AgentConfig(false, null, null);
        }

        String endpoint = required(value(args, fileProps, "endpoint"), "endpoint");
        String token = required(value(args, fileProps, "token"), "token");
        String bizLineVal = blankToNull(value(args, fileProps, "bizLine", "defaultBizLine"));

        ReporterConfig config = ReporterConfig.builder()
                .endpoint(endpoint)
                .token(token)
                .batchSize(integer(value(args, fileProps, "batchSize"), 500, "batchSize"))
                .maxBatchBytes(integer(value(args, fileProps, "maxBatchBytes"), 1_800_000, "maxBatchBytes"))
                .flushInterval(parseDuration(value(args, fileProps, "flushIntervalMs", "flushInterval"), Duration.ofMillis(2_000), "flushIntervalMs"))
                .queueCapacity(integer(value(args, fileProps, "queueCapacity"), 10_000, "queueCapacity"))
                .maxRetries(integer(value(args, fileProps, "maxRetries"), 3, "maxRetries"))
                .connectTimeout(parseDuration(value(args, fileProps, "connectTimeoutMs", "connectTimeout"), Duration.ofMillis(2_000), "connectTimeoutMs"))
                .requestTimeout(parseDuration(value(args, fileProps, "requestTimeoutMs", "requestTimeout"), Duration.ofMillis(5_000), "requestTimeoutMs"))
                .userAgent(defaulted(value(args, fileProps, "userAgent"), "monitor-metrics-java-agent/1.0"))
                .build();
        return new AgentConfig(true, config, bizLineVal);
    }

    boolean isEnabled() {
        return enabled;
    }

    ReporterConfig getReporterConfig() {
        return reporterConfig;
    }

    String getBizLine() {
        return bizLine;
    }

    String getDefaultBizLine() {
        return bizLine;
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

    /**
     * 参数取值优先级：
     * 1. Agent 启动参数（-javaagent:xxx.jar=key=val）
     * 2. JVM 系统属性（-Dmonitor.sdk.key=val）
     * 3. 环境变量（MONITOR_SDK_KEY=val）
     * 4. 配置文件（application.yml / application.properties / monitor-sdk.properties 等）
     */
    private static String value(Map<String, String> args, Map<String, String> fileProps, String... keys) {
        for (String key : keys) {
            String v = getFromArgs(args, key);
            if (!isBlank(v)) {
                return v;
            }
        }
        for (String key : keys) {
            String v = getFromSystemProperties(key);
            if (!isBlank(v)) {
                return v;
            }
        }
        for (String key : keys) {
            String v = getFromEnvironment(key);
            if (!isBlank(v)) {
                return v;
            }
        }
        if (fileProps != null && !fileProps.isEmpty()) {
            for (String key : keys) {
                String v = getFromFileProps(fileProps, key);
                if (!isBlank(v)) {
                    return v;
                }
            }
        }
        return null;
    }

    private static String getFromArgs(Map<String, String> args, String key) {
        if (args == null || args.isEmpty()) {
            return null;
        }
        String v = args.get(key);
        if (!isBlank(v)) {
            return v;
        }
        v = args.get(toKebabCase(key));
        if (!isBlank(v)) {
            return v;
        }
        v = args.get(toSnakeCase(key));
        if (!isBlank(v)) {
            return v;
        }
        return null;
    }

    private static String getFromSystemProperties(String key) {
        String[] prefixes = {"monitor.sdk.", "monitor.", ""};
        String[] variations = {key, toKebabCase(key), toSnakeCase(key)};
        for (String p : prefixes) {
            for (String var : variations) {
                if (var == null) {
                    continue;
                }
                String full = p + var;
                String v = System.getProperty(full);
                if (!isBlank(v)) {
                    return v;
                }
            }
        }
        return null;
    }

    private static String getFromEnvironment(String key) {
        String envKey = toEnvironmentKey(key);
        String v = System.getenv(envKey);
        if (!isBlank(v)) {
            return v;
        }
        String snake = toSnakeCase(key);
        if (snake != null) {
            String rawEnvKey = snake.toUpperCase(Locale.ROOT);
            v = System.getenv(rawEnvKey);
            if (!isBlank(v)) {
                return v;
            }
        }
        return null;
    }

    private static String getFromFileProps(Map<String, String> fileProps, String key) {
        String[] prefixes = {"monitor.sdk.", "monitor.", ""};
        String[] variations = {key, toKebabCase(key), toSnakeCase(key)};
        for (String p : prefixes) {
            for (String var : variations) {
                if (var == null) {
                    continue;
                }
                String full = p + var;
                String v = fileProps.get(full);
                if (!isBlank(v)) {
                    return v;
                }
            }
        }
        return null;
    }

    private static Map<String, String> loadConfigFile(Map<String, String> args) {
        Map<String, String> result = new LinkedHashMap<String, String>();

        // 1. 显式指定的配置文件路径
        String explicitPath = value(args, Collections.<String, String>emptyMap(), "config", "configFile", "configPath");
        if (!isBlank(explicitPath)) {
            loadFileIntoMap(explicitPath, result);
            return result;
        }

        // 2. 自动探测 classpath 与工作目录中常见配置文件
        String[] candidates = {
                "monitor-sdk.properties",
                "monitor-agent.properties",
                "application.yml",
                "application.yaml",
                "application.properties",
                "config/application.yml",
                "config/application.yaml",
                "config/application.properties"
        };

        // 优先 classpath
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = AgentConfig.class.getClassLoader();
        }
        for (String candidate : candidates) {
            try {
                InputStream is = cl != null ? cl.getResourceAsStream(candidate) : null;
                if (is == null && !candidate.startsWith("/")) {
                    is = cl != null ? cl.getResourceAsStream("/" + candidate) : null;
                }
                if (is != null) {
                    try {
                        parseStreamIntoMap(candidate, is, result);
                        if (!result.isEmpty()) {
                            return result;
                        }
                    } finally {
                        is.close();
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        // 其次尝试工作目录中的文件
        for (String candidate : candidates) {
            File f = new File(candidate);
            if (f.exists() && f.isFile() && f.canRead()) {
                loadFileIntoMap(f.getAbsolutePath(), result);
                if (!result.isEmpty()) {
                    return result;
                }
            }
        }

        return result;
    }

    private static void loadFileIntoMap(String filePath, Map<String, String> target) {
        File f = new File(filePath);
        if (!f.exists() || !f.isFile()) {
            return;
        }
        try {
            FileInputStream fis = new FileInputStream(f);
            try {
                parseStreamIntoMap(f.getName(), fis, target);
            } finally {
                fis.close();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void parseStreamIntoMap(String fileName, InputStream is, Map<String, String> target) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".yml") || lower.endsWith(".yaml")) {
            parseYamlStream(is, target);
        } else {
            parsePropertiesStream(is, target);
        }
    }

    private static void parsePropertiesStream(InputStream is, Map<String, String> target) {
        try {
            Properties props = new Properties();
            props.load(new InputStreamReader(is, StandardCharsets.UTF_8));
            for (String key : props.stringPropertyNames()) {
                target.put(key.trim(), props.getProperty(key).trim());
            }
        } catch (Throwable ignored) {
        }
    }

    /** 零依赖简易 YAML 解析器，将多级键展平为点分格式（例如 monitor.sdk.biz-line）。 */
    private static void parseYamlStream(InputStream is, Map<String, String> target) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            List<YamlNode> stack = new ArrayList<YamlNode>();
            String line;
            while ((line = reader.readLine()) != null) {
                int indent = 0;
                while (indent < line.length() && line.charAt(indent) == ' ') {
                    indent++;
                }
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int commentIdx = trimmed.indexOf(" #");
                if (commentIdx > 0) {
                    trimmed = trimmed.substring(0, commentIdx).trim();
                }

                int colonIdx = trimmed.indexOf(':');
                if (colonIdx <= 0) {
                    continue;
                }

                String key = trimmed.substring(0, colonIdx).trim();
                String rawVal = trimmed.substring(colonIdx + 1).trim();

                if ((rawVal.startsWith("\"") && rawVal.endsWith("\""))
                        || (rawVal.startsWith("'") && rawVal.endsWith("'"))) {
                    if (rawVal.length() >= 2) {
                        rawVal = rawVal.substring(1, rawVal.length() - 1);
                    }
                }

                while (!stack.isEmpty() && stack.get(stack.size() - 1).indent >= indent) {
                    stack.remove(stack.size() - 1);
                }

                if (rawVal.isEmpty()) {
                    stack.add(new YamlNode(indent, key));
                } else {
                    StringBuilder fullKey = new StringBuilder();
                    for (YamlNode node : stack) {
                        fullKey.append(node.key).append('.');
                    }
                    fullKey.append(key);
                    target.put(fullKey.toString(), rawVal);
                    target.put(key, rawVal);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static class YamlNode {
        final int indent;
        final String key;
        YamlNode(int indent, String key) {
            this.indent = indent;
            this.key = key;
        }
    }

    private static String toEnvironmentKey(String key) {
        StringBuilder env = new StringBuilder("MONITOR_SDK_");
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (Character.isUpperCase(c)) {
                env.append('_').append(c);
            } else if (c == '.' || c == '-') {
                env.append('_');
            } else {
                env.append(Character.toUpperCase(c));
            }
        }
        return env.toString();
    }

    private static String toKebabCase(String key) {
        if (key == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0 && key.charAt(i - 1) != '-' && key.charAt(i - 1) != '.') {
                    sb.append('-');
                }
                sb.append(Character.toLowerCase(c));
            } else if (c == '_') {
                sb.append('-');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String toSnakeCase(String key) {
        if (key == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0 && key.charAt(i - 1) != '_' && key.charAt(i - 1) != '.') {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else if (c == '-') {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean bool(String value, boolean fallback) {
        return isBlank(value) ? fallback : Boolean.parseBoolean(value.trim());
    }

    private static int integer(String value, int fallback, String key) {
        if (isBlank(value)) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(key + " must be an integer: " + value, ex);
        }
    }

    private static Duration parseDuration(String value, Duration fallback, String key) {
        if (isBlank(value)) {
            return fallback;
        }
        String s = value.trim().toLowerCase(Locale.ROOT);
        try {
            if (s.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(s.substring(0, s.length() - 2).trim()));
            } else if (s.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(s.substring(0, s.length() - 1).trim()));
            } else if (s.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(s.substring(0, s.length() - 1).trim()));
            } else {
                return Duration.ofMillis(Long.parseLong(s));
            }
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(key + " duration format invalid: " + value, ex);
        }
    }

    private static String required(String value, String key) {
        if (isBlank(value)) {
            throw new IllegalArgumentException("monitor.sdk." + key + " is required when agent is enabled");
        }
        return value.trim();
    }

    private static String defaulted(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
