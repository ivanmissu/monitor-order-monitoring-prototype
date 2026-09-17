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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.Set;

/**
 * §08 客服明细查询。直读 ODS，走 bloom_filter 索引 + 强制时间窗，SLO：P99 &lt; 3s。
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);
    private static final Set<String> EVENT_DOMAINS =
            Set.of("supply", "match", "fulfill", "fund", "risk", "exp", "quality");

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
        TokenAuthFilter.Principal principal = requireDetailPrincipal();
        MonitorStore.OrderSnapshot snapshot = requireOrder(orderId, principal);
        RequestContext.freshness(store.freshness());
        return ApiResponse.ok(toOrderView(snapshot));
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
        TokenAuthFilter.Principal principal = requireDetailPrincipal();
        MonitorStore.OrderSnapshot snapshot = requireOrder(orderId, principal);
        return ApiResponse.ok(loadTimeline(snapshot, resolveWindow(from, to), domain));
    }

    // ── 客服工作台聚合查询 ────────────────────────────────────────────────

    /**
     * 客服工作台首屏契约。快照与时间线在同一权限上下文、同一查询窗口内返回，
     * 避免前端分别请求后出现一半成功、一半回退的混合状态。
     */
    public record WorkbenchView(OrderView order, Timeline timeline) {
    }

    @GetMapping("/{orderId}/workbench")
    public ApiResponse<WorkbenchView> workbench(@PathVariable String orderId,
                                                 @RequestParam(required = false) String from,
                                                 @RequestParam(required = false) String to,
                                                 @RequestParam(required = false) String domain) {
        TokenAuthFilter.Principal principal = requireDetailPrincipal();
        MonitorStore.OrderSnapshot snapshot = requireOrder(orderId, principal);
        Timeline timeline = loadTimeline(snapshot, resolveWindow(from, to), domain);
        RequestContext.freshness(store.freshness());
        return ApiResponse.ok(new WorkbenchView(toOrderView(snapshot), timeline));
    }

    private TokenAuthFilter.Principal requireDetailPrincipal() {
        TokenAuthFilter.Principal principal = TokenAuthFilter.current();
        principal.assertScope("ods");
        return principal;
    }

    private MonitorStore.OrderSnapshot requireOrder(String orderId,
                                                     TokenAuthFilter.Principal principal) {
        if (orderId == null || orderId.isBlank() || orderId.length() > 64) {
            throw ApiException.invalidParam("order_id 长度必须为 1-64 个字符");
        }
        MonitorStore.OrderSnapshot snapshot = store.order(orderId.trim())
                .orElseThrow(() -> ApiException.notFound("订单 " + orderId));
        principal.assertCity(snapshot.cityId());
        return snapshot;
    }

    private OrderView toOrderView(MonitorStore.OrderSnapshot snapshot) {
        return new OrderView(snapshot.orderId(), snapshot.bizLine(), snapshot.status(), snapshot.statusLabel(),
                snapshot.cityId(), snapshot.cityName(), snapshot.seatType(), snapshot.amountFen(),
                snapshot.driverHashMasked(), snapshot.tripId(), snapshot.dt().toString(), snapshot.eventCount(),
                new Completeness(snapshot.completeness(), snapshot.expectedNodes(),
                        snapshot.expectedNodes() - snapshot.missing().size(), snapshot.missing()),
                List.of(), "仅展示脱敏后的维度快照；原始标识和非白名单属性不返回");
    }

    private Timeline loadTimeline(MonitorStore.OrderSnapshot snapshot, TimeRange range, String domain) {
        String normalizedDomain = domain == null ? null : domain.trim().toLowerCase();
        if (normalizedDomain != null && !normalizedDomain.isBlank() && !EVENT_DOMAINS.contains(normalizedDomain)) {
            throw ApiException.invalidParam("domain 必须为 supply/match/fulfill/fund/risk/exp/quality");
        }
        long started = System.currentTimeMillis();
        List<MonitorStore.OrderEventRow> rows = store.orderEvents(snapshot.orderId(), range);
        if (rows.isEmpty()) {
            throw ApiException.notFound("订单 " + snapshot.orderId() + " 在查询窗口内的事件");
        }
        Map<String, String> domainOf = new LinkedHashMap<>();
        DictSeed.eventTypes().forEach(e -> domainOf.put(e.eventType(), e.domain()));

        List<TimelineEvent> events = new ArrayList<>();
        int seq = 1;
        for (MonitorStore.OrderEventRow row : rows) {
            if (normalizedDomain != null && !normalizedDomain.isBlank()
                    && !normalizedDomain.equalsIgnoreCase(domainOf.getOrDefault(row.eventType(), ""))) {
                continue;
            }
            String note = row.note();
            if ((note == null || note.isBlank()) && row.props() != null) {
                Object value = row.props().get("note");
                note = value == null ? null : String.valueOf(value);
            }
            events.add(new TimelineEvent(seq++, row.eventTime().toString(), row.eventType(),
                    row.label() != null ? row.label()
                            : EVENT_LABELS.getOrDefault(row.eventType(), row.eventType()),
                    note, row.amountFen(), row.props(), row.gapFromPrevSec(), row.abnormal()));
        }
        if (events.isEmpty()) {
            throw ApiException.notFound("订单 " + snapshot.orderId() + " 在指定 domain 下的事件");
        }
        var first = rows.getFirst();
        var last = rows.getLast();
        long duration = Duration.between(first.eventTime(), last.eventTime()).toSeconds();
        return new Timeline(snapshot.orderId(), duration, first.eventTime().toString(),
                last.eventTime().toString(), System.currentTimeMillis() - started,
                events, snapshot.missing());
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
        TokenAuthFilter.Principal principal = requireDetailPrincipal();
        MonitorStore.OrderSnapshot snapshot = requireOrder(orderId, principal);
        List<MonitorStore.OrderEventRow> rows = store.orderEvents(snapshot.orderId(), resolveWindow(from, to));
        if (rows.isEmpty()) {
            throw ApiException.notFound("订单 " + snapshot.orderId() + " 在查询窗口内的事件");
        }
        log.info("AUDIT order_events_export subject={} order_id={} event_count={}",
                principal.subject(), snapshot.orderId(), rows.size());
        response.setContentType("application/x-ndjson");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition", "inline; filename=order-" + snapshot.orderId() + "-events.ndjson");
        tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
        try (PrintWriter writer = response.getWriter()) {
            for (MonitorStore.OrderEventRow row : rows) {
                writer.write(mapper.writeValueAsString(row));
                writer.write("\n");
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
