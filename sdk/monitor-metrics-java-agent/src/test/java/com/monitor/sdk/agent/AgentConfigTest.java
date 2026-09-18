package com.monitor.sdk.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentConfigTest {

    @Test
    void resolvesExplicitAgentArguments() {
        AgentConfig config = AgentConfig.from("endpoint=http://localhost:8080/api/v1/ingest/events,token=abc,"
                + "batchSize=20,flushIntervalMs=50,bizLine=driver");

        assertTrue(config.isEnabled());
        assertEquals(20, config.getReporterConfig().getBatchSize());
        assertEquals(50L, config.getReporterConfig().getFlushInterval().toMillis());
        assertEquals("driver", config.getBizLine());
        assertEquals("driver", config.getDefaultBizLine());
    }

    @Test
    void resolvesDefaultBizLineAlias() {
        AgentConfig config = AgentConfig.from("endpoint=http://localhost:8080/api/v1/ingest/events,token=abc,"
                + "defaultBizLine=carpool");

        assertEquals("carpool", config.getBizLine());
    }

    @Test
    void canBeExplicitlyDisabledWithoutEndpointOrToken() {
        assertFalse(AgentConfig.from("enabled=false").isEnabled());
    }

    @Test
    void resolvesPropertiesConfigFile(@TempDir Path tempDir) throws Exception {
        File propFile = tempDir.resolve("monitor-sdk.properties").toFile();
        String content = "monitor.sdk.endpoint=http://config-server:8080/api/v1/ingest/events\n"
                + "monitor.sdk.token=config-token\n"
                + "monitor.sdk.biz-line=transfer\n"
                + "monitor.sdk.batch-size=100\n"
                + "monitor.sdk.flush-interval=500ms\n";
        try (FileOutputStream fos = new FileOutputStream(propFile)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }

        AgentConfig config = AgentConfig.from("config=" + propFile.getAbsolutePath());
        assertTrue(config.isEnabled());
        assertEquals("http://config-server:8080/api/v1/ingest/events", config.getReporterConfig().getEndpoint());
        assertEquals("config-token", config.getReporterConfig().getToken());
        assertEquals("transfer", config.getBizLine());
        assertEquals(100, config.getReporterConfig().getBatchSize());
        assertEquals(500L, config.getReporterConfig().getFlushInterval().toMillis());
    }

    @Test
    void resolvesYamlConfigFile(@TempDir Path tempDir) throws Exception {
        File yamlFile = tempDir.resolve("application.yml").toFile();
        String content = "monitor:\n"
                + "  sdk:\n"
                + "    endpoint: 'http://yaml-server:8080/api/v1/ingest/events'\n"
                + "    token: yaml-token\n"
                + "    biz-line: airport\n"
                + "    flush-interval: 3s\n";
        try (FileOutputStream fos = new FileOutputStream(yamlFile)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }

        AgentConfig config = AgentConfig.from("configFile=" + yamlFile.getAbsolutePath());
        assertTrue(config.isEnabled());
        assertEquals("http://yaml-server:8080/api/v1/ingest/events", config.getReporterConfig().getEndpoint());
        assertEquals("yaml-token", config.getReporterConfig().getToken());
        assertEquals("airport", config.getBizLine());
        assertEquals(3000L, config.getReporterConfig().getFlushInterval().toMillis());
    }
}
