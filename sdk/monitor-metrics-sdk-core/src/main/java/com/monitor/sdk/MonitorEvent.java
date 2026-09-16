package com.monitor.sdk;

import com.monitor.sdk.internal.JsonCodec;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Monitor 平台的统一指标事件。
 *
 * <p>平台由已登记的 {@code eventType} 聚合原子指标，而非接受任意指标名。因此请使用
 * 指标字典中已经登记的事件类型，并确保 cityId、seatType、amount 等维度都是发生时刻的
 * 快照。SDK 不会在请求期回查业务库。</p>
 */
public final class MonitorEvent {

    private static final EventIdGenerator DEFAULT_ID_GENERATOR = new TimeOrderedEventIdGenerator();

    private final String eventId;
    private final String eventType;
    private final Instant eventTime;
    private final String orderId;
    private final String tripId;
    private final String bizLine;
    private final Long cityId;
    private final String seatType;
    private final String driverIdHash;
    private final long amountFen;
    private final Map<String, Object> props;
    private final long version;

    private MonitorEvent(Builder builder) {
        this.eventId = requireText(builder.eventId, "eventId");
        this.eventType = requireText(builder.eventType, "eventType");
        this.eventTime = requireNonNull(builder.eventTime, "eventTime");
        this.orderId = requireText(builder.orderId, "orderId");
        this.tripId = blankToNull(builder.tripId);
        this.bizLine = requireText(builder.bizLine, "bizLine");
        this.cityId = requireNonNull(builder.cityId, "cityId");
        if (this.cityId.longValue() <= 0L) {
            throw new IllegalArgumentException("cityId must be positive");
        }
        this.seatType = blankToNull(builder.seatType);
        this.driverIdHash = blankToNull(builder.driverIdHash);
        this.amountFen = builder.amountFen;
        this.props = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(builder.props));
        this.version = builder.version <= 0L ? 1L : builder.version;
    }

    public static Builder builder(String eventType) {
        return new Builder(eventType, DEFAULT_ID_GENERATOR);
    }

    public static Builder builder(String eventType, EventIdGenerator idGenerator) {
        return new Builder(eventType, idGenerator);
    }

    public String getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public Instant getEventTime() {
        return eventTime;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getTripId() {
        return tripId;
    }

    public String getBizLine() {
        return bizLine;
    }

    public Long getCityId() {
        return cityId;
    }

    public String getSeatType() {
        return seatType;
    }

    public String getDriverIdHash() {
        return driverIdHash;
    }

    public long getAmountFen() {
        return amountFen;
    }

    public Map<String, Object> getProps() {
        return props;
    }

    public long getVersion() {
        return version;
    }

    /** 返回符合 {@code POST /api/v1/ingest/events} 契约的 snake_case Map。 */
    public Map<String, Object> toWireMap() {
        Map<String, Object> wire = new LinkedHashMap<String, Object>();
        wire.put("event_id", eventId);
        wire.put("event_type", eventType);
        wire.put("event_time", eventTime.toString());
        wire.put("order_id", orderId);
        if (tripId != null) {
            wire.put("trip_id", tripId);
        }
        wire.put("biz_line", bizLine);
        wire.put("city_id", cityId);
        if (seatType != null) {
            wire.put("seat_type", seatType);
        }
        if (driverIdHash != null) {
            wire.put("driver_id_hash", driverIdHash);
        }
        wire.put("amount", amountFen);
        wire.put("props", props);
        wire.put("version", version);
        return wire;
    }

    public String toJson() {
        return JsonCodec.toJson(toWireMap());
    }

    public int estimatedWireBytes() {
        return toJson().getBytes(StandardCharsets.UTF_8).length;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static <T> T requireNonNull(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value;
    }

    public static final class Builder {
        private String eventId;
        private final String eventType;
        private Instant eventTime = Instant.now();
        private String orderId;
        private String tripId;
        private String bizLine;
        private Long cityId;
        private String seatType;
        private String driverIdHash;
        private long amountFen = 0L;
        private final Map<String, Object> props = new LinkedHashMap<String, Object>();
        private long version = 1L;

        private Builder(String eventType, EventIdGenerator idGenerator) {
            if (idGenerator == null) {
                throw new IllegalArgumentException("idGenerator must not be null");
            }
            this.eventType = eventType;
            this.eventId = idGenerator.nextId();
        }

        public Builder eventId(String value) {
            this.eventId = value;
            return this;
        }

        public Builder eventTime(Instant value) {
            this.eventTime = value;
            return this;
        }

        public Builder orderId(String value) {
            this.orderId = value;
            return this;
        }

        public Builder tripId(String value) {
            this.tripId = value;
            return this;
        }

        public Builder bizLine(String value) {
            this.bizLine = value;
            return this;
        }

        public Builder cityId(long value) {
            this.cityId = Long.valueOf(value);
            return this;
        }

        public Builder seatType(String value) {
            this.seatType = value;
            return this;
        }

        /**
         * 传入已经 SHA-256 脱敏的司机标识。原始手机号、证件号等 PII 禁止上报。
         */
        public Builder driverIdHash(String value) {
            this.driverIdHash = value;
            return this;
        }

        /**
         * 对稳定业务 ID 做 SHA-256 脱敏后写入 {@code driver_id_hash}，避免业务代码误传明文。
         */
        public Builder hashedDriverId(String rawStableId) {
            this.driverIdHash = Hashing.sha256Hex(rawStableId);
            return this;
        }

        /** 金额以分为单位；无金额的状态事件请保留默认值 0。 */
        public Builder amountFen(long value) {
            this.amountFen = value;
            return this;
        }

        public Builder prop(String key, Object value) {
            if (key == null || key.trim().isEmpty()) {
                throw new IllegalArgumentException("prop key must not be blank");
            }
            props.put(key, value);
            return this;
        }

        public Builder props(Map<String, ?> values) {
            if (values != null) {
                for (Map.Entry<String, ?> entry : values.entrySet()) {
                    prop(entry.getKey(), entry.getValue());
                }
            }
            return this;
        }

        /** 同一 eventId 的修正版本；服务端保留版本号较大的事件。 */
        public Builder version(long value) {
            this.version = value;
            return this;
        }

        public MonitorEvent build() {
            return new MonitorEvent(this);
        }
    }
}
