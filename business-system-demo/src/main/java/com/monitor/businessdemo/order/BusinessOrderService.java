package com.monitor.businessdemo.order;

import com.monitor.sdk.EventReporter;
import com.monitor.sdk.MonitorEvent;
import com.monitor.sdk.ReporterStats;
import com.monitor.sdk.spring.MonitorEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 模拟订单领域服务。
 *
 * <p>这里的关键点是 {@link MonitorEventPublisher#publish(MonitorEvent)}：业务状态先改变，
 * 再发布标准事件。接入方只需在状态跃迁位置调用一次 SDK，不需要自己拼 HTTP 请求，
 * 也不会把 ingest token 暴露给浏览器。</p>
 */
@Service
public class BusinessOrderService {

    private static final Set<String> BIZ_LINES =
            Set.of("driver", "transfer", "carpool", "designated", "airport");
    private static final Set<String> SCENARIOS =
            Set.of("delivered", "cancelled", "payment_failed", "mixed");
    private static final Map<String, Long> DEFAULT_CITY = Map.of(
            "driver", 440300L,
            "transfer", 330100L,
            "carpool", 330100L,
            "designated", 440100L,
            "airport", 510100L);

    private final MonitorEventPublisher monitorEvents;
    private final EventReporter reporter;
    private final Map<String, OrderAggregate> orders = new ConcurrentHashMap<>();
    private final AtomicLong orderSequence = new AtomicLong();

    public BusinessOrderService(MonitorEventPublisher monitorEvents, EventReporter reporter) {
        this.monitorEvents = monitorEvents;
        this.reporter = reporter;
    }

    public OrderSnapshot create(CreateOrderCommand command) {
        String bizLine = normalizeBizLine(command.bizLine());
        long cityId = command.cityId() == null ? DEFAULT_CITY.get(bizLine) : command.cityId();
        if (cityId <= 0) {
            throw new IllegalArgumentException("cityId 必须为正数");
        }
        long amountFen = command.amountFen() == null ? 8650L : command.amountFen();
        if (amountFen < 0) {
            throw new IllegalArgumentException("amountFen 不能为负数");
        }

        String orderId = command.orderId() == null || command.orderId().isBlank()
                ? nextOrderId() : command.orderId();
        if (orders.containsKey(orderId)) {
            throw new IllegalArgumentException("订单已存在: " + orderId);
        }
        String seatType = command.seatType() == null || command.seatType().isBlank()
                ? "standard" : command.seatType();
        OrderAggregate order = new OrderAggregate(orderId, "TRIP-" + orderId,
                bizLine, cityId, seatType, amountFen, "driver-demo-" + orderId);
        if (orders.putIfAbsent(orderId, order) != null) {
            throw new IllegalArgumentException("订单已存在: " + orderId);
        }

        publish(order, baseEvent("order_created", order)
                .prop("channel", command.channel() == null ? "demo" : command.channel()));
        return order.snapshot();
    }

    public List<OrderSnapshot> list() {
        return orders.values().stream()
                .map(OrderAggregate::snapshot)
                .sorted(Comparator.comparing(OrderSnapshot::createdAt).reversed())
                .toList();
    }

    public OrderSnapshot find(String orderId) {
        OrderAggregate order = orders.get(orderId);
        if (order == null) {
            throw new IllegalArgumentException("订单不存在: " + orderId);
        }
        return order.snapshot();
    }

    public OrderSnapshot action(String orderId, String rawAction) {
        OrderAggregate order = requireOrder(orderId);
        String action = rawAction == null ? "" : rawAction.trim().toLowerCase(Locale.ROOT);
        switch (action) {
            case "confirm" -> {
                order.status("confirmed");
                publish(order, baseEvent("passenger_confirmed", order)
                        .prop("result", "confirmed"));
            }
            case "arrive" -> {
                order.status("arrived");
                publish(order, baseEvent("arrive_pickup", order)
                        .prop("wait_started_at", Instant.now().toString()));
            }
            case "deliver" -> {
                order.status("delivered");
                publish(order, baseEvent("order_delivered", order)
                        .prop("trip_duration_sec", 4533L)
                        .prop("seat_type", order.seatType()));
            }
            case "cancel" -> {
                order.status("cancelled");
                publish(order, baseEvent("order_cancelled", order)
                        .prop("by", "passenger")
                        .prop("stage", "before_departure")
                        .prop("fault", "no-fault")
                        .prop("reason_code", "demo_user_cancel"));
            }
            case "pay" -> {
                order.status("paid");
                publish(order, baseEvent("prepay_succeeded", order)
                        .prop("pay_channel", "demo_wallet"));
            }
            case "pay_fail" -> {
                order.status("payment_failed");
                publish(order, baseEvent("prepay_failed", order)
                        .prop("fail_code", "DEMO_TIMEOUT")
                        .prop("pay_channel", "demo_wallet"));
            }
            default -> throw new IllegalArgumentException(
                    "不支持的 action: " + rawAction + "，可选 confirm、arrive、deliver、cancel、pay、pay_fail");
        }
        return order.snapshot();
    }

    /**
     * 批量生成可直接在监控大盘看到的订单事件。
     * 每个订单都先上报 order_created，再按场景上报后续状态事件。
     */
    public SimulationResult simulate(int requestedCount, String rawBizLine, String rawScenario) {
        if (requestedCount < 1 || requestedCount > 100) {
            throw new IllegalArgumentException("count 必须在 1 到 100 之间");
        }
        String bizLine = normalizeBizLine(rawBizLine);
        String scenario = normalizeScenario(rawScenario);
        int generatedEvents = 0;
        List<String> orderIds = new ArrayList<>(requestedCount);

        for (int i = 0; i < requestedCount; i++) {
            String currentScenario = "mixed".equals(scenario)
                    ? (i % 4 == 3 ? "cancelled" : i % 4 == 2 ? "payment_failed" : "delivered")
                    : scenario;
            OrderSnapshot created = create(new CreateOrderCommand(
                    null, bizLine, DEFAULT_CITY.get(bizLine), "standard",
                    8650L + (i * 100L), "demo"));
            orderIds.add(created.orderId());
            generatedEvents++;

            OrderAggregate order = requireOrder(created.orderId());
            publish(order, baseEvent("order_grab_submitted", order)
                    .prop("candidate_count", 12 + (i % 5)));
            generatedEvents++;
            publish(order, baseEvent("order_grab_won", order)
                    .prop("match_duration_sec", 8 + i)
                    .prop("route_score", 0.82));
            generatedEvents++;
            order.status("confirmed");
            publish(order, baseEvent("passenger_confirmed", order)
                    .prop("result", "confirmed"));
            generatedEvents++;

            if ("cancelled".equals(currentScenario)) {
                order.status("cancelled");
                publish(order, baseEvent("order_cancelled", order)
                        .prop("by", "passenger")
                        .prop("stage", "before_departure")
                        .prop("fault", "no-fault")
                        .prop("reason_code", "DEMO_RANDOM_CANCEL"));
                generatedEvents++;
            } else if ("payment_failed".equals(currentScenario)) {
                order.status("payment_failed");
                publish(order, baseEvent("prepay_failed", order)
                        .prop("fail_code", "DEMO_CHANNEL_TIMEOUT")
                        .prop("pay_channel", "demo_wallet"));
                generatedEvents++;
            } else {
                order.status("arrived");
                publish(order, baseEvent("arrive_pickup", order)
                        .prop("wait_started_at", Instant.now().toString()));
                publish(order, baseEvent("passenger_boarded", order)
                        .prop("wait_sec", 180L + (i * 11L)));
                publish(order, baseEvent("prepay_succeeded", order)
                        .prop("pay_channel", "demo_wallet"));
                order.status("delivered");
                publish(order, baseEvent("order_delivered", order)
                        .prop("trip_duration_sec", 3600L + (i * 60L))
                        .prop("seat_type", order.seatType()));
                generatedEvents += 4;
            }
        }

        boolean flushed = reporter.flush(Duration.ofSeconds(8));
        return new SimulationResult(requestedCount, generatedEvents, scenario, bizLine, flushed,
                orderIds, stats());
    }

    public ReporterStatsView stats() {
        return ReporterStatsView.from(reporter.stats());
    }

    private OrderAggregate requireOrder(String orderId) {
        OrderAggregate order = orders.get(orderId);
        if (order == null) {
            throw new IllegalArgumentException("订单不存在: " + orderId);
        }
        return order;
    }

    private void publish(OrderAggregate order, MonitorEvent.Builder builder) {
        MonitorEvent event = builder.build();
        order.reported(event.getEventType());
        // Starter 会在无事务时立即入队；真实业务有事务时则在 AFTER_COMMIT 入队。
        monitorEvents.publish(event);
    }

    private MonitorEvent.Builder baseEvent(String eventType, OrderAggregate order) {
        return MonitorEvent.builder(eventType)
                .eventTime(Instant.now())
                .orderId(order.orderId())
                .tripId(order.tripId())
                .bizLine(order.bizLine())
                .cityId(order.cityId())
                .seatType(order.seatType())
                .hashedDriverId(order.driverId())
                .amountFen(order.amountFen());
    }

    private String nextOrderId() {
        return "DEMO-" + LocalDate.now().toString().replace("-", "")
                + "-" + String.format("%05d", orderSequence.incrementAndGet());
    }

    private static String normalizeBizLine(String raw) {
        String value = raw == null || raw.isBlank() ? "carpool" : raw.trim().toLowerCase(Locale.ROOT);
        if (!BIZ_LINES.contains(value)) {
            throw new IllegalArgumentException("不支持的 bizLine: " + raw + "，可选 " + BIZ_LINES);
        }
        return value;
    }

    private static String normalizeScenario(String raw) {
        String value = raw == null || raw.isBlank() ? "delivered" : raw.trim().toLowerCase(Locale.ROOT);
        if (!SCENARIOS.contains(value)) {
            throw new IllegalArgumentException("不支持的 scenario: " + raw + "，可选 " + SCENARIOS);
        }
        return value;
    }

    public record CreateOrderCommand(String orderId, String bizLine, Long cityId,
                                     String seatType, Long amountFen, String channel) {
    }

    public record SimulationResult(int requestedCount, int generatedEvents, String scenario,
                                   String bizLine, boolean flushed, List<String> orderIds,
                                   ReporterStatsView reporterStats) {
    }

    public record ReporterStatsView(long submitted, long queued, long delivered, long retried,
                                    long permanentlyFailed, long dropped, int queueDepth) {
        static ReporterStatsView from(ReporterStats stats) {
            return new ReporterStatsView(stats.getSubmitted(), stats.getQueued(), stats.getDelivered(),
                    stats.getRetried(), stats.getPermanentlyFailed(), stats.getDropped(),
                    stats.getQueueDepth());
        }
    }
}
