package com.monitor.sdk.agent;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PropertyExtractorTest {

    static class OrderCommand {
        private final String orderId;
        private final long cityId;
        private final Detail detail;

        public OrderCommand(String orderId, long cityId, Detail detail) {
            this.orderId = orderId;
            this.cityId = cityId;
            this.detail = detail;
        }

        public String getOrderId() {
            return orderId;
        }

        public long getCityId() {
            return cityId;
        }

        public Detail getDetail() {
            return detail;
        }
    }

    static class Detail {
        private final long amountFen;
        private final String seatType;

        public Detail(long amountFen, String seatType) {
            this.amountFen = amountFen;
            this.seatType = seatType;
        }

        public long getAmountFen() {
            return amountFen;
        }

        public String seatType() {
            return seatType; // record-style getter
        }
    }

    static class DummyService {
        public void processOrder(OrderCommand command, Map<String, Object> extra, String channel) {
        }

        public void singleArg(OrderCommand command) {
        }
    }

    @Test
    void extractsFromPojoAndGetters() throws Exception {
        Method method = DummyService.class.getMethod("singleArg", OrderCommand.class);
        Detail detail = new Detail(8800L, "comfort");
        OrderCommand cmd = new OrderCommand("ORDER-123", 440300L, detail);
        Object[] args = new Object[]{cmd};

        assertEquals("ORDER-123", PropertyExtractor.extract(args, method, "orderId"));
        assertEquals(440300L, PropertyExtractor.extract(args, method, "0.cityId"));
        assertEquals(8800L, PropertyExtractor.extract(args, method, "detail.amountFen"));
        assertEquals("comfort", PropertyExtractor.extract(args, method, "0.detail.seatType"));
    }

    @Test
    void extractsFromMap() throws Exception {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("order_id", "MAP-999");
        map.put("cityId", 330100L);
        map.put("amount_fen", 1500L);

        Map<String, Object> inner = new LinkedHashMap<String, Object>();
        inner.put("channel", "app-v2");
        map.put("meta", inner);

        Object[] args = new Object[]{map};

        assertEquals("MAP-999", PropertyExtractor.extract(args, null, "order_id"));
        assertEquals("MAP-999", PropertyExtractor.extract(args, null, "orderId"));
        assertEquals(330100L, PropertyExtractor.extract(args, null, "0.cityId"));
        assertEquals(1500L, PropertyExtractor.extract(args, null, "amountFen"));
        assertEquals("app-v2", PropertyExtractor.extract(args, null, "meta.channel"));
        assertEquals("app-v2", PropertyExtractor.extract(args, null, "0.meta.channel"));
    }

    @Test
    void extractsFromMultipleArguments() throws Exception {
        Method method = DummyService.class.getMethod("processOrder", OrderCommand.class, Map.class, String.class);
        Detail detail = new Detail(5000L, "standard");
        OrderCommand cmd = new OrderCommand("MULTI-001", 110000L, detail);

        Map<String, Object> extra = new HashMap<String, Object>();
        extra.put("coupon_id", "COUPON-77");

        Object[] args = new Object[]{cmd, extra, "web_direct"};

        assertEquals("MULTI-001", PropertyExtractor.extract(args, method, "0.orderId"));
        assertEquals(110000L, PropertyExtractor.extract(args, method, "0.cityId"));
        assertEquals("COUPON-77", PropertyExtractor.extract(args, method, "1.coupon_id"));
        assertEquals("COUPON-77", PropertyExtractor.extract(args, method, "extra.coupon_id"));
        assertEquals("web_direct", PropertyExtractor.extract(args, method, "2"));
    }

    @Test
    void extractsBracketAndListPaths() throws Exception {
        Map<String, Object> payload = new HashMap<String, Object>();
        List<String> tags = Arrays.asList("vip", "discount");
        payload.put("tags", tags);

        Object[] args = new Object[]{payload};

        assertEquals("vip", PropertyExtractor.extract(args, null, "tags[0]"));
        assertEquals("discount", PropertyExtractor.extract(args, null, "0.tags.1"));
        assertEquals("vip", PropertyExtractor.extract(args, null, "args[0].tags[0]"));
    }

    @Test
    void safelyHandlesNullAndNotFound() throws Exception {
        assertNull(PropertyExtractor.extract(null, null, "orderId"));
        assertNull(PropertyExtractor.extract(new Object[0], null, "orderId"));
        assertNull(PropertyExtractor.extract(new Object[]{null}, null, "orderId"));
        assertNull(PropertyExtractor.extract(new Object[]{"abc"}, null, "nonExistingField"));
        assertNull(PropertyExtractor.extract(new Object[]{"abc"}, null, "99.orderId"));
    }
}
