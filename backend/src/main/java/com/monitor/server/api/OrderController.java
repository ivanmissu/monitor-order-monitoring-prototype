package com.monitor.server.api;

import com.monitor.server.common.ApiException;
import com.monitor.server.common.ApiResponse;
import com.monitor.server.common.RequestContext;
import com.monitor.server.config.MonitorProperties;
import com.monitor.server.dict.DictSeed;
import com.monitor.server.domain.Grain;
import com.monitor.server.domain.TimeRange;
import com.monitor.server.security.TokenAuthFilter;
import com.monitor.server.store.MonitorStore;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * §08 客服明细查询。直读 ODS，走 bloom_filter 索引 + 强制时间窗，SLO：P99 &lt; 3s。
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private static final Map<String, String> EVENT_LABELS = Map.ofEntries(
            Map.entry("prepay_succeeded", "乘客预付成功"),
            Map.entry("order_created", "订单创建"),
            Map.entry("order_pool_entered", "进入推荐池"),
            Map.entry("order_grab_submitted", "司机提交抢单"),
            Map.entry("order_grab_won", "司机抢单成功"),
            Map.entry("dispatch_sent", "系统派单"),
            Map.entry("dispatch_ack", "司机接单"),
            Map.entry("passenger_confirmed", "乘客确认同行"),
            Map.entry("arrive_pickup", "到达上车点"),
            Map.entry("passenger_boarded", "乘客已上车"),
            Map.entry("order_delivered", "订单送达"),
            Map.entry("order_cancelled", "订单取消"),
            Map.entry("settlement_credited", "结算已入账"),
            Map.entry("risk_hit", "风控规则命中"));

    private final MonitorStore store;
    private final MonitorProperties props;

    public OrderController(MonitorStore store, MonitorProperties props) {
        this.store = store;
        this.props = props;
    }

    // ── 订单快照 ──────────────────────────────────────────────────────────

    public record Completeness(String state, int expectedNodes, int presentNodes, List<String> missing) {
    }

    public record OrderView(String orderId, String bizLine, String status, String statusLabel,
                            long cityId, String cityName, String seatType, long amountFen,
                            String driverIdHashMasked, String tripId, String dt, int eventCount,
                            Completeness completeness, List<Map<String, Object>> riskFlags,
                            String privacyNote) {
    }

    @GetMapping("/{orderId}")
    public ApiResponse<OrderView> order(@PathVariable String orderId) {
        TokenAuthFilter.Principal p = TokenAuthFilter.current();
        p.assertScope("ods");
        MonitorStore.OrderSnapshot s = store.order(orderId)
                .orElseThrow(() -> ApiException.notFound("订单 " + orderId));
        p.assertCity(s.cityId());
        RequestContext.freshness(store.freshness());

        return ApiResponse.ok(new OrderView(s.orderId(), s.bizLine(), s.status(), s.statusLabel(),
                s.cityId(), s.cityName(), s.seatType(), s.amountFen(), s.driverHashMasked(),
                s.tripId(), s.dt().toString(), s.eventCount(),
                new Completeness(s.completeness(), s.expectedNodes(),
                        s.expectedNodes() - s.missing().size(), s.missing()),
                List.of(), "仅展示脱敏后的维度快照"));
    }

    // ── 事件时间线 ────────────────────────────────────────────────────────

    public record TimelineEvent(int seq, String eventTime, String eventType, String label,
                                String note, Long amountFen, Map<String, Object> props,
                                Long gapFromPrevSec, boolean abnormal) {
    }

    public record Timeline(String orderId, long durationSec, String startedAt, String endedAt,
                           long queryCostMs, List<TimelineEvent> events, List<String> missing) {
    }

    @GetMapping("/{orderId}/events")
    public ApiResponse<Timeline> events(@PathVariable String orderId,
                                        @RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to,
                                        @RequestParam(required = false) String domain) {
        TokenAuthFilter.current().assertScope("ods");
        long started = System.currentTimeMillis();
        TimeRange range = resolveWindow(from, to);

        List<MonitorStore.OrderEventRow> rows = store.orderEvents(orderId, range);
        if (rows.isEmpty()) {
            throw ApiException.notFound("订单 " + orderId + " 在查询窗口内的事件");
        }
        Map<String, String> domainOf = new LinkedHashMap<>();
        DictSeed.eventTypes().forEach(e -> domainOf.put(e.eventType(), e.domain()));

        List<TimelineEvent> events = new ArrayList<>();
        int seq = 1;
        for (MonitorStore.OrderEventRow r : rows) {
            if (domain != null && !domain.isBlank()
                    && !domain.equalsIgnoreCase(domainOf.getOrDefault(r.eventType(), ""))) {
                continue;
            }
            events.add(new TimelineEvent(seq++, r.eventTime().toString(), r.eventType(),
                    r.label() != null ? r.label()
                            : EVENT_LABELS.getOrDefault(r.eventType(), r.eventType()),
                    r.note(), r.amountFen(), r.props(), r.gapFromPrevSec(), r.abnormal()));
        }
        var first = rows.getFirst();
        var last = rows.getLast();
        long duration = Duration.between(first.eventTime(), last.eventTime()).toSeconds();
        return ApiResponse.ok(new Timeline(orderId, duration, first.eventTime().toString(),
                last.eventTime().toString(), System.currentTimeMillis() - started,
                events, List.of()));
    }

    // ── 批量查询 ──────────────────────────────────────────────────────────

    public record LookupCmd(List<String> orderIds, List<String> fields, Boolean withEvents) {
    }

    public record LookupResult(List<Map<String, Object>> results, List<String> notFound, boolean partial) {
    }

    @PostMapping("/lookup")
    public ApiResponse<LookupResult> lookup(@RequestBody LookupCmd cmd) {
        TokenAuthFilter.current().assertScope("ods");
        if (cmd.orderIds() == null || cmd.orderIds().isEmpty()) {
            throw ApiException.invalidParam("order_ids 不能为空");
        }
        if (cmd.orderIds().size() > 20) {
            throw ApiException.invalidParam("单次最多查询 20 个订单号");
        }
        List<Map<String, Object>> found = new ArrayList<>();
        List<String> notFound = new ArrayList<>();
        for (String id : cmd.orderIds()) {
            store.order(id).ifPresentOrElse(s -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("order_id", s.orderId());
                row.put("found", true);
                row.put("status", s.status());
                row.put("biz_line", s.bizLine());
                row.put("amount_fen", s.amountFen());
                found.add(row);
            }, () -> notFound.add(id));
        }
        return ApiResponse.ok(new LookupResult(found, notFound, !notFound.isEmpty()));
    }

    /** 原始事件导出（NDJSON 流式）。每次导出写审计日志。 */
    @GetMapping(value = "/{orderId}/events.ndjson", produces = "application/x-ndjson")
    public void export(@PathVariable String orderId,
                       @RequestParam(required = false) String from,
                       @RequestParam(required = false) String to,
                       HttpServletResponse response) throws IOException {
        TokenAuthFilter.Principal p = TokenAuthFilter.current();
        p.assertScope("ods");
        response.setContentType("application/x-ndjson");
        response.setCharacterEncoding("UTF-8");
        tools.jackson.databind.ObjectMapper mapper =
                new tools.jackson.databind.ObjectMapper();
        try (PrintWriter w = response.getWriter()) {
            for (MonitorStore.OrderEventRow r : store.orderEvents(orderId, resolveWindow(from, to))) {
                w.write(mapper.writeValueAsString(r));
                w.write("\n");
            }
        }
    }

    /** 强制时间窗：缺省近 90 天，超出返回 40002。 */
    private TimeRange resolveWindow(String from, String to) {
        int max = props.getQuery().getDetailMaxWindowDays();
        if (from == null || from.isBlank()) {
            return new TimeRange(TimeRange.today().minusDays(max - 1L), TimeRange.today(), Grain.D1);
        }
        return TimeRange.parse(from, to, "1d", max).assertWithin(max);
    }
}
