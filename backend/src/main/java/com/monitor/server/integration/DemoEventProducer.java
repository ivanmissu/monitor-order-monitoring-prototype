package com.monitor.server.integration;

import com.monitor.server.ingest.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * integration profile 的演示「业务方」：模拟五条业务线的订单生命周期，
 * 以 KafkaTemplate 将标准事件投递到 {@code biz.order.event}，
 * 由 {@code EventConsumer} 消费入库 —— 与生产 MQ 主通路完全同构。
 *
 * <p>事件字段严格遵守 {@code EventValidator} 的字典约束：
 * event_type 已登记、props 仅白名单键、金额单位为分、司机标识为 SHA-256 形态。
 *
 * <p>约 2% 事件故意「迟到」（event_time 早于当前 20 分钟），
 * 用于在元监控视图驱动 {@code late_event_cnt / ingest_delay_p99} 指标。
 */
@Component
@Profile("integration")
public class DemoEventProducer {

    private static final Logger log = LoggerFactory.getLogger(DemoEventProducer.class);
    private static final SecureRandom RND = new SecureRandom();

    /** (城市 ID, 城市名) 与前端演示维度同源。 */
    private static final long[][] CITIES = {
            {330100}, {440100}, {510100}, {310000}, {320100}, {440300}, {420100}};

    private static final String[] BIZ = {"driver", "transfer", "carpool", "designated", "airport"};

    /** 每条业务线的座型枚举。 */
    private static final Map<String, String[]> SEATS = Map.of(
            "driver", new String[]{"express", "express_pool"},
            "transfer", new String[]{"express"},
            "carpool", new String[]{"shared_2", "shared_4", "exclusive"},
            "designated", new String[]{"designated"},
            "airport", new String[]{"pickup", "dropoff"});

    private static final String[] CANCEL_BY = {"driver", "passenger", "system"};
    private static final String[] CANCEL_STAGE = {"pre1h", "enroute", "lt1h"};
    private static final String[] CANCEL_FAULT = {"at-fault", "no-fault"};
    private static final String[] CANCEL_REASON = {"ROUTE_MISMATCH", "VEHICLE_ISSUE", "PRICE_CHANGE", "WEATHER"};
    private static final String[] RISK_RULES = {"R-1142", "R-2201", "R-3310"};
    private static final String[] PAY_CHANNELS = {"alipay", "wechat", "balance"};

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final int rounds;
    private final AtomicLong orderSeq = new AtomicLong(9_000_000);
    private final AtomicLong eventSeq = new AtomicLong();

    public DemoEventProducer(KafkaTemplate<String, String> kafka, ObjectMapper mapper,
                             com.monitor.server.config.MonitorProperties props) {
        this.kafka = kafka;
        this.mapper = mapper;
        this.rounds = Math.max(1, props.getIntegration().getProducerRounds());
    }

    @Scheduled(fixedDelayString = "${monitor.integration.producer-interval-ms:4000}",
            initialDelay = 15_000)
    public void produce() {
        if (!IntegrationSeedRunner.ready()) {
            log.debug("ClickHouse 种子初始化进行中，本轮跳过投递");
            return;
        }
        for (int i = 0; i < rounds; i++) {
            try {
                emitOrderLifecycle();
            } catch (Exception ex) {
                log.warn("演示事件投递失败: {}", ex.getMessage());
            }
        }
    }

    private void emitOrderLifecycle() throws Exception {
        String biz = BIZ[RND.nextInt(BIZ.length)];
        String seat = pick(SEATS.get(biz));
        long cityId = CITIES[RND.nextInt(CITIES.length)][0];
        String orderId = bizTag(biz) + OffsetDateTime.now().toLocalDate().toString().replace("-", "")
                .substring(2) + String.format("%07d", orderSeq.incrementAndGet());
        String driver = hex(RND, 40);
        String tripId = "T" + (8_800_000_000L + orderSeq.get());
        long amount = 1_500 + RND.nextInt(28_000);
        OffsetDateTime base = OffsetDateTime.now().minusSeconds(60 + RND.nextInt(120));

        List<EventEnvelope> events = new ArrayList<>();
        boolean cancelled = RND.nextInt(10) == 0;

        switch (biz) {
            case "carpool" -> events.add(ev("trip_published", base, orderId, tripId, biz, cityId, seat,
                    driver, 0, props("seats", 2 + RND.nextInt(4))));
            case "airport" -> events.add(ev("booking_created", base, orderId, tripId, biz, cityId, seat,
                    driver, 0, props("flight_no_hash", hex(RND, 12), "service_type", "pickup")));
            default -> {
            }
        }
        OffsetDateTime t = base.plusSeconds(20);
        events.add(ev("order_created", t, orderId, tripId, biz, cityId, seat, driver, amount, props("channel", "app")));
        t = t.plusSeconds(15);
        switch (biz) {
            case "driver" -> events.add(ev("dispatch_sent", t, orderId, tripId, biz, cityId, seat, driver, 0,
                    props("radius_m", 1500 + RND.nextInt(3000), "candidate_count", 3 + RND.nextInt(9))));
            case "transfer" -> events.add(ev("transfer_submitted", t, orderId, tripId, biz, cityId, seat, driver, 0,
                    props("reason_code", pick(CANCEL_REASON))));
            case "carpool" -> events.add(ev("order_grab_submitted", t, orderId, tripId, biz, cityId, seat, driver, 0,
                    props("candidate_count", 1 + RND.nextInt(8))));
            case "designated" -> events.add(ev("designated_assigned", t, orderId, tripId, biz, cityId, seat, driver, 0,
                    props("distance_m", 800 + RND.nextInt(4000))));
            default -> {
            }
        }
        if (cancelled) {
            t = t.plusSeconds(30 + RND.nextInt(600));
            events.add(ev("order_cancelled", t, orderId, tripId, biz, cityId, seat, driver, 0,
                    props("by", pick(CANCEL_BY), "stage", pick(CANCEL_STAGE),
                            "fault", pick(CANCEL_FAULT), "reason_code", pick(CANCEL_REASON))));
            if (RND.nextBoolean()) {
                events.add(ev("refund_completed", t.plusSeconds(30), orderId, tripId, biz, cityId, seat,
                        driver, amount, props("dispute_id", hex(RND, 8))));
            }
            publish(orderId, events);
            return;
        }
        t = t.plusSeconds(20);
        switch (biz) {
            case "driver" -> events.add(ev("dispatch_ack", t, orderId, tripId, biz, cityId, seat, driver, 0,
                    props("ack_duration_sec", 1 + RND.nextInt(9))));
            case "transfer" -> events.add(ev("transfer_accepted", t, orderId, tripId, biz, cityId, seat, driver, 0,
                    props("match_duration_sec", 2 + RND.nextInt(30))));
            case "carpool" -> {
                events.add(ev("order_grab_won", t, orderId, tripId, biz, cityId, seat, driver, 0,
                        props("match_duration_sec", 1 + RND.nextInt(10), "route_score", 60 + RND.nextInt(40))));
                events.add(ev("passenger_confirmed", t.plusSeconds(10), orderId, tripId, biz, cityId, seat,
                        driver, 0, props("result", "confirmed")));
            }
            default -> {
            }
        }
        t = t.plusSeconds(60 + RND.nextInt(300));
        events.add(ev("arrive_pickup", t, orderId, tripId, biz, cityId, seat, driver, 0,
                props("wait_started_at", t.toString())));
        t = t.plusSeconds(30 + RND.nextInt(600));
        if (RND.nextInt(50) == 0) {
            events.add(ev("no_show", t, orderId, tripId, biz, cityId, seat, driver, 0, Map.of()));
            publish(orderId, events);
            return;
        }
        events.add(ev("passenger_boarded", t, orderId, tripId, biz, cityId, seat, driver, 0,
                props("wait_sec", 60 + RND.nextInt(900))));
        t = t.plusSeconds(300 + RND.nextInt(2400));
        events.add(ev("order_delivered", t, orderId, tripId, biz, cityId, seat, driver, 0,
                props("trip_duration_sec", 300 + RND.nextInt(3000), "seat_type", seat)));
        t = t.plusSeconds(10);
        if (RND.nextInt(20) > 0) {
            events.add(ev("prepay_succeeded", t, orderId, tripId, biz, cityId, seat, driver, amount,
                    props("pay_channel", pick(PAY_CHANNELS))));
        } else {
            events.add(ev("prepay_failed", t, orderId, tripId, biz, cityId, seat, driver, amount,
                    props("fail_code", RND.nextBoolean() ? "PAY_CHANNEL_TIMEOUT" : "WALLET_LOCK_BUSY",
                            "pay_channel", pick(PAY_CHANNELS))));
        }
        t = t.plusSeconds(120 + RND.nextInt(600));
        events.add(ev("settlement_credited", t, orderId, tripId, biz, cityId, seat, driver,
                amount / 10, props("commission", amount / 10, "driver_income", amount - amount / 10)));

        if (RND.nextInt(25) == 0) {
            events.add(ev("risk_hit", t, orderId, tripId, biz, cityId, seat, driver,
                    5_000 + RND.nextInt(50_000), props("rule_id", pick(RISK_RULES), "action", "freeze")));
        }
        if (RND.nextInt(60) == 0) {
            events.add(ev("complaint_submitted", t.plusSeconds(30), orderId, tripId, biz, cityId, seat,
                    driver, 0, props("complaint_tag", pick(new String[]{"SERVICE", "ROUTE", "FEE"}))));
        }
        if (RND.nextInt(120) == 0) {
            events.add(ev("appeal_submitted", t.plusSeconds(60), orderId, tripId, biz, cityId, seat,
                    driver, 0, Map.of()));
            events.add(ev("appeal_resolved", t.plusSeconds(300), orderId, tripId, biz, cityId, seat,
                    driver, 0, props("upheld", RND.nextBoolean() ? 1 : 0)));
        }
        if (RND.nextInt(80) == 0) {
            events.add(ev("withdraw_requested", t.plusSeconds(90), orderId, tripId, biz, cityId, seat,
                    driver, 0, Map.of()));
            events.add(ev("withdraw_succeeded", t.plusSeconds(120), orderId, tripId, biz, cityId, seat,
                    driver, 0, Map.of()));
        }
        publish(orderId, events);
    }

    private void publish(String orderId, List<EventEnvelope> events) throws Exception {
        // 约 2% 的批次整体「迟到」20 分钟，驱动 late_event_cnt / ingest_delay_p 指标
        OffsetDateTime shift = RND.nextInt(50) == 0 ? OffsetDateTime.now().minusMinutes(20) : null;
        for (EventEnvelope e : events) {
            EventEnvelope out = shift == null ? e : new EventEnvelope(
                    e.eventId(), e.eventType(), e.eventTime().minusMinutes(20), e.ingestTime(),
                    e.orderId(), e.tripId(), e.bizLine(), e.cityId(), e.seatType(),
                    e.driverIdHash(), e.amount(), e.props(), e.version());
            kafka.send("biz.order.event", orderId, mapper.writeValueAsString(out));
        }
        log.debug("已投递订单 {} 共 {} 个事件", orderId, events.size());
    }

    private EventEnvelope ev(String type, OffsetDateTime time, String orderId, String tripId,
                             String biz, long cityId, String seat, String driver,
                             long amount, Map<String, Object> props) {
        return new EventEnvelope(nextEventId(), type, time, null, orderId, tripId, biz,
                cityId, seat, driver, amount, props, 1L);
    }

    private String nextEventId() {
        return "E" + OffsetDateTime.now().toInstant().toEpochMilli()
                + String.format("%06d", eventSeq.incrementAndGet() % 1_000_000);
    }

    private static Map<String, Object> props(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private static <T> T pick(T[] arr) {
        return arr[RND.nextInt(arr.length)];
    }

    private static String hex(SecureRandom r, int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append("0123456789abcdef".charAt(r.nextInt(16)));
        }
        return sb.toString();
    }

    private static String bizTag(String biz) {
        return switch (biz) {
            case "driver" -> "DR";
            case "transfer" -> "TR";
            case "carpool" -> "CP";
            case "designated" -> "DD";
            case "airport" -> "AP";
            default -> "XX";
        };
    }
}
