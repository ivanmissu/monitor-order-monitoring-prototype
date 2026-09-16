package com.monitor.sdk.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentConfigTest {

    @Test
    void resolvesExplicitAgentArguments() {
        AgentConfig config = AgentConfig.from("endpoint=http://localhost:8080/api/v1/ingest/events,token=abc,"
                + "batchSize=20,flushIntervalMs=50,defaultBizLine=driver");

        assertTrue(config.isEnabled());
        assertEquals(20, config.getReporterConfig().getBatchSize());
        assertEquals(50L, config.getReporterConfig().getFlushInterval().toMillis());
        assertEquals("driver", config.getDefaultBizLine());
    }

    @Test
    void canBeExplicitlyDisabledWithoutEndpointOrToken() {
        assertFalse(AgentConfig.from("enabled=false").isEnabled());
    }
}
