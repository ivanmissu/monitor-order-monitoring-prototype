package com.monitor.sdk.spring;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MonitorSdkPropertiesTest {

    @Test
    void mapsSpringPropertiesToCoreReporterConfig() {
        MonitorSdkProperties properties = new MonitorSdkProperties();
        properties.setEndpoint("http://localhost:8080/api/v1/ingest/events");
        properties.setToken("ingest-token");
        properties.setBatchSize(20);

        assertEquals(20, properties.toReporterConfig().getBatchSize());
        assertEquals("ingest-token", properties.toReporterConfig().getToken());
    }
}
