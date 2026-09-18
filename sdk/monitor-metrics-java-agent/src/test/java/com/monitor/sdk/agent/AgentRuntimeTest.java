package com.monitor.sdk.agent;

import com.monitor.sdk.EventReporter;
import com.monitor.sdk.MonitorEvent;
import com.monitor.sdk.ReporterStats;
import com.monitor.sdk.annotation.MonitorMetricEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRuntimeTest {

    private CapturingReporter capturingReporter;

    static class CapturingReporter implements EventReporter {
        final List<MonitorEvent> reportedEvents = new ArrayList<MonitorEvent>();

        @Override
        public boolean report(MonitorEvent event) {
            reportedEvents.add(event);
            return true;
        }

        @Override
        public boolean flush(Duration timeout) {
            return true;
        }

        @Override
        public ReporterStats stats() {
            return null;
        }

        @Override
        public void close() {
        }
    }

    static class SampleOrderDto {
        private final String orderId;
        private final long cityId;
        private final long amountFen;
        private final String driverId;

        public SampleOrderDto(String orderId, long cityId, long amountFen, String driverId) {
            this.orderId = orderId;
            this.cityId = cityId;
            this.amountFen = amountFen;
            this.driverId = driverId;
        }

        public String getOrderId() { return orderId; }
        public long getCityId() { return cityId; }
        public long getAmountFen() { return amountFen; }
        public String getDriverId() { return driverId; }
    }

    static class BusinessSampleService {
        @MonitorMetricEvent(
                eventType = "order_created",
                orderIdPath = "orderId",
                cityIdPath = "cityId",
                amountFenPath = "amountFen",
                driverIdPath = "driverId",
                propNames = {"channel"},
                propPaths = {"channel"}
        )
        public void createWithDto(SampleOrderDto dto, String channel) {
        }

        @MonitorMetricEvent(
                eventType = "order_delivered",
                orderIdPath = "0.order_id",
                cityIdPath = "0.city_id",
                amountFenPath = "0.amount_fen",
                propNames = {"trip_duration_sec"},
                propPaths = {"0.trip_duration"}
        )
        public void completeWithMap(Map<String, Object> params) {
        }

        @MonitorMetricEvent(
                eventType = "order_cancelled",
                bizLine = "custom_line",
                orderIdArg = 0,
                cityIdArg = 1
        )
        public void cancelPositional(String orderId, long cityId) {
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        AgentConfig config = AgentConfig.from("endpoint=http://localhost:8080/api/v1/ingest/events,token=test,bizLine=driver");
        AgentRuntime.initialize(config);

        capturingReporter = new CapturingReporter();
        Field reporterField = AgentRuntime.class.getDeclaredField("reporter");
        reporterField.setAccessible(true);
        reporterField.set(null, capturingReporter);
    }

    @AfterEach
    void tearDown() {
        AgentRuntime.close();
    }

    @Test
    void reportsEventUsingDtoAndGlobalBizLine() throws Exception {
        Method method = BusinessSampleService.class.getMethod("createWithDto", SampleOrderDto.class, String.class);
        SampleOrderDto dto = new SampleOrderDto("ORDER-DTO-100", 440300L, 8900L, "driver-999");
        Object[] args = new Object[]{dto, "app_direct"};

        AgentRuntime.report(method, args, null);

        assertEquals(1, capturingReporter.reportedEvents.size());
        MonitorEvent event = capturingReporter.reportedEvents.get(0);
        assertEquals("order_created", event.getEventType());
        assertEquals("ORDER-DTO-100", event.getOrderId());
        assertEquals("driver", event.getBizLine()); // from AgentConfig
        assertEquals(440300L, event.getCityId());
        assertEquals(8900L, event.getAmountFen());
        assertNotNull(event.getDriverIdHash());
        assertEquals("app_direct", event.getProps().get("channel"));
    }

    @Test
    void reportsEventUsingMap() throws Exception {
        Method method = BusinessSampleService.class.getMethod("completeWithMap", Map.class);
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("order_id", "MAP-200");
        map.put("city_id", 330100L);
        map.put("amount_fen", 12500L);
        map.put("trip_duration", 3600L);
        Object[] args = new Object[]{map};

        AgentRuntime.report(method, args, null);

        assertEquals(1, capturingReporter.reportedEvents.size());
        MonitorEvent event = capturingReporter.reportedEvents.get(0);
        assertEquals("order_delivered", event.getEventType());
        assertEquals("MAP-200", event.getOrderId());
        assertEquals("driver", event.getBizLine());
        assertEquals(330100L, event.getCityId());
        assertEquals(12500L, event.getAmountFen());
        assertEquals(3600L, event.getProps().get("trip_duration_sec"));
    }

    @Test
    void annotationBizLineOverridesGlobalConfig() throws Exception {
        Method method = BusinessSampleService.class.getMethod("cancelPositional", String.class, long.class);
        Object[] args = new Object[]{"CANCEL-300", 510100L};

        AgentRuntime.report(method, args, null);

        assertEquals(1, capturingReporter.reportedEvents.size());
        MonitorEvent event = capturingReporter.reportedEvents.get(0);
        assertEquals("order_cancelled", event.getEventType());
        assertEquals("CANCEL-300", event.getOrderId());
        assertEquals("custom_line", event.getBizLine()); // overridden by annotation
        assertEquals(510100L, event.getCityId());
    }
}
