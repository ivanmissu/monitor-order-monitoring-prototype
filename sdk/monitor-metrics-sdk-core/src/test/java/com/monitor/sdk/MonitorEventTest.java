package com.monitor.sdk;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MonitorEventTest {

    @Test
    void createsContractCompatiblePayloadAndHashesDriverId() {
        MonitorEvent event = MonitorEvent.builder("order_delivered", new EventIdGenerator() {
                    @Override public String nextId() { return "10001"; }
                })
                .eventTime(Instant.parse("2026-09-16T08:00:00Z"))
                .orderId("ORDER-1")
                .bizLine("driver")
                .cityId(440300L)
                .amountFen(8650L)
                .hashedDriverId("driver-internal-id")
                .prop("trip_duration_sec", 3600)
                .prop("labels", Arrays.asList("peak", "rain"))
                .build();

        assertEquals("10001", event.getEventId());
        assertEquals(2, event.getProps().size()); // props remains accessible and immutable below
        assertEquals(64, event.getDriverIdHash().length());
        assertTrue(event.toJson().contains("\"event_type\":\"order_delivered\""));
        assertTrue(event.toJson().contains("\"amount\":8650"));
        assertFalse(event.toJson().contains("driver-internal-id"));
        assertThrows(UnsupportedOperationException.class, () -> event.getProps().put("x", "y"));
    }

    @Test
    void requiresMonitorContractDimensions() {
        assertThrows(IllegalArgumentException.class, () -> MonitorEvent.builder("order_delivered")
                .bizLine("driver")
                .cityId(440300L)
                .build());
        assertThrows(IllegalArgumentException.class, () -> MonitorEvent.builder("order_delivered")
                .orderId("ORDER-1")
                .bizLine("driver")
                .cityId(0L)
                .build());
    }

    @Test
    void generatesMonotonicIds() {
        TimeOrderedEventIdGenerator generator = new TimeOrderedEventIdGenerator(1);
        long previous = Long.parseLong(generator.nextId());
        for (int i = 0; i < 100; i++) {
            long current = Long.parseLong(generator.nextId());
            assertTrue(current > previous);
            previous = current;
        }
    }
}
